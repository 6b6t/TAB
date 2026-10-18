package me.neznamy.tab.shared.features.proxy;

import lombok.NonNull;
import me.neznamy.tab.shared.*;
import me.neznamy.tab.shared.chat.component.TabComponent;
import me.neznamy.tab.shared.cpu.*;
import me.neznamy.tab.shared.data.Server;
import me.neznamy.tab.shared.features.globalplayerlist.GlobalPlayerList;
import me.neznamy.tab.shared.features.globalplayerlist.GlobalPlayerListConfiguration;
import me.neznamy.tab.shared.features.proxy.message.PlayerQuit;
import me.neznamy.tab.shared.features.proxy.message.ServerSwitch;
import me.neznamy.tab.shared.features.types.TabFeature;
import me.neznamy.tab.shared.patch6b6t.*;
import me.neznamy.tab.shared.platform.*;
import me.neznamy.tab.shared.platform.decorators.TrackedTabList;
import me.neznamy.tab.shared.util.OnlinePlayers;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.*;
import sun.misc.Unsafe;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * [6b6t patch 6b6t.7] joinloss residue (TEST-2 case 5): the backend a player just left removes him from its players'
 * tab lists on its next tick, which can be after the global playerlist already showed him on his new server. The
 * late remove must not delete the entry for good. The fake tab list below plays Velocity + the packet pipeline: it
 * queues the packets TAB and the "backend" write and {@link FakeTabList#flush()} passes them through the production
 * pipeline hooks of {@link TrackedTabList} in write order.
 */
class EntryReaddTest {
    private static final UUID P = Fix4Test.P;
    private final Fix4Test fixture = new Fix4Test();
    private final List<Runnable> delayed = new ArrayList<>();
    private GlobalPlayerList global;
    private BotTabFilter filter;
    private Fix6Test.Viewer viewer;
    private FakeTabList list;
    private ProxyPlayer copy;
    private Server w0, w1;

    private static <T> T allocate(Class<T> type) throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        return type.cast(((Unsafe) f.get(null)).allocateInstance(type));
    }
    private static void field(Object object, Class<?> type, String name, Object value) throws Exception {
        Fix4Test.field(object, type, name, value);
    }
    private static Runnable body(TimedCaughtTask task) {
        try {
            Field f = TimedCaughtTask.class.getDeclaredField("task"); f.setAccessible(true);
            return (Runnable) f.get(task);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** Velocity + pipeline stand-in: client-side entries, packets written but not yet through the pipeline */
    static final class FakeTabList extends TrackedTabList<TabPlayer> {
        final Set<UUID> client = new HashSet<>();
        final List<Object[]> wire = new ArrayList<>();
        int ownAdds;
        FakeTabList(TabPlayer player) { super(player); }
        @Override public void removeEntry(@NonNull UUID entry) { noteOwnRemove(entry); wire.add(new Object[]{false, entry}); }
        @Override public void addEntry0(@NonNull Entry entry) { removeEntry(entry.getUniqueId()); wire.add(new Object[]{true, entry.getUniqueId()}); ownAdds++; }
        void backendAdd(UUID entry) { wire.add(new Object[]{true, entry}); }
        void backendRemove(UUID entry) { wire.add(new Object[]{false, entry}); }
        /** Passes every written packet through the pipeline hooks, in write order */
        void flush() {
            while (!wire.isEmpty()) {
                Object[] packet = wire.remove(0);
                UUID entry = (UUID) packet[1];
                if ((Boolean) packet[0]) { client.add(entry); onClientAdd(entry); }
                else { client.remove(entry); onClientRemove(Collections.singletonList(entry)); }
            }
        }
        @Override public boolean containsEntry(@NonNull UUID entry) { return client.contains(entry); }
        @Override public @NotNull Object onPacketSend(@NonNull Object packet) { return packet; }
        @Override public void updateDisplayName0(@NonNull UUID entry, @Nullable TabComponent displayName) { }
        @Override public void setPlayerListHeaderFooter0(@NonNull TabComponent header, @NonNull TabComponent footer) { }
        @Override public void updateLatency(@NonNull UUID entry, int latency) { }
        @Override public void updateGameMode(@NonNull UUID entry, int gameMode) { }
        @Override public void updateListed(@NonNull UUID entry, boolean listed) { }
        @Override public void updateListOrder(@NonNull UUID entry, int listOrder) { }
        @Override public void updateHat(@NonNull UUID entry, boolean showHat) { }
        @Override public @Nullable Skin getSkin() { return null; }
    }

    private Fix6Test.Viewer player(String name, Server server, int protocol) throws Exception {
        Fix6Test.Viewer v = allocate(Fix6Test.Viewer.class);
        UUID id = name.equals("viewer") ? UUID.randomUUID() : UUID.nameUUIDFromBytes(name.getBytes());
        field(v, TabPlayer.class, "uniqueId", id);
        field(v, TabPlayer.class, "tablistId", id);
        field(v, TabPlayer.class, "name", name);
        field(v, TabPlayer.class, "nickname", name);
        field(v, TabPlayer.class, "versionId", protocol);
        field(v, TabPlayer.class, "version", ProtocolVersion.fromNetworkId(protocol));
        field(v, TabPlayer.class, "botFlags", new BotFlags());
        field(v, TabPlayer.class, "online", true);
        v.server = server;
        FakeTabList tabList = new FakeTabList(v);
        field(v, TabPlayer.class, "tabList", tabList);
        return v;
    }

    @BeforeEach void setup() throws Exception {
        fixture.setup();
        field(TAB.getInstance(), TAB.class, "playersByTabListId", new HashMap<>());
        // global playerlist enabled, every server in the default group (Server.canSee)
        GlobalPlayerListConfiguration gplConfig = allocate(GlobalPlayerListConfiguration.class);
        me.neznamy.tab.shared.config.files.Config config = allocate(me.neznamy.tab.shared.config.files.Config.class);
        field(config, me.neznamy.tab.shared.config.files.Config.class, "globalPlayerList", gplConfig);
        me.neznamy.tab.shared.config.Configs configs = allocate(me.neznamy.tab.shared.config.Configs.class);
        field(configs, me.neznamy.tab.shared.config.Configs.class, "config", config);
        field(TAB.getInstance(), TAB.class, "configuration", configs);
        w0 = Server.byName("worker-0"); w1 = Server.byName("worker-1");
        viewer = player("viewer", w0, ProtocolVersion.V1_21_11.getNetworkId());
        list = (FakeTabList) viewer.getTabList();
        global = allocate(GlobalPlayerList.class);
        filter = new BotTabFilter(true, null);
        field(global, GlobalPlayerList.class, "botFilter", filter);
        field(global, GlobalPlayerList.class, "proxy", fixture.proxy);
        field(global, GlobalPlayerList.class, "configuration", gplConfig);
        field(global, GlobalPlayerList.class, "readdLimiter", new EntryReaddLimiter());
        field(global, GlobalPlayerList.class, "onlinePlayers", new OnlinePlayers(new TabPlayer[]{viewer}));
        // Run the feature's queued callbacks at once and keep the delayed ones for the test to release
        field(global, GlobalPlayerList.class, "customThread", new ThreadExecutor("entry re-add test") {
            @Override public void execute(TimedCaughtTask task) { body(task).run(); }
            @Override public void executeLater(TimedCaughtTask task, int delayMillis) {
                assertEquals(GlobalPlayerList.READD_CHECK_DELAY_MS, delayMillis);
                delayed.add(body(task));
            }
        });
        FeatureManager manager = TAB.getInstance().getFeatureManager();
        Field features = FeatureManager.class.getDeclaredField("features"); features.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, TabFeature> map = (Map<String, TabFeature>) features.get(manager);
        map.put(TabConstants.Feature.GLOBAL_PLAYER_LIST, global);
        field(manager, FeatureManager.class, "values", new TabFeature[]{global});
        copy = fixture.copy();
        copy.setServer(w0);
        copy.setConnectionState(ProxyPlayer.ConnectionState.CONNECTED);
        // The copy plays on the viewer's server: the backend lists him there
        list.backendAdd(P); list.flush();
    }
    @AfterEach void cleanup() throws Exception {
        if (global != null) global.getCustomThread().shutdown();
        fixture.cleanup();
    }
    private void releaseDelayed() {
        List<Runnable> now = new ArrayList<>(delayed); delayed.clear();
        now.forEach(Runnable::run);
    }
    private void switchCopy(Server to) {
        ServerSwitch change = new ServerSwitch(P, to); change.setSourceProxy("origin"); change.setSequence(50); change.process(fixture.proxy);
    }

    @Test void lateBackendRemoveAfterRemoteSwitchIsPutBack() {
        long before = PatchStats.entryReadded.get();
        switchCopy(w1);                 // TAB shows him for the worker-0 viewer at once
        list.flush();
        assertTrue(list.client.contains(P));
        list.backendRemove(P);          // worker-0 processes his disconnect only now
        list.flush();
        assertFalse(list.client.contains(P), "the late remove deleted the entry (the TEST-2 residue)");
        assertEquals(1, delayed.size());
        releaseDelayed(); list.flush();
        assertTrue(list.client.contains(P));
        assertEquals(before + 1, PatchStats.entryReadded.get());
        assertTrue(delayed.isEmpty(), "TAB's own re-add must not trigger another check");
    }

    @Test void backendRemoveBeforeTheSwitchNeedsNoRepair() {
        list.backendRemove(P); list.flush();   // normal order: backend first, he is still on worker-0 here
        assertTrue(delayed.isEmpty());
        switchCopy(w1); list.flush();
        assertTrue(list.client.contains(P));
        assertTrue(delayed.isEmpty());
    }

    @Test void addOnItsWayWhenTheRemovePassesIsNotDuplicated() {
        copy.setServer(w1);            // switch applied, the feature's add not written yet
        list.backendRemove(P); list.flush();
        assertEquals(1, delayed.size());
        int adds = list.ownAdds;
        global.onServerSwitch(copy); list.flush();   // the feature's own add passes before the check
        releaseDelayed(); list.flush();
        assertTrue(list.client.contains(P));
        assertEquals(adds + 1, list.ownAdds, "only the feature's own add, no extra re-add");
    }

    @Test void ownRemovesNeverStartACheck() {
        switchCopy(w1); list.flush();
        global.onServerSwitch(copy); list.flush();   // remove + add written by TAB itself
        copy.setVanished(true); global.onVanishStatusChange(copy); list.flush();
        assertTrue(delayed.isEmpty());
        assertFalse(list.client.contains(P));
    }

    @Test void intendedAbsenceIsKept() {
        switchCopy(w1); list.flush();
        copy.setVanished(true);         // vanished meanwhile: the viewer must not see him
        list.backendRemove(P); list.flush();
        releaseDelayed(); list.flush();
        assertFalse(list.client.contains(P));
    }

    @Test void quitCopyIsNotPutBack() {
        switchCopy(w1); list.flush();
        list.backendRemove(P); list.flush();
        PlayerQuit quit = new PlayerQuit(P); quit.setSourceProxy("origin"); quit.setSequence(60); quit.process(fixture.proxy);
        list.flush();
        releaseDelayed(); list.flush();
        assertFalse(list.client.contains(P));
    }

    @Test void hiddenBotIsNotPutBack() {
        viewer.botFlags.update(false, true);
        filter.getRemote().handle(RemoteBots.encodeDelta("origin", P, true), "here", System.currentTimeMillis());
        switchCopy(w1); list.flush();
        list.backendRemove(P); list.flush();
        releaseDelayed(); list.flush();
        assertFalse(list.client.contains(P));
    }

    @Test void loopWithAnotherRemoverIsCapped() {
        switchCopy(w1); list.flush();
        for (int i = 0; i < 10; i++) {
            list.backendRemove(P); list.flush();
            releaseDelayed(); list.flush();
        }
        assertEquals(EntryReaddLimiter.MAX_PER_WINDOW + 1, list.ownAdds, "the switch add plus at most the cap");
    }

    @Test void localPlayerOnAnotherServerIsPutBack() throws Exception {
        Fix6Test.Viewer local = player("Local", w1, ProtocolVersion.V1_21_11.getNetworkId());
        Field byId = TAB.class.getDeclaredField("playersByTabListId"); byId.setAccessible(true);
        @SuppressWarnings("unchecked") Map<UUID, TabPlayer> players = (Map<UUID, TabPlayer>) byId.get(TAB.getInstance());
        players.put(local.getTablistId(), local);
        field(global, GlobalPlayerList.class, "onlinePlayers", new OnlinePlayers(new TabPlayer[]{viewer, local}));
        list.backendRemove(local.getTablistId()); list.flush();   // worker-0 removed him after his switch
        releaseDelayed(); list.flush();
        assertTrue(list.client.contains(local.getTablistId()));
        local.server = w0;                                         // back on the viewer's server: the backend's business
        list.backendRemove(local.getTablistId()); list.flush();
        releaseDelayed(); list.flush();
        assertFalse(list.client.contains(local.getTablistId()));
    }

    @Test void olderClientsAndSwitchOffAreUntouched() throws Exception {
        switchCopy(w1); list.flush();
        field(viewer, TabPlayer.class, "versionId", ProtocolVersion.V1_20.getNetworkId());
        list.backendRemove(P); list.flush();
        assertTrue(delayed.isEmpty());
        field(viewer, TabPlayer.class, "versionId", ProtocolVersion.V1_21_11.getNetworkId());
        Properties off = new Properties(); off.setProperty("entry-readd", "false");
        PatchSettings.setForTests(off);
        global.onServerSwitch(copy); list.flush();
        list.backendRemove(P); list.flush();
        assertTrue(delayed.isEmpty());
        assertFalse(list.client.contains(P));
    }
}
