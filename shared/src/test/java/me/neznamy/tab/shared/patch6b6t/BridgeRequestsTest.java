package me.neznamy.tab.shared.patch6b6t;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [6b6t patch 6b6t.8] The bridge request tracker: asks the TAB-Bridge again when an answer is lost, with backoff, a
 * cap, a per-second budget for many players, and never for servers without a bridge.
 */
class BridgeRequestsTest {

    /** Fake player: where TAB thinks he is, whether he is online, whether the bridge answered, what was sent */
    static final class P {
        final UUID id = UUID.randomUUID();
        String server;
        boolean online = true;
        boolean answered;
        boolean autoAnswer;
        final List<Long> sends = new ArrayList<>();
        P(String server) { this.server = server; }
    }

    private long now;
    private final List<String> logs = new ArrayList<>();
    private final Set<String> answeredServers = new HashSet<>();
    private BridgeRequests<P> tracker;

    private final BridgeRequests.Port<P> port = new BridgeRequests.Port<P>() {
        @Override public boolean isCurrent(@NotNull P player) { return player.online; }
        @Override public @Nullable String currentServer(@NotNull P player) { return player.server; }
        @Override public boolean isAnswered(@NotNull P player) { return player.answered; }
        @Override public void send(@NotNull P player) { player.answered = player.autoAnswer; player.sends.add(now); }
        @Override public void log(@NotNull String line) { logs.add(line); }
    };

