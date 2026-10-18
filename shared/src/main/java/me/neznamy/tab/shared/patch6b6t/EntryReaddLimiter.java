package me.neznamy.tab.shared.patch6b6t;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * [6b6t patch 6b6t.7] Caps how often the global playerlist puts back one entry for one viewer after a foreign remove,
 * so TAB can never end up in a remove/re-add loop with another plugin that keeps removing the same entry.
 * Not thread-safe: used on the Global PlayerList thread only.
 */
public final class EntryReaddLimiter {

    /** Re-adds allowed per (viewer, entry) within one window */
    public static final int MAX_PER_WINDOW = 3;

    /** Window length */
    public static final long WINDOW_MILLIS = 60_000;

    /** Records of one viewer are pruned when it has more than this many */
    private static final int PRUNE_AT = 256;

    /** Per viewer: entry -> {window start, re-adds in the window} */
    private final Map<UUID, Map<UUID, long[]>> records = new HashMap<>();

    /**
     * Returns {@code true} and counts the re-add if this entry may be put back for this viewer now.
     *
     * @param   viewer
     *          viewer UUID
     * @param   entry
     *          tab list entry id
     * @param   now
     *          current time in millis
     * @return  {@code true} if allowed
     */
    public boolean tryAcquire(@NotNull UUID viewer, @NotNull UUID entry, long now) {
        Map<UUID, long[]> perViewer = records.computeIfAbsent(viewer, v -> new HashMap<>());
        long[] record = perViewer.get(entry);
        if (record == null || now - record[0] >= WINDOW_MILLIS) {
            if (record == null && perViewer.size() >= PRUNE_AT) prune(perViewer, now);
            perViewer.put(entry, new long[]{now, 1});
            return true;
        }
        if (record[1] >= MAX_PER_WINDOW) return false;
        record[1]++;
        return true;
    }

    /**
     * Forgets a viewer (quit).
     *
     * @param   viewer
     *          viewer UUID
     */
    public void forget(@NotNull UUID viewer) {
        records.remove(viewer);
    }

    /**
     * Returns the number of records kept for a viewer (tests).
     *
     * @param   viewer
     *          viewer UUID
     * @return  number of entries with a record
     */
    public int size(@NotNull UUID viewer) {
        Map<UUID, long[]> perViewer = records.get(viewer);
        return perViewer == null ? 0 : perViewer.size();
    }

    private static void prune(@NotNull Map<UUID, long[]> perViewer, long now) {
        for (Iterator<long[]> it = perViewer.values().iterator(); it.hasNext(); ) {
            if (now - it.next()[0] >= WINDOW_MILLIS) it.remove();
        }
    }
}
