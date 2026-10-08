package me.neznamy.tab.shared.features;

import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.chat.component.TabComponent;
import me.neznamy.tab.shared.config.Configs;
import me.neznamy.tab.shared.config.file.ConfigurationSection;
import me.neznamy.tab.shared.config.files.Config;
import me.neznamy.tab.shared.cpu.CpuManager;
import me.neznamy.tab.shared.cpu.ThreadExecutor;
import me.neznamy.tab.shared.features.sorting.Sorting;
import me.neznamy.tab.shared.features.sorting.SortingConfiguration;
import me.neznamy.tab.shared.features.sorting.SortingPlayerData;
import me.neznamy.tab.shared.features.types.CustomThreaded;
import me.neznamy.tab.shared.features.types.RefreshableFeature;
import me.neznamy.tab.shared.placeholders.*;
import me.neznamy.tab.shared.placeholders.expansion.ExpansionData;
import me.neznamy.tab.shared.placeholders.types.RelationalPlaceholderImpl;
import me.neznamy.tab.shared.placeholders.types.TabPlaceholder;
import me.neznamy.tab.shared.platform.Platform;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real placeholder polling and Sorting, with the minimal platform fixture used by fork tests. */
class PlaceholderRegistrationTest {
    private static final String ID = "%6b6t_tabrank%";
    private TAB previous;
    private CpuManager cpu;
    private PlaceholderManagerImpl manager;
    private Viewer player;
    private ConfigurationSection section;

