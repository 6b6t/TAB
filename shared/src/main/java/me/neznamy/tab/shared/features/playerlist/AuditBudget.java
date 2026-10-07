package me.neznamy.tab.shared.features.playerlist;

import java.util.function.LongSupplier;

/** One tick's budget; checked before each viewer/target pair. */
public final class AuditBudget {
    private final LongSupplier clock;
    private final long start, nanos;
    private final int cap;
    private int repairs;
    public AuditBudget(long nanos, int cap, LongSupplier clock) {
        this.clock = clock; this.start = clock.getAsLong(); this.nanos = nanos; this.cap = cap;
    }
    public boolean canCheck() { return repairs < cap && clock.getAsLong() - start < nanos; }
    public void repaired() { repairs++; }
}
