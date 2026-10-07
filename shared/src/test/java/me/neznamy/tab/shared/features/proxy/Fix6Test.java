package me.neznamy.tab.shared.features.proxy;

import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.chat.component.TabComponent;
import me.neznamy.tab.shared.features.playerlist.*;
import me.neznamy.tab.shared.features.nametags.*;
import me.neznamy.tab.shared.features.sorting.SortingPlayerData;
import me.neznamy.tab.shared.patch6b6t.PatchSettings;
import me.neznamy.tab.shared.platform.*;
import me.neznamy.tab.shared.util.cache.StringToComponentCache;
import org.junit.jupiter.api.*;
import sun.misc.Unsafe;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Fix6Test {
    private final Fix4Test fixture = new Fix4Test();
    private static final UUID P = Fix4Test.P;
    private ProxySupport proxy;
    @BeforeEach void setup() throws Exception { fixture.setup(); proxy = fixture.proxy; }
    @AfterEach void cleanup() throws Exception { fixture.cleanup(); }
    private ProxyPlayer copy() { return fixture.copy(); }
    private static void field(Object object, Class<?> type, String name, Object value) throws Exception {
        Fix4Test.field(object, type, name, value);
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
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
    private Viewer viewer() throws Exception {
        Viewer v = allocate(Viewer.class);
        field(v, TabPlayer.class, "sortingData", new SortingPlayerData());
        field(v, TabPlayer.class, "teamData", new NameTagPlayerData(v));
        field(TAB.getInstance(), TAB.class, "onlinePlayers", new TabPlayer[]{v});
        return v;
    }
    private void auditing(boolean enabled) {
        Properties p = new Properties(); p.setProperty("audit-enabled", Boolean.toString(enabled));
        PatchSettings.setForTests(p);
    }
    @AfterEach void resetSettings() { PatchSettings.setForTests(new Properties()); }

    @Test void equalFormatResendRepairsStaleViewerWithAuditDisabled() throws Exception {
        auditing(false);
        Viewer v = viewer();
        List<TabComponent> display = new ArrayList<>(); display.add(null);
        field(v, TabPlayer.class, "tabList", java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TabList.class}, (o, m, args) -> {
                    if (m.getName().equals("updateDisplayName")) { assertEquals(P, args[0]); display.set(0, (TabComponent) args[1]); }
                    return null;
                }));
        PlayerList feature = allocate(PlayerList.class);
        ProxyPlayer target = copy(); target.setConnectionState(ProxyPlayer.ConnectionState.CONNECTED);
        TabComponent expected = TabComponent.fromColoredText("prefix player suffix");
        target.setTabFormat(new PlayerListProxyPlayerData(feature, 1, P, "player", "prefix", "player", "suffix", expected, false));
        PlayerListProxyPlayerData resend = new PlayerListProxyPlayerData(feature, 2, P, "player", "prefix", "player", "suffix", expected, false);
        resend.process(proxy);
        assertSame(resend, target.getTabFormat());
        assertSame(expected, display.get(0));
    }

    @Test void equalNametagResendRepairsMissingViewerTeamWithAuditDisabled() throws Exception {
        auditing(false);
        Viewer v = viewer();
        List<String> registered = new ArrayList<>();
        field(v, TabPlayer.class, "scoreboard", java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Scoreboard.class}, (o, m, args) -> { if (m.getName().equals("registerTeam")) registered.add((String) args[0]); return null; }));
        NameTag feature = allocate(NameTag.class);
        field(feature, NameTag.class, "onlinePlayers", new me.neznamy.tab.shared.util.OnlinePlayers(new TabPlayer[]{v}));
        for (String name : List.of("prefixCache", "suffixCache", "lastColorCache"))
            field(feature, NameTag.class, name, new StringToComponentCache("test", 10) {
                @Override public TabComponent convert(String text) { return TabComponent.fromColoredText(text); }
            });
        ProxyPlayer target = copy(); target.setConnectionState(ProxyPlayer.ConnectionState.CONNECTED);
        NameTagProxyPlayerData old = new NameTagProxyPlayerData(feature, 1, P, "teamA", "", "", Scoreboard.NameVisibility.ALWAYS, false);
        field(old, NameTagProxyPlayerData.class, "resolvedTeamName", "teamA"); target.setNametag(old);
        NameTagProxyPlayerData resend = new NameTagProxyPlayerData(feature, 2, P, "teamA", "", "", Scoreboard.NameVisibility.ALWAYS, false);
        resend.process(proxy);
        assertSame(resend, target.getNametag());
        assertEquals(List.of("teamA"), registered);
        assertEquals("teamA", v.teamData.getRegisteredProxyTeamName(target));
    }
}
