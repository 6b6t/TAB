package me.neznamy.tab.shared.proxy;

import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.features.PlaceholderManagerImpl;
import me.neznamy.tab.shared.patch6b6t.BridgeRequests;
import me.neznamy.tab.shared.patch6b6t.PatchSettings;
import me.neznamy.tab.shared.placeholders.PlaceholderReference;
import me.neznamy.tab.shared.placeholders.types.PlayerPlaceholderImpl;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * [6b6t patch 6b6t.8] Keeps the backend (TAB-Bridge) values of every player through a TAB reload.
 * <p>
 * Upstream wipes them on reload and sends every player's request to the backends at once; until a backend answers,
 * its players show raw placeholders, and if an answer is lost they stay raw (12 Oct, worker-2). With this, the new
 * copy of each player starts with the values of the old copy (same server only), so a reload no longer turns names
 * raw, and the requests go out through {@link BridgeRequests} in batches.
 */
public final class BridgeCarry {

    private BridgeCarry() {
    }

    /**
     * Remembers the bridge values of all proxy players (called at the start of unload).
     *
     * @param   players
     *          online players
     */
    public static void snapshot(@NotNull TabPlayer[] players) {
        BridgeRequests.clearCarry();
        if (!PatchSettings.get().bridgeReloadCarry) return;
        long now = System.currentTimeMillis();
        for (TabPlayer player : players) {
            if (!(player instanceof ProxyTabPlayer)) continue;
            ProxyTabPlayer p = (ProxyTabPlayer) player;
            Map<String, String> values = copy(p.getPlaceholders());
            if (values == null) continue; // changing right now: this player is asked right away instead
            BridgeRequests.carry(p.getUniqueId(), p.server == null ? null : p.server.getName(), values, p.vanished,
                    p.isDisguised(), p.hasInvisibilityPotion(), p.getGamemode(), now);
        }
    }

    /**
     * Copies a map that the processing thread may change at the same time (unload runs on the command thread).
     *
     * @param   map
     *          map to copy
     * @return  copy, or {@code null} if it kept changing
     */
    static Map<String, String> copy(@NotNull Map<String, String> map) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return new HashMap<>(map);
            } catch (java.util.ConcurrentModificationException ignored) {
                // try again
            }
        }
        return null;
    }

    /**
     * Applies carried values to the bridge placeholders registered so far (called right after the players of a load
     * were created, before features load). Placeholders registered later read the same values from
     * {@link ProxyTabPlayer#getPlaceholders()} in {@link ProxyPlatform#registerUnknownPlaceholder(String)}.
     */
    public static void applyCarriedValues() {
        PlaceholderManagerImpl manager = TAB.getInstance().getPlaceholderManager();
        for (TabPlayer player : TAB.getInstance().getOnlinePlayers()) {
            if (!(player instanceof ProxyTabPlayer)) continue;
            Map<String, String> values = ((ProxyTabPlayer) player).getPlaceholders();
            if (values.isEmpty()) continue;
            for (String identifier : manager.getBridgePlaceholders().keySet()) {
                String value = values.get(identifier);
                if (value == null || identifier.startsWith("%rel_")) continue;
                PlaceholderReference reference = manager.getPlaceholderRaw(identifier);
                if (reference != null && reference.getHandle() instanceof PlayerPlaceholderImpl) {
                    ((PlayerPlaceholderImpl) reference.getHandle()).updateValue(player, value);
                }
            }
        }
    }
}
