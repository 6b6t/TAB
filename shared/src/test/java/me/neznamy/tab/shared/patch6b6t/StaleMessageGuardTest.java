package me.neznamy.tab.shared.patch6b6t;

import me.neznamy.tab.shared.features.proxy.StaleMessageGuard;
import me.neznamy.tab.shared.features.proxy.StaleMessageGuard.Kind;
import me.neznamy.tab.shared.features.proxy.StaleMessageGuard.Verdict;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [6b6t patch] Every branch of the stale message guard.
 */
class StaleMessageGuardTest {

    private static final long TTL = 120_000;
    private static final String Y = "proxy-Y";
    private static final String Z = "proxy-Z";
    private final UUID p = UUID.randomUUID();

    @Test
    void messagesWithoutSubjectOrSourceAreAccepted() {
        StaleMessageGuard g = new StaleMessageGuard();
        assertEquals(Verdict.ACCEPT, g.check(Kind.OTHER, null, Y, null, 0, TTL));
        assertEquals(Verdict.ACCEPT, g.check(Kind.OTHER, p, null, Y, 0, TTL));
    }

    @Test
    void noCopyNoTombstoneIsAccepted() {
        StaleMessageGuard g = new StaleMessageGuard();
        for (Kind k : Kind.values()) assertEquals(Verdict.ACCEPT, g.check(k, p, Y, null, 0, TTL));
    }

    @Test
    void ownerOfCopyIsAccepted() {
        StaleMessageGuard g = new StaleMessageGuard();
        for (Kind k : Kind.values()) assertEquals(Verdict.ACCEPT, g.check(k, p, Y, Y, 0, TTL));
    }

    @Test
    void raceA_lateUpdateAndQuitOfRetiredSession() {
        StaleMessageGuard g = new StaleMessageGuard();
        // P joined this proxy, copy from Y retired
        g.addTombstone(p, Y, 1000);
        // Y's late nametag update: must not touch the local team
        assertEquals(Verdict.QUEUE, g.check(Kind.OTHER, p, Y, null, 1100, TTL));
        assertEquals(Verdict.QUEUE, g.check(Kind.OTHER, p, Y, null, 1200, TTL));
        // Y's quit: dropped, tombstone gone
        assertEquals(Verdict.DROP, g.check(Kind.QUIT, p, Y, null, 1300, TTL));
        assertEquals(0, g.tombstoneCount());
        // Afterwards everything from Y is normal again
        assertEquals(Verdict.ACCEPT, g.check(Kind.OTHER, p, Y, null, 1400, TTL));
    }

    @Test
    void newSessionOnTombstoneOriginClearsIt() {
        StaleMessageGuard g = new StaleMessageGuard();
        g.addTombstone(p, Y, 1000);
        // P went back to Y (Y's old quit was lost): Y's new join is accepted and clears the tombstone
        assertEquals(Verdict.ACCEPT, g.check(Kind.JOIN, p, Y, null, 2000, TTL));
        assertEquals(0, g.tombstoneCount());
        assertEquals(Verdict.ACCEPT, g.check(Kind.OTHER, p, Y, Y, 2100, TTL));
    }

    @Test
    void tombstoneOfOtherOriginDoesNotAffectMessages() {
        StaleMessageGuard g = new StaleMessageGuard();
        g.addTombstone(p, Y, 1000);
        assertEquals(Verdict.ACCEPT, g.check(Kind.OTHER, p, Z, null, 1100, TTL));
        assertEquals(Verdict.ACCEPT, g.check(Kind.JOIN, p, Z, null, 1100, TTL));
        assertEquals(1, g.tombstoneCount());
    }

    @Test
    void tombstoneExpires() {
        StaleMessageGuard g = new StaleMessageGuard();
        g.addTombstone(p, Y, 1000);
        assertEquals(Verdict.ACCEPT, g.check(Kind.OTHER, p, Y, null, 1000 + TTL + 1, TTL));
        assertEquals(0, g.tombstoneCount());
        g.addTombstone(p, Y, 1000);
        g.expire(1000 + TTL + 1, TTL);
        assertEquals(0, g.tombstoneCount());
        g.addTombstone(p, Y, 1000);
        g.expire(1000 + TTL, TTL); // exactly at TTL: still alive
        assertEquals(1, g.tombstoneCount());
    }

    @Test
    void ghostCopyIsReplacedByJoinFromNewProxyInstance() {
        StaleMessageGuard g = new StaleMessageGuard();
        // Copy owned by Y (old instance, killed); P joins Z (new instance)
        assertEquals(Verdict.ACCEPT_REPLACE_ORIGIN, g.check(Kind.JOIN, p, Z, Y, 0, TTL));
    }

    @Test
    void nonOwnerCannotChangeOrRemoveCopy() {
        StaleMessageGuard g = new StaleMessageGuard();
        // Late quit of the old owner after the new owner's join replaced the copy
        assertEquals(Verdict.DROP, g.check(Kind.QUIT, p, Y, Z, 0, TTL));
        // Data of a proxy that does not own the copy (yet): queued only, picked up by its join
        assertEquals(Verdict.QUEUE, g.check(Kind.OTHER, p, Z, Y, 0, TTL));
    }
}
