package me.neznamy.tab.shared.features.globalplayerlist;

import lombok.Getter;
import me.neznamy.tab.shared.ProtocolVersion;
import me.neznamy.tab.shared.chat.component.TabComponent;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.cpu.ThreadExecutor;
import me.neznamy.tab.shared.cpu.TimedCaughtTask;
import me.neznamy.tab.shared.data.Server;
import me.neznamy.tab.shared.data.ServerGroup;
import me.neznamy.tab.shared.features.playerlist.PlayerList;
import me.neznamy.tab.shared.features.proxy.ProxyPlayer;
import me.neznamy.tab.shared.features.proxy.ProxySupport;
import me.neznamy.tab.shared.features.types.*;
import me.neznamy.tab.shared.hook.LuckPermsHook;
import me.neznamy.tab.shared.patch6b6t.BotTabFilter;
import me.neznamy.tab.shared.patch6b6t.PatchSettings;
import me.neznamy.tab.shared.patch6b6t.RemoteBots;
import me.neznamy.tab.shared.platform.TabList;
import me.neznamy.tab.api.integration.VanishIntegration;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.util.OnlinePlayers;
import me.neznamy.tab.shared.util.PerformanceUtil;
import me.neznamy.tab.shared.util.DumpUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Feature handler for global PlayerList feature.
 */
