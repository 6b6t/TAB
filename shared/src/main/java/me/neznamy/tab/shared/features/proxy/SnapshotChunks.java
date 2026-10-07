package me.neznamy.tab.shared.features.proxy;

import java.util.*;

/** Processing-thread confined proof of one complete response to one request. */
public final class SnapshotChunks {
    private final long request;
    private long snapshot = -1;
    private int count;
    private final Map<Integer, Set<UUID>> chunks = new HashMap<>();
    public SnapshotChunks(long request) { this.request = request; }
    public void add(long request, long snapshot, int index, int count, Set<UUID> players) {
        if (request != this.request || snapshot < 0 || count < 1 || count > 100000 || index < 0 || index >= count) return;
        if (this.snapshot == -1) { this.snapshot = snapshot; this.count = count; }
        if (this.snapshot != snapshot || this.count != count) return;
        chunks.putIfAbsent(index, new HashSet<>(players));
    }
    public boolean isComplete() { return count > 0 && chunks.size() == count; }
    public Set<UUID> players() {
        Set<UUID> result = new HashSet<>();
        for (Set<UUID> part : chunks.values()) result.addAll(part);
        return result;
    }
}
