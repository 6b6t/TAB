package me.neznamy.tab.shared.features.nametags;

import lombok.RequiredArgsConstructor;
import me.neznamy.tab.shared.features.proxy.ProxyPlayer;
import me.neznamy.tab.shared.features.types.ProxyFeature;
import me.neznamy.tab.shared.platform.Scoreboard;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;

/**
 * Class for handling proxy players in NameTag feature.
 * Separated to avoid the main class getting too massive.
 */
@RequiredArgsConstructor
public class NameTagProxyHandler implements ProxyFeature {

    @NotNull
    private final NameTag feature;

    public void sendProxyMessage(@NotNull TabPlayer player) {
        if (feature.getProxy() != null) {
            feature.getProxy().sendMessage(new NameTagProxyPlayerData(
                    feature,
                    feature.getProxy().getIdCounter().incrementAndGet(),
                    player.getUniqueId(),
                    player.teamData.teamName,
                    player.teamData.prefix.get(),
                    player.teamData.suffix.get(),
                    player.teamData.getTeamVisibility(player) ? Scoreboard.NameVisibility.ALWAYS : Scoreboard.NameVisibility.NEVER,
                    player.teamData.isDisabled()
            ));
        }
    }

    @Override
    public void onProxyLoadRequest() {
        for (TabPlayer all : feature.getOnlinePlayers().getPlayers()) {
            sendProxyMessage(all);
        }
    }

    @Override
    public void onQuit(@NotNull ProxyPlayer player) {
        if (player.getNametag() == null) {
            // One of the two options is being forcibly unregistered when real player joined
            return;
        }
        feature.unregisterTeam(player);
    }

    @Override
    public void onJoin(@NotNull ProxyPlayer player) {
        if (player.getNametag() == null) return; // Player not loaded yet
        if (player.getNametag().isDisabled()) return;
        for (TabPlayer viewer : feature.getOnlinePlayers().getPlayers()) {
            // [6b6t patch] NameTag.onJoin of a local player may have registered this copy for him already
            // (copy became CONNECTED in between); upstream registered again -> "Tried to register duplicated team"
            if (viewer.teamData.hasTeamRegistered(player)) continue;
            registerFor(player, viewer);
        }
    }

    /**
     * Registers team of a proxy player for one viewer (also used by the [6b6t patch] team audit).
     *
     * @param   player
     *          proxy player with nametag data
     * @param   viewer
     *          viewer to register the team for
     */
    void registerFor(@NotNull ProxyPlayer player, @NotNull TabPlayer viewer) {
        if (player.getNametag() == null) return;
        viewer.teamData.registerTeam(
                player,
                player.getNametag().getResolvedTeamName(),
                feature.getPrefixCache().get(player.getNametag().getPrefix()),
                feature.getSuffixCache().get(player.getNametag().getSuffix()),
                player.getNametag().getNameVisibility(),
                Scoreboard.CollisionRule.ALWAYS,
                Collections.singletonList(player.getNickname()),
                feature.getTeamOptions(),
                feature.getLastColorCache().get(player.getNametag().getPrefix()).getLastStyle().toEnumChatFormat()
        );
    }
}
