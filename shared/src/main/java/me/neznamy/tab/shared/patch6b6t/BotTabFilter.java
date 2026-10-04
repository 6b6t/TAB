package me.neznamy.tab.shared.patch6b6t;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * [6b6t patch] Hides bots from the tab list of players who chose so ({@code /bots tab off} on the workers).
 * <p>
 * Only entries the global playerlist adds are affected: players on other servers of this proxy and
 * players on the other proxy. Players on the viewer's own server are listed by the backend.
 * <ul>
 *   <li>Bot = LuckPerms meta {@code 6b6t-bot} = {@code true} (direct or inherited), same rule as KubernetesManager.</li>
 *   <li>Viewer choice = LuckPerms meta {@code botsfilter-hide-tab} = {@code true}, written by the worker plugin.</li>
 *   <li>Both are cached per local player in {@link BotFlags} (no LuckPerms lookup per visibility check).</li>
 *   <li>Bots on the other proxy come from {@link RemoteBots} (announced by that proxy on its own Redis channel).</li>
 * </ul>
 * Switch: {@code bot-tab-filter=false} in {@code 6b6t-patch.properties} + {@code /btab reload} = upstream behaviour.
 */
public final class BotTabFilter {

    /** LuckPerms meta key marking a bot */
    public static final String BOT_META = "6b6t-bot";

    /** LuckPerms meta key of a viewer who hides bots in the tab list */
    public static final String HIDE_META = "botsfilter-hide-tab";

    /**
     * Source of the two meta values of a player (LuckPerms in production, a map in tests).
     */
    public interface MetaReader {

        /**
         * Reads the flags of a player.
         *
         * @param   id
         *          player UUID
         * @return  {@code {bot, hideBots}}, or {@code null} if unknown right now (keep the cached values)
         */
        @Nullable
        boolean[] read(@NotNull UUID id);

        /**
         * Starts calling the listener with the UUID of every player whose data may have changed (any thread).
         *
         * @param   listener
         *          listener, must only schedule work
         */
        default void subscribe(@NotNull Consumer<UUID> listener) {
            // no change events by default (the periodic refresh still applies changes)
        }

        /**
         * Stops calling the listener.
         */
        default void unsubscribe() {
            // nothing to stop by default
        }
    }

    /** What to do with one (viewer, target) entry after a flag changed */
    public enum Action {
        /** Leave the entry alone */
        NONE,
        /** Send the entry (it should be visible and may be missing) */
        ADD,
        /** Remove the entry (bot hidden for this viewer) */
        REMOVE
    }

    /** Whether the filter is on (setting {@code bot-tab-filter}) */
    private final boolean enabled;

    /** Where the flags of local players come from, {@code null} = LuckPerms not installed (flags stay false) */
    @Nullable private final MetaReader reader;

    /**
     * Creates the LuckPerms reader if LuckPerms is installed. The return type keeps {@link LuckPermsBotMeta}
     * (and with it the LuckPerms API) from being loaded when LuckPerms is missing.
     *
     * @param   luckPermsInstalled
     *          whether LuckPerms is installed
     * @return  reader, or {@code null} without LuckPerms
     */
    @Nullable
    public static MetaReader luckPermsReader(boolean luckPermsInstalled) {
        return luckPermsInstalled ? new LuckPermsBotMeta() : null;
    }

    /**
     * Returns the source of local players' flags.
     *
     * @return  reader, {@code null} if none
     */
    @Nullable
    public MetaReader getReader() {
        return reader;
    }

    /** Bots on other proxies */
    @NotNull private final RemoteBots remote = new RemoteBots();

    /**
     * Constructs new instance.
     *
     * @param   enabled
     *          {@code false} = never hide anything
     * @param   reader
     *          source of local players' flags, {@code null} if not available
     */
    public BotTabFilter(boolean enabled, @Nullable MetaReader reader) {
        this.enabled = enabled;
        this.reader = reader;
    }

    /**
     * Returns {@code true} if the filter is on.
     *
     * @return  {@code true} if on
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the registry of bots on other proxies.
     *
     * @return  remote bots
     */
    @NotNull
    public RemoteBots getRemote() {
        return remote;
    }

    /**
     * The visibility rule. Hot path: only field reads; the viewer flag is checked first, so for the
     * (many) viewers who did not opt in this costs one volatile read.
     *
     * @param   viewerHidesBots
     *          viewer chose to hide bots
     * @param   differentServer
     *          viewer and target are on different servers (= the entry is managed by the global playerlist)
     * @param   targetIsBot
     *          target is a bot
     * @return  {@code true} if the entry must not be shown
     */
    public boolean hides(boolean viewerHidesBots, boolean differentServer, boolean targetIsBot) {
        return enabled && viewerHidesBots && differentServer && targetIsBot;
    }

    /**
     * Decides what to do with an entry of a bot (or a player whose bot marker just changed) for a viewer
     * after a flag changed. Callers only pass pairs on different servers.
     *
     * @param   viewerHidesBots
     *          viewer's current choice
     * @param   targetIsBot
     *          target's current bot marker
     * @param   shouldSee
     *          result of the full global playerlist rule (includes {@link #hides})
     * @return  action to take
     */
    @NotNull
    public Action afterChange(boolean viewerHidesBots, boolean targetIsBot, boolean shouldSee) {
        if (hides(viewerHidesBots, true, targetIsBot)) return Action.REMOVE;
        return shouldSee ? Action.ADD : Action.NONE;
    }

    /**
     * Re-reads a local player's flags and stores them.
     *
     * @param   flags
     *          player's cached flags
     * @param   id
     *          player UUID
     * @return  bit mask of {@link BotFlags#BOT_CHANGED} / {@link BotFlags#HIDE_CHANGED}, 0 if nothing changed or unknown
     */
    public int refresh(@NotNull BotFlags flags, @NotNull UUID id) {
        if (reader == null) return 0;
        boolean[] values;
        try {
            values = reader.read(id);
        } catch (Throwable t) {
            return 0; // keep the cached values, the next periodic refresh tries again
        }
        if (values == null || values.length < 2) return 0;
        return flags.update(values[0], values[1]);
    }
}
