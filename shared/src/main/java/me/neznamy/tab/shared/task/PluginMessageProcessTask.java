package me.neznamy.tab.shared.task;

import lombok.RequiredArgsConstructor;
import me.neznamy.tab.shared.proxy.ProxyTabPlayer;
import me.neznamy.tab.shared.proxy.message.incoming.IncomingMessage;

/**
 * Task for processing incoming plugin messages from backend server.
 */
@RequiredArgsConstructor
public class PluginMessageProcessTask implements Runnable {

    /** Decoded plugin message */
    private final IncomingMessage message;

    /** Player who received the plugin message */
    private final ProxyTabPlayer player;

    /** [6b6t patch 6b6t.8] Server the message came from, {@code null} if unknown */
    @org.jetbrains.annotations.Nullable private final String sourceServer;

    /**
     * Creates a task for a message of unknown origin.
     *
     * @param   message
     *          decoded message
     * @param   player
     *          player the message is about
     */
    public PluginMessageProcessTask(@org.jetbrains.annotations.NotNull IncomingMessage message, @org.jetbrains.annotations.NotNull ProxyTabPlayer player) {
        this(message, player, null);
    }

    @Override
    public void run() {
        // [6b6t patch 6b6t.8] a late message of the server the player already left must not change his state
        // (e.g. an old answer marking him answered while the new server's answer was lost). Same thread as the switch.
        if (sourceServer != null && player.server != null && !sourceServer.equals(player.server.getName())
                && me.neznamy.tab.shared.patch6b6t.PatchSettings.get().bridgeSourceCheck) {
            me.neznamy.tab.shared.patch6b6t.PatchStats.bridgeForeignDropped.incrementAndGet();
            return;
        }
        message.process(player);
    }
}
