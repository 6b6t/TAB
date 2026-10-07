package me.neznamy.tab.shared.features.proxy;

import com.google.common.io.*;
import me.neznamy.tab.shared.*;
import me.neznamy.tab.shared.data.*;
import me.neznamy.tab.shared.features.proxy.message.*;
import me.neznamy.tab.shared.features.playerlist.AuditBudget;
import me.neznamy.tab.shared.features.playerlist.AuditCursor;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.patch6b6t.PatchSettings;
import org.junit.jupiter.api.*;
import sun.misc.Unsafe;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class Fix4Test {
    static final UUID P = UUID.randomUUID();
    TAB old;
    ProxySupport proxy;
    static void field(Object object, Class<?> type, String name, Object value) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(object, value);
    }
    @BeforeEach void setup() throws Exception {
        old = TAB.getInstance();
        Field f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        TAB tab = (TAB) ((Unsafe) f.get(null)).allocateInstance(TAB.class);
        field(null, TAB.class, "instance", tab);
        field(tab, TAB.class, "dataManager", new DataManager());
        field(tab, TAB.class, "featureManager", new FeatureManager());
        field(tab, TAB.class, "data", new HashMap<>());
        field(tab, TAB.class, "onlinePlayers", new TabPlayer[0]);
        field(tab, TAB.class, "platform", java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{me.neznamy.tab.shared.platform.Platform.class}, (o,m,args) -> null));
        PatchSettings.setForTests(new Properties());
        proxy = new ProxySupport("test") {
            public void sendMessage(String message) { }
            public void register() { }
            public void unregister() { }
        };
    }
    @AfterEach void cleanup() throws Exception { field(null, TAB.class, "instance", old); }
    static PlayerJoin join(String server, boolean vanish, int skinSize) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeLong(P.getMostSignificantBits()); out.writeLong(P.getLeastSignificantBits());
        out.writeLong(P.getMostSignificantBits()); out.writeLong(P.getLeastSignificantBits());
        out.writeUTF("player"); out.writeUTF(server); out.writeBoolean(vanish); out.writeBoolean(false);
        out.writeBoolean(skinSize > 0);
        if (skinSize > 0) { out.writeUTF("a".repeat(skinSize)); out.writeBoolean(false); }
        return new PlayerJoin(ByteStreams.newDataInput(out.toByteArray()));
    }
    ProxyPlayer copy() {
        ProxyPlayer p = new ProxyPlayer(P, P, "player", Server.byName("backup"), false, false, null);
        p.setSourceProxy("origin"); proxy.getProxyPlayers().put(P, p); return p;
    }
    @Test void delayedLoadCannotUndoSwitchOrVanishAndLegacyCannotRefresh() {
        ProxyPlayer p = copy();
        ServerSwitch change = new ServerSwitch(P, Server.byName("worker")); change.setSequence(20); change.process(proxy);
        UpdateVanishStatus vanish = new UpdateVanishStatus(P, true); vanish.setSequence(21); vanish.process(proxy);
        PlayerJoin stale = join("backup", false, 0); stale.setSourceProxy("origin"); stale.setSequence(10);
        Load delayed = Load.splitPlayers(List.of(stale)).get(0); delayed.setSnapshot(7, 10); delayed.setSourceProxy("origin"); delayed.process(proxy);
        assertEquals("worker", p.server.getName()); assertTrue(p.isVanished());
        stale.setSequence(-1); stale.process(proxy);
        assertEquals("worker", p.server.getName()); assertTrue(p.isVanished());
        PlayerJoin fresh = join("worker", false, 0); fresh.setSourceProxy("origin"); fresh.setSequence(22); fresh.process(proxy);
        assertFalse(p.isVanished());
    }
    @Test void legacySwitchBlocksDelayedSequencedSnapshot() {
        ProxyPlayer p = copy();
        new ServerSwitch(P, Server.byName("worker")).process(proxy);
        PlayerJoin stale = join("backup", false, 0); stale.setSourceProxy("origin"); stale.setSequence(10); stale.process(proxy);
        assertEquals("worker", p.server.getName());
    }
    @Test void chunksRequireIdentityAndEveryIndexDespiteEqualCountChurn() {
        SnapshotChunks chunks = new SnapshotChunks(7);
        UUID survivor = UUID.randomUUID();
        chunks.add(7, 10, 1, 2, Set.of(P));
        chunks.add(7, 10, 1, 2, Set.of(P));
        chunks.add(8, 11, 0, 2, Set.of(survivor));
        chunks.add(7, 11, 0, 2, Set.of(survivor));
        assertFalse(chunks.isComplete()); // population can now be one; that proves nothing
        chunks.add(7, 10, 0, 2, Set.of(survivor));
        assertTrue(chunks.isComplete()); assertEquals(Set.of(P, survivor), chunks.players());
    }
    @Test void partialActualLoadCannotDeleteLiveCopyAtEqualHeartbeatCount() throws Exception {
        ProxyPlayer live = copy(); live.setLastChangeMillis(0);
        Class<?> type = Class.forName(ProxySupport.class.getName() + "$DigestCheck");
        Constructor<?> ctor = type.getDeclaredConstructor(); ctor.setAccessible(true);
        Object check = ctor.newInstance();
        field(check, type, "requestedAt", System.currentTimeMillis());
        field(check, type, "loaded", new SnapshotChunks(7));
        ((Map) proxy.getDigestChecks()).put("origin", check);
        proxy.noteLoaded("origin", 7, 10, 0, 2, Set.of(UUID.randomUUID()));
        Method digest = ProxySupport.class.getDeclaredMethod("copiesDigest", String.class); digest.setAccessible(true);
        Method heartbeat = ProxySupport.class.getDeclaredMethod("checkDigest", String.class, String.class); heartbeat.setAccessible(true);
        heartbeat.invoke(proxy, "origin", digest.invoke(proxy, "origin"));
        assertSame(live, proxy.getProxyPlayers().get(P));
        // Complete empty snapshot is authoritative, and exercises the actual quit path.
        field(check, type, "loaded", new SnapshotChunks(8));
        proxy.noteLoaded("origin", 8, 20, 0, 1, Set.of());
        heartbeat.invoke(proxy, "origin", "0:0:0:0");
        assertFalse(proxy.getProxyPlayers().containsKey(P));
    }
    @Test void emptySnapshotCompleteButLegacyNeverAuthoritative() {
        SnapshotChunks chunks = new SnapshotChunks(7);
        chunks.add(-1, -1, 0, 1, Set.of()); assertFalse(chunks.isComplete());
        chunks.add(7, 10, 0, 1, Set.of()); assertTrue(chunks.isComplete());
    }
    @Test void sizedChunksAndLegacyReaderBothDirections() {
        List<PlayerJoin> players = new ArrayList<>();
        for (int i=0; i<80; i++) players.add(join("worker", false, 1500));
        List<Load> chunks = Load.splitPlayers(players);
        assertTrue(chunks.size() > 1);
        int total = 0;
        for (Load load : chunks) {
            load.setSnapshot(7, 10);
            ByteArrayDataOutput out = ByteStreams.newDataOutput(); load.write(out);
            assertTrue(Base64.getEncoder().encodeToString(out.toByteArray()).length() + 200 < 65535);
            ByteArrayDataInput legacy = ByteStreams.newDataInput(out.toByteArray());
            int n = legacy.readInt(); total += n;
            for (int i=0; i<n; i++) new PlayerJoin(legacy); // .3 ignores appended metadata
            Load decoded = new Load(ByteStreams.newDataInput(out.toByteArray()));
            ByteArrayDataOutput again = ByteStreams.newDataOutput(); decoded.write(again);
            assertArrayEquals(out.toByteArray(), again.toByteArray());
        }
        assertEquals(80, total);
        ByteArrayDataOutput legacy = ByteStreams.newDataOutput(); legacy.writeInt(1); players.get(0).write(legacy);
        assertDoesNotThrow(() -> new Load(ByteStreams.newDataInput(legacy.toByteArray())));
        assertThrows(IllegalArgumentException.class, () -> Load.splitPlayers(List.of(join("worker", false, 45000))));
        assertEquals(1, Load.split(new TabPlayer[0]).size());
    }
    @Test void budgetStopsInsideViewerAndCapsRepairs() {
        AtomicLong clock = new AtomicLong(); AuditBudget b = new AuditBudget(5, 2, clock::get);
        assertTrue(b.canCheck()); clock.set(5); assertFalse(b.canCheck());
        b = new AuditBudget(5, 2, () -> 0); b.repaired(); assertTrue(b.canCheck()); b.repaired(); assertFalse(b.canCheck());
    }
    @Test void resumeInsideViewerAndSweep600And1500() {
        for (int size : new int[]{600, 1500}) {
            AuditCursor cursor = new AuditCursor();
            long checked = 0; int ticks = 0;
            while (cursor.viewer() < size) {
                AtomicLong clock = new AtomicLong();
                AuditBudget budget = new AuditBudget(5000000, 32, clock::get);
                int repairs = 0;
                while (cursor.viewer() < size && budget.canCheck()) {
                    cursor.nextTarget(); checked++; clock.addAndGet(1000);
                    if (checked % 10 == 0) { budget.repaired(); repairs++; }
                    if (cursor.target() == size) cursor.nextViewer();
                }
                assertTrue(repairs <= 32);
                if (++ticks == 1) { assertEquals(0, cursor.viewer()); assertEquals(320, cursor.target()); }
            }
            assertEquals((long) size * size, checked);
        }
    }
    @Test void measureCursorSweepAt600And1500() {
        for (int size : new int[]{600, 1500}) {
            AuditCursor cursor = new AuditCursor(); long checks = 0, maxTick = 0;
            int ticks = 0; long start = System.nanoTime();
            while (cursor.viewer() < size) {
                long tick = System.nanoTime();
                AuditBudget budget = new AuditBudget(5000000, 32, System::nanoTime);
                while (cursor.viewer() < size && budget.canCheck()) {
                    cursor.nextTarget(); checks++;
                    if (cursor.target() == size) cursor.nextViewer();
                }
                maxTick = Math.max(maxTick, System.nanoTime() - tick); ticks++;
            }
            assertEquals((long)size*size, checks);
            System.out.printf("cursor-only size=%d checks=%d ticks=%d sweep-ms=%.3f max-tick-ms=%.3f%n",
                    size, checks, ticks, (System.nanoTime()-start)/1e6, maxTick/1e6);
        }
    }
    @Test void digestIdsAreAtomicMonotonicAndNametagIsPublished() throws Exception {
        Method note = ProxySupport.class.getDeclaredMethod("noteSent", UUID.class, int.class, long.class); note.setAccessible(true);
        Thread a = new Thread(() -> { for(int i=0; i<10000; i++) try { note.invoke(proxy, P, 0, (long)i); } catch(Exception e) { throw new RuntimeException(e); } });
        Thread b = new Thread(() -> { for(int i=9999; i>=0; i--) try { note.invoke(proxy, P, 0, (long)i); } catch(Exception e) { throw new RuntimeException(e); } });
        a.start(); b.start(); a.join(); b.join();
        assertEquals(9999, proxy.getSentIds().get(P).get(0));
        assertEquals(AtomicLongArray.class, proxy.getSentIds().get(P).getClass());
        assertTrue(Modifier.isVolatile(ProxyPlayer.class.getDeclaredField("nametag").getModifiers()));
        assertTrue(Modifier.isVolatile(ProxyPlayer.class.getDeclaredField("tabFormat").getModifiers()));
    }
}
