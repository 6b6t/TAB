package me.neznamy.tab.shared.features.proxy.message;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import lombok.AllArgsConstructor;
import lombok.ToString;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.features.proxy.ProxyPlayer;
import me.neznamy.tab.shared.features.proxy.ProxySupport;
import me.neznamy.tab.shared.features.proxy.QueuedData;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Message sent from proxy to server to update vanish status of a player.
 */
@AllArgsConstructor
@ToString
public class UpdateVanishStatus extends ProxyMessage {

    @NotNull private final UUID playerId;
    private final boolean vanished;

    /**
     * Creates new instance and reads data from byte input.
     *
     * @param   in
     *          Input stream to read data from
     */
    public UpdateVanishStatus(@NotNull ByteArrayDataInput in) {
        playerId = readUUID(in);
        vanished = in.readBoolean();
    }

    @Override
    public void write(@NotNull ByteArrayDataOutput out) {
        writeUUID(out, playerId);
        out.writeBoolean(vanished);
    }


    /** [6b6t patch] Player this message is about */
    @Override
    @NotNull
    public UUID getSubjectId() {
        return playerId;
    }

    @Override
    public void queue(@NotNull ProxySupport proxySupport) {
        QueuedData data = proxySupport.queuedFor(playerId, getSourceProxy());
        if (getSequence() >= 0 && getSequence() < data.getVanishSequence()) return;
        data.setVanishSequence(getSequence() < 0 ? Long.MAX_VALUE : getSequence());
        data.setVanished(vanished);
        data.setVanishedSet(true);
    }

    @Override
    public void process(@NotNull ProxySupport proxySupport) {
        ProxyPlayer target = proxySupport.getProxyPlayers().get(playerId);
        if (target == null) {
            unknownPlayer(playerId.toString(), "vanish status update");
            queue(proxySupport);
            return;
        }
        target.setStateSequence(getSequence() < 0 ? Long.MAX_VALUE : Math.max(target.getStateSequence(), getSequence()));
        target.setLastChangeMillis(System.currentTimeMillis());
        target.setVanished(vanished);
        TAB.getInstance().getFeatureManager().onVanishStatusChange(target);
    }
}