public class GlobalPlayerList extends RefreshableFeature implements JoinListener, QuitListener, VanishListener, GameModeListener,
        Loadable, UnLoadable, ServerSwitchListener, TabListClearListener, CustomThreaded, ProxyFeature, Dumpable {

    @Getter private final ThreadExecutor customThread = new ThreadExecutor("TAB Global PlayerList Thread");
    @Getter private OnlinePlayers onlinePlayers;
    @Nullable private final ProxySupport proxy = TAB.getInstance().getFeatureManager().getFeature(TabConstants.Feature.PROXY_SUPPORT);
    @NotNull private final GlobalPlayerListConfiguration configuration;
    @Nullable private final PlayerList playerlist = TAB.getInstance().getFeatureManager().getFeature(TabConstants.Feature.PLAYER_LIST);

    /** [6b6t patch 6b6t.3] Hides bots for viewers who chose so (LuckPerms meta botsfilter-hide-tab) */
    @Getter @NotNull private final BotTabFilter botFilter = new BotTabFilter(PatchSettings.get().botTabFilter,
            PatchSettings.get().botTabFilter ? BotTabFilter.luckPermsReader(LuckPermsHook.getInstance().isInstalled()) : null);

    /** [6b6t patch 6b6t.3] Ticks of the 10 s bot flag task, every 3rd also sends our bot list */
    private int botTicks;

    /**
     * Constructs new instance and registers new placeholders.
     *
     * @param   configuration
     *          Feature configuration
     */
    public GlobalPlayerList(@NotNull GlobalPlayerListConfiguration configuration) {
        this.configuration = configuration;
        for (Map.Entry<String, List<String>> entry : configuration.getSharedServers().entrySet()) {
            TAB.getInstance().getPlaceholderManager().registerServerPlaceholder(TabConstants.Placeholder.globalPlayerListGroup(entry.getKey()), 1000, () -> {
                if (onlinePlayers == null) return "0"; // Not loaded yet
                int count = 0;
                for (TabPlayer player : onlinePlayers.getPlayers()) {
                    if (matchesAnyPattern(player.server.getName(), entry.getValue()) && !player.isVanished()) count++;
                }
                if (proxy != null) {
                    for (ProxyPlayer player : proxy.getProxyPlayers().values()) {
                        if (matchesAnyPattern(player.server.getName(), entry.getValue()) && !player.isVanished()) count++;
                    }
                }
                return PerformanceUtil.toString(count);
            });
        }
    }

    @Override
    public void load() {
        onlinePlayers =  new OnlinePlayers(TAB.getInstance().getOnlinePlayers());
        if (configuration.isUpdateLatency()) addUsedPlaceholder(TabConstants.Placeholder.PING);
        // [6b6t patch 6b6t.3] flags first, so the entries below already respect them
        for (TabPlayer p : onlinePlayers.getPlayers()) botFilter.refresh(p.botFlags, p.getUniqueId());
        startBotFilter();
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            for (TabPlayer displayed : onlinePlayers.getPlayers()) {
                if (viewer.server == displayed.server) continue;
                if (shouldSee(viewer, displayed)) {
                    viewer.getTabList().addEntry(getAddInfoData(displayed, viewer));
                }
            }
        }
    }

    /**
     * Returns {@code true} if viewer should see the target player, {@code false} if not.
     *
     * @param   viewer
     *          Player viewing the tablist
     * @param   displayed
     *          Player who is being displayed
     * @return  {@code true} if viewer should see the target, {@code false} if not
     */
    public boolean shouldSee(@NotNull TabPlayer viewer, @NotNull TabPlayer displayed) {
        // [6b6t patch 6b6t.3] bots hidden for viewers who chose so (viewer flag first: one volatile read for everyone else)
        if (viewer.botFlags.hidesBots() && botFilter.hides(true, viewer.server != displayed.server, displayed.botFlags.isBot())) return false;
        return viewer.server.canSee(displayed.server) && viewer.canSee(displayed);
    }

    @Override
    public void unload() {
        stopBotFilter(); // [6b6t patch 6b6t.3]
        for (TabPlayer displayed : onlinePlayers.getPlayers()) {
            for (TabPlayer viewer : onlinePlayers.getPlayers()) {
                if (displayed.server != viewer.server) viewer.getTabList().removeEntry(displayed.getTablistId());
            }
        }
    }

    @Override
    public void onJoin(@NotNull TabPlayer connectedPlayer) {
        // [6b6t patch 6b6t.3] read the flags before any entry is sent and tell the other proxy (it shows his copy
        // 200 ms after the join message, so this normally arrives first). "-" for a non-bot only clears a stale
        // flag of an earlier session (no change and no packet on the other side otherwise).
        botFilter.refresh(connectedPlayer.botFlags, connectedPlayer.getUniqueId());
        if (botFilter.isEnabled()) sendBotDelta(connectedPlayer, connectedPlayer.botFlags.isBot());
        onlinePlayers.addPlayer(connectedPlayer);
        for (TabPlayer all : onlinePlayers.getPlayers()) {
            if (connectedPlayer.server == all.server) continue;
            if (shouldSee(all, connectedPlayer)) {
                all.getTabList().addEntry(getAddInfoData(connectedPlayer, all));
            }
            if (shouldSee(connectedPlayer, all)) {
                connectedPlayer.getTabList().addEntry(getAddInfoData(all, connectedPlayer));
            }
        }
        if (proxy != null) {
            for (ProxyPlayer proxied : proxy.getProxyPlayers().values()) {
                if (proxied.server != connectedPlayer.server && shouldSee(connectedPlayer, proxied)) {
                    connectedPlayer.getTabList().addEntry(proxied.asEntry());
                }
            }
        }
    }

    @Override
    public void onQuit(@NotNull TabPlayer disconnectedPlayer) {
        onlinePlayers.removePlayer(disconnectedPlayer);
        for (TabPlayer all : onlinePlayers.getPlayers()) {
            if (disconnectedPlayer.server == all.server) continue; // Already removed by server itself
            all.getTabList().removeEntry(disconnectedPlayer.getTablistId());
        }
    }

    @Override
    public void onServerChange(@NotNull TabPlayer changed, @NotNull Server from, @NotNull Server to) {
        // TODO fix players potentially not appearing on rapid server switching (if anyone reports it)
        // Player who switched server is removed from tablist of other players in ~70-110ms (depending on online count), re-add with a delay
        customThread.executeLater(new TimedCaughtTask(TAB.getInstance().getCpu(), () -> {
            if (!changed.isOnline()) return; // Player disconnected in the meantime
            for (TabPlayer all : onlinePlayers.getPlayers()) {
                // Remove for everyone and add back if visible, easy solution to display-others-as-spectators option
                // Also do not remove/add players from the same server, let backend handle it
                if (all.server != changed.server) {
                    all.getTabList().removeEntry(changed.getTablistId());
                    if (shouldSee(all, changed)) {
                        all.getTabList().addEntry(getAddInfoData(changed, all));
                    }
                }
            }
        }, getFeatureName(), TabConstants.CpuUsageCategory.SERVER_SWITCH), 200);
    }

    @Override
    public void onTabListClear(@NotNull TabPlayer player) {
        for (TabPlayer all : onlinePlayers.getPlayers()) {
            // Ignore players on the same server, since the server already sends add packet
            if (all.server != player.server && shouldSee(player, all)) {
                player.getTabList().addEntry(getAddInfoData(all, player));
            }
        }
        if (proxy != null) {
            for (ProxyPlayer proxied : proxy.getProxyPlayers().values()) {
                if (proxied.server != player.server && shouldSee(player, proxied)) {
                    player.getTabList().addEntry(proxied.asEntry());
                }
            }
        }
    }

    /**
     * Creates new entry of given target player for viewer.
     *
     * @param   p
     *          Displayed player
     * @param   viewer
     *          Player viewing the tablist
     * @return  Entry of target for viewer
     */
    @NotNull
    public TabList.Entry getAddInfoData(@NotNull TabPlayer p, @NotNull TabPlayer viewer) {
        TabComponent format = null;
        if (playerlist != null && !p.tablistData.disabled.get()) {
            format = playerlist.getTabFormat(p, viewer);
        }
        return new TabList.Entry(
                p.getTablistId(),
                p.getNickname(),
                p.getTabList().getSkin(),
                true,
                configuration.isUpdateLatency() ? p.getPing() : 0,
                configuration.isOthersAsSpectators() || (configuration.isVanishedAsSpectators() && p.isVanished()) ? 3 : p.getGamemode(),
                viewer.getVersion().getNetworkId() >= ProtocolVersion.V1_8.getNetworkId() ? format : null,
                0,
                true
        );
    }

    @Override
    public void onGameModeChange(@NotNull TabPlayer player) {
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            if (player.server != viewer.server && viewer.server.canSee(player.server)) {
                viewer.getTabList().updateGameMode(player, configuration.isOthersAsSpectators() ? 3 : player.getGamemode());
            }
        }
    }

    @Override
    public void onVanishStatusChange(@NotNull TabPlayer p) {
        if (p.isVanished()) {
            for (TabPlayer all : onlinePlayers.getPlayers()) {
                if (all == p) continue;
                if (!shouldSee(all, p)) {
                    all.getTabList().removeEntry(p.getTablistId());
                }
            }
        } else {
            for (TabPlayer viewer : onlinePlayers.getPlayers()) {
                if (viewer == p) continue;
                if (shouldSee(viewer, p)) {
                    viewer.getTabList().addEntry(getAddInfoData(p, viewer));
                }
            }
        }
    }

    @NotNull
    @Override
    public String getRefreshDisplayName() {
        return "Updating latency";
    }

    @Override
    public void refresh(@NotNull TabPlayer refreshed, boolean force) {
        //player ping changed, must manually update latency for players on other servers
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            if (refreshed.server != viewer.server && viewer.server.canSee(refreshed.server)) {
                viewer.getTabList().updateLatency(refreshed, refreshed.getPing());
            }
        }
    }

    private boolean shouldSee(@NotNull TabPlayer viewer, @NotNull ProxyPlayer target) {
        // Do not show duplicate player that will be removed in a sec
        if (TAB.getInstance().isPlayerConnected(target.getTablistId())) return false;
        // [6b6t patch 6b6t.5] Every entry TAB adds for a remote copy must respect hiding, including
        // same-server self-repair: adding that entry would override the backend's unlisted bot.
        if (viewer.botFlags.hidesBots() && botFilter.hides(true, true, botFilter.getRemote().isBot(target.getUniqueId()))) return false;
        return viewer.server.canSee(target.server) && (!target.isVanished() || viewer.hasPermission(TabConstants.Permission.SEE_VANISHED));
    }

    // ------------------
    // ProxySupport
    // ------------------

    @Override
    public void onJoin(@NotNull ProxyPlayer player) {
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            if (shouldSee(viewer, player) && viewer.server != player.server) {
                viewer.getTabList().addEntry(player.asEntry());
            }
        }
    }

    @Override
    public void onServerSwitch(@NotNull ProxyPlayer player) {
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            if (viewer.server == player.server) continue;
            if (shouldSee(viewer, player)) {
                viewer.getTabList().addEntry(player.asEntry());
            } else {
                viewer.getTabList().removeEntry(player.getTablistId());
            }
        }
    }

    /**
     * [6b6t patch 6b6t.4] Called when the self-repair put a copy back on its real server (it was stuck on another
     * one here). Viewers on that server normally get the entry from the backend, but while the copy looked like it
     * was on an isolated server, TAB itself removed the entry, and the backend does not send it again.
     *
     * @param   player
     *          repaired copy
     */
    public void restoreSameServerEntries(@NotNull ProxyPlayer player) {
        customThread.execute(new TimedCaughtTask(TAB.getInstance().getCpu(), () -> {
            if (player.getConnectionState() != ProxyPlayer.ConnectionState.CONNECTED) return;
            for (TabPlayer viewer : onlinePlayers.getPlayers()) {
                if (viewer.server == player.server && shouldSee(viewer, player)) viewer.getTabList().addEntry(player.asEntry());
            }
        }, getFeatureName(), "6b6t entry restore"));
    }

    @Override
    public void onQuit(@NotNull ProxyPlayer player) {
        TabPlayer connected = TAB.getInstance().getPlayer(player.getUniqueId());
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            // Make sure to not remove player if they are connected already and added into tablist by the server
            if (player.server != viewer.server && (connected == null || !shouldSee(viewer, connected))) {
                viewer.getTabList().removeEntry(player.getTablistId());
            }
        }
    }

    @Override
    public void onVanishStatusChange(@NotNull ProxyPlayer player) {
        if (player.isVanished()) {
            for (TabPlayer all : onlinePlayers.getPlayers()) {
                if (!shouldSee(all, player)) {
                    all.getTabList().removeEntry(player.getTablistId());
                }
            }
        } else {
            for (TabPlayer viewer : onlinePlayers.getPlayers()) {
                if (shouldSee(viewer, player)) {
                    viewer.getTabList().addEntry(player.asEntry());
                }
            }
        }
    }

    @NotNull
    @Override
    public String getFeatureName() {
        return "Global PlayerList";
    }

    // ------------------
    // [6b6t patch 6b6t.3] bot tab filter
    // ------------------

    /**
     * Starts LuckPerms change events, the bot channel and the 10 s task (refresh flags, expire remote bots,
     * every 30 s send our bot list). Everything that changes entries runs on this feature's thread.
     */
    private void startBotFilter() {
        if (!botFilter.isEnabled()) return;
        BotTabFilter.MetaReader reader = botFilter.getReader();
        if (reader != null) {
            try {
                reader.subscribe(id -> {
                    TabPlayer player = TAB.getInstance().getPlayer(id);
                    if (player == null) return;
                    customThread.execute(new TimedCaughtTask(TAB.getInstance().getCpu(), () -> refreshBotFlags(player),
                            getFeatureName(), "6b6t bot flags"));
                });
            } catch (Throwable t) {
                TAB.getInstance().getErrorManager().printError("[TAB-6b6t] Could not subscribe to LuckPerms events, bot flags refresh every 10 s only", t);
            }
        }
        if (proxy != null) {
            proxy.setBotChannelListener(this::onBotChannelLine);
            proxy.sendBotChannelMessage(RemoteBots.encodeRequest(proxy.getProxy().toString()));
            sendBotSnapshot();
        }
        customThread.repeatTask(new TimedCaughtTask(TAB.getInstance().getCpu(), () -> {
            for (TabPlayer p : onlinePlayers.getPlayers()) refreshBotFlags(p);
            for (UUID id : botFilter.getRemote().expire(System.currentTimeMillis())) reevaluateRemoteBot(id);
            if (++botTicks % 3 == 0) sendBotSnapshot();
        }, getFeatureName(), "6b6t bot flags"), 10_000);
    }

    /**
     * Stops LuckPerms change events and the bot channel listener.
     */
    private void stopBotFilter() {
        BotTabFilter.MetaReader reader = botFilter.getReader();
        if (reader != null) {
            try {
                reader.unsubscribe();
            } catch (Throwable ignored) {
                // LuckPerms already disabled
            }
        }
        if (proxy != null) proxy.setBotChannelListener(null);
    }

    /**
     * Re-reads a local player's flags and updates entries if they changed. Runs on this feature's thread.
     *
     * @param   player
     *          local player
     */
    private void refreshBotFlags(@NotNull TabPlayer player) {
        if (!player.isOnline() || !onlinePlayers.contains(player)) return; // not joined yet (onJoin reads the flags) or gone
        int changed = botFilter.refresh(player.botFlags, player.getUniqueId());
        if ((changed & me.neznamy.tab.shared.patch6b6t.BotFlags.BOT_CHANGED) != 0) {
            sendBotDelta(player, player.botFlags.isBot());
            reevaluateBotTarget(player);
        }
        if ((changed & me.neznamy.tab.shared.patch6b6t.BotFlags.HIDE_CHANGED) != 0) {
            reevaluateBotViewer(player);
        }
    }

    /**
     * A viewer turned bot hiding on or off: remove or re-add the entries of bots on other servers / the other proxy.
     *
     * @param   viewer
     *          viewer whose choice changed
     */
    private void reevaluateBotViewer(@NotNull TabPlayer viewer) {
        boolean hides = viewer.botFlags.hidesBots();
        for (TabPlayer target : onlinePlayers.getPlayers()) {
            if (target == viewer || target.server == viewer.server || !target.botFlags.isBot()) continue;
            apply(viewer, target, botFilter.afterChange(hides, true, shouldSee(viewer, target)));
        }
        if (proxy == null) return;
        for (ProxyPlayer target : proxy.getProxyPlayers().values()) {
            if (target.getConnectionState() != ProxyPlayer.ConnectionState.CONNECTED || target.server == viewer.server) continue;
            if (TAB.getInstance().isPlayerConnected(target.getTablistId())) continue; // local twin, handled above
            if (!botFilter.getRemote().isBot(target.getUniqueId())) continue;
            apply(viewer, target, botFilter.afterChange(hides, true, shouldSee(viewer, target)));
        }
    }

    /**
     * A local player's bot marker changed: update his entry for viewers who hide bots.
     *
     * @param   target
     *          local player
     */
    private void reevaluateBotTarget(@NotNull TabPlayer target) {
        boolean bot = target.botFlags.isBot();
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            if (viewer == target || viewer.server == target.server || !viewer.botFlags.hidesBots()) continue;
            apply(viewer, target, botFilter.afterChange(true, bot, shouldSee(viewer, target)));
        }
    }

    /**
     * The bot marker of a player on the other proxy changed: update his entry for viewers who hide bots.
     *
     * @param   id
     *          player UUID
     */
    private void reevaluateRemoteBot(@NotNull UUID id) {
        if (proxy == null) return;
        ProxyPlayer target = proxy.getProxyPlayers().get(id);
        if (target == null || target.getConnectionState() != ProxyPlayer.ConnectionState.CONNECTED) return; // join adds it later
        if (TAB.getInstance().isPlayerConnected(target.getTablistId())) return; // local twin uses local flags
        boolean bot = botFilter.getRemote().isBot(id);
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            if (viewer.server == target.server || !viewer.botFlags.hidesBots()) continue;
            BotTabFilter.Action action = botFilter.afterChange(true, bot, shouldSee(viewer, target));
            if (action == BotTabFilter.Action.REMOVE) viewer.getTabList().removeEntry(target.getTablistId());
            else if (action == BotTabFilter.Action.ADD) viewer.getTabList().addEntry(target.asEntry());
        }
    }

    private void apply(@NotNull TabPlayer viewer, @NotNull TabPlayer target, @NotNull BotTabFilter.Action action) {
        if (action == BotTabFilter.Action.REMOVE) viewer.getTabList().removeEntry(target.getTablistId());
        else if (action == BotTabFilter.Action.ADD) viewer.getTabList().addEntry(getAddInfoData(target, viewer));
    }

    private void apply(@NotNull TabPlayer viewer, @NotNull ProxyPlayer target, @NotNull BotTabFilter.Action action) {
        if (action == BotTabFilter.Action.REMOVE) viewer.getTabList().removeEntry(target.getTablistId());
        else if (action == BotTabFilter.Action.ADD) viewer.getTabList().addEntry(target.asEntry());
    }

    /**
     * Line received on the bot channel (messenger thread): update the registry, re-check changed players
     * on this feature's thread, answer a list request.
     *
     * @param   line
     *          received line
     */
    private void onBotChannelLine(@NotNull String line) {
        if (proxy == null) return;
        RemoteBots.Result result = botFilter.getRemote().handle(line, proxy.getProxy().toString(), System.currentTimeMillis());
        if (result.changed.isEmpty() && !result.snapshotRequested) return;
        customThread.execute(new TimedCaughtTask(TAB.getInstance().getCpu(), () -> {
            for (UUID id : result.changed) reevaluateRemoteBot(id);
            if (result.snapshotRequested) sendBotSnapshot();
        }, getFeatureName(), "6b6t bot flags"));
    }

    private void sendBotDelta(@NotNull TabPlayer player, boolean bot) {
        if (proxy == null) return;
        proxy.sendBotChannelMessage(RemoteBots.encodeDelta(proxy.getProxy().toString(), player.getUniqueId(), bot));
    }

    private void sendBotSnapshot() {
        if (proxy == null) return;
        List<UUID> bots = new ArrayList<>();
        for (TabPlayer p : onlinePlayers.getPlayers()) {
            if (p.botFlags.isBot()) bots.add(p.getUniqueId());
        }
        for (String line : RemoteBots.encodeSnapshot(proxy.getProxy().toString(), bots)) {
            proxy.sendBotChannelMessage(line);
        }
    }

    /**
     * Checks if a server name matches any of the given patterns. Supports:
     * - Exact match: "lobby"
     * - Prefix wildcard: "lobby*"
     * - Suffix wildcard: "*lobby"
     * - Regex pattern: "regex:lobby-[0-9]+"
     *
     * @param   serverName
     *          Server name to check
     * @param   patterns
     *          List of patterns to match against
     * @return  {@code true} if server name matches any pattern, {@code false} otherwise
     */
    private boolean matchesAnyPattern(@NotNull String serverName, @NotNull List<String> patterns) {
        for (String pattern : patterns) {
            if (TAB.getInstance().getDataManager().matchesPattern(serverName, pattern)) {
                return true;
            }
        }
        return false;
    }

    @Override
    @NotNull
    public Object dump(@NotNull TabPlayer player) {
        Map<String, Object> playerInfo = new LinkedHashMap<>();
        playerInfo.put("server", player.server.getName());
        playerInfo.put("server is spy server", player.server.isSpyServer());
        playerInfo.put("server group", player.server.getServerGroup().getName());
        playerInfo.put("6b6t bot", player.botFlags.isBot()); // [6b6t patch 6b6t.3]
        playerInfo.put("6b6t hides bots", player.botFlags.hidesBots());
        playerInfo.put("6b6t bots on other proxies", botFilter.getRemote().size());
        playerInfo.put("servers in the group", player.server.getServerGroup().getPatterns());

        List<List<String>> rows = new ArrayList<>();
        List<TabPlayer> targets = new ArrayList<>(Arrays.asList(TAB.getInstance().getOnlinePlayers()));
        targets.sort(Comparator.comparing(TabPlayer::getName, String.CASE_INSENSITIVE_ORDER));
        for (TabPlayer t : targets) {
            String msg = getShouldSeeMessage(t, player);
            rows.add(Arrays.asList(
                    t.getName(),
                    t.server.getName(),
                    t.server.getServerGroup().getName(),
                    msg
            ));
        }
        if (proxy != null) {
            List<ProxyPlayer> proxyTargets = new ArrayList<>(proxy.getProxyPlayers().values());
            proxyTargets.sort(Comparator.comparing(ProxyPlayer::getName, String.CASE_INSENSITIVE_ORDER));
            for (ProxyPlayer t : proxyTargets) {
                String msg = getShouldSeeMessage(t, player);
                rows.add(Arrays.asList(
                        "[Proxy] " + t.getName(),
                        t.server.getName(),
                        t.server.getServerGroup().getName(),
                        msg
                ));
            }
        }
        playerInfo.put("visibility from viewer's perspective", DumpUtils.tableToLines(
                Arrays.asList("Player", "Server", "Server Group", "Visible for " + player.getName()),
                rows
        ));

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("configuration", configuration.getSection().getMap());
        map.put("player info", playerInfo);
        return map;
    }

    /**
     * Returns a message about whether viewer can see target player or not and why.
     *
     * @param   target
     *          Target player to see
     * @param   viewer
     *          Tablist viewer
     * @return  Message about whether viewer can see target player or not and why
     */
    @NotNull
    private String getShouldSeeMessage(@NotNull TabPlayer target, @NotNull TabPlayer viewer) {
        if (viewer == target) return "yes - same player";

        if (!viewer.server.canSee(target.server)) {
            return "no - servers are in different groups (viewer=" + viewer.server.getServerGroup().getName() +
                    ", target=" + target.server.getServerGroup().getName() + ") and viewer is not in a spy server";
        }
        for (VanishIntegration i : VanishIntegration.getHandlers()) {
            try {
                if (!i.canSee(viewer, target)) {
                    return "no - vanish integration '" + i.getPlugin() + "' prevents viewer from seeing target";
                }
            } catch (Throwable ignored) {
            }
        }
        if (target.isVanished() && !viewer.hasPermission(TabConstants.Permission.SEE_VANISHED)) {
            return "no - target is vanished and viewer lacks permission (" + TabConstants.Permission.SEE_VANISHED + ")";
        }
        if (viewer.botFlags.hidesBots() && botFilter.hides(true, viewer.server != target.server, target.botFlags.isBot())) {
            return "no - target is a bot (" + BotTabFilter.BOT_META + ") and viewer hides bots (" + BotTabFilter.HIDE_META + ")";
        }

        if (viewer.server == target.server) {
            return "yes - same server (" + viewer.server.getName() + ")";
        }
        if (viewer.server.isSpyServer()) {
            return "yes - viewer is in spy server (" + viewer.server.getName() + ")";
        }
        if (viewer.server.getServerGroup() == target.server.getServerGroup()) {
            if (viewer.server.getServerGroup() == ServerGroup.DEFAULT) {
                return "yes - both servers are not listed in any group, so they are put in a default shared group";
            } else {
                return "yes - same server group (" + viewer.server.getServerGroup().getName() + ")";
            }
        }

        return "You should never see this message";
    }

    /**
     * Returns a message about whether viewer can see target player or not and why.
     *
     * @param   target
     *          Target player to see
     * @param   viewer
     *          Tablist viewer
     * @return  Message about whether viewer can see target player or not and why
     */
    @NotNull
    private String getShouldSeeMessage(@NotNull ProxyPlayer target, @NotNull TabPlayer viewer) {
        if (!viewer.server.canSee(target.server)) {
            return "no - servers are in different groups (viewer=" + viewer.server.getServerGroup().getName() +
                    ", target=" + target.server.getServerGroup().getName() + ") and viewer is not in a spy server";
        }
        if (target.isVanished() && !viewer.hasPermission(TabConstants.Permission.SEE_VANISHED)) {
            return "no - target is vanished and viewer lacks permission (" + TabConstants.Permission.SEE_VANISHED + ")";
        }
        if (viewer.botFlags.hidesBots() && botFilter.hides(true, viewer.server != target.server, botFilter.getRemote().isBot(target.getUniqueId()))) {
            return "no - target is a bot on another proxy and viewer hides bots (" + BotTabFilter.HIDE_META + ")";
        }

        if (viewer.server == target.server) {
            return "yes - same server (" + viewer.server.getName() + ")";
        }
        if (viewer.server.isSpyServer()) {
            return "yes - viewer is in spy server (" + viewer.server.getName() + ")";
        }
        if (viewer.server.getServerGroup() == target.server.getServerGroup()) {
            if (viewer.server.getServerGroup() == ServerGroup.DEFAULT) {
                return "yes - both servers are not listed in any group, so they are put in a default shared group";
            } else {
                return "yes - same server group (" + viewer.server.getServerGroup().getName() + ")";
            }
        }

        return "You should never see this message";
    }
}