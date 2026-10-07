package me.neznamy.tab.shared.features.nametags;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.chat.component.TabComponent;
import me.neznamy.tab.shared.cpu.ThreadExecutor;
import me.neznamy.tab.shared.features.proxy.ProxyPlayer;
import me.neznamy.tab.shared.features.proxy.ProxySupport;
import me.neznamy.tab.shared.features.proxy.QueuedData;
import me.neznamy.tab.shared.features.proxy.message.ProxyMessage;
import me.neznamy.tab.shared.patch6b6t.PatchStats;
import me.neznamy.tab.shared.platform.Scoreboard;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.UUID;

/**
 * Proxy message to update team data of a player.
 */
@RequiredArgsConstructor
@ToString(exclude = "feature")
@Getter
public class NameTagProxyPlayerData extends ProxyMessage {

    @NotNull private final NameTag feature;
    private final long id;
    @NotNull private final UUID playerId;
    @NotNull private final String teamName;
    @NotNull private final String prefix;
    @NotNull private final String suffix;
    @NotNull private final Scoreboard.NameVisibility nameVisibility;
    @Nullable private String resolvedTeamName;
    private final boolean disabled;

    /**
     * Creates new instance and reads data from byte input.
     *
     * @param   in
     *          Input stream to read from
     * @param   feature
     *          Feature instance to use for processing
     */
    public NameTagProxyPlayerData(@NotNull ByteArrayDataInput in, @NotNull NameTag feature) {
        this.feature = feature;
        id = in.readLong();
        playerId = readUUID(in);
        teamName = in.readUTF();
        prefix = in.readUTF();
        suffix = in.readUTF();
        nameVisibility = Scoreboard.NameVisibility.getByName(in.readUTF());
        disabled = in.readBoolean();
    }

    @NotNull
    public ThreadExecutor getCustomThread() {
        return feature.getCustomThread();
    }

    @Override
    public void write(@NotNull ByteArrayDataOutput out) {
        out.writeLong(id);
        writeUUID(out, playerId);
        out.writeUTF(teamName);
        out.writeUTF(prefix);
        out.writeUTF(suffix);
        out.writeUTF(nameVisibility.toString());
        out.writeBoolean(disabled);
    }

    /** [6b6t patch] Player this message is about */
    @Override
    @NotNull
    public UUID getSubjectId() {
        return playerId;
    }

    /** [6b6t patch] Stores the data as queued data of the sending proxy */
    @Override
    public void queue(@NotNull ProxySupport proxySupport) {
        QueuedData data = proxySupport.queuedFor(playerId, getSourceProxy());
        if (data.getNametag() == null || data.getNametag().id < id) {
            resolvedTeamName = checkTeamName(null, teamName.substring(0, teamName.length()-1));
            data.setNametag(this);
        }
    }

