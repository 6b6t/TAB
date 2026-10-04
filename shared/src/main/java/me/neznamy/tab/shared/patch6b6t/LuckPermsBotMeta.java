package me.neznamy.tab.shared.patch6b6t;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * [6b6t patch] LuckPerms side of {@link BotTabFilter}. Only loaded when LuckPerms is installed
 * (kept out of {@link BotTabFilter}, so that class works without LuckPerms on the classpath).
 * <p>
 * Uses {@code getCachedData().getMetaData()} with the player's own query options, exactly like
 * KubernetesManager's {@code LuckPermsBotFilter}, so both agree on who is a bot.
 */
public final class LuckPermsBotMeta implements BotTabFilter.MetaReader {

    @Nullable private EventSubscription<UserDataRecalculateEvent> subscription;

    @Override
    @Nullable
    public boolean[] read(@NotNull UUID id) {
        User user = LuckPermsProvider.get().getUserManager().getUser(id);
        if (user == null) return null; // not loaded (yet), keep cached values
        CachedMetaData meta = user.getCachedData().getMetaData();
        return new boolean[] {
                Boolean.parseBoolean(meta.getMetaValue(BotTabFilter.BOT_META)),
                Boolean.parseBoolean(meta.getMetaValue(BotTabFilter.HIDE_META))
        };
    }

    /**
     * Calls the listener (on a LuckPerms thread) with the UUID of every user whose data was recalculated,
     * e.g. after a meta change made on a worker reached this proxy through LuckPerms' messaging service.
     *
     * @param   listener
     *          listener, must be fast and thread-safe (it only schedules work)
     */
    @Override
    public void subscribe(@NotNull Consumer<UUID> listener) {
        unsubscribe();
        subscription = LuckPermsProvider.get().getEventBus().subscribe(UserDataRecalculateEvent.class,
                event -> listener.accept(event.getUser().getUniqueId()));
    }

    /**
     * Closes the event subscription (feature unload / {@code /tab reload}).
     */
    @Override
    public void unsubscribe() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }
}
