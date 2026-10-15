package me.neznamy.tab.shared.proxy.message.incoming;

import com.google.common.io.ByteArrayDataInput;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.placeholders.types.PlayerPlaceholderImpl;
import me.neznamy.tab.shared.proxy.ProxyTabPlayer;
import org.jetbrains.annotations.NotNull;

public class Vanished implements IncomingMessage {

    private boolean vanished;

    @Override
    public void read(@NotNull ByteArrayDataInput in) {
        vanished = in.readBoolean();
    }

    @Override
    public void process(@NotNull ProxyTabPlayer player) {
        player.noteBridgeFlagUpdate(ProxyTabPlayer.FLAG_VANISH); // [6b6t patch 6b6t.8]
        if (player.isVanished() != vanished) {
            player.setVanished(vanished);
            TAB.getInstance().getFeatureManager().onVanishStatusChange(player);
            ((PlayerPlaceholderImpl) TAB.getInstance().getPlaceholderManager().getPlaceholder(TabConstants.Placeholder.VANISHED))
                    .updateValue(player, Boolean.toString(player.isVanished()));
        }
    }
}
