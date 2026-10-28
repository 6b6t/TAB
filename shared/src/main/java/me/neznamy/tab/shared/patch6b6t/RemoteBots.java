package me.neznamy.tab.shared.patch6b6t;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [6b6t patch] Which players on OTHER proxies are bots, as announced by the proxy they are on.
 * <p>
 * The proxy a player is connected to has his LuckPerms user loaded and gets every change of it,
 * so it decides whether he is a bot and tells the other proxies on the extra Redis channel
 * {@code TAB_4_TAB-6b6t-bots}. An unpatched TAB or 6.1.0-6b6t.1/.2 is not subscribed to that
 * channel and never sees these messages; nothing changes on the main channel {@code TAB_4_TAB}.
 * <p>
 * One text line per message, fields separated by a space:
 * <pre>
 * B1 &lt;origin&gt; S &lt;uuid,uuid,...&gt;   the origin's local bots, every 30 s and as a reply to R
 *                                 (split into lines of at most {@link #MAX_PER_LINE} UUIDs)
 * B1 &lt;origin&gt; + &lt;uuid&gt;            a local player of the origin is a bot (join, or marked)
 * B1 &lt;origin&gt; - &lt;uuid&gt;            a local player of the origin is not a bot (join, or unmarked)
 * B1 &lt;origin&gt; R                   please send your list (sent at load)
 * </pre>
 * Nothing is sent on quit (a "-" racing the quit message would re-show the leaving bot for a moment):
 * a bot that was not confirmed (by "+" or a list) for {@link #CONFIRM_TTL_MS} is forgotten instead, so
 * quit bots, a lost "-" or a stopped proxy cannot keep a flag for long. Unknown versions / types and malformed lines
 * are ignored. Thread-safe (Redis subscriber thread writes, the global playerlist thread reads).
 */
public final class RemoteBots {

    /** Message format version prefix */
    public static final String VERSION = "B1";

    /** Lists are sent every 30 s; a bot not confirmed for 3 periods is forgotten */
    public static final long CONFIRM_TTL_MS = 95_000;

    /** UUIDs per list line (37 bytes each; the messenger writes a line with writeUTF, limit 65535 bytes) */
    public static final int MAX_PER_LINE = 1000;

    /** One known remote bot */
    private static final class Known {
        @NotNull final String origin;
        volatile long confirmed;

        Known(@NotNull String origin, long confirmed) {
            this.origin = origin;
            this.confirmed = confirmed;
        }
    }

    /** Bot UUID -> origin proxy that announced it + last confirmation */
    @NotNull private final Map<UUID, Known> bots = new ConcurrentHashMap<>();

    /**
     * Result of one incoming message.
     */
    public static final class Result {

        /** Players whose bot status changed (re-check their tab entries) */
        @NotNull public final Set<UUID> changed;

        /** The sender asked for our list */
        public final boolean snapshotRequested;

        Result(@NotNull Set<UUID> changed, boolean snapshotRequested) {
            this.changed = changed;
            this.snapshotRequested = snapshotRequested;
        }
    }

    private static final Result NOTHING = new Result(Collections.<UUID>emptySet(), false);

    /**
     * Returns {@code true} if a player on another proxy was announced as a bot. Hot path: one map lookup.
     *
     * @param   id
     *          player UUID
     * @return  {@code true} if bot
     */
    public boolean isBot(@NotNull UUID id) {
        return bots.containsKey(id);
    }

    /**
     * Number of known remote bots (for dumps / tests).
     *
     * @return  number of remote bots
     */
    public int size() {
        return bots.size();
    }

    /**
     * Processes one line received on the bot channel.
     *
     * @param   line
     *          received line
     * @param   ownId
     *          id of this proxy (own messages are ignored)
     * @param   now
     *          current time millis
     * @return  what changed
     */
    @NotNull
    public Result handle(@Nullable String line, @NotNull String ownId, long now) {
        if (line == null) return NOTHING;
        String[] parts = line.split(" ", 4);
        if (parts.length < 3 || !VERSION.equals(parts[0])) return NOTHING;
        String origin = parts[1];
        if (origin.isEmpty() || origin.equals(ownId)) return NOTHING;
        String type = parts[2];
        String arg = parts.length > 3 ? parts[3] : "";
        switch (type) {
            case "R":
                return new Result(Collections.<UUID>emptySet(), true);
            case "+": {
                UUID id = parse(arg);
                if (id == null) return NOTHING;
                return confirm(id, origin, now) ? new Result(Collections.singleton(id), false) : NOTHING;
            }
            case "-": {
                UUID id = parse(arg);
                if (id == null) return NOTHING;
                // Only the proxy that announced the bot can take it back (the player may be on the other proxy by now)
                Known known = bots.get(id);
                if (known != null && known.origin.equals(origin) && bots.remove(id, known)) {
                    return new Result(Collections.singleton(id), false);
                }
                return NOTHING;
            }
            case "S": {
                if (arg.isEmpty()) return NOTHING;
                Set<UUID> changed = new HashSet<>();
                for (String s : arg.split(",")) {
                    UUID id = parse(s);
                    if (id != null && confirm(id, origin, now)) changed.add(id);
                }
                return changed.isEmpty() ? NOTHING : new Result(changed, false);
            }
            default:
                return NOTHING;
        }
    }

    /**
     * Marks a player as a bot of given origin (or refreshes it).
     *
     * @return  {@code true} if the player was not known as a bot before
     */
    private boolean confirm(@NotNull UUID id, @NotNull String origin, long now) {
        Known known = bots.get(id);
        if (known != null && known.origin.equals(origin)) {
            known.confirmed = now;
            return false;
        }
        return bots.put(id, new Known(origin, now)) == null;
    }

    /**
     * Forgets bots that were not confirmed for {@link #CONFIRM_TTL_MS} (lost "-", proxy stopped or downgraded).
     *
     * @param   now
     *          current time millis
     * @return  players whose bot status changed
     */
    @NotNull
    public Set<UUID> expire(long now) {
        Set<UUID> changed = new HashSet<>();
        for (Map.Entry<UUID, Known> e : bots.entrySet()) {
            if (now - e.getValue().confirmed > CONFIRM_TTL_MS && bots.remove(e.getKey(), e.getValue())) {
                changed.add(e.getKey());
            }
        }
        return changed;
    }

    /**
     * Encodes the list of this proxy's local bots, at most {@link #MAX_PER_LINE} per line.
     *
     * @param   ownId
     *          id of this proxy
     * @param   localBots
     *          UUIDs of local players marked as bots
     * @return  lines to send (empty if there are no bots)
     */
    @NotNull
    public static List<String> encodeSnapshot(@NotNull String ownId, @NotNull Collection<UUID> localBots) {
        List<String> lines = new ArrayList<>();
        StringBuilder sb = null;
        int count = 0;
        for (UUID id : localBots) {
            if (sb == null) {
                sb = new StringBuilder(64 + Math.min(localBots.size(), MAX_PER_LINE) * 37);
                sb.append(VERSION).append(' ').append(ownId).append(" S ");
            } else {
                sb.append(',');
            }
            sb.append(id);
            if (++count == MAX_PER_LINE) {
                lines.add(sb.toString());
                sb = null;
                count = 0;
            }
        }
        if (sb != null) lines.add(sb.toString());
        return lines;
    }
    /**
     * Encodes a change of one local player.
     *
     * @param   ownId
     *          id of this proxy
     * @param   id
     *          player UUID
     * @param   bot
     *          {@code true} if the player is a bot now
     * @return  line to send
     */
    @NotNull
    public static String encodeDelta(@NotNull String ownId, @NotNull UUID id, boolean bot) {
        return VERSION + " " + ownId + (bot ? " + " : " - ") + id;
    }

    /**
     * Encodes a request for the full lists of the other proxies.
     *
     * @param   ownId
     *          id of this proxy
     * @return  line to send
     */
    @NotNull
    public static String encodeRequest(@NotNull String ownId) {
        return VERSION + " " + ownId + " R";
    }

    @Nullable
    private static UUID parse(@NotNull String s) {
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
