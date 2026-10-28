package me.neznamy.tab.shared.features.proxy;

import com.saicone.delivery4j.AbstractMessenger;
import com.saicone.delivery4j.Broker;
import com.saicone.delivery4j.MessageChannel;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.chat.TabTextColor;
import me.neznamy.tab.shared.chat.component.TabTextComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public class ProxyMessengerSupport extends ProxySupport {

    @NotNull
    private final String messengerName;

    @NotNull
    private final Supplier<Broker> brokerSupplier;

    @Nullable
    private AbstractMessenger messenger;

    /**
     * Creates new instance with given parameters.
     *
     * @param   messengerName
     *          Messenger name
     * @param   channelName
     *          Name of the messaging channel
     * @param   brokerSupplier
     *          Supplier returning broker instance
     */
    public ProxyMessengerSupport(@NotNull String messengerName, @NotNull String channelName, @NotNull Supplier<Broker> brokerSupplier) {
        super(channelName);
        this.messengerName = messengerName;
        this.brokerSupplier = brokerSupplier;
    }

    /**
     * [6b6t patch 6b6t.4] Message id cache of the main channel that never drops anything. delivery4j's
     * {@code cache(true)} gives every sent message a random id in [0, 1,000,000), remembers it for 10 s and DROPS
     * every received message with a remembered id, also messages of the other proxy: with thousands of messages in
     * 10 s (Fox restart, mass reconnect) some joins / switches / formats were lost silently. Our own messages are
     * already ignored by the proxy id inside the message. The id stays in the wire format (6b6t.3 reads it) and is
     * negative, so a 6b6t.3 receiver (which remembers only its own ids, all >= 0) never drops our messages either.
     */
    static final class WireCache extends MessageChannel.Cache {
        @Override protected void save(int id) { }
        @Override public boolean contains(int id) { return false; }
        @Override public int generate() { return -1; }
        @Override public void clear() { }
    }

    @Override
    public void sendMessage(@NotNull String message) {
        if (messenger == null || !messenger.isEnabled()) return;
        messenger.send(getChannelName(), message);
    }

    @Override
    public void register() {
        try {
            Broker broker = brokerSupplier.get();
            messenger = new AbstractMessenger() {

                @Override
                @NotNull
                protected Broker loadBroker() {
                    return broker;
                }
            };
            messenger.subscribe(getChannelName()).consume((channel, lines) -> processMessage(lines[0])).cache(new WireCache());
            // [6b6t patch] Heartbeat on a SEPARATE channel: an unpatched TAB is not subscribed to it and never sees it
            // (an unknown action on the main channel would be logged as an error there). No cache, so our own
            // heartbeat comes back to us and proves our Redis link works.
            messenger.subscribe(getHeartbeatChannelName()).consume((channel, lines) -> {
                // [6b6t patch 6b6t.4] second line = digest of the sender's players (6b6t.1-.3 send one line)
                if (lines.length > 0 && lines[0] != null) onHeartbeat(lines[0], lines.length > 1 ? lines[1] : null);
            });
            // [6b6t patch 6b6t.3] Bot flags of each proxy's players, also on a separate channel (see RemoteBots)
            messenger.subscribe(getBotChannelName()).consume((channel, lines) -> {
                // Never let a bot-channel problem (e.g. a message arriving during reload/disable) escape into the
                // messenger thread that also carries the main sync channel.
                try {
                    if (lines.length > 0 && lines[0] != null) onBotChannelMessage(lines[0]);
                } catch (Exception ignored) {
                    // dropped; the next full bot list (every 30 s) restores the state
                }
            });
            messenger.start();
            TAB.getInstance().getPlatform().logInfo(new TabTextComponent("Successfully connected to " + messengerName, TabTextColor.GREEN));
        } catch (Exception e) {
            TAB.getInstance().getErrorManager().criticalError("Failed to connect to " + messengerName + ": " + e.getClass().getName() + ": " + e.getMessage(), null);
        }
    }

    /**
     * [6b6t patch] Name of the heartbeat channel.
     *
     * @return  heartbeat channel name
     */
    @NotNull
    public String getHeartbeatChannelName() {
        return getChannelName() + "-6b6t";
    }

    /**
     * [6b6t patch 6b6t.3] Name of the bot channel.
     *
     * @return  bot channel name
     */
    @NotNull
    public String getBotChannelName() {
        return getChannelName() + "-6b6t-bots";
    }

    @Override
    public void sendBotChannelMessage(@NotNull String line) {
        if (messenger == null || !messenger.isEnabled()) return;
        try {
            messenger.send(getBotChannelName(), line);
        } catch (Exception e) {
            // The periodic full list repairs anything lost
            TAB.getInstance().debug("[TAB-6b6t] Failed to send bot flags: " + e);
        }
    }

    @Override
    protected void sendHeartbeat() {
        if (messenger == null || !messenger.isEnabled()) return;
        try {
            messenger.send(getHeartbeatChannelName(), getProxy().toString(), localDigest());
        } catch (Exception e) {
            // Ghost removal is skipped while our own heartbeat does not come back, nothing else to do
            TAB.getInstance().debug("[TAB-6b6t] Failed to send heartbeat: " + e);
        }
    }

    @Override
    public void unregister() {
        if (messenger == null) return;
        messenger.close();
        messenger.clear();
    }
}
