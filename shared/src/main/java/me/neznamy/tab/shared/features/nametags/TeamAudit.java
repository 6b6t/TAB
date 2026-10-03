package me.neznamy.tab.shared.features.nametags;

import lombok.RequiredArgsConstructor;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.chat.TabTextColor;
import me.neznamy.tab.shared.chat.component.TabTextComponent;
import me.neznamy.tab.shared.features.proxy.ProxyPlayer;
import me.neznamy.tab.shared.features.proxy.ProxySupport;
import me.neznamy.tab.shared.patch6b6t.PatchSettings;
import me.neznamy.tab.shared.patch6b6t.PatchStats;
import me.neznamy.tab.shared.platform.Scoreboard;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.platform.decorators.SafeScoreboard;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * [6b6t patch] Fix 3, layer B: slow periodic audit of the teams every viewer should have.
 * <p>
 * Runs on the NameTag thread (so no locking against NameTag code), once per second, for at most
 * {@code audit-slice-ms} per run, round-robin over viewers. One full pass over ~1,500 viewers takes
 * about 30-60 s. Compares what TAB intends ({@link NameTag#shouldRegister}, the proxy copies) with what is
 * registered, and repairs only the scoreboard of the affected viewer (no flicker for anyone else).
 * <p>
 * Players and copies that joined or switched server less than {@code audit-grace-seconds} ago are skipped,
 * so the normal join / Fox handoff flow is never disturbed. A (viewer, team) pair is repaired at most once
 * per 5 minutes and at most 3 times in total, so the audit cannot fight another plugin forever.
 */
@RequiredArgsConstructor
public class TeamAudit {

    private static final long REPAIR_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int MAX_REPAIRS_PER_PAIR = 3;
    private static final long PRUNE_AFTER_MS = TimeUnit.MINUTES.toMillis(30);
    private static final int LOGGED_REPAIRS_PER_WINDOW = 10;

    /** Repair bookkeeping of one (viewer, team) pair */
    private static class RepairRecord {
        int count;
        long last;
        boolean gaveUpLogged;
    }

    @NotNull private final NameTag feature;

    /** Next viewer index */
    private int cursor;

    /** Repairs by "viewer uuid|team name" */
    private final Map<String, RepairRecord> repairs = new HashMap<>();

    /** Window for rate-limited console logging of repairs */
    private long logWindowStart;
    private int loggedInWindow;
    private long lastPrune;

    /**
     * Runs one slice of the audit. Called every second on the NameTag thread.
     */
    public void tick() {
        PatchSettings settings = PatchSettings.get();
        if (!settings.auditEnabled) return;
        TabPlayer[] viewers = feature.getOnlinePlayers().getPlayers();
        if (viewers.length == 0) return;
        long start = System.nanoTime();
        long now = System.currentTimeMillis();
        int checked = 0;
        while (checked < viewers.length && System.nanoTime() - start < settings.auditSliceNanos) {
            if (cursor >= viewers.length) cursor = 0;
            TabPlayer viewer = viewers[cursor++];
            checked++;
            auditViewer(viewer, viewers, now, settings.auditGraceMillis);
        }
        if (now - lastPrune > PRUNE_AFTER_MS) {
            lastPrune = now;
            repairs.values().removeIf(r -> now - r.last > PRUNE_AFTER_MS);
        }
    }

    /**
     * Audits all teams of one viewer.
     *
     * @param   viewer
     *          viewer to audit
     * @param   players
     *          players known to the NameTag feature
     * @param   now
     *          current time
     * @param   grace
     *          grace period after joins / switches
     */
    void auditViewer(@NotNull TabPlayer viewer, @NotNull TabPlayer[] players, long now, long grace) {
        if (!viewer.isOnline() || !viewer.isLoaded()) return;
        if (now - viewer.lastTeamStateChange < grace) return;
        Scoreboard sb = viewer.getScoreboard();
        SafeScoreboard<?> safe = sb instanceof SafeScoreboard ? (SafeScoreboard<?>) sb : null;

        // 1. Local players
        for (TabPlayer target : players) {
            if (!target.isOnline()) continue;
            if (now - target.lastTeamStateChange < grace) continue;
            String teamName = target.teamData.teamName;
            if (teamName == null) continue;
            boolean want = feature.shouldRegister(target, viewer);
            String have = viewer.teamData.getRegisteredTeamName(target);
            if (want) {
                if (have == null) {
                    repair(viewer, teamName, "missing team of " + target.getName(), now, () -> feature.registerTeam(target, viewer));
                } else if (!have.equals(teamName)) {
                    repair(viewer, teamName, "wrong team name of " + target.getName(), now, () -> {
                        viewer.teamData.unregisterTeam(target);
                        feature.registerTeam(target, viewer);
                    });
                } else if (safe != null) {
                    checkEntry(viewer, safe, have, target.getNickname(), target.getName(), now, () -> {
                        viewer.teamData.forgetTeam(target);
                        feature.registerTeam(target, viewer);
                    });
                }
            } else if (have != null) {
                repair(viewer, have, "team of " + target.getName() + " should not be registered", now,
                        () -> viewer.teamData.unregisterTeam(target));
            }
        }

        ProxySupport proxy = feature.getProxy();
        if (proxy == null) return;

        // 2. Remote teams registered for this viewer
        for (Map.Entry<ProxyPlayer, String> e : viewer.teamData.snapshotProxyTeams().entrySet()) {
            ProxyPlayer copy = e.getKey();
            String have = e.getValue();
            if (now - copy.getLastChangeMillis() < grace) continue;
            if (isOrphan(proxy, copy)) {
                repair(viewer, have, "orphan team of remote " + copy.getName(), now, () -> viewer.teamData.unregisterTeam(copy));
                continue;
            }
            NameTagProxyPlayerData nametag = copy.getNametag();
            if (nametag == null || nametag.getResolvedTeamName() == null) continue;
            if (!have.equals(nametag.getResolvedTeamName())) {
                repair(viewer, nametag.getResolvedTeamName(), "wrong team name of remote " + copy.getName(), now, () -> {
                    viewer.teamData.unregisterTeam(copy);
                    feature.getProxyHandler().registerFor(copy, viewer);
                });
            } else if (safe != null) {
                checkEntry(viewer, safe, have, copy.getNickname(), copy.getName(), now, () -> {
                    viewer.teamData.forgetTeam(copy);
                    feature.getProxyHandler().registerFor(copy, viewer);
                });
            }
        }

        // 3. Remote copies that are shown but not registered for this viewer
        for (ProxyPlayer copy : proxy.getProxyPlayers().values()) {
            if (now - copy.getLastChangeMillis() < grace) continue;
            if (isOrphan(proxy, copy)) continue;
            NameTagProxyPlayerData nametag = copy.getNametag();
            if (nametag == null || nametag.getResolvedTeamName() == null) continue;
            if (viewer.teamData.hasTeamRegistered(copy)) continue;
            repair(viewer, nametag.getResolvedTeamName(), "missing team of remote " + copy.getName(), now,
                    () -> feature.getProxyHandler().registerFor(copy, viewer));
        }
    }

    /**
     * Returns {@code true} if the copy must not have a team: not the current copy of its player, not shown,
     * no or disabled nametag data, or the player is online on this proxy.
     */
    private boolean isOrphan(@NotNull ProxySupport proxy, @NotNull ProxyPlayer copy) {
        if (proxy.getProxyPlayers().get(copy.getUniqueId()) != copy) return true;
        if (copy.getConnectionState() != ProxyPlayer.ConnectionState.CONNECTED) return true;
        if (copy.getNametag() == null || copy.getNametag().isDisabled()) return true;
        return TAB.getInstance().getPlayer(copy.getUniqueId()) != null;
    }

    /**
     * Checks that the viewer's scoreboard really has the team with the entry, and that the entry was last
     * sent into this team (otherwise the client shows the entry in another team or in none).
     */
    private void checkEntry(@NotNull TabPlayer viewer, @NotNull SafeScoreboard<?> safe, @NotNull String teamName,
                            @NotNull String entry, @NotNull String ownerName, long now, @NotNull Runnable reRegister) {
        if (safe.isEntryInTeam(teamName, entry)) return;
        if (!safe.hasTeam(teamName)) {
            // Our map says registered, the scoreboard does not have it (removed by another owner with the same name)
            repair(viewer, teamName, "team of " + ownerName + " missing in scoreboard", now, reRegister);
        } else if (safe.teamHasEntry(teamName, entry)) {
            // Team is fine, but the entry was moved into another team on the client
            repair(viewer, teamName, ownerName + " shown in another team", now, () -> safe.resendTeam(teamName));
        }
        // else: the name is used by another owner's team (name collision) - cannot be fixed by resending, skip
    }

    /**
     * Runs a repair unless this (viewer, team) pair was repaired recently or too often.
     */
    private void repair(@NotNull TabPlayer viewer, @NotNull String teamName, @NotNull String reason, long now, @NotNull Runnable action) {
        String key = viewer.getUniqueId() + "|" + teamName;
        RepairRecord record = repairs.get(key);
        if (record != null) {
            if (record.count >= MAX_REPAIRS_PER_PAIR) {
                if (!record.gaveUpLogged) {
                    record.gaveUpLogged = true;
                    log("[TAB-6b6t] audit gave up on team " + clean(teamName) + " for " + viewer.getName() + " (" + reason + ")");
                }
                return;
            }
            if (now - record.last < REPAIR_COOLDOWN_MS) return;
        } else {
            record = new RepairRecord();
            repairs.put(key, record);
        }
        record.count++;
        record.last = now;
        action.run();
        PatchStats.auditRepaired.incrementAndGet();
        if (now - logWindowStart > REPAIR_COOLDOWN_MS) {
            logWindowStart = now;
            loggedInWindow = 0;
        }
        if (loggedInWindow++ < LOGGED_REPAIRS_PER_WINDOW) {
            log("[TAB-6b6t] audit repaired team " + clean(teamName) + " for " + viewer.getName() + ": " + reason);
        }
    }

    /** Team names start with invisible sorting characters, strip them for log lines */
    @NotNull
    private static String clean(@Nullable String teamName) {
        return teamName == null ? "null" : teamName.replaceAll("\\p{C}", "");
    }

    private static void log(@NotNull String message) {
        TAB.getInstance().getPlatform().logInfo(new TabTextComponent(message, (TabTextColor) null));
    }
}
