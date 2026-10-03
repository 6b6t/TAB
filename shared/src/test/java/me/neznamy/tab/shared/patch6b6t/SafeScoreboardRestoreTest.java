package me.neznamy.tab.shared.patch6b6t;

import lombok.NonNull;
import me.neznamy.tab.shared.chat.EnumChatFormat;
import me.neznamy.tab.shared.chat.component.TabComponent;
import me.neznamy.tab.shared.platform.Scoreboard;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.platform.decorators.SafeScoreboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [6b6t patch] Entry ownership + restore in SafeScoreboard (fix 3, layer A), checked against a simulated
 * vanilla client (an entry is in at most one team; adding it to a team removes it from the previous one).
 */
class SafeScoreboardRestoreTest {

    /** Simulated client: entry -> team */
    private Map<String, String> client;
    private FakeScoreboard sb;

    /** Records platform calls and applies them to the simulated client */
    private class FakeScoreboard extends SafeScoreboard<TabPlayer> {
        final List<String> calls = new ArrayList<>();

        FakeScoreboard() {
            super(null);
        }

        @Override public void registerObjective(@NonNull Objective objective) {}
        @Override public void setDisplaySlot(@NonNull Objective objective) {}
        @Override public void unregisterObjective(@NonNull Objective objective) {}
        @Override public void updateObjective(@NonNull Objective objective) {}
        @Override public void setScore(@NonNull Score score) {}
        @Override public void removeScore(@NonNull Score score) {}
        @Override @NotNull public Object createTeam(@NonNull String name) { return name; }

        @Override
        public void registerTeam(@NonNull Team team) {
            calls.add("register " + team.getName());
            for (String e : team.getPlayers()) client.put(e, team.getName());
        }

        @Override
        public void unregisterTeam(@NonNull Team team) {
            calls.add("unregister " + team.getName());
            client.values().removeIf(t -> t.equals(team.getName()));
        }

        @Override public void updateTeam(@NonNull Team team) { calls.add("update " + team.getName()); }
    }

    @BeforeEach
    void setUp() {
        PatchSettings.setForTests(new Properties());
        client = new HashMap<>();
        sb = new FakeScoreboard();
    }

    private void register(String team, String entry) {
        sb.registerTeam(team, TabComponent.empty(), TabComponent.empty(), Scoreboard.NameVisibility.ALWAYS,
                Scoreboard.CollisionRule.ALWAYS, Collections.singletonList(entry), 0, EnumChatFormat.RESET);
    }

    @Test
    void raceA_entryIsRestoredWhenTheTeamThatTookItIsRemoved() {
        long before = PatchStats.entryRestored.get();
        register("ownerA", "P");       // local P's team ("...B" in the race)
        register("copyA", "P");        // stale copy's team takes P on the client
        assertEquals("copyA", client.get("P"));
        sb.unregisterTeam("copyA");    // copy's quit
        // Without the patch the client would now have P in no team (white, sorted to the top)
        assertEquals("ownerA", client.get("P"));
        assertTrue(sb.isEntryInTeam("ownerA", "P"));
        assertEquals(before + 1, PatchStats.entryRestored.get());
    }

    @Test
    void noRestoreWhenOriginalTeamIsGoneFirst() {
        register("ownerA", "P");
        register("copyA", "P");
        sb.unregisterTeam("ownerA");   // entry is shown in copyA, nothing lost
        assertEquals("copyA", client.get("P"));
        sb.unregisterTeam("copyA");
        assertNull(client.get("P"));
        assertFalse(sb.hasTeam("ownerA"));
    }

    @Test
    void normalRenameDoesNotRestore() {
        register("old", "P");
        sb.unregisterTeam("old");
        register("new", "P");
        assertEquals("new", client.get("P"));
        sb.unregisterTeam("new");
        assertNull(client.get("P"));
        assertEquals(Arrays.asList("register old", "unregister old", "register new", "unregister new"), sb.calls);
    }

    @Test
    void restoreCanBeSwitchedOff() {
        Properties p = new Properties();
        p.setProperty("entry-restore", "false");
        PatchSettings.setForTests(p);
        register("ownerA", "P");
        register("copyA", "P");
        sb.unregisterTeam("copyA");
        assertNull(client.get("P")); // upstream behaviour
    }

    @Test
    void clearDoesNotRestore() {
        register("ownerA", "P");
        register("copyA", "P");
        sb.calls.clear();
        sb.clear();
        assertTrue(client.isEmpty());
        for (String call : sb.calls) assertTrue(call.startsWith("unregister"), call);
    }

    @Test
    void consistencyChecksAndResend() {
        register("ownerA", "P");
        register("copyA", "P");
        assertFalse(sb.isEntryInTeam("ownerA", "P")); // client shows P in copyA
        assertTrue(sb.teamHasEntry("ownerA", "P"));
        assertTrue(sb.isEntryInTeam("copyA", "P"));
        assertTrue(sb.resendTeam("ownerA"));
        assertEquals("ownerA", client.get("P"));
        assertTrue(sb.isEntryInTeam("ownerA", "P"));
        assertFalse(sb.resendTeam("missing"));
    }

    @Test
    void conflictCounterCounts() {
        long before = PatchStats.entryConflicts.get();
        register("a", "P");
        register("b", "P");
        register("c", "Q");
        assertEquals(before + 1, PatchStats.entryConflicts.get());
    }
}
