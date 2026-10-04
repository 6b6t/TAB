package me.neznamy.tab.shared.features.proxy;

import com.saicone.delivery4j.AbstractMessenger;
import com.saicone.delivery4j.Broker;
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
            messenger.subscribe(getChannelName()).consume((channel, lines) -> processMessage(lines[0])).cache(true);
            // [6b6t patch] Heartbeat on a SEPARATE channel: an unpatched TAB is not subscribed to it and never sees it
            // (an unknown action on the main channel would be logged as an error there). No cache, so our own
            // heartbeat comes back to us and proves our Redis link works.
            messenger.subscribe(getHeartbeatChannelName()).consume((channel, lines) -> {
                if (lines.length > 0 && lines[0] != null) onHeartbeat(lines[0]);
            });
            // [6b6t patch 6b6t.3] Bot flags of each proxy's players, also on a separate channel (see RemoteBots)
            messenger.subscribe(getBotChannelName()).consume((channel, lines) -> {
                if (lines.length > 0 && lines[0] != null) onBotChannelMessage(lines[0]);
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
            messenger.send(getHeartbeatChannelName(), getProxy().toString());
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