    @BeforeEach void setup() throws Exception {
        previous = TAB.getInstance();
        TAB tab = allocate(TAB.class);
        field(null, TAB.class, "instance", tab);
        Configs configs = allocate(Configs.class);
        Config config = allocate(Config.class);
        section = allocate(ConfigurationSection.class);
        field(config, Config.class, "placeholders", new PlaceholdersConfiguration(section,
                new java.text.SimpleDateFormat(), new java.text.SimpleDateFormat(), 0, false));
        field(config, Config.class, "replacements", new PlaceholderReplacementsConfiguration(section, Map.of()));
        field(configs, Configs.class, "config", config);
        field(tab, TAB.class, "configuration", configs);
        field(tab, TAB.class, "onlinePlayers", new TabPlayer[0]);
        cpu = new CpuManager();
        field(tab, TAB.class, "cpu", cpu);
        manager = new PlaceholderManagerImpl(cpu, new PlaceholderRefreshConfiguration(section, 500, Map.of(), 500));
        field(tab, TAB.class, "placeholderManager", manager);
        field(tab, TAB.class, "platform", java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Platform.class}, (o, m, args) -> {
                    if (m.getName().equals("registerUnknownPlaceholder"))
                        manager.registerPlayerPlaceholder((String) args[0], 500, ignored -> "default");
                    return null;
                }));
        player = allocate(Viewer.class);
        field(player, TabPlayer.class, "online", true);
        field(player, TabPlayer.class, "loaded", true);
        field(player, TabPlayer.class, "lastPlaceholderValues", new ConcurrentHashMap<>());
        field(player, TabPlayer.class, "lastRelationalValues", new ConcurrentHashMap<>());
        field(tab, TAB.class, "data", new ConcurrentHashMap<>(Map.of(UUID.randomUUID(), player)));
        field(player, TabPlayer.class, "sortingData", new SortingPlayerData());
        field(player, TabPlayer.class, "expansionData", new ExpansionData(player));
    }

    @AfterEach void cleanup() throws Exception {
        if (cpu != null) cpu.cancelAllTasks();
        field(null, TAB.class, "instance", previous);
    }

    @Test void registrationAfterSortingPreservesLiveResortingAndReplacementInterval() throws Exception {
        Sorting sorting = new Sorting(new SortingConfiguration(section, true,
                List.of("PLACEHOLDER:" + ID + ":legend,prime,default")));
        PlaceholderReference reference = manager.getPlaceholderReference(ID);
        assertTrue(reference.getHandle().getUsedByFeatures().contains(sorting));
        field(TAB.getInstance(), TAB.class, "onlinePlayers", new TabPlayer[]{player});
        sorting.onJoin(player);
        assertEquals("2A", player.sortingData.shortTeamName);
        AtomicReference<String> value = new AtomicReference<>("legend");
        manager.registerPlayerPlaceholder(ID, 1000, ignored -> value.get());
        assertSame(reference, manager.getPlaceholderReference(ID));
        assertEquals(1000, reference.getRefresh());
        drainProcessing();
        assertEquals("0A", player.sortingData.shortTeamName); // Existing forced refresh on registration.
        value.set("prime");
        tick(500);
        assertEquals("0A", player.sortingData.shortTeamName);
        tick(500);
        assertEquals("1A", player.sortingData.shortTeamName);
        value.set("default"); // Online downgrade, without relogging.
        tick(1000);
        assertEquals("2A", player.sortingData.shortTeamName);
        manager.registerPlayerPlaceholder(ID, -1, ignored -> value.get());
        assertEquals(-1, reference.getRefresh());
        drainProcessing();
        value.set("legend");
        tick(1000);
        assertEquals("2A", player.sortingData.shortTeamName);
        manager.registerPlayerPlaceholder(ID, 1000, ignored -> value.get());
        drainProcessing();
        assertEquals("0A", player.sortingData.shortTeamName);
        value.set("prime");
        tick(1000);
        assertEquals("1A", player.sortingData.shortTeamName);
    }

    @Test void nestedReplacementRewiresParentsAndChildren() throws Exception {
        AtomicReference<String> rank = new AtomicReference<>("legend");
        var child = manager.registerPlayerPlaceholder("%child%", 1000, ignored -> rank.get());
        var middle = manager.registerPlayerPlaceholder(ID, 1000, ignored -> "%child%");
        var parent = manager.registerPlayerPlaceholder("%parent%", -1, ignored -> ID);
        Sorting sorting = new Sorting(new SortingConfiguration(section, true,
                List.of("PLACEHOLDER:%parent%:legend,prime,default")));
        field(TAB.getInstance(), TAB.class, "onlinePlayers", new TabPlayer[]{player});
        sorting.onJoin(player); // Discover the actual nested links through parse.
        assertEquals("0A", player.sortingData.shortTeamName);
        var replacement = manager.registerPlayerPlaceholder(ID, 1000, ignored -> "%child%");
        drainProcessing();
        assertTrue(parent.getChildren().contains(replacement));
        assertFalse(parent.getChildren().contains(middle));
        assertTrue(child.getParents().contains(replacement));
        assertFalse(child.getParents().contains(middle));
        rank.set("prime");
        tick(1000);
        assertEquals("1A", player.sortingData.shortTeamName);
        assertEquals("prime", parent.parse(player));
        var newChild = manager.registerPlayerPlaceholder("%child%", 1000, ignored -> "default");
        assertTrue(replacement.getChildren().contains(newChild));
        assertFalse(replacement.getChildren().contains(child));
        tick(1000);
        assertEquals("2A", player.sortingData.shortTeamName);
        // Re-registering the same object must not damage or duplicate links.
        manager.registerPlaceholder(replacement);
        drainProcessing();
        assertEquals(List.of(newChild), replacement.getChildren());
        assertEquals(List.of(parent), replacement.getParents());
    }

    @ParameterizedTest
    @CsvSource({"player,1000", "player,-1", "server,1000", "server,-1"})
    void nestedReplacementRefreshesDisabledAncestorsWithoutWaitingForAChange(String type, int interval) throws Exception {
        manager.registerPlayerPlaceholder("%child%", -1, ignored -> "legend");
        var middle = manager.registerPlayerPlaceholder(ID, -1, ignored -> "%child%");
        var parent = manager.registerPlayerPlaceholder("%parent%", -1, ignored -> ID);
        Sorting sorting = new Sorting(new SortingConfiguration(section, true,
                List.of("PLACEHOLDER:%parent%:legend,prime,default")));
        field(TAB.getInstance(), TAB.class, "onlinePlayers", new TabPlayer[]{player});
        sorting.onJoin(player);
        assertEquals("legend", parent.parse(player));
        for (int i = 0; i < 10; i++) {
            String value = i % 2 == 0 ? "prime" : "legend";
            var replacement = type.equals("server") ?
                    manager.registerServerPlaceholder("%child%", interval, () -> value) :
                    manager.registerPlayerPlaceholder("%child%", interval, ignored -> value);
            drainProcessing();
            assertEquals(value, replacement.parse(player));
            assertEquals(value, middle.parse(player));
            assertEquals(value, parent.parse(player));
            assertEquals(i % 2 == 0 ? "1A" : "0A", player.sortingData.shortTeamName);
            assertEquals(List.of(replacement), middle.getChildren());
            assertEquals(List.of(middle), replacement.getParents());
            tick(2000); // Constant server output must not be needed to invalidate ancestors.
            assertEquals(value, parent.parse(player));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void supersededPollCannotApplyEvenWhenItsHandleIsRegisteredAgain(boolean reuse) throws Exception {
        AtomicReference<String> value = new AtomicReference<>("legend");
        AtomicBoolean blockPoll = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var original = manager.registerPlayerPlaceholder(ID, 1000, ignored -> {
            String captured = value.get();
            if (Thread.currentThread().getName().equals("TAB Placeholder Refreshing Thread") && blockPoll.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Poll was not released");
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
            return captured;
        });
        var parent = manager.registerPlayerPlaceholder("%parent%", -1, ignored -> ID);
        Sorting sorting = new Sorting(new SortingConfiguration(section, true,
                List.of("PLACEHOLDER:%parent%:legend,prime,default")));
        field(TAB.getInstance(), TAB.class, "onlinePlayers", new TabPlayer[]{player});
        sorting.onJoin(player);
        RecordingFeature feature = new RecordingFeature();
        manager.addUsedPlaceholder(ID, feature);
        value.set("prime");
        blockPoll.set(true);
        try {
            initiateTicks(1000);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            manager.registerPlayerPlaceholder(ID, -1, ignored -> "default");
            if (reuse) {
                value.set("default");
                manager.registerPlaceholder(original);
            }
            drainProcessing();
            assertEquals("default", parent.parse(player));
            int callbacks = feature.threads.size();
            release.countDown();
            drainPolling();
            drainProcessing();
            assertEquals(reuse ? "default" : "legend", original.getLastValue(player));
            assertEquals("default", parent.parse(player));
            assertEquals("2A", player.sortingData.shortTeamName);
            assertEquals(callbacks, feature.threads.size());
            assertTrue(feature.threads.stream().allMatch("TAB Processing Thread"::equals));
        } finally {
            release.countDown();
        }
    }

    @Test void queuedRepeatedRegistrationsRefreshOnlyTheLatestAndPreserveCustomDispatch() throws Exception {
        Sorting sorting = new Sorting(new SortingConfiguration(section, true,
                List.of("PLACEHOLDER:" + ID + ":legend,prime,default")));
        field(TAB.getInstance(), TAB.class, "onlinePlayers", new TabPlayer[]{player});
        sorting.onJoin(player);
        RecordingFeature ordinary = new RecordingFeature();
        CustomRecordingFeature custom = new CustomRecordingFeature();
        manager.addUsedPlaceholder(ID, ordinary);
        manager.addUsedPlaceholder(ID, custom);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        cpu.getProcessingThread().execute(() -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var first = manager.registerPlayerPlaceholder(ID, -1, ignored -> "legend");
            for (int i = 0; i < 10; i++) manager.registerPlayerPlaceholder(ID, -1, ignored -> "prime");
            manager.registerPlaceholder(first); // Earlier queued tasks for this very handle are stale too.
            assertTrue(ordinary.threads.isEmpty());
            assertTrue(custom.threads.isEmpty());
            release.countDown();
            drainProcessing();
            CountDownLatch customDone = new CountDownLatch(1);
            custom.thread.execute(customDone::countDown);
            assertTrue(customDone.await(10, TimeUnit.SECONDS));
            assertEquals("0A", player.sortingData.shortTeamName);
            assertEquals(List.of("TAB Processing Thread"), ordinary.threads);
            assertEquals(List.of("Registration custom thread"), custom.threads);
            assertEquals(List.of(true), ordinary.forced);
            assertEquals(List.of(true), custom.forced);
        } finally {
            release.countDown();
            drainProcessing();
            custom.thread.shutdown();
        }
    }

    @ParameterizedTest
    @CsvSource({"player,50", "player,500", "player,1000", "player,-1",
            "server,50", "server,500", "server,1000", "server,-1",
            "relational,50", "relational,500", "relational,1000", "relational,-1"})
    void replacementTypeAndIntervalRemainAuthoritative(String type, int interval) throws Exception {
        String id = type.equals("relational") ? "%rel_rank%" : ID;
        AtomicReference<String> value = new AtomicReference<>("legend");
        register(type, id, 500, value);
        PlaceholderReference reference = manager.getPlaceholderReference(id);
        RecordingFeature feature = new RecordingFeature() {
            @Override public void refresh(TabPlayer player, boolean force) {
                super.refresh(player, force);
                parsed(manager.getPlaceholder(id)); // Model the feature reading its placeholder.
            }
        };
        manager.addUsedPlaceholder(id, feature);
        field(TAB.getInstance(), TAB.class, "onlinePlayers", new TabPlayer[]{player});
        assertEquals("legend", parsed(reference.getHandle()));
        value.set("prime");
        register(type, id, interval, value);
        drainProcessing();
        assertSame(reference, manager.getPlaceholderReference(id));
        assertEquals(interval, reference.getRefresh());
        assertEquals("prime", parsed(reference.getHandle()));
        assertEquals(1, feature.threads.size());
        value.set("default");
        if (interval == -1) {
            tick(2000);
            assertEquals("prime", parsed(reference.getHandle()));
            assertEquals(1, feature.threads.size());
        } else {
            tick(interval - me.neznamy.tab.shared.TabConstants.Placeholder.MINIMUM_REFRESH_INTERVAL);
            assertEquals("prime", parsed(reference.getHandle()));
            tick(me.neznamy.tab.shared.TabConstants.Placeholder.MINIMUM_REFRESH_INTERVAL);
            assertEquals("default", parsed(reference.getHandle()));
            assertEquals(2, feature.threads.size());
        }
        for (int i = 0; i < 10; i++) {
            int before = feature.threads.size();
            register(type, id, -1, value);
            drainProcessing();
            assertEquals(before + 1, feature.threads.size());
            assertSame(reference, manager.getPlaceholderReference(id));
        }
        register(type, id, 50, value);
        drainProcessing();
        value.set("legend");
        tick(50);
        assertEquals("legend", parsed(reference.getHandle()));
        assertTrue(feature.threads.stream().allMatch("TAB Processing Thread"::equals));
    }

    private TabPlaceholder register(String type, String id, int interval, AtomicReference<String> value) {
        switch (type) {
            case "server": return manager.registerServerPlaceholder(id, interval, value::get);
            case "relational": return manager.registerRelationalPlaceholder(id, interval, (viewer, target) -> value.get());
            default: return manager.registerPlayerPlaceholder(id, interval, ignored -> value.get());
        }
    }

    private String parsed(TabPlaceholder placeholder) {
        if (placeholder instanceof RelationalPlaceholderImpl)
            return ((RelationalPlaceholderImpl) placeholder).getLastValue(player, player);
        return placeholder.parse(player);
    }

    private void initiateTicks(int milliseconds) throws Exception {
        Method refresh = PlaceholderManagerImpl.class.getDeclaredMethod("refresh");
        refresh.setAccessible(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        cpu.getProcessingThread().execute(() -> {
            try {
                for (int i = 0; i < milliseconds / me.neznamy.tab.shared.TabConstants.Placeholder.MINIMUM_REFRESH_INTERVAL; i++)
                    refresh.invoke(manager);
            } catch (Exception e) {
                failure.set(e);
            }
        });
        drainProcessing();
        assertNull(failure.get());
    }

    private void drainPolling() throws Exception {
        CountDownLatch polled = new CountDownLatch(1);
        cpu.getPlaceholderThread().execute(polled::countDown);
        assertTrue(polled.await(10, TimeUnit.SECONDS));
    }

    static class RecordingFeature extends RefreshableFeature {
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<Boolean> forced = new CopyOnWriteArrayList<>();
        public String getFeatureName() { return "Registration recorder"; }
        public String getRefreshDisplayName() { return "Refresh"; }
        public void refresh(TabPlayer player, boolean force) {
            threads.add(Thread.currentThread().getName());
            forced.add(force);
        }
    }

    static class CustomRecordingFeature extends RecordingFeature implements CustomThreaded {
        final ThreadExecutor thread = new ThreadExecutor("Registration custom thread");
        public ThreadExecutor getCustomThread() { return thread; }
    }

    private void drainProcessing() throws Exception {
        CountDownLatch applied = new CountDownLatch(1);
        cpu.getProcessingThread().execute(applied::countDown);
        assertTrue(applied.await(10, TimeUnit.SECONDS));
    }

    private void tick(int milliseconds) throws Exception {
        initiateTicks(milliseconds);
        drainPolling();
        drainProcessing();
    }

    private static void field(Object object, Class<?> type, String name, Object value) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        f.set(object, value);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return type.cast(((Unsafe) f.get(null)).allocateInstance(type));
    }

    static class Viewer extends TabPlayer {
        Viewer() { super(null, null, UUID.randomUUID(), "viewer", "server", "world", 0, true); }
        public boolean isDisguised() { return false; }
        public boolean hasInvisibilityPotion() { return false; }
        public boolean isVanished() { return false; }
        public int getGamemode() { return 0; }
        public int getPing() { return 0; }
        public void sendMessage(TabComponent message) { }
        public boolean hasPermission(String permission) { return false; }
        public Platform getPlatform() { return null; }
        public Object getPlayer() { return null; }
    }
}
