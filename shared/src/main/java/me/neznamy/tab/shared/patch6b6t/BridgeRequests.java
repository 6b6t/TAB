package me.neznamy.tab.shared.patch6b6t;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * [6b6t patch 6b6t.8] Watches the requests the proxy sends to the backend TAB-Bridge for a player's values
 * (the "PlayerJoin" plugin message, sent on join, server switch and reload).
 * <p>
 * The bridge answers one request once and afterwards only sends values that change. If that one answer is lost or
 * never sent, the player shows raw placeholders until he switches server (12 Oct: a reload left 184 players of
 * worker-2 raw). This tracker asks again when no answer came within {@link PatchSettings#bridgeRetryTimeoutMillis}
 * (then twice as long after every retry, at most {@link PatchSettings#bridgeRetryMax} times), and it sends requests
 * that may come in large numbers (retries, {@code /btab bridgeresync}, the requests of a reload) through one budget
 * of {@link PatchSettings#bridgeRequestsPerSecond} per second, so 400+ players never hit a backend at once.
 * <p>
 * Retries only go to servers whose bridge has answered at least once since the proxy started (servers without a
 * bridge, like login-server, are never asked again). {@code /btab bridgeresync} always sends.
 * <p>
 * {@link #tick()} runs on TAB's processing thread; the other methods may be called from any thread.
 *
 * @param   <P>
 *          player type (ProxyTabPlayer in production, a fake in tests)
 */
public final class BridgeRequests<P> {

    /** What the tracker needs to know about and do with players */
    public interface Port<P> {

        /** {@code true} if this object is still the online player object of its UUID */
        boolean isCurrent(@NotNull P player);

        /** Name of the server TAB thinks the player is on */
        @Nullable String currentServer(@NotNull P player);

        /** {@code true} if the bridge answered the player's latest request */
        boolean isAnswered(@NotNull P player);

        /** Sends the request again, keeping the values the proxy already has */
        void send(@NotNull P player);

        /** Prints one console line */
        void log(@NotNull String line);
    }

    /** What a pending entry waits for */
    enum Kind {
        /** Request not sent yet (reload batch, bridgeresync): send when the budget allows */
        SEND,
        /** Request sent: ask again if no answer came by the due time */
        WAIT
    }

    /** One player's state; immutable, replaced atomically in {@link #pending} */
    static final class Pending<P> {
        final P player;
        @Nullable final String server;
        final Kind kind;
        final int attempts;
        final long dueAt;

        Pending(@NotNull P player, @Nullable String server, @NotNull Kind kind, int attempts, long dueAt) {
            this.player = player;
            this.server = server;
            this.kind = kind;
            this.attempts = attempts;
            this.dueAt = dueAt;
        }
    }

    /** Longest wait between two requests of one player */
    static final long MAX_WAIT_MILLIS = 120_000;

    /** A console line about players a bridge never answered, at most once per server per this interval */
    static final long GAVE_UP_LOG_INTERVAL_MILLIS = 60_000;

    /** Servers whose bridge answered at least once since the proxy started; kept through TAB reloads */
    private static final Set<String> ANSWERED_SERVERS = ConcurrentHashMap.newKeySet();

    /** Tracker of the current TAB load (null when not on a proxy or TAB is not loaded) */
    private static volatile BridgeRequests<?> current;

    @NotNull private final Port<P> port;
    @NotNull private final Supplier<PatchSettings> settings;
    @NotNull private final Clock clock;
    @NotNull private final Set<String> answeredServers;
    private final Map<UUID, Pending<P>> pending = new ConcurrentHashMap<>();
    private final Map<String, Integer> gaveUpSinceLog = new HashMap<>();
    private final Map<String, Long> lastGaveUpLog = new HashMap<>();
    private double tokens;
    private long lastRefill;

    /** Time source, replaceable in tests */
    public interface Clock {
        long millis();
    }

    /**
     * Creates a tracker.
     *
     * @param   port
     *          player access
     * @param   settings
     *          settings source
     * @param   clock
     *          time source
     * @param   answeredServers
     *          servers known to run a bridge (shared static set in production)
     */
    public BridgeRequests(@NotNull Port<P> port, @NotNull Supplier<PatchSettings> settings, @NotNull Clock clock,
                          @NotNull Set<String> answeredServers) {
        this.port = port;
        this.settings = settings;
        this.clock = clock;
        this.answeredServers = answeredServers;
        lastRefill = clock.millis();
        tokens = Math.max(1, settings.get().bridgeRequestsPerSecond);
    }

    /**
     * Creates the production tracker for a new TAB load and makes it current.
     *
     * @param   port
     *          production port
     * @param   <P>
     *          player type
     * @return  the new tracker
     */
    @NotNull
    public static <P> BridgeRequests<P> begin(@NotNull Port<P> port) {
        BridgeRequests<P> tracker = new BridgeRequests<>(port, PatchSettings::get, System::currentTimeMillis, ANSWERED_SERVERS);
        current = tracker;
        return tracker;
    }

    /** Forgets the current tracker (TAB unloaded) */
    public static void end() {
        current = null;
    }

    /**
     * Returns the tracker of the current TAB load.
     *
     * @return  current tracker or {@code null}
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public static <P> BridgeRequests<P> get() {
        return (BridgeRequests<P>) current;
    }

    /**
     * A request was just sent to the player's current backend (join, server switch, or by this tracker).
     *
     * @param   id
     *          player UUID
     * @param   player
     *          player
     * @param   server
     *          server the request went to
     */
    public void requestSent(@NotNull UUID id, @NotNull P player, @Nullable String server) {
        pending.put(id, new Pending<>(player, server, Kind.WAIT, 0, clock.millis() + settings.get().bridgeRetryTimeoutMillis));
    }

    /**
     * Queues a request to be sent when the budget allows (reload batches, bridgeresync).
     *
     * @param   id
     *          player UUID
     * @param   player
     *          player
     */
    public void defer(@NotNull UUID id, @NotNull P player) {
        pending.put(id, new Pending<>(player, port.currentServer(player), Kind.SEND, 0, clock.millis()));
    }

    /**
     * The bridge answered a request of this player.
     *
     * @param   id
     *          player UUID
     * @param   player
     *          player object the answer was processed for
     * @param   sourceServer
     *          server the answer came from, {@code null} if unknown
     */
    public void answered(@NotNull UUID id, @NotNull P player, @Nullable String sourceServer) {
        String from = sourceServer != null ? sourceServer : port.currentServer(player);
        if (from != null) answeredServers.add(from);
        Pending<P> entry = pending.get(id);
        // A request that is not sent yet stays: the bridge forgot the player on reload (Unload) and needs it.
        if (entry == null || entry.player != player || entry.kind != Kind.WAIT) return;
        if (from != null && entry.server != null && !from.equals(entry.server)) return; // late answer of the previous server
        if (pending.remove(id, entry) && entry.attempts > 0) PatchStats.bridgeRecovered.incrementAndGet();
    }

    /**
     * Number of players waiting for an answer or for their request to be sent.
     *
     * @return  pending count
     */
    public int waiting() {
        return pending.size();
    }

    /**
     * Returns {@code true} if a request of this player is waiting to be sent or answered.
     *
     * @param   id
     *          player UUID
     * @return  whether the player is tracked
     */
    public boolean isPending(@NotNull UUID id) {
        return pending.containsKey(id);
    }

    /**
     * Sends due requests within the budget, retries unanswered ones and drops finished ones.
     * Runs on TAB's processing thread every 250 ms.
     */
    public void tick() {
        PatchSettings s = settings.get();
        long now = clock.millis();
        int rate = Math.max(1, s.bridgeRequestsPerSecond);
        tokens = Math.min(rate, tokens + (now - lastRefill) * rate / 1000.0);
        lastRefill = now;
        if (pending.isEmpty()) {
            flushGaveUpLog(now, false);
            return;
        }
        List<Map.Entry<UUID, Pending<P>>> due = new ArrayList<>();
        for (Map.Entry<UUID, Pending<P>> e : pending.entrySet()) {
            if (e.getValue().dueAt <= now) due.add(e);
        }
        due.sort(Comparator.comparingLong(e -> e.getValue().dueAt));
        for (Map.Entry<UUID, Pending<P>> e : due) {
            UUID id = e.getKey();
            Pending<P> entry = e.getValue();
            if (pending.get(id) != entry) continue; // replaced meanwhile (new request after a switch)
            P player = entry.player;
            if (!port.isCurrent(player)) {
                pending.remove(id, entry);
                continue;
            }
            String server = port.currentServer(player);
            if (entry.kind == Kind.WAIT) {
                if (port.isAnswered(player)) {
                    if (pending.remove(id, entry) && entry.attempts > 0) PatchStats.bridgeRecovered.incrementAndGet();
                    continue;
                }
                if (!Objects.equals(server, entry.server)) {
                    pending.remove(id, entry); // the switch sent its own request
                    continue;
                }
                if (!s.bridgeRetry || server == null || !answeredServers.contains(server)) {
                    pending.remove(id, entry);
                    continue;
                }
                if (entry.attempts >= s.bridgeRetryMax) {
                    if (pending.remove(id, entry) && s.bridgeRetryMax > 0) {
                        PatchStats.bridgeGaveUp.incrementAndGet();
                        gaveUpSinceLog.merge(server, 1, Integer::sum);
                    }
                    continue;
                }
                if (tokens < 1) break;
                tokens--;
                int attempts = entry.attempts + 1;
                long wait = Math.min(MAX_WAIT_MILLIS, s.bridgeRetryTimeoutMillis << Math.min(attempts, 20));
                if (pending.replace(id, entry, new Pending<>(player, server, Kind.WAIT, attempts, now + wait))) {
                    port.send(player);
                    PatchStats.bridgeRetried.incrementAndGet();
                }
            } else {
                if (tokens < 1) break;
                tokens--;
                if (pending.replace(id, entry, new Pending<>(player, server, Kind.WAIT, 0, now + s.bridgeRetryTimeoutMillis))) {
                    port.send(player);
                }
            }
        }
        flushGaveUpLog(now, false);
    }

    private void flushGaveUpLog(long now, boolean force) {
        if (gaveUpSinceLog.isEmpty()) return;
        for (Map.Entry<String, Integer> e : new ArrayList<>(gaveUpSinceLog.entrySet())) {
            Long last = lastGaveUpLog.get(e.getKey());
            if (!force && last != null && now - last < GAVE_UP_LOG_INTERVAL_MILLIS) continue;
            port.log("[TAB-6b6t] The TAB-Bridge of " + e.getKey() + " did not answer " + e.getValue() + " player(s) after "
                    + settings.get().bridgeRetryMax + " retries; they keep raw values until they switch server or "
                    + "/btab bridgeresync " + e.getKey() + " is run");
            lastGaveUpLog.put(e.getKey(), now);
            gaveUpSinceLog.remove(e.getKey());
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Values kept through a TAB reload
    // ---------------------------------------------------------------------------------------------------------

    /** Carried values older than this are ignored */
    static final long CARRY_TTL_MILLIS = 60_000;

    /** Bridge values of the players online at the last unload: UUID -> carry */
    private static final Map<UUID, Carry> CARRY = new ConcurrentHashMap<>();

    /** Bridge state of one player at unload */
    public static final class Carry {
        final String server;
        /** Placeholder values (identifier -> value), relational ones excluded */
        public final Map<String, String> values;
        /** Vanish / disguise / invisibility potion / game mode as the bridge last reported them */
        public final boolean vanished;
        public final boolean disguised;
        public final boolean invisible;
        public final int gamemode;
        final long takenAt;

        Carry(@NotNull String server, @NotNull Map<String, String> values, boolean vanished, boolean disguised,
              boolean invisible, int gamemode, long takenAt) {
            this.server = server;
            this.values = values;
            this.vanished = vanished;
            this.disguised = disguised;
            this.invisible = invisible;
            this.gamemode = gamemode;
            this.takenAt = takenAt;
        }
    }

    /**
     * Remembers a player's bridge values at unload so the next load can show them until the bridge answers again.
     * Relational values are not kept (they are not stored per player).
     *
     * @param   id
     *          player UUID
     * @param   server
     *          server the values came from
     * @param   values
     *          the player's bridge values
     * @param   now
     *          current time
     */
    public static void carry(@NotNull UUID id, @Nullable String server, @NotNull Map<String, String> values, long now) {
        carry(id, server, values, false, false, false, 0, now);
    }

    /**
     * Remembers a player's bridge state at unload (values and the flags the bridge reported).
     *
     * @param   id
     *          player UUID
     * @param   server
     *          server the state came from
     * @param   values
     *          the player's bridge values
     * @param   vanished
     *          vanish status from the bridge
     * @param   disguised
     *          disguise status from the bridge
     * @param   invisible
     *          invisibility potion status from the bridge
     * @param   gamemode
     *          game mode from the bridge
     * @param   now
     *          current time
     */
    public static void carry(@NotNull UUID id, @Nullable String server, @NotNull Map<String, String> values, boolean vanished,
                             boolean disguised, boolean invisible, int gamemode, long now) {
        if (server == null) return;
        Map<String, String> copy = new HashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getKey().startsWith("%rel_")) continue;
            copy.put(e.getKey(), e.getValue());
        }
        CARRY.put(id, new Carry(server, Collections.unmodifiableMap(copy), vanished, disguised, invisible, gamemode, now));
    }

    /**
     * Takes (and forgets) the state carried for this player, if it comes from the same server and is fresh.
     *
     * @param   id
     *          player UUID
     * @param   server
     *          player's current server
     * @param   now
     *          current time
     * @return  the carried state, or {@code null} if there is none for this player and server
     */
    @Nullable
    public static Carry takeCarry(@NotNull UUID id, @Nullable String server, long now) {
        Carry carry = CARRY.remove(id);
        if (carry == null || server == null || !carry.server.equals(server) || now - carry.takenAt > CARRY_TTL_MILLIS) return null;
        return carry;
    }

    /** Forgets every carried value (end of a load, so later joins never get them) */
    public static void clearCarry() {
        CARRY.clear();
    }

    /**
     * Number of players with carried values (tests and logging).
     *
     * @return  carried player count
     */
    public static int carriedCount() {
        return CARRY.size();
    }

    /**
     * Servers whose bridge answered since the proxy started (tests and the command output).
     *
     * @return  live view of the set
     */
    @NotNull
    public static Set<String> answeredServers() {
        return ANSWERED_SERVERS;
    }
}
