package me.neznamy.tab.shared.features.proxy.message;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteArrayDataInput;
import lombok.ToString;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.features.proxy.ProxySupport;
import org.jetbrains.annotations.NotNull;

/**
 * Message sent by another server to request loading of all players connected to this server.
 */
@ToString
public class LoadRequest extends ProxyMessage {

    private long requestId = java.util.concurrent.ThreadLocalRandom.current().nextLong();
    public LoadRequest() { }
    public LoadRequest(ByteArrayDataInput in) {
        try { requestId = in.readLong(); } catch (IllegalStateException legacyEnd) { requestId = -1; }
    }
    public long getRequestId() { return requestId; }

    @Override
    public void write(@NotNull ByteArrayDataOutput out) {
        out.writeLong(requestId);
    }

    @Override
    public void process(@NotNull ProxySupport proxySupport) {
        // [6b6t patch 6b6t.4] in parts that fit into one message (see Load.split)
        long snapshot = proxySupport.snapshotSequence();
        for (Load load : Load.split(TAB.getInstance().getOnlinePlayers())) {
            load.setSnapshot(requestId, snapshot);
            proxySupport.sendMessage(load);
        }
        TAB.getInstance().getFeatureManager().onProxyLoadRequest();
    }
}