    private void settings(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) p.setProperty(kv[i], kv[i + 1]);
        PatchSettings.setForTests(p);
    }

    @BeforeEach void setup() {
        settings();
        now = 1_000_000;
        answeredServers.add("worker-2");
        tracker = new BridgeRequests<>(port, PatchSettings::get, () -> now, answeredServers);
    }

    @AfterEach void reset() { PatchSettings.setForTests(new Properties()); }

    private void runFor(long millis) {
        long end = now + millis;
        while (now < end) { now += 250; tracker.tick(); }
    }

    private long counter(java.util.concurrent.atomic.AtomicLong c, Runnable r) {
        long before = c.get(); r.run(); return c.get() - before;
    }

    @Test void lostAnswerIsAskedAgainAfterTheTimeoutAndCountsAsRecovered() {
        P p = new P("worker-2");
        tracker.requestSent(p.id, p, p.server);
        runFor(9_750);
        assertTrue(p.sends.isEmpty(), "nothing before the 10 s timeout");
        long retried = counter(PatchStats.bridgeRetried, () -> runFor(250));
        assertEquals(1, p.sends.size());
        assertEquals(1, retried);
        long recovered = counter(PatchStats.bridgeRecovered, () -> {
            p.answered = true;
            tracker.answered(p.id, p, "worker-2");
        });
        assertEquals(1, recovered);
        assertFalse(tracker.isPending(p.id));
        runFor(300_000);
        assertEquals(1, p.sends.size(), "no more requests after the answer");
    }

    @Test void answeredInTimeNeverRetries() {
        P p = new P("worker-2");
        tracker.requestSent(p.id, p, p.server);
        p.answered = true;
        tracker.answered(p.id, p, "worker-2");
        runFor(300_000);
        assertTrue(p.sends.isEmpty());
        assertEquals(0, tracker.waiting());
    }

    @Test void backoffDoublesThenGivesUpWithOneLogLine() {
        P p = new P("worker-2");
        long start = now;
        tracker.requestSent(p.id, p, p.server);
        long gaveUp = counter(PatchStats.bridgeGaveUp, () -> runFor(200_000));
        assertEquals(List.of(start + 10_000, start + 30_000, start + 70_000), p.sends, "retries at 10 s, then +20 s, then +40 s");
        assertEquals(1, gaveUp);
        assertFalse(tracker.isPending(p.id));
        assertEquals(1, logs.size());
        assertTrue(logs.get(0).contains("worker-2") && logs.get(0).contains("bridgeresync"), logs.get(0));
    }

    @Test void serverWithoutBridgeIsNeverAskedAgain() {
        P p = new P("login-server");
        tracker.requestSent(p.id, p, p.server);
        runFor(200_000);
        assertTrue(p.sends.isEmpty());
        assertFalse(tracker.isPending(p.id));
        assertTrue(logs.isEmpty());
    }

    @Test void anAnswerTeachesThatAServerHasABridge() {
        P a = new P("lobby"), b = new P("lobby");
        tracker.requestSent(a.id, a, "lobby");
        tracker.requestSent(b.id, b, "lobby");
        a.answered = true;
        tracker.answered(a.id, a, "lobby");
        assertTrue(answeredServers.contains("lobby"));
        runFor(10_000);
        assertEquals(1, b.sends.size());
    }

    @Test void switchOrQuitDropsTheWait() {
        P moved = new P("worker-2"), gone = new P("worker-2");
        tracker.requestSent(moved.id, moved, moved.server);
        tracker.requestSent(gone.id, gone, gone.server);
        moved.server = "worker-1"; // the switch sends its own request (and would replace the entry)
        gone.online = false;
        runFor(20_000);
        assertTrue(moved.sends.isEmpty());
        assertTrue(gone.sends.isEmpty());
        assertEquals(0, tracker.waiting());
    }

    @Test void lateAnswerOfThePreviousServerDoesNotCount() {
        P p = new P("worker-2");
        tracker.requestSent(p.id, p, "worker-2");
        tracker.answered(p.id, p, "worker-0"); // answer to the request of the server he left
        assertTrue(tracker.isPending(p.id));
        runFor(10_000);
        assertEquals(1, p.sends.size());
    }

    @Test void answersNeverCancelARequestThatIsNotSentYet() {
        // After a reload the bridge forgot the player (Unload): a late answer to an old request must not cancel the new one.
        P p = new P("worker-2");
        tracker.defer(p.id, p);
        tracker.answered(p.id, p, "worker-2");
        runFor(250);
        assertEquals(1, p.sends.size());
    }

    @Test void manyPlayersAreSpreadOverTheBudget() {
        settings("bridge-requests-per-second", "25");
        tracker = new BridgeRequests<>(port, PatchSettings::get, () -> now, answeredServers);
        List<P> players = new ArrayList<>();
        for (int i = 0; i < 400; i++) { P p = new P("worker-" + (i % 3)); p.autoAnswer = true; players.add(p); tracker.defer(p.id, p); }
        answeredServers.addAll(List.of("worker-0", "worker-1"));
        long start = now;
        runFor(30_000);
        List<Long> times = new ArrayList<>();
        for (P p : players) { assertEquals(1, p.sends.size(), "every player asked exactly once"); times.add(p.sends.get(0)); }
        Collections.sort(times);
        for (int i = 0; i + 50 < times.size(); i++) {
            assertTrue(times.get(i + 50) - times.get(i) >= 1_000, "at most ~25/s after the first burst (51 sends within 1 s at index " + i + ")");
        }
        long last = times.get(times.size() - 1) - start;
        assertTrue(last >= 14_000 && last <= 17_000, "400 at 25/s take ~15 s, took " + last);
    }

    @Test void retriesShareTheBudget() {
        settings("bridge-requests-per-second", "10");
        tracker = new BridgeRequests<>(port, PatchSettings::get, () -> now, answeredServers);
        List<P> players = new ArrayList<>();
        for (int i = 0; i < 100; i++) { P p = new P("worker-2"); players.add(p); tracker.requestSent(p.id, p, p.server); }
        runFor(10_000); // all due now
        runFor(1_000);
        int sent = 0;
        for (P p : players) sent += p.sends.size();
        assertTrue(sent >= 10 && sent <= 22, "about 10 per second plus the first burst, got " + sent);
    }

    @Test void killSwitchStopsRetriesButNotExplicitRequests() {
        settings("bridge-retry", "false");
        P waiting = new P("worker-2"), resync = new P("worker-2");
        tracker.requestSent(waiting.id, waiting, waiting.server);
        tracker.defer(resync.id, resync);
        runFor(200_000);
        assertTrue(waiting.sends.isEmpty());
        assertEquals(1, resync.sends.size(), "bridgeresync still sends, only without retries");
        assertEquals(0, tracker.waiting());
    }

    @Test void retryMaxZeroOnlyWatches() {
        settings("bridge-retry-max", "0");
        P p = new P("worker-2");
        tracker.requestSent(p.id, p, p.server);
        long gaveUp = counter(PatchStats.bridgeGaveUp, () -> runFor(200_000));
        assertTrue(p.sends.isEmpty());
        assertEquals(0, gaveUp);
    }

    @Test void deferredRequestIsWatchedAfterSending() {
        P p = new P("worker-2");
        tracker.defer(p.id, p);
        runFor(250);
        assertEquals(1, p.sends.size());
        runFor(10_000);
        assertEquals(2, p.sends.size(), "the deferred request is retried like any other");
    }

    @Test void newRequestReplacesTheOldOne() {
        P p = new P("worker-2");
        tracker.requestSent(p.id, p, "worker-2");
        runFor(5_000);
        p.server = "worker-1";
        answeredServers.add("worker-1");
        tracker.requestSent(p.id, p, "worker-1"); // server switch
        runFor(5_000);
        assertTrue(p.sends.isEmpty(), "the switch restarted the timeout");
        runFor(5_000);
        assertEquals(1, p.sends.size());
    }

    @Test void carryKeepsValuesOfTheSameServerOnly() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        Map<String, String> values = new HashMap<>(Map.of("%lp_color%", "&a", "%rel_seal%", "{x=1}"));
        BridgeRequests.carry(a, "worker-2", values, 0);
        BridgeRequests.carry(b, "worker-2", values, 0);
        BridgeRequests.carry(c, "worker-2", values, 0);
        assertEquals(Map.of("%lp_color%", "&a"), BridgeRequests.takeCarry(a, "worker-2", 1_000), "relational values are not kept");
        assertNull(BridgeRequests.takeCarry(a, "worker-2", 1_000), "taken once");
        assertNull(BridgeRequests.takeCarry(b, "worker-1", 1_000), "other server: ask fresh");
        assertNull(BridgeRequests.takeCarry(c, "worker-2", 61_000), "too old");
        BridgeRequests.carry(a, "worker-2", values, 0);
        BridgeRequests.clearCarry();
        assertNull(BridgeRequests.takeCarry(a, "worker-2", 1_000));
    }
}
