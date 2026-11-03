package me.neznamy.tab.shared.features.globalplayerlist;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local hard-mute snapshot shared by TAB's list logic and Velocity packet adapter. */
public final class HardMuteStatus {

    private static final Set<UUID> MUTED = ConcurrentHashMap.newKeySet();

    private HardMuteStatus() {}

    public static boolean isMuted(UUID playerId) {
        return MUTED.contains(playerId);
    }

    public static boolean shouldShow(UUID viewerId, UUID targetId) {
        return viewerId == null || targetId == null || viewerId.equals(targetId) || !MUTED.contains(targetId);
    }

    public static void setMuted(UUID playerId, boolean muted) {
        if (muted) MUTED.add(playerId);
        else MUTED.remove(playerId);
    }

    static Set<UUID> snapshot() {
        return Collections.unmodifiableSet(new HashSet<>(MUTED));
    }
}
