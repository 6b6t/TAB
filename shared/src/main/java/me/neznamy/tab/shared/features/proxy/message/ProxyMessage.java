package me.neznamy.tab.shared.features.proxy.message;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.cpu.ThreadExecutor;
import me.neznamy.tab.shared.features.proxy.ProxySupport;
import me.neznamy.tab.shared.features.proxy.StaleMessageGuard;
import me.neznamy.tab.shared.platform.TabList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public abstract class ProxyMessage {

    /** Optional trailing origin sequence; .3 decoders ignore it and .3 senders omit it. */
    private long sequence = -1;
    public long getSequence() { return sequence; }
    public void setSequence(long value) { sequence = value; }
    public void readSequence(ByteArrayDataInput in) {
        try { sequence = in.readLong(); } catch (IllegalStateException legacyEnd) { sequence = -1; }
    }

    /**
     * [6b6t patch] Id of the proxy that sent this message, taken from the message header.
     * Not part of the wire format (set by the receiver), so the format stays compatible with unpatched TAB.
     */
    @Nullable
    private String sourceProxy;

    /**
     * [6b6t patch] Returns id of the proxy that sent this message, {@code null} for outgoing messages.
     *
     * @return  id of the sending proxy
     */
    @Nullable
    public String getSourceProxy() {
        return sourceProxy;
    }

    /**
     * [6b6t patch] Sets id of the proxy that sent this message.
     *
     * @param   sourceProxy
     *          id of the sending proxy
     */
    public void setSourceProxy(@Nullable String sourceProxy) {
        this.sourceProxy = sourceProxy;
    }

    /**
     * [6b6t patch] Returns UUID of the player this message is about, {@code null} if it is not about one player.
     *
     * @return  UUID of the player this message is about
     */
    @Nullable
    public UUID getSubjectId() {
        return null;
    }

    /**
     * [6b6t patch] Returns kind of this message for {@link StaleMessageGuard}.
     *
     * @return  kind of this message
     */
    @NotNull
    public StaleMessageGuard.Kind getGuardKind() {
        return StaleMessageGuard.Kind.OTHER;
    }

    /**
     * [6b6t patch] Stores this message only as queued data of its sending proxy, without applying
     * it to the current remote copy (see {@link StaleMessageGuard.Verdict#QUEUE}).
     * Default: nothing to queue, the message is dropped.
     *
     * @param   proxySupport
     *          Proxy support feature
     */
    public void queue(@NotNull ProxySupport proxySupport) {
        // Nothing to queue by default
    }

    @Nullable
    public ThreadExecutor getCustomThread() {
        return null;
    }

    public void writeUUID(@NotNull ByteArrayDataOutput out, @NotNull UUID id) {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    public UUID readUUID(@NotNull ByteArrayDataInput in) {
        return new UUID(in.readLong(), in.readLong());
    }

    public void writeSkin(@NotNull ByteArrayDataOutput out, @Nullable TabList.Skin skin) {
        out.writeBoolean(skin != null);
        if (skin != null) {
            out.writeUTF(skin.getValue());
            out.writeBoolean(skin.getSignature() != null);
            if (skin.getSignature() != null) {
                out.writeUTF(skin.getSignature());
            }
        }
    }

    @Nullable
    public TabList.Skin readSkin(@NotNull ByteArrayDataInput in) {
        if (!in.readBoolean()) return null;
        String value = in.readUTF();
        String signature = null;
        if (in.readBoolean()) {
            signature = in.readUTF();
        }
        return new TabList.Skin(value, signature);
    }

    public abstract void write(@NotNull ByteArrayDataOutput out);

    public abstract void process(@NotNull ProxySupport proxySupport);

    public void unknownPlayer(@NotNull String playerId, @NotNull String action) {
        TAB.getInstance().debug("[Proxy Support] Unable to process " + action + " of proxy player " + playerId + ", because no such player exists. Queueing data.");
    }
}
