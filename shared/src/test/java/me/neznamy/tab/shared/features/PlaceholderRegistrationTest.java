package me.neznamy.tab.shared.features;

import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.chat.component.TabComponent;
import me.neznamy.tab.shared.config.Configs;
import me.neznamy.tab.shared.config.file.ConfigurationSection;
import me.neznamy.tab.shared.config.files.Config;
import me.neznamy.tab.shared.cpu.CpuManager;
import me.neznamy.tab.shared.features.sorting.Sorting;
import me.neznamy.tab.shared.features.sorting.SortingConfiguration;
import me.neznamy.tab.shared.features.sorting.SortingPlayerData;
import me.neznamy.tab.shared.placeholders.*;
import me.neznamy.tab.shared.placeholders.expansion.ExpansionData;
import me.neznamy.tab.shared.platform.Platform;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.junit.jupiter.api.*;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        value.set("legend");
        tick(1000);
        assertEquals("2A", player.sortingData.shortTeamName);
        manager.registerPlayerPlaceholder(ID, 1000, ignored -> value.get());
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
        assertEquals(List.of(newChild), replacement.getChildren());
        assertEquals(List.of(parent), replacement.getParents());
    }

    private void tick(int milliseconds) throws Exception {
        Method refresh = PlaceholderManagerImpl.class.getDeclaredMethod("refresh");
        refresh.setAccessible(true);
        for (int i = 0; i < milliseconds / me.neznamy.tab.shared.TabConstants.Placeholder.MINIMUM_REFRESH_INTERVAL; i++)
            refresh.invoke(manager);
        CountDownLatch polled = new CountDownLatch(1);
        cpu.getPlaceholderThread().execute(polled::countDown);
        assertTrue(polled.await(10, TimeUnit.SECONDS));
        CountDownLatch applied = new CountDownLatch(1);
        cpu.getProcessingThread().execute(applied::countDown);
        assertTrue(applied.await(10, TimeUnit.SECONDS));
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
