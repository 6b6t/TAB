package me.neznamy.tab.shared.features.proxy;

import me.neznamy.tab.shared.*;
import me.neznamy.tab.shared.cpu.*;
import me.neznamy.tab.shared.data.Server;
import me.neznamy.tab.shared.features.globalplayerlist.GlobalPlayerList;
import me.neznamy.tab.shared.features.proxy.message.PlayerJoin;
import me.neznamy.tab.shared.features.types.TabFeature;
import me.neznamy.tab.shared.patch6b6t.*;
import me.neznamy.tab.shared.platform.*;
import me.neznamy.tab.shared.util.OnlinePlayers;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sun.misc.Unsafe;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BotTabRepairTest {
    private final Fix4Test fixture = new Fix4Test();
    private final List<TabList.Entry> added = new ArrayList<>();
    private GlobalPlayerList global;
    private BotTabFilter filter;
    private ProxyPlayer target;
    private TabPlayer viewer;

    private static <T> T allocate(Class<T> type) throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        return type.cast(((Unsafe) f.get(null)).allocateInstance(type));
    }
    private static void field(Object object, Class<?> type, String name, Object value) throws Exception {
        Fix4Test.field(object, type, name, value);
    }
    @BeforeEach void setup() throws Exception {
        fixture.setup();
        field(TAB.getInstance(), TAB.class, "playersByTabListId", new HashMap<>());
        viewer = allocate(Fix6Test.Viewer.class);
        viewer.server = Server.byName("worker");
        field(viewer, TabPlayer.class, "botFlags", new BotFlags());
        field(viewer, TabPlayer.class, "tabList", java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TabList.class}, (o, m, args) -> {
                    if (m.getName().equals("addEntry")) added.add((TabList.Entry) args[0]);
                    return null;
                }));
        global = allocate(GlobalPlayerList.class);
        filter = new BotTabFilter(true, null);
        field(global, GlobalPlayerList.class, "botFilter", filter);
        field(global, GlobalPlayerList.class, "onlinePlayers", new OnlinePlayers(new TabPlayer[]{viewer}));
        // Execute the queued production callbacks deterministically and propagate their failures to JUnit.
        field(global, GlobalPlayerList.class, "customThread", new ThreadExecutor("bot repair test") {
            @Override public void execute(TimedCaughtTask task) {
                try {
                    Field f = TimedCaughtTask.class.getDeclaredField("task"); f.setAccessible(true);
                    ((Runnable) f.get(task)).run();
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            }
        });
        FeatureManager manager = TAB.getInstance().getFeatureManager();
        Field features = FeatureManager.class.getDeclaredField("features"); features.setAccessible(true);
        ((Map<String, TabFeature>) features.get(manager)).put(TabConstants.Feature.GLOBAL_PLAYER_LIST, global);
        field(manager, FeatureManager.class, "values", new TabFeature[]{global});
        target = fixture.copy();
        target.setConnectionState(ProxyPlayer.ConnectionState.CONNECTED);
    }
    @AfterEach void cleanup() throws Exception {
        if (global != null) global.getCustomThread().shutdown();
        fixture.cleanup();
    }
    private void flags(boolean bot, boolean hides) {
        viewer.botFlags.update(false, hides);
        filter.getRemote().handle(RemoteBots.encodeDelta("origin", Fix4Test.P, bot), "here", System.currentTimeMillis());
    }
    private void assertEntries(boolean visible) {
        assertEquals(visible ? 1 : 0, added.size());
        if (visible) assertEquals(Fix4Test.P, added.getFirst().getUniqueId());
    }

    @ParameterizedTest @CsvSource({"true,true,false", "false,true,true", "true,false,true"})
    void sameServerRestoreRespectsBotVisibility(boolean bot, boolean hides, boolean visible) {
        flags(bot, hides);
        target.setServer(viewer.server);
        global.restoreSameServerEntries(target);
        assertEntries(visible);
    }

    @ParameterizedTest @CsvSource({"true,true,false", "false,true,true", "true,false,true"})
    void playerJoinRefreshRespectsBotVisibility(boolean bot, boolean hides, boolean visible) {
        flags(bot, hides);
        PlayerJoin snapshot = Fix4Test.join("worker", false, 0);
        snapshot.setSourceProxy("origin"); snapshot.setSequence(10);
        snapshot.process(fixture.proxy);
        assertSame(target, fixture.proxy.getProxyPlayers().get(Fix4Test.P));
        assertSame(viewer.server, target.server);
        assertEquals(10, target.getStateSequence());
        assertEntries(visible);
    }

    @ParameterizedTest @CsvSource({"true,true,false", "false,true,true", "true,false,true"})
    void playerJoinVanishRefreshRespectsBotVisibility(boolean bot, boolean hides, boolean visible) {
        flags(bot, hides);
        target.setServer(viewer.server); target.setVanished(true);
        PlayerJoin snapshot = Fix4Test.join("worker", false, 0);
        snapshot.setSourceProxy("origin"); snapshot.setSequence(10);
        snapshot.process(fixture.proxy);
        assertFalse(target.isVanished());
        assertEntries(visible);
    }
}
