package me.neznamy.tab.shared.patch6b6t;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [6b6t patch 6b6t.7] Tab list removes that TAB itself sent to one viewer and that have not passed the viewer's
 * packet pipeline yet. Every other remove of an entry (the backend the target just left, the proxy, another plugin)
 * is "foreign" and may have deleted an entry the global playerlist must show.
 * <p>
 * {@link #sent(UUID)} is called right before TAB's remove is written, {@link #foreign(Collection)} by the pipeline
 * for each remove packet on its way to the client. Removes leave the pipeline in the order they were written, so
 * counting per entry is enough: when a foreign remove and TAB's own remove of the same entry cross, the first one
 * seen is taken as TAB's, which only moves the re-check to the other packet. Thread-safe.
 */
public final class OwnRemoveLedger {

    /** Own removes per entry not seen in the pipeline yet */
    private final Map<UUID, Integer> pending = new ConcurrentHashMap<>();

    /**
     * More distinct entries than this means removes that never reached the pipeline (closed connection) piled up:
     * forget them. Safe: an own remove taken for a foreign one only costs a check that finds nothing to put back.
     */
    static final int MAX_PENDING_ENTRIES = 10_000;

    /**
     * Notes that TAB is about to send a remove of this entry.
     *
     * @param   entry
     *          tab list entry id
     */
    public void sent(@NotNull UUID entry) {
        if (pending.size() >= MAX_PENDING_ENTRIES) pending.clear();
        pending.merge(entry, 1, Integer::sum);
    }

    /**
     * Consumes TAB's own removes among the entries of one remove packet and returns the others.
     *
     * @param   entries
     *          entries removed by a packet on its way to the client
     * @return  entries this packet removed that TAB did not remove itself (possibly empty)
     */
    @NotNull
    public List<UUID> foreign(@NotNull Collection<UUID> entries) {
        List<UUID> foreign = null;
        for (UUID entry : entries) {
            boolean[] own = {false};
            pending.computeIfPresent(entry, (k, count) -> {
                own[0] = true;
                return count > 1 ? count - 1 : null;
            });
            if (own[0]) continue;
            if (foreign == null) foreign = new ArrayList<>(entries.size());
            foreign.add(entry);
        }
        return foreign == null ? Collections.emptyList() : foreign;
    }

    /**
     * Returns the number of own removes not seen in the pipeline yet (tests).
     *
     * @return  pending own removes
     */
    public int pendingCount() {
        int sum = 0;
        for (int count : pending.values()) sum += count;
        return sum;
    }
}
