package me.neznamy.tab.shared.proxy;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import me.neznamy.tab.shared.GroupManager;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.chat.component.TabComponent;
import me.neznamy.tab.shared.config.Configs;
import me.neznamy.tab.shared.config.file.ConfigurationSection;
import me.neznamy.tab.shared.config.files.Config;
import me.neznamy.tab.shared.cpu.CpuManager;
import me.neznamy.tab.shared.data.Server;
import me.neznamy.tab.shared.FeatureManager;
import me.neznamy.tab.shared.features.PlaceholderManagerImpl;
import me.neznamy.tab.shared.features.playerlist.PlayerList;
import me.neznamy.tab.shared.features.playerlist.PlayerListProxyPlayerData;
import me.neznamy.tab.shared.features.proxy.ProxySupport;
import me.neznamy.tab.shared.patch6b6t.BridgeRequests;
import me.neznamy.tab.shared.patch6b6t.PatchSettings;
import me.neznamy.tab.shared.patch6b6t.PatchStats;
import me.neznamy.tab.shared.placeholders.PlaceholderRefreshConfiguration;
import me.neznamy.tab.shared.placeholders.PlaceholderReplacementsConfiguration;
import me.neznamy.tab.shared.placeholders.PlaceholdersConfiguration;
import me.neznamy.tab.shared.placeholders.expansion.ExpansionData;
import me.neznamy.tab.shared.placeholders.types.PlayerPlaceholderImpl;
import me.neznamy.tab.shared.platform.Platform;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.proxy.message.incoming.PlayerJoinResponse;
import me.neznamy.tab.shared.proxy.message.incoming.UpdatePlaceholder;
import me.neznamy.tab.shared.task.PluginMessageDecodeTask;
import me.neznamy.tab.shared.util.cache.StringToComponentCache;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [6b6t patch 6b6t.8] Proxy side of the bridge fix with the real placeholder manager, message classes and formats:
 * relational values of the other proxy's players (Legend Ultra seal), unanswered placeholders in cross-proxy
 * formats, answers stopping the retry, and values kept through a reload.
 */
class BridgeProxyTest {
    static final String SEAL = "%rel_seal%";
    static final String COLOR = "%lp_color%";
    private TAB previous;
    private CpuManager cpu;
    private PlaceholderManagerImpl manager;
    private TAB tab;

    /** Proxy player without Velocity: records the plugin messages it would send */
    static class Fake extends ProxyTabPlayer {
        final List<byte[]> sent = new ArrayList<>();
        @SuppressWarnings("ConstantConditions") Fake() { super(null, null, null, null, null, 0); }
        @Override public boolean hasPermission0(String permission) { return false; }
        @Override public void sendPluginMessage(byte[] message) { sent.add(message); }
        @Override public int getPing() { return 0; }
        @Override public void sendMessage(@NotNull TabComponent message) { }
        @Override public Platform getPlatform() { return null; }
        @Override public Object getPlayer() { return null; }
    }

