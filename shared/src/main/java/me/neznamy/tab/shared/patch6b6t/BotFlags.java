package me.neznamy.tab.shared.patch6b6t;

/**
 * [6b6t patch] Cached LuckPerms flags of one local player for the bot tab filter.
 * <p>
 * Read on every visibility check of the global playerlist (hot path), so they are plain volatile
 * booleans. Written only by {@link #update(boolean, boolean)} from a refresh (join, LuckPerms
 * recalculation, periodic recheck); never looked up in LuckPerms per check.
 */
public final class BotFlags {

    /** {@link #update} result bit: the player's own bot marker ({@code 6b6t-bot}) changed */
    public static final int BOT_CHANGED = 1;

    /** {@link #update} result bit: the player's choice to hide bots ({@code botsfilter-hide-tab}) changed */
    public static final int HIDE_CHANGED = 2;

    /** LuckPerms meta {@code 6b6t-bot} = true (direct or inherited) */
    private volatile boolean bot;

    /** LuckPerms meta {@code botsfilter-hide-tab} = true: this viewer does not want to see bots in the tab list */
    private volatile boolean hideBots;

    /**
     * Returns {@code true} if this player is marked as a bot.
     *
     * @return  {@code true} if bot
     */
    public boolean isBot() {
        return bot;
    }

    /**
     * Returns {@code true} if this player chose to hide bots in the tab list.
     *
     * @return  {@code true} if bots are hidden for this viewer
     */
    public boolean hidesBots() {
        return hideBots;
    }

    /**
     * Stores new values and returns what changed.
     *
     * @param   bot
     *          new bot marker
     * @param   hideBots
     *          new hide choice
     * @return  bit mask of {@link #BOT_CHANGED} and {@link #HIDE_CHANGED}, 0 if nothing changed
     */
    public synchronized int update(boolean bot, boolean hideBots) {
        int changed = 0;
        if (this.bot != bot) changed |= BOT_CHANGED;
        if (this.hideBots != hideBots) changed |= HIDE_CHANGED;
        this.bot = bot;
        this.hideBots = hideBots;
        return changed;
    }
}
