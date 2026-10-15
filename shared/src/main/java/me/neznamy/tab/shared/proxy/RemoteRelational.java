package me.neznamy.tab.shared.proxy;

import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.chat.EnumChatFormat;
import me.neznamy.tab.shared.features.PlaceholderManagerImpl;
import me.neznamy.tab.shared.features.proxy.ProxyPlayer;
import me.neznamy.tab.shared.features.proxy.ProxySupport;
import me.neznamy.tab.shared.patch6b6t.PatchSettings;
import me.neznamy.tab.shared.patch6b6t.PatchStats;
import me.neznamy.tab.shared.placeholders.PlaceholderReference;
import me.neznamy.tab.shared.placeholders.types.PlayerPlaceholderImpl;
import me.neznamy.tab.shared.placeholders.types.RelationalPlaceholderImpl;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [6b6t patch 6b6t.8] Backend (TAB-Bridge) placeholders in the formats that travel between the proxies.
 * <p>
 * <b>Sending side.</b> A player's tab format is sent to the other proxy as plain text. Upstream sends it as it is,
 * so a backend placeholder that has no value yet (bridge answer lost or not there yet) reaches the other proxy as
 * raw {@code %identifier%} text. {@link #stripUnanswered} removes those before sending; the format is sent again
 * with the value as soon as the bridge answers.
 * <p>
 * <b>Receiving side.</b> Relational placeholders ({@code %rel_...%}, e.g. the Legend Ultra seal) cannot be filled in
 * by the sending proxy: the value depends on the viewer. Upstream showed them raw to every viewer on the other proxy.
 * The bridge of the viewer's server does send the values for every player on that server, also for players of the
 * other proxy, but upstream dropped those because the target is not a local player. They are now kept per viewer
 * ({@link ProxyTabPlayer#getRemoteRelational()}) and {@link #render} fills them in for that viewer. A pair with no
 * value (viewer and target on different servers, the same as for local players) gets the placeholder's own output
 * replacement for its raw text, and never the raw text itself.
 */
public final class RemoteRelational {

    /**
     * [6b6t patch 6b6t.8] Hook for proxy plugins: resolves a relational placeholder for a viewer on this proxy and a
     * player of the other proxy (who has no TabPlayer here). Register it next to the normal TAB API registration
     * ({@code TabAPI.getInstance().getPlaceholderManager().registerRelationalPlaceholder(...)}, which serves local
     * players) with {@link #registerResolver}; call {@link #refreshRemote} / {@link #refreshViewer(TabPlayer)} when the
     * value may have changed (TAB does not poll other-proxy players).
     */
    @FunctionalInterface
    public interface RemoteResolver {

        /**
         * Returns the value for this viewer and other-proxy player.
         *
         * @param   viewer
         *          viewer on this proxy
         * @param   targetId
         *          UUID of the player of the other proxy
         * @param   targetName
         *          his name
         * @param   targetServer
         *          his server as the other proxy reported it, {@code null} if not known yet
         * @return  value (output replacements and colors are applied as for local players), or {@code null} to use the
         *          value the viewer's bridge sent (else the placeholder's empty output)
         */
        @Nullable
        String resolve(@NotNull me.neznamy.tab.api.TabPlayer viewer, @NotNull UUID targetId, @NotNull String targetName,
                       @Nullable String targetServer);
    }

    /** identifier -> resolver; kept through TAB reloads (plugins register once) */
    private static final Map<String, RemoteResolver> RESOLVERS = new ConcurrentHashMap<>();

    private RemoteRelational() {
    }

    /**
     * Registers (or replaces) the resolver of a relational placeholder for players of the other proxy.
     *
     * @param   identifier
     *          placeholder identifier, {@code %rel_...%}
     * @param   resolver
     *          resolver
     */
    public static void registerResolver(@NotNull String identifier, @NotNull RemoteResolver resolver) {
        if (!identifier.startsWith("%rel_") || !identifier.endsWith("%")) {
            throw new IllegalArgumentException("Relational placeholder identifiers start with %rel_ and end with % (got " + identifier + ")");
        }
        RESOLVERS.put(identifier, resolver);
        refreshRemote(null);
    }

    /**
     * Removes the resolver of a relational placeholder.
     *
     * @param   identifier
     *          placeholder identifier
     */
    public static void unregisterResolver(@NotNull String identifier) {
        if (RESOLVERS.remove(identifier) != null) refreshRemote(null);
    }

    /**
     * Shows the current value of every relational placeholder in the format of a player of the other proxy to every
     * viewer on this proxy (e.g. after his seal changed). Runs on TAB's processing thread.
     *
     * @param   targetId
     *          UUID of the player of the other proxy, {@code null} = all of them
     */
    public static void refreshRemote(@Nullable UUID targetId) {
        TAB tab = TAB.getInstance();
        if (tab == null || tab.isPluginDisabled() || tab.getCPUManager() == null) return;
        tab.getCPUManager().runTask(() -> {
            for (TabPlayer viewer : TAB.getInstance().getOnlinePlayers()) refreshViewer(viewer, null, targetId);
        });
    }

    /**
     * Shows one viewer the current relational values of every player of the other proxy (e.g. after the viewer's client
     * capabilities became known). Runs on TAB's processing thread.
     *
     * @param   viewer
     *          viewer on this proxy
     */
    public static void refreshViewer(@NotNull TabPlayer viewer) {
        TAB tab = TAB.getInstance();
        if (tab == null || tab.isPluginDisabled() || tab.getCPUManager() == null) return;
        tab.getCPUManager().runTask(() -> refreshViewer(viewer, null, null));
    }

    /**
     * Stores a relational value the viewer's bridge sent for a player that is not on this proxy.
     *
     * @param   viewer
     *          viewer (local player)
     * @param   identifier
     *          relational placeholder identifier
     * @param   target
     *          target player name as the bridge sent it
     * @param   value
     *          value
     * @return  {@code true} if the value changed
     */
    public static boolean store(@NotNull ProxyTabPlayer viewer, @NotNull String identifier, @NotNull String target, @NotNull String value) {
        if (!PatchSettings.get().remoteRelational) return false; // switched off: nothing is kept, everything renders empty
        Map<String, String> values = viewer.getRemoteRelational().computeIfAbsent(identifier, i -> new ConcurrentHashMap<>());
        return !value.equals(values.put(target.toLowerCase(Locale.ROOT), value));
    }

    /**
     * Returns the stored value for a viewer and a target of the other proxy.
     *
     * @param   viewer
     *          viewer
     * @param   identifier
     *          relational placeholder identifier
     * @param   target
     *          target name
     * @return  stored value or {@code null}
     */
    @Nullable
    public static String get(@NotNull ProxyTabPlayer viewer, @NotNull String identifier, @NotNull String target) {
        Map<String, String> values = viewer.getRemoteRelational().get(identifier);
        return values == null ? null : values.get(target.toLowerCase(Locale.ROOT));
    }

    /**
     * Forgets the values of a player who left the other proxy, for every local viewer.
     *
     * @param   target
     *          name of the player who left
     */
    public static void forget(@NotNull String target) {
        String key = target.toLowerCase(Locale.ROOT);
        for (TabPlayer viewer : TAB.getInstance().getOnlinePlayers()) {
            if (!(viewer instanceof ProxyTabPlayer)) continue;
            for (Map<String, String> values : ((ProxyTabPlayer) viewer).getRemoteRelational().values()) {
                values.remove(key);
            }
        }
    }

    /**
     * Fills in the relational placeholders of a format of the other proxy's player for one viewer.
     *
     * @param   text
     *          format text as received (prefix + name + suffix)
     * @param   viewer
     *          viewer, {@code null} = no viewer (every relational placeholder gets its empty output)
     * @param   target
     *          name of the player the format belongs to
     * @return  text with every relational placeholder replaced
     */
    @NotNull
    public static String render(@NotNull String text, @Nullable TabPlayer viewer, @NotNull String target) {
        return render(text, viewer, null, target);
    }

    /**
     * Fills in the relational placeholders of a format of the other proxy's player for one viewer: a registered
     * {@link RemoteResolver} first, else the value the viewer's bridge sent, else the placeholder's empty output.
     *
     * @param   text
     *          format text as received (prefix + name + suffix)
     * @param   viewer
     *          viewer, {@code null} = no viewer (every relational placeholder gets its empty output)
     * @param   targetId
     *          UUID of the player the format belongs to, {@code null} if unknown (no resolver is asked)
     * @param   target
     *          name of the player the format belongs to
     * @return  text with every relational placeholder replaced
     */
    @NotNull
    public static String render(@NotNull String text, @Nullable TabPlayer viewer, @Nullable UUID targetId, @NotNull String target) {
        if (!text.contains("%rel_")) return text;
        boolean resolve = viewer instanceof ProxyTabPlayer && PatchSettings.get().remoteRelational;
        String result = text;
        for (String identifier : PlaceholderManagerImpl.detectPlaceholders(text)) {
            if (!identifier.startsWith("%rel_")) continue;
            String value = null;
            if (resolve) {
                RemoteResolver resolver = targetId == null ? null : RESOLVERS.get(identifier);
                if (resolver != null) value = resolveSafely(resolver, viewer, targetId, target);
                // a bridge value only counts while viewer and target are on the same server (the bridge computed it there)
                if (value == null && sameServer(viewer, targetId)) value = get((ProxyTabPlayer) viewer, identifier, target);
            }
            result = result.replace(identifier, finish(identifier, value));
        }
        return result;
    }

    /**
     * Returns {@code false} if the viewer and the other proxy's player are known to be on different servers.
     *
     * @param   viewer
     *          viewer
     * @param   targetId
     *          other proxy's player, {@code null} if unknown
     * @return  whether a bridge value for this pair may be shown
     */
    private static boolean sameServer(@NotNull TabPlayer viewer, @Nullable UUID targetId) {
        if (targetId == null || viewer.server == null) return true;
        ProxyPlayer copy = copyOf(targetId);
        return copy == null || copy.server == null || copy.server.getName().equals(viewer.server.getName());
    }

    @Nullable
    private static ProxyPlayer copyOf(@NotNull UUID targetId) {
        if (TAB.getInstance().getFeatureManager() == null) return null;
        ProxySupport proxy = TAB.getInstance().getFeatureManager().getFeature(TabConstants.Feature.PROXY_SUPPORT);
        return proxy == null ? null : proxy.getProxyPlayers().get(targetId);
    }

    @Nullable
    private static String resolveSafely(@NotNull RemoteResolver resolver, @NotNull TabPlayer viewer, @NotNull UUID targetId, @NotNull String target) {
        try {
            ProxyPlayer copy = copyOf(targetId);
            return resolver.resolve(viewer, targetId, target, copy == null || copy.server == null ? null : copy.server.getName());
        } catch (Throwable t) {
            return null; // a broken resolver must never break the tab list; the bridge value / empty output is used
        }
    }

    /**
     * Turns a relational value (or none) into display text the same way the local path does (output replacements,
     * colors), but never returns the raw identifier.
     *
     * @param   identifier
     *          relational placeholder identifier
     * @param   value
     *          value, {@code null} if none
     * @return  display text
     */
    @NotNull
    static String finish(@NotNull String identifier, @Nullable String value) {
        String output = value == null ? identifier : value;
        PlaceholderReference reference = TAB.getInstance().getPlaceholderManager() == null ? null
                : TAB.getInstance().getPlaceholderManager().getPlaceholderRaw(identifier);
        if (reference != null && reference.getHandle() instanceof RelationalPlaceholderImpl) {
            output = reference.getHandle().getReplacements().findReplacement(output);
        }
        output = EnumChatFormat.color(output);
        return output.contains("%rel_") ? "" : output;
    }

    /**
     * Updates the display name of the other proxy's players for one viewer after its relational values changed.
     *
     * @param   viewer
     *          viewer
     * @param   target
     *          name of the target whose value changed, {@code null} = every player of the other proxy
     */
    public static void refreshViewer(@NotNull TabPlayer viewer, @Nullable String target) {
        refreshViewer(viewer, target, null);
    }

    private static void refreshViewer(@NotNull TabPlayer viewer, @Nullable String target, @Nullable UUID targetId) {
        ProxySupport proxy = TAB.getInstance().getFeatureManager().getFeature(TabConstants.Feature.PROXY_SUPPORT);
        if (proxy == null || !TAB.getInstance().getFeatureManager().isFeatureEnabled(TabConstants.Feature.PLAYER_LIST)) return;
        for (ProxyPlayer player : proxy.getProxyPlayers().values()) {
            if (target != null && !player.getName().equalsIgnoreCase(target)) continue;
            if (targetId != null && !player.getUniqueId().equals(targetId)) continue;
            if (player.isVanished() || player.getTabFormat() == null || player.getTabFormat().isDisabled()
                    || !player.getTabFormat().isRelational()) continue; // a disabled format keeps its cleared name
            viewer.getTabList().updateDisplayName(player.getTablistId(), player.getTabFormat().getFormatComponent(viewer));
        }
    }

    /**
     * Removes backend placeholders that are still unresolved (no answer yet, or the raw text as value) from text that is
     * sent to the other proxy.
     * Relational placeholders stay (the receiving proxy fills them in per viewer, see {@link #render}).
     *
     * @param   owner
     *          player the text belongs to
     * @param   text
     *          text to send
     * @return  text without unanswered backend placeholders
     */
    @NotNull
    public static String stripUnanswered(@NotNull TabPlayer owner, @NotNull String text) {
        if (!(owner instanceof ProxyTabPlayer) || text.indexOf('%') == -1 || !PatchSettings.get().bridgeRawStrip) return text;
        PlaceholderManagerImpl manager = TAB.getInstance().getPlaceholderManager();
        if (manager == null) return text;
        String result = text;
        for (String identifier : PlaceholderManagerImpl.detectPlaceholders(text)) {
            // a backend identifier still in the text is unresolved: no answer yet, or the bridge answered with the raw text
            if (identifier.startsWith("%rel_") || !manager.getBridgePlaceholders().containsKey(identifier)) continue;
            PlaceholderReference reference = manager.getPlaceholderRaw(identifier);
            if (reference != null && !(reference.getHandle() instanceof PlayerPlaceholderImpl)) continue;
            result = result.replace(identifier, "");
        }
        if (!result.equals(text)) PatchStats.bridgeRawStripped.incrementAndGet();
        return result;
    }

    /**
     * Fallback for formats that cannot be filled in per viewer (name tags of the other proxy's players): every
     * relational placeholder gets its empty output, never the raw text.
     *
     * @param   text
     *          text
     * @return  text without relational placeholders
     */
    @NotNull
    public static String withoutRelational(@NotNull String text) {
        if (!text.contains("%rel_")) return text;
        String result = text;
        for (String identifier : PlaceholderManagerImpl.detectPlaceholders(text)) {
            if (identifier.startsWith("%rel_")) result = result.replace(identifier, finish(identifier, null));
        }
        return result;
    }
}