    static <T> T allocate(Class<T> type) throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        return type.cast(((Unsafe) f.get(null)).allocateInstance(type));
    }

    static void field(Object object, Class<?> type, String name, Object value) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(object, value);
    }

    private Fake player(String name, String server) throws Exception {
        Fake p = allocate(Fake.class);
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        field(p, TabPlayer.class, "uniqueId", id);
        field(p, TabPlayer.class, "tablistId", id);
        field(p, TabPlayer.class, "name", name);
        field(p, TabPlayer.class, "nickname", name);
        field(p, TabPlayer.class, "online", true);
        field(p, TabPlayer.class, "loaded", false); // values are stored, no feature refresh in this fixture
        field(p, TabPlayer.class, "lastPlaceholderValues", new ConcurrentHashMap<>());
        field(p, TabPlayer.class, "lastRelationalValues", new ConcurrentHashMap<>());
        field(p, TabPlayer.class, "expansionData", new ExpansionData(p));
        field(p, ProxyTabPlayer.class, "permissions", new HashMap<>());
        field(p, ProxyTabPlayer.class, "placeholders", new ConcurrentHashMap<>());
        field(p, ProxyTabPlayer.class, "remoteRelational", new ConcurrentHashMap<>());
        field(p, ProxyTabPlayer.class, "bridgeFlagUpdates", new java.util.concurrent.atomic.AtomicIntegerArray(3));
        field(p, Fake.class, "sent", new ArrayList<>());
        p.server = Server.byName(server);
        return p;
    }

    private void online(TabPlayer... players) throws Exception {
        field(tab, TAB.class, "onlinePlayers", players);
        Map<String, TabPlayer> byName = new ConcurrentHashMap<>();
        Map<UUID, TabPlayer> data = new ConcurrentHashMap<>();
        for (TabPlayer p : players) { byName.put(p.getName(), p); data.put(p.getUniqueId(), p); }
        field(tab, TAB.class, "data", data);
        field(tab, TAB.class, "playersByName", byName);
    }

    @BeforeEach void setup() throws Exception {
        PatchSettings.setForTests(new Properties());
        previous = TAB.getInstance();
        tab = allocate(TAB.class);
        field(null, TAB.class, "instance", tab);
        Configs configs = allocate(Configs.class);
        Config config = allocate(Config.class);
        ConfigurationSection section = allocate(ConfigurationSection.class);
        field(config, Config.class, "placeholders", new PlaceholdersConfiguration(section,
                new java.text.SimpleDateFormat(), new java.text.SimpleDateFormat(), 0, false));
        // Production has exactly this: the raw seal text becomes empty
        Map<String, Map<Object, Object>> replacements = new HashMap<>();
        replacements.put(SEAL, new HashMap<>(Map.of(SEAL, "", "gold", "&6✦")));
        field(config, Config.class, "replacements", new PlaceholderReplacementsConfiguration(section, replacements));
        field(config, Config.class, "components", new me.neznamy.tab.shared.chat.ComponentConfiguration(section, false, false));
        field(configs, Configs.class, "config", config);
        field(tab, TAB.class, "configuration", configs);
        field(tab, TAB.class, "onlinePlayers", new TabPlayer[0]);
        field(tab, TAB.class, "data", new ConcurrentHashMap<>());
        field(tab, TAB.class, "playersByName", new ConcurrentHashMap<>());
        field(tab, TAB.class, "featureManager", new FeatureManager());
        field(tab, TAB.class, "dataManager", new me.neznamy.tab.shared.data.DataManager());
        cpu = new CpuManager();
        field(tab, TAB.class, "cpu", cpu);
        manager = new PlaceholderManagerImpl(cpu, new PlaceholderRefreshConfiguration(section, 500, Map.of(), 500));
        field(tab, TAB.class, "placeholderManager", manager);
        GroupManager groups = allocate(GroupManager.class);
        field(groups, GroupManager.class, "permissionPlugin", "LuckPerms");
        field(tab, TAB.class, "groupManager", groups);
        field(tab, TAB.class, "platform", java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Platform.class}, (o, m, args) -> {
                    if (m.getName().equals("registerUnknownPlaceholder")) manager.registerPlayerPlaceholder((String) args[0], -1, ignored -> "");
                    if (m.getName().equals("isProxy")) return true;
                    if (m.getName().equals("getCommand")) return "btab";
                    return null;
                }));
        manager.registerRelationalBridgePlaceholder(SEAL, 500);
        manager.registerBridgePlaceholder(COLOR, 500);
        manager.registerPlayerPlaceholder(me.neznamy.tab.shared.TabConstants.Placeholder.WORLD, -1, p -> "world");
    }

    @AfterEach void cleanup() throws Exception {
        if (cpu != null) cpu.cancelAllTasks();
        BridgeRequests.end();
        BridgeRequests.clearCarry();
        PatchSettings.setForTests(new Properties());
        field(null, TAB.class, "instance", previous);
    }

    private static byte[] updateRel(String identifier, String target, String value) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(identifier); out.writeUTF(target); out.writeUTF(value);
        return out.toByteArray();
    }

    private PlayerListProxyPlayerData remoteFormat(String name, String prefix) throws Exception {
        PlayerList feature = allocate(PlayerList.class);
        field(feature, PlayerList.class, "cache", new StringToComponentCache("test", 100));
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeLong(1); UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
        out.writeUTF(name); out.writeUTF(prefix); out.writeUTF(name); out.writeUTF(""); out.writeBoolean(false);
        return new PlayerListProxyPlayerData(ByteStreams.newDataInput(out.toByteArray()), feature);
    }

    @Test void sealOfAPlayerOnTheOtherProxyShowsPerViewerAndNeverRaw() throws Exception {
        Fake withValue = player("ViewerA", "worker-2"), withoutValue = player("ViewerB", "worker-0");
        online(withValue, withoutValue);
        // The bridge of worker-2 sends ViewerA the seal of Remote1 (a player of the other proxy on worker-2)
        UpdatePlaceholder update = new UpdatePlaceholder();
        update.read(ByteStreams.newDataInput(updateRel(SEAL, "Remote1", "gold")));
        update.process(withValue);
        assertEquals("gold", RemoteRelational.get(withValue, SEAL, "remote1"));

        PlayerListProxyPlayerData data = remoteFormat("Remote1", SEAL + "§7[Player] ");
        assertTrue(data.isRelational());
        String a = data.getFormatComponent(withValue).toLegacyText();
        String b = data.getFormatComponent(withoutValue).toLegacyText();
        String none = data.getFormatComponent().toLegacyText();
        assertTrue(a.contains("✦"), "viewer with the value sees the seal (output replacement applied): " + a);
        assertFalse(a.contains("%rel_") || b.contains("%rel_") || none.contains("%rel_"), "never raw: " + a + " | " + b + " | " + none);
        assertFalse(b.contains("✦"));
        assertTrue(b.contains("[Player] Remote1"), b);

        // Switch off: everybody gets the empty output, still never raw
        Properties off = new Properties(); off.setProperty("remote-relational", "false"); PatchSettings.setForTests(off);
        String offText = data.getFormatComponent(withValue).toLegacyText();
        assertTrue(offText.contains("[Player] Remote1") && !offText.contains("✦") && !offText.contains("%rel_"), offText);
    }

    @Test void relationalPlaceholderWithoutConfiguredReplacementIsStillNeverRaw() throws Exception {
        manager.registerRelationalBridgePlaceholder("%rel_other%", 500);
        Fake viewer = player("Viewer", "worker-1");
        online(viewer);
        assertEquals("§7Remote1", RemoteRelational.render("%rel_other%§7Remote1", viewer, "Remote1"));
        assertEquals("§7Remote1", RemoteRelational.render("%rel_unknown_to_this_proxy%§7Remote1", viewer, "Remote1"));
        RemoteRelational.store(viewer, "%rel_other%", "Remote1", "&cX");
        assertEquals("§cX§7Remote1", RemoteRelational.render("%rel_other%§7Remote1", viewer, "Remote1"));
    }

    @Test void proxyPluginResolverServesOtherProxyPlayersFirst() throws Exception {
        Fake viewer = player("Viewer", "worker-0");
        online(viewer);
        UUID remote = UUID.nameUUIDFromBytes("Remote1".getBytes());
        RemoteRelational.store(viewer, SEAL, "Remote1", "gold"); // what the viewer's bridge sent
        List<String> asked = new ArrayList<>();
        try {
            RemoteRelational.registerResolver(SEAL, (v, id, name, server) -> {
                asked.add(v.getName() + "/" + id + "/" + name + "/" + server);
                return name.equals("Remote1") ? "&d*" : null;
            });
            assertEquals("§d*§7Remote1", RemoteRelational.render(SEAL + "§7Remote1", viewer, remote, "Remote1"), "resolver wins");
            assertEquals("Viewer/" + remote + "/Remote1/null", asked.get(0));
            UUID other = UUID.randomUUID();
            RemoteRelational.store(viewer, SEAL, "Remote2", "gold");
            assertTrue(RemoteRelational.render(SEAL + "§7Remote2", viewer, other, "Remote2").contains("✦"), "null = bridge value");
            assertEquals("§7Remote3", RemoteRelational.render(SEAL + "§7Remote3", viewer, UUID.randomUUID(), "Remote3"), "neither = empty");
            RemoteRelational.registerResolver(SEAL, (v, id, name, server) -> { throw new IllegalStateException("broken plugin"); });
            assertTrue(RemoteRelational.render(SEAL + "§7Remote1", viewer, remote, "Remote1").contains("✦"), "a throwing resolver falls back");
            assertThrows(IllegalArgumentException.class, () -> RemoteRelational.registerResolver("%seal%", (v, id, n, srv) -> "x"));
        } finally {
            RemoteRelational.unregisterResolver(SEAL);
        }
        assertTrue(RemoteRelational.render(SEAL + "§7Remote1", viewer, remote, "Remote1").contains("✦"), "unregistered = bridge value");
    }

    @Test void localTargetsKeepTheUpstreamPath() throws Exception {
        Fake viewer = player("Viewer", "worker-2"), local = player("Local1", "worker-2");
        online(viewer, local);
        UpdatePlaceholder update = new UpdatePlaceholder();
        update.read(ByteStreams.newDataInput(updateRel(SEAL, "Local1", "gold")));
        update.process(viewer);
        assertTrue(viewer.getRemoteRelational().isEmpty(), "local targets are not stored as remote");
    }

    @Test void joinAnswerStoresRemoteValuesAndStopsTheRetry() throws Exception {
        Fake viewer = player("Viewer", "worker-2");
        online(viewer);
        List<Fake> resent = new ArrayList<>();
        BridgeRequests<ProxyTabPlayer> tracker = BridgeRequests.begin(new BridgeRequests.Port<ProxyTabPlayer>() {
            public boolean isCurrent(@NotNull ProxyTabPlayer p) { return true; }
            public String currentServer(@NotNull ProxyTabPlayer p) { return p.server.getName(); }
            public boolean isAnswered(@NotNull ProxyTabPlayer p) { return p.isBridgeConnected(); }
            public void send(@NotNull ProxyTabPlayer p) { resent.add((Fake) p); }
            public void log(@NotNull String line) { }
        });
        tracker.requestSent(viewer.getUniqueId(), viewer, "worker-2");
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(9); // PlayerJoinResponse id in PluginMessageDecodeTask
        out.writeUTF("world");
        out.writeInt(2);
        out.writeUTF(COLOR); out.writeUTF("&a");
        out.writeUTF(SEAL); out.writeInt(2); out.writeUTF("Remote1"); out.writeUTF("gold"); out.writeUTF("Remote2"); out.writeUTF("");
        out.writeInt(0);
        // decode exactly as production does, with the server the message came from
        PlayerJoinResponse response = new PlayerJoinResponse();
        com.google.common.io.ByteArrayDataInput in = ByteStreams.newDataInput(out.toByteArray());
        assertEquals(9, in.readByte());
        response.read(in);
        response.setSourceServer("worker-2");
        response.process(viewer);
        assertTrue(viewer.isBridgeConnected());
        assertFalse(tracker.isPending(viewer.getUniqueId()), "answer stops the retry");
        assertTrue(BridgeRequests.answeredServers().contains("worker-2"));
        assertEquals("gold", RemoteRelational.get(viewer, SEAL, "Remote1"));
        assertEquals("", RemoteRelational.get(viewer, SEAL, "remote2"));
        assertEquals("&a", viewer.getPlaceholders().get(COLOR));
        assertTrue(resent.isEmpty());
        RemoteRelational.forget("REMOTE1");
        assertNull(RemoteRelational.get(viewer, SEAL, "Remote1"));
    }

    @Test void decodeTaskPassesTheSourceServer() throws Exception {
        Fake viewer = player("Viewer", "worker-2");
        online(viewer);
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(9); out.writeUTF("world"); out.writeInt(0); out.writeInt(0);
        new PluginMessageDecodeTask(viewer.getUniqueId(), out.toByteArray(), "worker-1").run();
        Field queueField = CpuManager.class.getDeclaredField("taskQueue"); queueField.setAccessible(true);
        Object task = ((Queue<?>) queueField.get(cpu)).poll(); // CPU not enabled yet: the process task waits here
        assertNotNull(task);
        Field msgField = task.getClass().getDeclaredField("message"); msgField.setAccessible(true);
        Object msg = msgField.get(task);
        assertTrue(msg instanceof PlayerJoinResponse);
        Field source = PlayerJoinResponse.class.getDeclaredField("sourceServer"); source.setAccessible(true);
        assertEquals("worker-1", source.get(msg));
    }

    @Test void unansweredBackendPlaceholdersNeverTravelRaw() throws Exception {
        Fake owner = player("Owner", "worker-2");
        online(owner);
        manager.registerPlayerPlaceholder("%proxyside%", 500, p -> "x");
        String text = "§7" + COLOR + "[Player] " + SEAL + "%proxyside%";
        long before = PatchStats.bridgeRawStripped.get();
        assertEquals("§7[Player] " + SEAL + "%proxyside%", RemoteRelational.stripUnanswered(owner, text),
                "only the backend placeholder without a value goes; relational stays for the receiver, proxy-side stays");
        assertEquals(1, PatchStats.bridgeRawStripped.get() - before);
        owner.getPlaceholders().put(COLOR, COLOR); // the bridge answered with the raw text (expansion missing): still raw
        assertEquals("§7[Player] " + SEAL + "%proxyside%", RemoteRelational.stripUnanswered(owner, text));
        owner.getPlaceholders().clear();
        Properties off = new Properties(); off.setProperty("bridge-raw-strip", "false"); PatchSettings.setForTests(off);
        assertEquals(text, RemoteRelational.stripUnanswered(owner, text));
        assertEquals("§7[Player] ", RemoteRelational.withoutRelational("§7[Player] " + SEAL));
    }

    private ProxySupport proxySupport() throws Exception {
        ProxySupport proxy = new ProxySupport("test") {
            public void sendMessage(String message) { }
            public void register() { }
            public void unregister() { }
        };
        TAB.getInstance().getFeatureManager().registerFeature(me.neznamy.tab.shared.TabConstants.Feature.PROXY_SUPPORT, proxy);
        return proxy;
    }

    private static byte[] joinAnswer(String colorValue) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("world"); out.writeInt(1); out.writeUTF(COLOR); out.writeUTF(colorValue); out.writeInt(0);
        return out.toByteArray();
    }

    @Test void lateAnswerOfThePreviousServerChangesNothingAndTheRetryStillHappens() throws Exception {
        Fake p = player("Mover", "worker-2"); // switched worker-0 -> worker-2; worker-2's answer is lost
        online(p);
        p.getPlaceholders().put(COLOR, "&cOLD");
        List<ProxyTabPlayer> resent = new ArrayList<>();
        long[] now = {0};
        java.util.Set<String> servers = new java.util.HashSet<>(Set.of("worker-0", "worker-2"));
        BridgeRequests<ProxyTabPlayer> tracker = new BridgeRequests<>(new BridgeRequests.Port<ProxyTabPlayer>() {
            public boolean isCurrent(@NotNull ProxyTabPlayer x) { return true; }
            public String currentServer(@NotNull ProxyTabPlayer x) { return x.server.getName(); }
            public boolean isAnswered(@NotNull ProxyTabPlayer x) { return x.isBridgeConnected(); }
            public void send(@NotNull ProxyTabPlayer x) { resent.add(x); }
            public void log(@NotNull String line) { }
        }, PatchSettings::get, () -> now[0], servers);
        tracker.requestSent(p.getUniqueId(), p, "worker-2");
        PlayerJoinResponse old = new PlayerJoinResponse();
        old.read(ByteStreams.newDataInput(joinAnswer("&aWORKER0")));
        old.setSourceServer("worker-0");
        long dropped = PatchStats.bridgeForeignDropped.get();
        new me.neznamy.tab.shared.task.PluginMessageProcessTask(old, p, "worker-0").run(); // production path, processing thread
        assertEquals(1, PatchStats.bridgeForeignDropped.get() - dropped);
        assertFalse(p.isBridgeConnected(), "the old server's answer must not mark him answered");
        assertEquals("&cOLD", p.getPlaceholders().get(COLOR), "nor change his values");
        for (int i = 0; i < 41; i++) { now[0] += 250; tracker.tick(); }
        assertEquals(1, resent.size(), "worker-2 is asked again");
        // the answer of the current server is processed normally
        PlayerJoinResponse current = new PlayerJoinResponse();
        current.read(ByteStreams.newDataInput(joinAnswer("&aWORKER2")));
        new me.neznamy.tab.shared.task.PluginMessageProcessTask(current, p, "worker-2").run();
        assertTrue(p.isBridgeConnected());
        assertEquals("&aWORKER2", p.getPlaceholders().get(COLOR));
    }

    @Test void vanishedPlayerStaysHiddenThroughASameServerAnswer() throws Exception {
        Fake p = player("Staff", "worker-2");
        online(p);
        p.vanished = true;
        field(p, ProxyTabPlayer.class, "bridgeStateServer", "worker-2"); // e.g. carried through a reload, or answered before
        PlayerJoinResponse refresh = new PlayerJoinResponse();
        refresh.read(ByteStreams.newDataInput(joinAnswer("&a")));
        refresh.setSourceServer("worker-2");
        refresh.process(p); // bridgeresync / retry / reload answer from the same worker
        assertTrue(p.isVanished(), "no reveal while the bridge has not reported yet");
        // the bridge's status poll confirms vanish within ~1 s
        int[] seen = p.flagUpdateCounts();
        me.neznamy.tab.shared.proxy.message.incoming.Vanished confirm = new me.neznamy.tab.shared.proxy.message.incoming.Vanished();
        confirm.read(ByteStreams.newDataInput(new byte[]{1}));
        confirm.process(p); // SetVanished(true) from the new BridgePlayer's first status poll
        p.finishPostponedFlagReset("worker-2", seen);
        assertTrue(p.isVanished(), "confirmed: stays hidden");
        // no confirmation (he un-vanished meanwhile): reset like upstream, after the delay
        int[] seen2 = p.flagUpdateCounts();
        p.finishPostponedFlagReset("worker-2", seen2);
        assertFalse(p.isVanished());
        // an answer from a NEW server resets at once (upstream: the new server reports vanish only when it is on)
        p.vanished = true;
        PlayerJoinResponse other = new PlayerJoinResponse();
        other.read(ByteStreams.newDataInput(joinAnswer("&a")));
        other.setSourceServer("worker-1");
        p.server = me.neznamy.tab.shared.data.Server.byName("worker-1");
        other.process(p);
        assertFalse(p.isVanished());
    }

    private static byte[] bool(boolean b) { return new byte[]{(byte) (b ? 1 : 0)}; }

    @Test void eachFlagIsReconciledOnItsOwn() throws Exception {
        // vanished + invisible held; he un-vanished meanwhile, the new BridgePlayer only reports Invisible(true)
        Fake p = player("Staff", "worker-2");
        online(p);
        p.vanished = true;
        field(p, ProxyTabPlayer.class, "invisibilityPotion", true);
        field(p, ProxyTabPlayer.class, "bridgeStateServer", "worker-2");
        PlayerJoinResponse refresh = new PlayerJoinResponse();
        refresh.read(ByteStreams.newDataInput(joinAnswer("&a")));
        refresh.setSourceServer("worker-2");
        refresh.process(p);
        int[] seen = p.flagUpdateCounts();
        me.neznamy.tab.shared.proxy.message.incoming.Invisible inv = new me.neznamy.tab.shared.proxy.message.incoming.Invisible();
        inv.read(ByteStreams.newDataInput(bool(true)));
        inv.process(p);
        p.finishPostponedFlagReset("worker-2", seen);
        assertFalse(p.isVanished(), "vanish was not confirmed: reset");
        assertTrue(p.hasInvisibilityPotion(), "invisibility was confirmed: kept");
        // the other way round: only Vanished(true) confirmed, the stale invisibility goes
        p.vanished = true;
        refresh = new PlayerJoinResponse();
        refresh.read(ByteStreams.newDataInput(joinAnswer("&a")));
        refresh.setSourceServer("worker-2");
        refresh.process(p);
        seen = p.flagUpdateCounts();
        me.neznamy.tab.shared.proxy.message.incoming.Vanished van = new me.neznamy.tab.shared.proxy.message.incoming.Vanished();
        van.read(ByteStreams.newDataInput(bool(true)));
        van.process(p);
        p.finishPostponedFlagReset("worker-2", seen);
        assertTrue(p.isVanished());
        assertFalse(p.hasInvisibilityPotion());
    }

    @Test void scheduledFlagCheckRunsAfterTheDelay() throws Exception {
        Fake p = player("Staff", "worker-2");
        online(p);
        p.vanished = true;
        field(p, ProxyTabPlayer.class, "invisibilityPotion", true);
        field(p, ProxyTabPlayer.class, "bridgeStateServer", "worker-2");
        PlayerJoinResponse refresh = new PlayerJoinResponse();
        refresh.read(ByteStreams.newDataInput(joinAnswer("&a")));
        refresh.setSourceServer("worker-2");
        refresh.process(p); // schedules the check on TAB's processing thread (ProxyTabPlayer.FLAG_RESET_DELAY_MILLIS)
        me.neznamy.tab.shared.proxy.message.incoming.Invisible inv = new me.neznamy.tab.shared.proxy.message.incoming.Invisible();
        inv.read(ByteStreams.newDataInput(bool(true)));
        inv.process(p);
        Thread.sleep(ProxyTabPlayer.FLAG_RESET_DELAY_MILLIS / 2);
        assertTrue(p.isVanished(), "nothing reset before the delay");
        long end = System.currentTimeMillis() + ProxyTabPlayer.FLAG_RESET_DELAY_MILLIS + 3000;
        while (p.isVanished() && System.currentTimeMillis() < end) Thread.sleep(50);
        assertFalse(p.isVanished(), "the scheduled check reset the unconfirmed vanish");
        assertTrue(p.hasInvisibilityPotion(), "and kept the confirmed invisibility");
    }

    @Test void relationalUpdateKeepsADisabledRemoteFormatCleared() throws Exception {
        ProxySupport proxy = proxySupport();
        PlayerList feature = allocate(PlayerList.class);
        field(feature, PlayerList.class, "cache", new StringToComponentCache("test", 100));
        TAB.getInstance().getFeatureManager().registerFeature(me.neznamy.tab.shared.TabConstants.Feature.PLAYER_LIST, feature);
        Fake viewer = player("Viewer", "worker-2");
        List<Object> writes = new ArrayList<>();
        field(viewer, TabPlayer.class, "tabList", java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{me.neznamy.tab.shared.platform.TabList.class}, (o, m, args) -> {
                    if (m.getName().equals("updateDisplayName")) writes.add(args[1]);
                    return m.getReturnType() == boolean.class ? false : null;
                }));
        online(viewer);
        UUID remoteId = UUID.nameUUIDFromBytes("Remote1".getBytes());
        me.neznamy.tab.shared.features.proxy.ProxyPlayer copy = new me.neznamy.tab.shared.features.proxy.ProxyPlayer(
                remoteId, remoteId, "Remote1", me.neznamy.tab.shared.data.Server.byName("worker-2"), false, false, null);
        proxy.getProxyPlayers().put(remoteId, copy);
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeLong(1); out.writeLong(remoteId.getMostSignificantBits()); out.writeLong(remoteId.getLeastSignificantBits());
        out.writeUTF("Remote1"); out.writeUTF(SEAL + "§7"); out.writeUTF("Remote1"); out.writeUTF(""); out.writeBoolean(true); // disabled
        copy.setTabFormat(new PlayerListProxyPlayerData(ByteStreams.newDataInput(out.toByteArray()), feature));
        UpdatePlaceholder update = new UpdatePlaceholder();
        update.read(ByteStreams.newDataInput(updateRel(SEAL, "Remote1", "gold")));
        update.process(viewer);
        assertTrue(writes.isEmpty(), "a disabled format keeps its cleared display name: " + writes);
        // enabled: the new value is shown
        out = ByteStreams.newDataOutput();
        out.writeLong(2); out.writeLong(remoteId.getMostSignificantBits()); out.writeLong(remoteId.getLeastSignificantBits());
        out.writeUTF("Remote1"); out.writeUTF(SEAL + "§7"); out.writeUTF("Remote1"); out.writeUTF(""); out.writeBoolean(false);
        copy.setTabFormat(new PlayerListProxyPlayerData(ByteStreams.newDataInput(out.toByteArray()), feature));
        UpdatePlaceholder update2 = new UpdatePlaceholder();
        update2.read(ByteStreams.newDataInput(updateRel(SEAL, "Remote1", "&6x")));
        update2.process(viewer);
        assertEquals(1, writes.size());
    }

    @Test void remoteRelationalValuesAreOnlyShownWhileOnTheSameServer() throws Exception {
        ProxySupport proxy = proxySupport();
        Fake viewer = player("Viewer", "worker-2");
        online(viewer);
        UUID remoteId = UUID.nameUUIDFromBytes("Remote1".getBytes());
        me.neznamy.tab.shared.features.proxy.ProxyPlayer copy = new me.neznamy.tab.shared.features.proxy.ProxyPlayer(
                remoteId, remoteId, "Remote1", me.neznamy.tab.shared.data.Server.byName("worker-2"), false, false, null);
        proxy.getProxyPlayers().put(remoteId, copy);
        RemoteRelational.store(viewer, SEAL, "Remote1", "gold");
        assertTrue(RemoteRelational.render(SEAL + "§7Remote1", viewer, remoteId, "Remote1").contains("✦"));
        copy.setServer(me.neznamy.tab.shared.data.Server.byName("worker-1")); // he moved to another worker
        assertEquals("§7Remote1", RemoteRelational.render(SEAL + "§7Remote1", viewer, remoteId, "Remote1"), "no stale seal");
        copy.setServer(me.neznamy.tab.shared.data.Server.byName("worker-2"));
        // the viewer switches: the values of his previous server's bridge are dropped before the new request goes out
        viewer.sendJoinPluginMessage();
        assertTrue(viewer.getRemoteRelational().isEmpty());
        assertEquals("§7Remote1", RemoteRelational.render(SEAL + "§7Remote1", viewer, remoteId, "Remote1"));
        // switch off: nothing is kept at all
        Properties off = new Properties(); off.setProperty("remote-relational", "false"); PatchSettings.setForTests(off);
        assertFalse(RemoteRelational.store(viewer, SEAL, "Remote1", "gold"));
        assertTrue(viewer.getRemoteRelational().isEmpty());
    }

    @Test void reloadKeepsValuesOfTheSameServer() throws Exception {
        Fake before = player("Player1", "worker-2");
        before.getPlaceholders().put(COLOR, "&b");
        before.getPlaceholders().put(SEAL, "{Remote1=gold}");
        before.vanished = true;
        field(before, ProxyTabPlayer.class, "gamemode", 3);
        BridgeCarry.snapshot(new TabPlayer[]{before});
        assertEquals(1, BridgeRequests.carriedCount());
        BridgeRequests.Carry carry = BridgeRequests.takeCarry(before.getUniqueId(), "worker-2", System.currentTimeMillis());
        assertNotNull(carry);
        assertTrue(carry.vanished, "a vanished player stays hidden through the reload");
        assertEquals(3, carry.gamemode);
        Map<String, String> carried = carry.values;
        assertEquals(Map.of(COLOR, "&b"), carried);
        // the new copy of the player starts with them; bridge placeholders registered before the players get them now
        Fake after = player("Player1", "worker-2");
        after.getPlaceholders().putAll(carried);
        online(after);
        BridgeCarry.applyCarriedValues();
        PlayerPlaceholderImpl color = (PlayerPlaceholderImpl) manager.getPlaceholderRaw(COLOR).getHandle();
        assertEquals("&b", color.getLastValue(after)); // colors are applied by the Property, as for bridge answers
        // switch off: nothing is kept
        Properties off = new Properties(); off.setProperty("bridge-reload-carry", "false"); PatchSettings.setForTests(off);
        BridgeCarry.snapshot(new TabPlayer[]{before});
        assertEquals(0, BridgeRequests.carriedCount());
    }
}
