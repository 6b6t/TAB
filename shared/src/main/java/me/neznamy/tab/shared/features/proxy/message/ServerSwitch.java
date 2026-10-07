package me.neznamy.tab.shared.features.proxy.message;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import lombok.AllArgsConstructor;
import lombok.ToString;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.data.Server;
import me.neznamy.tab.shared.features.proxy.ProxyPlayer;
import me.neznamy.tab.shared.features.proxy.ProxySupport;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Message sent from another proxy to switch a player to a different server.
 */
@AllArgsConstructor
@ToString
public class ServerSwitch extends ProxyMessage {

    @NotNull private final UUID playerId;
    @NotNull private final Server newServer;

    /**
     * Creates new instance and reads data from byte input.
     *
     * @param   in
     *          Input stream to read from
     */
    public ServerSwitch(@NotNull ByteArrayDataInput in) {
        playerId = readUUID(in);
        newServer = Server.byName(in.readUTF());
    }

    @Override
    public void write(@NotNull ByteArrayDataOutput out) {
        writeUUID(out, playerId);
        out.writeUTF(newServer.getName());
    }


    /** [6b6t patch] Player this message is about */
    @Override
    @NotNull
    public UUID getSubjectId() {
        return playerId;
    }

    @Override
    public void queue(@NotNull ProxySupport proxySupport) {
        me.neznamy.tab.shared.features.proxy.QueuedData data = proxySupport.queuedFor(playerId, getSourceProxy());
        if (getSequence() >= 0 && getSequence() < data.getServerSequence()) return;
        data.setServer(newServer);
        data.setServerSequence(getSequence() < 0 ? Long.MAX_VALUE : getSequence());
    }

    @Override
    public void process(@NotNull ProxySupport proxySupport) {
        ProxyPlayer target = proxySupport.getProxyPlayers().get(playerId);
        if (target == null) {
            unknownPlayer(playerId.toString(), "server switch");
            queue(proxySupport);
            return;
        }
        target.setStateSequence(getSequence() < 0 ? Long.MAX_VALUE : Math.max(target.getStateSequence(), getSequence()));
        target.setServer(newServer);
        target.setLastChangeMillis(System.currentTimeMillis()); // [6b6t patch] team audit grace
        TAB.getInstance().getFeatureManager().onServerSwitch(target);
    }
}
