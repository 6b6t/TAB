package me.neznamy.tab.shared.features.proxy;

import lombok.AllArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [6b6t patch] Decides whether a proxy message about a player still belongs to the
 * session that this proxy currently shows for that player.
 * <p>
 * Problem it solves: player P leaves proxy Y and joins proxy X before X processed Y's quit.
 * Y's late messages for P (nametag update, quit) used to change or remove the team that the
 * local P now uses, leaving P white and teamless until a rejoin.
 * <p>
 * Uses only data that is already on the wire (the sender proxy id in the message header), so the
 * proxy message format stays 100% compatible with an unpatched TAB 6.1.0 on the other proxy.
 * <p>
 * State is touched only from the TAB Processing Thread (the maps are concurrent for reads by
 * other threads, e.g. dumps).
 */
public class StaleMessageGuard {

    /** What to do with a message */
    public enum Verdict {

        /** Process normally */
        ACCEPT,

        /** Ignore (used only for quits of a session that is no longer shown) */
        DROP,

        /**
         * Do not apply to the current copy, only keep it as queued data for the sending proxy.
         * If that proxy's join for the player follows (messages from one proxy can overtake each
         * other, upstream already queues data that arrives before the join), the join picks it up;
         * if it was stale, newer data of the same proxy has a higher id and replaces it.
         */
        QUEUE,

        /** A join from a different proxy than the one owning the current copy: remove the old copy first, then process */
        ACCEPT_REPLACE_ORIGIN
    }

    /** Kind of message, only the difference between join, quit and the rest matters */
    public enum Kind { JOIN, QUIT, OTHER }

    /** Remembered origin of a remote copy that was retired because the player joined this proxy */
    @AllArgsConstructor
    private static class Tombstone {
        @NotNull private final String origin;
        private final long createdMillis;
    }

    /** Tombstones by player UUID */
    private final Map<UUID, Tombstone> tombstones = new ConcurrentHashMap<>();

    /**
     * Remembers that the copy of player {@code id} coming from {@code origin} was retired.
     *
     * @param   id
     *          player UUID
     * @param   origin
     *          proxy id that sent the retired copy
     * @param   nowMillis
     *          current time
     */
    public void addTombstone(@NotNull UUID id, @NotNull String origin, long nowMillis) {
        tombstones.put(id, new Tombstone(origin, nowMillis));
    }

    /**
     * Returns number of tombstones currently held (for tests and dumps).
     *
     * @return  number of tombstones
     */
    public int tombstoneCount() {
        return tombstones.size();
    }

    /**
     * Removes expired tombstones.
     *
     * @param   nowMillis
     *          current time
     * @param   ttlMillis
     *          tombstone lifetime
     */
    public void expire(long nowMillis, long ttlMillis) {
        tombstones.values().removeIf(t -> nowMillis - t.createdMillis > ttlMillis);
    }

    /**
     * Decides what to do with a message.
     *
     * @param   kind
     *          message kind
     * @param   subject
     *          player the message is about, {@code null} for messages not about one player (always accepted)
     * @param   source
     *          proxy id from the message header
     * @param   copyOrigin
     *          origin proxy of the current remote copy of {@code subject}, {@code null} if there is no copy
     *          or its origin is unknown
     * @param   nowMillis
     *          current time
     * @param   ttlMillis
     *          tombstone lifetime
     * @return  verdict
     */
    @NotNull
    public Verdict check(@NotNull Kind kind, @Nullable UUID subject, @Nullable String source, @Nullable String copyOrigin,
                         long nowMillis, long ttlMillis) {
        if (subject == null || source == null) return Verdict.ACCEPT;

        // 1. Tombstone: the copy from this origin was retired because the player joined here
        Tombstone t = tombstones.get(subject);
        if (t != null && nowMillis - t.createdMillis > ttlMillis) {
            tombstones.remove(subject);
            t = null;
        }
        if (t != null && t.origin.equals(source)) {
            if (kind == Kind.JOIN) {
                // The origin has a NEW session of the player (player went back there). Forget and continue.
                tombstones.remove(subject);
            } else if (kind == Kind.QUIT) {
                // Last message of the old session, nothing more will come from it
                tombstones.remove(subject);
                return Verdict.DROP;
            } else {
                // Update from the old session: must not touch the local player's team
                return Verdict.QUEUE;
            }
        }

        // 2. A copy exists and is owned by another proxy
        if (copyOrigin != null && !copyOrigin.equals(source)) {
            if (kind == Kind.JOIN) return Verdict.ACCEPT_REPLACE_ORIGIN;
            // A proxy that does not own the copy must not change or remove it
            return kind == Kind.QUIT ? Verdict.DROP : Verdict.QUEUE;
        }
        return Verdict.ACCEPT;
    }
}
