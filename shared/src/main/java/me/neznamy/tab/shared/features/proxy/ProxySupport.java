package me.neznamy.tab.shared.features.proxy;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import lombok.Getter;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants.CpuUsageCategory;
import me.neznamy.tab.shared.chat.TabTextColor;
import me.neznamy.tab.shared.chat.component.TabTextComponent;
import me.neznamy.tab.shared.cpu.TimedCaughtTask;
import me.neznamy.tab.shared.data.Server;
import me.neznamy.tab.shared.features.proxy.message.*;
import me.neznamy.tab.shared.features.types.*;
import me.neznamy.tab.shared.patch6b6t.PatchSettings;
import me.neznamy.tab.shared.patch6b6t.PatchStats;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Feature synchronizing player display data between
 * multiple servers connected with a proxy messenger.
 */
@SuppressWarnings("UnstableApiUsage")
@Getter
public abstract class ProxySupport extends TabFeature implements JoinListener, QuitListener,
        Loadable, UnLoadable, ServerSwitchListener,
        VanishListener {

    /** Name of the messaging channel */
    @NotNull
    private final String channelName;

    /** Proxy players on other proxies by their UUID */
    @NotNull protected final Map<UUID, ProxyPlayer> proxyPlayers = new ConcurrentHashMap<>();

    /** Queued data of players on other proxies by their UUID */
    @NotNull private final Map<UUID, QueuedData> queuedData = new ConcurrentHashMap<>();

    /** UUID of this proxy to ignore messages coming from the same proxy */
    @NotNull private final UUID proxy = UUID.randomUUID();

    @NotNull private final Map<String, Function<ByteArrayDataInput, ProxyMessage>> stringToClass = new HashMap<>();
    @NotNull private final Map<Class<? extends ProxyMessage>, String> classToString = new HashMap<>();

    /** ID generator for messages requiring an ID */
    private final AtomicLong idCounter = new AtomicLong(0);

    // ---------------- [6b6t patch] ----------------

    /** Interval of heartbeats on the extra channel (patched proxies only) */
    private static final int HEARTBEAT_INTERVAL_MS = 30_000;

    /** An origin that sent heartbeats and was silent this long is considered dead */
    private static final long GHOST_SILENCE_MS = 180_000;

    /** Our own heartbeat must have come back over Redis within this time, or ghost removal is skipped */
    private static final long OWN_ECHO_MAX_AGE_MS = 90_000;

    /** Decides which incoming messages belong to an old session */
    @NotNull private final StaleMessageGuard guard = new StaleMessageGuard();

    /** Last time any message (or heartbeat) was received from each origin proxy */
    @NotNull private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    /** Origins that sent at least one heartbeat (= run the patched TAB) */
    @NotNull private final Set<String> heartbeatOrigins = ConcurrentHashMap.newKeySet();

    /** [6b6t patch r1] Origins whose copies were removed as ghosts; when they speak again, request a full Load */
    @NotNull private final Set<String> gcOrigins = ConcurrentHashMap.newKeySet();

    /** Last time our own heartbeat came back over the messenger (proves our link works) */
    private volatile long lastOwnHeartbeatEcho;

    /**
     * TEST ONLY: -Dtab6b6t.testDelayProxyMs=N delays every incoming proxy message by N ms (keeps their order),
     * to reproduce the cross-proxy race deterministically. 0 (default, production) = no delay.
     */
    private static final int TEST_DELAY_MS = Integer.getInteger("tab6b6t.testDelayProxyMs", 0);

    protected ProxySupport(@NotNull String channelName) {
        this.channelName = channelName;
        registerMessage(Load.class, Load::new);
        registerMessage(LoadRequest.class, in -> new LoadRequest());
        registerMessage(PlayerJoin.class, PlayerJoin::new);
        registerMessage(PlayerQuit.class, PlayerQuit::new);
        registerMessage(ServerSwitch.class, ServerSwitch::new);
        registerMessage(UpdateVanishStatus.class, UpdateVanishStatus::new);
        TAB.getInstance().debug("[Proxy Support] Using channel name: " + channelName);
    }

    @NotNull
    @Override
    public String getFeatureName() {
        return "ProxySupport";
    }

    /**
     * Processes incoming proxy message
     *
     * @param   msg
     *          json message to process
     */
    public synchronized void processMessage(@NotNull String msg) {
        ByteArrayDataInput in = ByteStreams.newDataInput(Base64.getDecoder().decode(msg));
        String proxy = in.readUTF();
        if (proxy.equals(this.proxy.toString())) return; // Message coming from current proxy
        String action = in.readUTF();
        Function<ByteArrayDataInput, ProxyMessage> function = stringToClass.get(action);
        if (function == null) {
            TAB.getInstance().getErrorManager().unknownProxyMessage(action);
            return;
        }
        ProxyMessage proxyMessage;
        try {
            proxyMessage = function.apply(in);
            TAB.getInstance().debug("[Proxy Support] Decoded message " + proxyMessage);
        } catch (Exception e) {
            TAB.getInstance().getErrorManager().printError("Failed to decode proxy message \"" + new String(Base64.getDecoder().decode(msg)) + "\" ", e);
            return;
        }

        if (TEST_DELAY_MS > 0) {
            try {
                Thread.sleep(TEST_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // [6b6t patch] remember the sender (header), not part of the message format
        proxyMessage.setSourceProxy(proxy);
        lastSeen.put(proxy, System.currentTimeMillis());
        resyncIfGhosted(proxy);

        // Queue the task to make sure it does not execute before plugin fully loads, causing NPE
        TAB.getInstance().getCpu().runMeasuredTask(getFeatureName(), CpuUsageCategory.PROXY_MESSAGE, () -> {
            // [6b6t patch] drop / queue messages of a session this proxy no longer shows (runs on the Processing Thread,
            // in the same order as local joins and quits, before forwarding to a feature thread)
            if (!acceptMessage(proxyMessage)) return;
            if (proxyMessage.getCustomThread() != null) {
                proxyMessage.getCustomThread().execute(new TimedCaughtTask(TAB.getInstance().getCpu(), () -> proxyMessage.process(this), getFeatureName(), CpuUsageCategory.PROXY_MESSAGE));
            } else {
                proxyMessage.process(this);
            }
        });
    }

    /**
     * Sends message to all proxies
     *
     * @param   message
     *          message to send
     */
    public abstract void sendMessage(@NotNull String message);

    /**
     * Registers event and proxy message listeners
     */
    public abstract void register();

    /**
     * Unregisters event and proxy message listeners
     */
    public abstract void unregister();

    @Override
    public void load() {
        register();
        for (TabPlayer p : TAB.getInstance().getOnlinePlayers()) onJoin(p);
        sendMessage(new LoadRequest());
        sendHeartbeat(); // [6b6t patch] announce at once, so others know this proxy runs the patch
        // [6b6t patch] tombstone expiry + heartbeat + ghost removal, all on the Processing Thread
        TAB.getInstance().getCpu().getProcessingThread().repeatTask(new TimedCaughtTask(TAB.getInstance().getCpu(), () -> {
            long now = System.currentTimeMillis();
            long ttl = PatchSettings.get().tombstoneTtlMillis;
            guard.expire(now, ttl);
            // [6b6t patch r1] queued data whose join never came (stock origin, retired copy, dead origin)
            queuedData.values().removeIf(q -> now - q.getCreatedAt() > Math.max(ttl, 60_000L));
            sendHeartbeat();
            removeGhosts();
        }, getFeatureName(), "6b6t maintenance"), HEARTBEAT_INTERVAL_MS);
    }

    @Override
    public void unload() {
        for (TabPlayer p : TAB.getInstance().getOnlinePlayers()) onQuit(p);
        unregister();
    }

    @Override
    public void onJoin(@NotNull TabPlayer p) {
        sendMessage(new PlayerJoin(p));
    }

    @Override
    public void onServerChange(@NotNull TabPlayer p, @NotNull Server from, @NotNull Server to) {
        sendMessage(new ServerSwitch(p.getUniqueId(), to));
    }

    @Override
    public void onQuit(@NotNull TabPlayer p) {
        sendMessage(new PlayerQuit(p.getUniqueId()));
    }

    /**
     * Sends message to other proxies.
     *
     * @param   message
     *          Message to send
     */
    public void sendMessage(@NotNull ProxyMessage message) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(proxy.toString());
        out.writeUTF(classToString.get(message.getClass()));
        TAB.getInstance().debug("[Proxy Support] Encoding message " + message);
        message.write(out);
        sendMessage(Base64.getEncoder().encodeToString(out.toByteArray()));
    }

    /**
     * Registers proxy message.
     *
     * @param   clazz
     *          Message class
     * @param   function
     *          Message function
     */
    public void registerMessage(@NotNull Class<? extends ProxyMessage> clazz, @NotNull Function<ByteArrayDataInput, ProxyMessage> function) {
        stringToClass.put(clazz.getSimpleName(), function);
        classToString.put(clazz, clazz.getSimpleName());
    }

    @Override
    public void onVanishStatusChange(@NotNull TabPlayer player) {
        sendMessage(new UpdateVanishStatus(player.getUniqueId(), player.isVanished()));
    }

    // ---------------- [6b6t patch] ----------------

    /**
     * [6b6t patch] Runs a decoded message through {@link StaleMessageGuard}. Must run on the Processing Thread.
     * Also used for every player inside a {@link Load} message.
     *
     * @param   message
     *          decoded message with its source proxy set
     * @return  {@code true} if the message should be processed, {@code false} if it was dropped or only queued
     */
    public boolean acceptMessage(@NotNull ProxyMessage message) {
        PatchSettings settings = PatchSettings.get();
        if (!settings.staleGuard) return true;
        UUID id = message.getSubjectId();
        if (id == null) return true;
        ProxyPlayer copy = proxyPlayers.get(id);
        StaleMessageGuard.Verdict verdict = guard.check(message.getGuardKind(), id, message.getSourceProxy(),
                copy == null ? null : copy.getSourceProxy(), System.currentTimeMillis(), settings.tombstoneTtlMillis);
        switch (verdict) {
            case ACCEPT:
                return true;
            case ACCEPT_REPLACE_ORIGIN:
                // The player joined another proxy (instance) than the one our copy came from, e.g. the old proxy
                // was killed before it sent its quits. Remove the old copy, then let the join create the new one.
                TAB.getInstance().getFeatureManager().onQuit(copy);
                proxyPlayers.remove(id, copy);
                PatchStats.originReplaced.incrementAndGet();
                TAB.getInstance().debug("[TAB-6b6t] Replaced remote copy of " + copy.getName() + " from " + shortId(copy.getSourceProxy())
                        + " with a join from " + shortId(message.getSourceProxy()));
                return true;
            case QUEUE:
                PatchStats.staleDropped.incrementAndGet();
                TAB.getInstance().debug("[TAB-6b6t] Not applying " + message.getClass().getSimpleName() + " for " + id
                        + " from " + shortId(message.getSourceProxy()) + " (not the current session), queued only");
                message.queue(this);
                return false;
            default:
                PatchStats.staleDropped.incrementAndGet();
                TAB.getInstance().debug("[TAB-6b6t] Dropped " + message.getClass().getSimpleName() + " for " + id
                        + " from " + shortId(message.getSourceProxy()) + " (not the current session)");
                QueuedData queued = queuedData.get(id);
                if (queued != null && Objects.equals(queued.getSourceProxy(), message.getSourceProxy())) {
                    queuedData.remove(id, queued);
                }
                return false;
        }
    }

    /**
     * [6b6t patch] Called when a player joins this proxy, before any feature processes the join.
     * Removes a remote copy of the same player (stale: the player left the other proxy, but its quit
     * was not processed yet) and remembers its origin, so late messages of that old session are ignored.
     * Without this, the copy's late update / quit unregistered the team the local player uses
     * (white, teamless name until rejoin). Must run on the Processing Thread.
     *
     * @param   player
     *          player who joined this proxy
     */
    public void retireRemoteCopy(@NotNull TabPlayer player) {
        if (!PatchSettings.get().staleGuard) return;
        UUID id = player.getUniqueId();
        ProxyPlayer copy = proxyPlayers.remove(id);
        if (copy == null) return;
        if (copy.getSourceProxy() != null) {
            guard.addTombstone(id, copy.getSourceProxy(), System.currentTimeMillis());
        }
        // Sets the copy DISCONNECTED (a pending delayed join of it aborts) and queues the feature quits
        // (NameTag: unregister the copy's team) ahead of the local player's join tasks on the same threads.
        TAB.getInstance().getFeatureManager().onQuit(copy);
        queuedData.remove(id);
        PatchStats.copiesRetired.incrementAndGet();
        TAB.getInstance().debug("[TAB-6b6t] Retired remote copy of " + copy.getName() + " from " + shortId(copy.getSourceProxy()));
    }

    /**
     * [6b6t patch] Returns queued data of a player for given sending proxy. Data queued by another
     * proxy is discarded, so a later join only receives data of its own proxy.
     *
     * @param   id
     *          player UUID
     * @param   source
     *          sending proxy
     * @return  queued data to fill
     */
    @NotNull
    public QueuedData queuedFor(@NotNull UUID id, @Nullable String source) {
        // [6b6t patch r1] atomic: called from the Processing Thread and the NameTag thread at the same time
        return queuedData.compute(id, (k, data) -> {
            if (data != null && (source == null || Objects.equals(source, data.getSourceProxy()))) return data;
            QueuedData fresh = new QueuedData();
            fresh.setSourceProxy(source);
            return fresh;
        });
    }

    /**
     * [6b6t patch r1] When an origin whose copies were removed as ghosts speaks again (heartbeat or message),
     * ask it for a full Load once, so its players come back without a rejoin. Any thread.
     *
     * @param   origin
     *          id of the proxy that sent something
     */
    private void resyncIfGhosted(@NotNull String origin) {
        if (!gcOrigins.remove(origin)) return;
        TAB.getInstance().getPlatform().logInfo(new TabTextComponent("[TAB-6b6t] Proxy " + shortId(origin)
                + " is back after its players were removed as ghosts, requesting its players again", (TabTextColor) null));
        TAB.getInstance().getCpu().runMeasuredTask(getFeatureName(), CpuUsageCategory.PROXY_MESSAGE, () -> sendMessage(new LoadRequest()));
    }

    /**
     * [6b6t patch] Sends a heartbeat on the extra channel. Default: not supported by this messenger.
     */
    protected void sendHeartbeat() {
        // Only ProxyMessengerSupport (Redis / RabbitMQ) supports it
    }

    /**
     * [6b6t patch] Processes a heartbeat received on the extra channel (any thread).
     *
     * @param   origin
     *          id of the proxy that sent it
     */
    public void onHeartbeat(@NotNull String origin) {
        long now = System.currentTimeMillis();
        if (origin.equals(proxy.toString())) {
            if (lastOwnHeartbeatEcho == 0) {
                TAB.getInstance().getPlatform().logInfo(new TabTextComponent("[TAB-6b6t] Heartbeat channel works (own heartbeat received back)", (TabTextColor) null));
            }
            lastOwnHeartbeatEcho = now;
            return;
        }
        if (heartbeatOrigins.add(origin)) {
            TAB.getInstance().getPlatform().logInfo(new TabTextComponent("[TAB-6b6t] Heartbeat from proxy " + shortId(origin) + " (runs the patched TAB)", (TabTextColor) null));
        }
        lastSeen.put(origin, now);
        resyncIfGhosted(origin);
    }

    /**
     * [6b6t patch] Removes copies of origin proxies that ran the patched TAB (sent heartbeats) and then went
     * silent, e.g. a proxy killed before it sent all quits. Never touches unpatched origins, and does nothing
     * unless our own heartbeat came back recently (so a broken Redis link on our side removes nothing).
     * Runs on the Processing Thread.
     */
    private void removeGhosts() {
        if (!PatchSettings.get().ghostGc) return;
        long now = System.currentTimeMillis();
        if (now - lastOwnHeartbeatEcho > OWN_ECHO_MAX_AGE_MS) return;
        Map<String, Integer> removed = new HashMap<>();
        for (ProxyPlayer copy : proxyPlayers.values()) {
            String origin = copy.getSourceProxy();
            if (origin == null || !heartbeatOrigins.contains(origin)) continue;
            Long seen = lastSeen.get(origin);
            if (seen != null && now - seen <= GHOST_SILENCE_MS) continue;
            TAB.getInstance().getFeatureManager().onQuit(copy);
            proxyPlayers.remove(copy.getUniqueId(), copy);
            PatchStats.ghostsRemoved.incrementAndGet();
            removed.merge(origin, 1, Integer::sum);
            gcOrigins.add(origin);
        }
        queuedData.values().removeIf(q -> q.getSourceProxy() != null && heartbeatOrigins.contains(q.getSourceProxy())
                && now - lastSeen.getOrDefault(q.getSourceProxy(), 0L) > GHOST_SILENCE_MS);
        for (Map.Entry<String, Integer> e : removed.entrySet()) {
            TAB.getInstance().getPlatform().logInfo(new TabTextComponent(
                    "[TAB-6b6t] Removed " + e.getValue() + " ghost players of proxy " + shortId(e.getKey()) + " (no heartbeat for "
                            + (now - lastSeen.getOrDefault(e.getKey(), 0L)) / 1000 + " s)", (TabTextColor) null));
        }
    }

    /**
     * [6b6t patch] First 8 characters of a proxy id for log lines.
     *
     * @param   id
     *          proxy id
     * @return  shortened id
     */
    @NotNull
    public static String shortId(@Nullable String id) {
        if (id == null) return "unknown";
        return id.length() > 8 ? id.substring(0, 8) : id;
    }
}
