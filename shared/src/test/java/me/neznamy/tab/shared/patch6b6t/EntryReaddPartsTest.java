package me.neznamy.tab.shared.patch6b6t;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** [6b6t patch 6b6t.7] own-remove accounting and the re-add cap */
class EntryReaddPartsTest {
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();

    @Test void ownRemovesAreConsumedOnceEach() {
        OwnRemoveLedger ledger = new OwnRemoveLedger();
        ledger.sent(A); ledger.sent(A);
        assertEquals(List.of(), ledger.foreign(List.of(A)));
        assertEquals(List.of(B), ledger.foreign(List.of(A, B)));
        assertEquals(0, ledger.pendingCount());
        assertEquals(List.of(A), ledger.foreign(List.of(A)), "a third remove of A was not written by TAB");
    }

    @Test void crossedOwnAndForeignRemoveStillReportsOne() {
        OwnRemoveLedger ledger = new OwnRemoveLedger();
        ledger.sent(A);                              // TAB writes its remove of A ...
        assertEquals(List.of(), ledger.foreign(List.of(A)));   // ... but the backend's passes first and is taken as TAB's
        assertEquals(List.of(A), ledger.foreign(List.of(A)));  // TAB's own then counts as the foreign one: still one check
    }

    @Test void pendingOwnRemovesAreBounded() {
        OwnRemoveLedger ledger = new OwnRemoveLedger();
        for (int i = 0; i < OwnRemoveLedger.MAX_PENDING_ENTRIES; i++) ledger.sent(UUID.randomUUID());
        assertEquals(OwnRemoveLedger.MAX_PENDING_ENTRIES, ledger.pendingCount());
        ledger.sent(A);
        assertEquals(1, ledger.pendingCount());
        assertEquals(List.of(), ledger.foreign(List.of(A)));
    }

    @Test void capPerViewerAndEntryWithinWindow() {
        EntryReaddLimiter limiter = new EntryReaddLimiter();
        UUID viewer = UUID.randomUUID();
        for (int i = 0; i < EntryReaddLimiter.MAX_PER_WINDOW; i++) assertTrue(limiter.tryAcquire(viewer, A, 1000));
        assertFalse(limiter.tryAcquire(viewer, A, 1000 + EntryReaddLimiter.WINDOW_MILLIS - 1));
        assertTrue(limiter.tryAcquire(viewer, B, 1000), "other entries have their own budget");
        assertTrue(limiter.tryAcquire(UUID.randomUUID(), A, 1000), "other viewers have their own budget");
        assertTrue(limiter.tryAcquire(viewer, A, 1000 + EntryReaddLimiter.WINDOW_MILLIS), "new window");
        limiter.forget(viewer);
        assertEquals(0, limiter.size(viewer));
    }

    @Test void recordsArePrunedAfterTheirWindow() {
        EntryReaddLimiter limiter = new EntryReaddLimiter();
        UUID viewer = UUID.randomUUID();
        for (int i = 0; i < 256; i++) limiter.tryAcquire(viewer, UUID.randomUUID(), 0);
        assertEquals(256, limiter.size(viewer));
        limiter.tryAcquire(viewer, A, EntryReaddLimiter.WINDOW_MILLIS);
        assertEquals(1, limiter.size(viewer));
    }
}