    @Override
    public void process(@NotNull ProxySupport proxySupport) {
        ProxyPlayer target = proxySupport.getProxyPlayers().get(playerId);
        if (target == null) {
            unknownPlayer(playerId.toString(), "nametag update update");
            queue(proxySupport);
            return;
        }
        if (target.getNametag() != null && target.getNametag().id > id) {
            TAB.getInstance().debug("Dropping nametag update action for player " + target.getName() + " due to newer action already being present");
            return;
        }
        NameTagProxyPlayerData oldData = target.getNametag();
        resolvedTeamName = checkTeamName(target, teamName.substring(0, teamName.length()-1));
        target.setNametag(this);
        // [6b6t patch 6b6t.4] Same data again (the origin resends everything when asked for its players): nothing
        // changed, so send no team packet to every viewer. A viewer missing the team is repaired by TeamAudit.
        if (oldData != null && oldData.disabled == disabled && oldData.teamName.equals(teamName)
                && oldData.prefix.equals(this.prefix) && oldData.suffix.equals(this.suffix)
                && oldData.nameVisibility == nameVisibility && resolvedTeamName.equals(oldData.resolvedTeamName)) return;

        if (target.getConnectionState() == ProxyPlayer.ConnectionState.CONNECTED) {
            // [6b6t patch] A local player with this UUID is online: the local team must win, never register or
            // update the copy's team over it (race A step 3: the client moved the player into the copy's team,
            // and the copy's quit then left him teamless).
            if (TAB.getInstance().getPlayer(playerId) != null) return;
            TabComponent prefix = feature.getPrefixCache().get(this.prefix);
            TabComponent lastColor = feature.getLastColorCache().get(this.prefix);
            TabComponent suffix = feature.getSuffixCache().get(this.suffix);
            for (TabPlayer viewer : feature.getOnlinePlayers().getPlayers()) {
                if (oldData != null && !oldData.disabled && disabled) {
                    // Enabled to disabled
                    viewer.teamData.unregisterTeam(target);
                    continue;
                }
                if (oldData != null && oldData.disabled && !disabled) {
                    // Disabled to enabled
                    viewer.teamData.unregisterTeam(target); // [6b6t patch] never register twice (no-op if not registered)
                    viewer.teamData.registerTeam(
                            target,
                            resolvedTeamName,
                            prefix,
                            suffix,
                            nameVisibility,
                            Scoreboard.CollisionRule.ALWAYS,
                            Collections.singletonList(target.getNickname()),
                            feature.getTeamOptions(),
                            lastColor.getLastStyle().toEnumChatFormat()
                    );
                    continue;
                }
                if (oldData != null && resolvedTeamName.equals(oldData.resolvedTeamName)) {
                    // Property update
                    // [6b6t patch] Bug B: upstream sent the update to oldData.teamName = the SENDER proxy's team name,
                    // not the name registered on this proxy (resolvedTeamName). When they differ ("...B" on the sender,
                    // "...A" here) every update failed with "Tried to modify non-existing team", or silently changed
                    // another player's team that happens to use that name. Use the name registered for this viewer.
                    String registered = viewer.teamData.getRegisteredProxyTeamName(target);
                    if (registered == null) {
                        if (oldData.disabled) continue; // disabled -> disabled: nothing should be registered
                        // Copy is connected and enabled, so it must be registered for every viewer: repair
                        viewer.teamData.registerTeam(
                                target,
                                resolvedTeamName,
                                prefix,
                                suffix,
                                nameVisibility,
                                Scoreboard.CollisionRule.ALWAYS,
                                Collections.singletonList(target.getNickname()),
                                feature.getTeamOptions(),
                                lastColor.getLastStyle().toEnumChatFormat()
                        );
                        PatchStats.inlineRegistered.incrementAndGet();
                    } else if (!registered.equals(resolvedTeamName)) {
                        // Defensive: registered under another name, re-register under the current one
                        viewer.teamData.unregisterTeam(target);
                        viewer.teamData.registerTeam(
                                target,
                                resolvedTeamName,
                                prefix,
                                suffix,
                                nameVisibility,
                                Scoreboard.CollisionRule.ALWAYS,
                                Collections.singletonList(target.getNickname()),
                                feature.getTeamOptions(),
                                lastColor.getLastStyle().toEnumChatFormat()
                        );
                    } else {
                        viewer.getScoreboard().updateTeam(
                                registered,
                                prefix,
                                suffix,
                                nameVisibility,
                                Scoreboard.CollisionRule.ALWAYS,
                                feature.getTeamOptions(),
                                lastColor.getLastStyle().toEnumChatFormat()
                        );
                    }
                } else {
                    // Team rename
                    viewer.teamData.unregisterTeam(target);
                    viewer.teamData.registerTeam(
                            target,
                            resolvedTeamName,
                            prefix,
                            suffix,
                            nameVisibility,
                            Scoreboard.CollisionRule.ALWAYS,
                            Collections.singletonList(target.getNickname()),
                            feature.getTeamOptions(),
                            lastColor.getLastStyle().toEnumChatFormat()
                    );
                }
            }
        }
    }

    @NotNull
    private String checkTeamName(@Nullable ProxyPlayer player, @NotNull String currentName15) {
        char id = 'A';
        while (true) {
            String potentialTeamName = currentName15 + id;
            boolean nameTaken = false;
            for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
                if (potentialTeamName.equals(all.sortingData.shortTeamName)) {
                    nameTaken = true;
                    break;
                }
            }
            if (!nameTaken && feature.getProxy() != null) {
                for (ProxyPlayer all : feature.getProxy().getProxyPlayers().values()) {
                    if (all == player) continue;
                    if (all.getUniqueId().equals(playerId)) continue; // [6b6t patch] another copy of the same player
                    if (all.getNametag() == null) continue;
                    // [6b6t patch] compare with the name used on THIS proxy (resolved), like Sorting does;
                    // upstream compared with the sender's name, so two remote players could resolve to the same team
                    String used = all.getNametag().getResolvedTeamName() != null ? all.getNametag().getResolvedTeamName() : all.getNametag().teamName;
                    if (potentialTeamName.equals(used)) {
                        nameTaken = true;
                        break;
                    }
                }
            }
            if (!nameTaken) {
                return potentialTeamName;
            }
            id++;
        }
    }
}