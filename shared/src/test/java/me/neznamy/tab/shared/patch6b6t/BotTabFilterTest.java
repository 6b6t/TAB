package me.neznamy.tab.shared.patch6b6t;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [6b6t patch 6b6t.3] Bot tab filter: visibility rule, cached flags, runtime changes, bots on the other proxy.
 * <p>
 * The global playerlist calls {@link BotTabFilter#hides} from both {@code shouldSee} overloads and
 * {@link BotTabFilter#afterChange} for every (viewer, bot) pair after a flag changed. The small
 * {@link TabModel} below applies those decisions the way the global playerlist does, to a set of
 * entries per viewer, so the tests check what the client ends up with.
 */
class BotTabFilterTest {

    private static final String OWN = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER = "22222222-2222-2222-2222-222222222222";
    private static final String THIRD = "33333333-3333-3333-3333-333333333333";

    /** Simulated LuckPerms: uuid -> {bot, hide}; missing = user not loaded */
    private final Map<UUID, boolean[]> luckPerms = new HashMap<>();
    private BotTabFilter filter;

    private final UUID viewerId = UUID.randomUUID();
    private final UUID botId = UUID.randomUUID();
    private final UUID humanId = UUID.randomUUID();
    private final BotFlags viewer = new BotFlags();
    private final BotFlags bot = new BotFlags();
    private final BotFlags human = new BotFlags();

    @BeforeEach
    void setUp() {
        filter = new BotTabFilter(true, id -> luckPerms.get(id));
        luckPerms.put(viewerId, new boolean[] {false, true});
        luckPerms.put(botId, new boolean[] {true, false});
        luckPerms.put(humanId, new boolean[] {false, false});
        filter.refresh(viewer, viewerId);
        filter.refresh(bot, botId);
        filter.refresh(human, humanId);
    }

    /** Upstream rule (server groups / vanish) is "visible" in these tests; this adds the bot rule like shouldSee does. */
    private boolean shouldSee(BotFlags v, boolean differentServer, boolean targetBot) {
        return !(v.hidesBots() && filter.hides(true, differentServer, targetBot));
    }

    /** Entries one viewer sees from the global playerlist (players on other servers) */
    private final class TabModel {
        final Set<UUID> entries = new HashSet<>();

        /** join / server switch / tab list clear / proxy join: add if shouldSee */
        void addIfVisible(BotFlags v, UUID target, boolean targetBot) {
            if (shouldSee(v, true, targetBot)) entries.add(target);
        }

        void apply(BotTabFilter.Action action, UUID target) {
            if (action == BotTabFilter.Action.REMOVE) entries.remove(target);
            if (action == BotTabFilter.Action.ADD) entries.add(target);
        }
    }

    @Test
    void botHiddenForViewerWhoOptedIn() {
        assertTrue(viewer.hidesBots());
        assertTrue(bot.isBot());
        assertTrue(filter.hides(viewer.hidesBots(), true, bot.isBot()));
        TabModel tab = new TabModel();
        tab.addIfVisible(viewer, botId, bot.isBot());
        tab.addIfVisible(viewer, humanId, human.isBot());
        assertEquals(Collections.singleton(humanId), tab.entries);
    }

    @Test
    void botVisibleForEveryoneElse() {
        BotFlags other = new BotFlags(); // no LuckPerms meta at all
        assertFalse(filter.hides(other.hidesBots(), true, bot.isBot()));
        TabModel tab = new TabModel();
        tab.addIfVisible(other, botId, true);
        assertTrue(tab.entries.contains(botId));
        // bots do not hide each other unless they opted in
        assertFalse(filter.hides(bot.hidesBots(), true, true));
    }

    @Test
    void sameServerAndSwitchedOffAreUpstreamBehaviour() {
        // same server = listed by the backend, TAB leaves it alone
        assertFalse(filter.hides(true, false, true));
        BotTabFilter off = new BotTabFilter(false, id -> luckPerms.get(id));
        assertFalse(off.hides(true, true, true));
        assertEquals(BotTabFilter.Action.ADD, off.afterChange(true, true, true));
    }

    @Test
    void nonBotNeverHidden() {
        for (boolean hides : new boolean[] {false, true}) {
            for (boolean diff : new boolean[] {false, true}) {
                assertFalse(filter.hides(hides, diff, false));
            }
            assertNotEquals(BotTabFilter.Action.REMOVE, filter.afterChange(hides, false, true));
            assertNotEquals(BotTabFilter.Action.REMOVE, filter.afterChange(hides, false, false));
        }
        assertFalse(human.isBot());
    }

    @Test
    void preferenceToggledAtRuntime() {
        TabModel tab = new TabModel();
        BotFlags v = new BotFlags();
        UUID vId = UUID.randomUUID();
        luckPerms.put(vId, new boolean[] {false, false});
        assertEquals(0, filter.refresh(v, vId));
        tab.addIfVisible(v, botId, true);
        tab.addIfVisible(v, humanId, false);
        assertEquals(new HashSet<>(Arrays.asList(botId, humanId)), tab.entries);

        // /bots tab off -> meta set -> LuckPerms recalculation -> refresh
        luckPerms.put(vId, new boolean[] {false, true});
        assertEquals(BotFlags.HIDE_CHANGED, filter.refresh(v, vId));
        // the global playerlist re-checks only bots for this viewer
        tab.apply(filter.afterChange(v.hidesBots(), true, shouldSee(v, true, true)), botId);
        assertEquals(Collections.singleton(humanId), tab.entries);

        // later events (server switch of the bot, proxy updates) go through shouldSee and do not re-add it
        tab.addIfVisible(v, botId, true);
        assertEquals(Collections.singleton(humanId), tab.entries);

        // /bots tab on -> meta removed
        luckPerms.put(vId, new boolean[] {false, false});
        assertEquals(BotFlags.HIDE_CHANGED, filter.refresh(v, vId));
        tab.apply(filter.afterChange(v.hidesBots(), true, shouldSee(v, true, true)), botId);
        assertEquals(new HashSet<>(Arrays.asList(botId, humanId)), tab.entries);

        // nothing changed -> nothing to do
        assertEquals(0, filter.refresh(v, vId));
    }

    @Test
    void preferenceOffDoesNotShowOtherwiseInvisibleBot() {
        // bot in another server group / vanished: upstream rule says invisible, so no ADD after the toggle
        assertEquals(BotTabFilter.Action.NONE, filter.afterChange(false, true, false));
    }

    @Test
    void botMarkerToggledAtRuntime() {
        TabModel tab = new TabModel();
        BotFlags target = new BotFlags();
        UUID targetId = UUID.randomUUID();
        luckPerms.put(targetId, new boolean[] {false, false});
        filter.refresh(target, targetId);
        tab.addIfVisible(viewer, targetId, target.isBot());
        assertTrue(tab.entries.contains(targetId));

        // mark_bots.py marks him
        luckPerms.put(targetId, new boolean[] {true, false});
        assertEquals(BotFlags.BOT_CHANGED, filter.refresh(target, targetId));
        tab.apply(filter.afterChange(viewer.hidesBots(), target.isBot(), shouldSee(viewer, true, target.isBot())), targetId);
        assertFalse(tab.entries.contains(targetId));

        // unmarked again
        luckPerms.put(targetId, new boolean[] {false, false});
        assertEquals(BotFlags.BOT_CHANGED, filter.refresh(target, targetId));
        tab.apply(filter.afterChange(viewer.hidesBots(), target.isBot(), shouldSee(viewer, true, target.isBot())), targetId);
        assertTrue(tab.entries.contains(targetId));

        // both at once
        luckPerms.put(targetId, new boolean[] {true, true});
        assertEquals(BotFlags.BOT_CHANGED | BotFlags.HIDE_CHANGED, filter.refresh(target, targetId));
    }

    @Test
    void unknownOrFailingLuckPermsKeepsCachedFlags() {
        BotFlags f = new BotFlags();
        UUID id = UUID.randomUUID();
        luckPerms.put(id, new boolean[] {true, true});
        filter.refresh(f, id);
        luckPerms.remove(id); // user unloaded
        assertEquals(0, filter.refresh(f, id));
        assertTrue(f.isBot());
        assertTrue(f.hidesBots());

        BotTabFilter throwing = new BotTabFilter(true, x -> { throw new IllegalStateException("LuckPerms disabled"); });
        assertEquals(0, throwing.refresh(f, id));
        assertTrue(f.isBot());

        BotTabFilter noLuckPerms = new BotTabFilter(true, null);
        BotFlags g = new BotFlags();
        assertEquals(0, noLuckPerms.refresh(g, id));
        assertFalse(g.isBot());
        assertNull(BotTabFilter.luckPermsReader(false));
    }

    @Test
    void proxyPlayerBotFromOtherProxy() {
        RemoteBots remote = filter.getRemote();
        UUID remoteBot = UUID.randomUUID();
        UUID remoteHuman = UUID.randomUUID();
        long now = 1_000_000;

        // join of a bot on the other proxy
        RemoteBots.Result r = remote.handle(RemoteBots.encodeDelta(OTHER, remoteBot, true), OWN, now);
        assertEquals(Collections.singleton(remoteBot), r.changed);
        assertTrue(remote.isBot(remoteBot));
        assertFalse(remote.isBot(remoteHuman));
        assertTrue(filter.hides(viewer.hidesBots(), true, remote.isBot(remoteBot)));
        assertFalse(filter.hides(viewer.hidesBots(), true, remote.isBot(remoteHuman)));

        // repeated announcement: no change
        assertTrue(remote.handle(RemoteBots.encodeDelta(OTHER, remoteBot, true), OWN, now).changed.isEmpty());

        // only the announcing proxy can take it back
        assertTrue(remote.handle(RemoteBots.encodeDelta(THIRD, remoteBot, false), OWN, now).changed.isEmpty());
        assertTrue(remote.isBot(remoteBot));
        assertEquals(Collections.singleton(remoteBot), remote.handle(RemoteBots.encodeDelta(OTHER, remoteBot, false), OWN, now).changed);
        assertFalse(remote.isBot(remoteBot));

        // own messages are ignored (they come back over Redis)
        assertTrue(remote.handle(RemoteBots.encodeDelta(OWN, remoteHuman, true), OWN, now).changed.isEmpty());
        assertFalse(remote.isBot(remoteHuman));
    }

    @Test
    void remoteListsChunksRequestsAndExpiry() {
        RemoteBots remote = new RemoteBots();
        long now = 5_000_000;
        List<UUID> bots = new ArrayList<>();
        for (int i = 0; i < 2500; i++) bots.add(UUID.randomUUID());
        List<String> lines = RemoteBots.encodeSnapshot(OTHER, bots);
        assertEquals(3, lines.size());
        for (String line : lines) {
            assertTrue(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 65_535, "fits writeUTF");
        }
        int changed = 0;
        for (String line : lines) changed += remote.handle(line, OWN, now).changed.size();
        assertEquals(2500, changed);
        assertEquals(2500, remote.size());
        assertTrue(RemoteBots.encodeSnapshot(OTHER, Collections.<UUID>emptyList()).isEmpty());

        // a list again only refreshes
        for (String line : lines) assertTrue(remote.handle(line, OWN, now + 30_000).changed.isEmpty());

        // request
        RemoteBots.Result req = remote.handle(RemoteBots.encodeRequest(OTHER), OWN, now);
        assertTrue(req.snapshotRequested);
        assertFalse(remote.handle(RemoteBots.encodeRequest(OWN), OWN, now).snapshotRequested);

        // not confirmed for too long (proxy stopped / downgraded to 6b6t.2): forgotten, so they become visible again
        UUID late = bots.get(0);
        remote.handle(RemoteBots.encodeDelta(OTHER, late, true), OWN, now + 100_000);
        Set<UUID> expired = remote.expire(now + 30_000 + RemoteBots.CONFIRM_TTL_MS + 1);
        assertEquals(2499, expired.size());
        assertFalse(expired.contains(late));
        assertTrue(remote.isBot(late));
    }

    @Test
    void malformedAndUnknownLinesIgnored() {
        RemoteBots remote = new RemoteBots();
        UUID id = UUID.randomUUID();
        for (String line : new String[] {null, "", "B1", "B1 " + OTHER, "B2 " + OTHER + " + " + id, "B1 " + OTHER + " X " + id,
                "B1 " + OTHER + " + not-a-uuid", "B1 " + OTHER + " S ", "B1  + " + id, OTHER /* a 6b6t.2 heartbeat */}) {
            RemoteBots.Result r = remote.handle(line, OWN, 1);
            assertTrue(r.changed.isEmpty(), String.valueOf(line));
            assertFalse(r.snapshotRequested, String.valueOf(line));
        }
        assertEquals(0, remote.size());
        // a list with one bad UUID keeps the good ones
        assertEquals(1, remote.handle("B1 " + OTHER + " S bad," + id, OWN, 1).changed.size());
    }

    @Test
    void settingDefaultsOnAndCanBeSwitchedOff() {
        PatchSettings.setForTests(new Properties());
        assertTrue(PatchSettings.get().botTabFilter);
        Properties p = new Properties();
        p.setProperty("bot-tab-filter", "false");
        PatchSettings.setForTests(p);
        assertFalse(PatchSettings.get().botTabFilter);
        PatchSettings.setForTests(new Properties());
    }
}
