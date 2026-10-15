package me.neznamy.tab.shared.proxy;

import lombok.Getter;
import lombok.Setter;
import me.neznamy.tab.api.integration.VanishIntegration;
import me.neznamy.tab.api.placeholder.PlayerPlaceholder;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.chat.TabTextColor;
import me.neznamy.tab.shared.chat.component.TabTextComponent;
import me.neznamy.tab.shared.cpu.CpuManager;
import me.neznamy.tab.shared.patch6b6t.BridgeRequests;
import me.neznamy.tab.shared.patch6b6t.PatchStats;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.proxy.message.outgoing.OutgoingMessage;
import me.neznamy.tab.shared.proxy.message.outgoing.PermissionRequest;
import me.neznamy.tab.shared.proxy.message.outgoing.PlayerJoin;
import me.neznamy.tab.shared.task.PluginMessageEncodeTask;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract class for player on proxy containing variables and methods
 * shared between proxies.
 */
@Getter @Setter
public abstract class ProxyTabPlayer extends TabPlayer {

    /** Player's vanish status from backend server */
    public boolean vanished;

    /** Player's disguise status from backend server */
    private boolean disguised;

    /** Player's invisibility potion status from backend server */
    private boolean invisibilityPotion;

    /** Timestamp when join plugin message was sent to track how long bridge took to respond */
    private long bridgeRequestTime;

    /** Flag for marking if this player has received join response from bridge or not */
    private boolean bridgeConnected;

    /** Player's gamemode */
    private int gamemode;

    /** Map of player's requested permissions */
    private final Map<String, Boolean> permissions = new HashMap<>();

    /** Map of placeholders sent by the bridge ([6b6t patch 6b6t.8] concurrent: also read when formats are sent to the other proxy) */
    private final Map<String, String> placeholders = new ConcurrentHashMap<>();

    /**
     * [6b6t patch 6b6t.8] Relational values the bridge of this player's server sent for players of the other proxy:
     * identifier -> lower-case target name -> value. Local players keep theirs in {@code lastRelationalValues}.
     */
    private final Map<String, Map<String, String>> remoteRelational = new ConcurrentHashMap<>();

    /**
     * Constructs new instance with given parameters and sends a message
     * to bridge about this player joining with join data
     *
     * @param   platform
     *          Server platform
     * @param   player
     *          platform-specific player object
     * @param   uniqueId
     *          Player's unique ID
     * @param   name
     *          Player's name
     * @param   server
     *          Player's server
     * @param   protocolVersion
     *          Player's protocol network id
     */
    protected ProxyTabPlayer(@NotNull ProxyPlatform platform, @NotNull Object player, @NotNull UUID uniqueId,
                             @NotNull String name, @NotNull String server, int protocolVersion) {
        super(platform, player, uniqueId, name, server, "N/A", protocolVersion, TAB.getInstance().getConfiguration().getConfig().isOnlineUuidInTabList());
        // [6b6t patch 6b6t.8] After a reload keep the values of the old copy of this player (same server) and send the
        // request in the reload's staggered batches; otherwise ask the bridge right away as before.
        Map<String, String> carried = BridgeRequests.takeCarry(uniqueId, server, System.currentTimeMillis());
        BridgeRequests<ProxyTabPlayer> requests = BridgeRequests.get();
        if (carried != null && requests != null) {
            bridgeConnected = false;
            placeholders.clear();
            placeholders.putAll(carried);
            bridgeRequestTime = System.currentTimeMillis();
            requests.defer(uniqueId, this);
            PatchStats.bridgeCarried.incrementAndGet();
        } else {
            sendJoinPluginMessage();
        }
    }

    /**
     * Sends plugin message to backend server that this player has
     * joined, containing all plugin configuration data.
     */
    public void sendJoinPluginMessage() {
        bridgeConnected = false; // Reset on server switch
        placeholders.clear();
        sendBridgeRequest();
        // [6b6t patch 6b6t.8] ask again if this request gets no answer
        BridgeRequests<ProxyTabPlayer> requests = BridgeRequests.get();
        if (requests != null) requests.requestSent(getUniqueId(), this, server == null ? null : server.getName());
    }

    /**
     * [6b6t patch 6b6t.8] Sends the same request again (retry, {@code /btab bridgeresync}, reload batch) without wiping
     * the values this proxy already has, so nothing goes raw while the bridge answers.
     */
    public void resendJoinPluginMessage() {
        bridgeConnected = false;
        sendBridgeRequest();
    }

    private void sendBridgeRequest() {
        sendPluginMessage(new PlayerJoin(
                TAB.getInstance().getGroupManager().getPermissionPlugin().contains("Vault") &&
                    !TAB.getInstance().getConfiguration().getConfig().isGroupsByPermissions(),
                TAB.getInstance().getPlaceholderManager().getBridgePlaceholders(),
                TAB.getInstance().getConfiguration().getConfig().getReplacements().getValues()
        ));
        expansionData.resendAllValues();
        bridgeRequestTime = System.currentTimeMillis();
    }

    /**
     * [6b6t patch 6b6t.8] Production access for {@link BridgeRequests}.
     */
    public static final BridgeRequests.Port<ProxyTabPlayer> BRIDGE_PORT = new BridgeRequests.Port<ProxyTabPlayer>() {

        @Override
        public boolean isCurrent(@NotNull ProxyTabPlayer player) {
            return player.isOnline() && TAB.getInstance().getPlayer(player.getUniqueId()) == player;
        }

        @Override
        public String currentServer(@NotNull ProxyTabPlayer player) {
            return player.server == null ? null : player.server.getName();
        }

        @Override
        public boolean isAnswered(@NotNull ProxyTabPlayer player) {
            return player.isBridgeConnected();
        }

        @Override
        public void send(@NotNull ProxyTabPlayer player) {
            player.resendJoinPluginMessage();
        }

        @Override
        public void log(@NotNull String line) {
            TAB.getInstance().getPlatform().logInfo(new TabTextComponent(line, (TabTextColor) null));
        }
    };

    /**
     * Sets permission presence status to provided value
     *
     * @param   permission
     *          Requested permission node
     * @param   value
     *          Permission value
     */
    public void setHasPermission(@NotNull String permission, boolean value) {
        permissions.put(permission, value);
    }

    /**
     * Updates player's tracked gamemode for internal logic and placeholders.
     *
     * @param   gamemode
     *          New gamemode
     */
    public void setGamemode(int gamemode) {
        if (this.gamemode == gamemode) return; // Player join with player in survival mode
        this.gamemode = gamemode;
        ((PlayerPlaceholder) TAB.getInstance().getPlaceholderManager().getPlaceholder(TabConstants.Placeholder.GAMEMODE)).update(this);
        TAB.getInstance().getFeatureManager().onGameModeChange(this);
    }

    /**
     * Updates player's tracked invisibility potion value for internal logic and placeholders.
     *
     * @param   invisibilityPotion
     *          Whether player has invisibility potion or not
     */
    public void setInvisibilityPotion(boolean invisibilityPotion) {
        if (this.invisibilityPotion == invisibilityPotion) return; // Player join without invisibility potion
        this.invisibilityPotion = invisibilityPotion;
        ((PlayerPlaceholder) TAB.getInstance().getPlaceholderManager().getPlaceholder(TabConstants.Placeholder.INVISIBLE)).update(this);
    }

    /**
     * Performs platform-specific permission check and returns the result
     *
     * @param   permission
     *          Permission to check for
     * @return  Result from hasPermission call
     */
    public abstract boolean hasPermission0(String permission);

    /**
     * Sends plugin message to this player
     *
     * @param   message
     *          message to send
     */
    public abstract void sendPluginMessage(byte[] message);

    @Override
    public boolean hasInvisibilityPotion() {
        return invisibilityPotion;
    }

    @Override
    public boolean hasPermission(@NotNull String permission) {
        if (TAB.getInstance().getConfiguration().getConfig().isBukkitPermissions()) {
            sendPluginMessage(new PermissionRequest(permission));
            return permissions != null && permissions.getOrDefault(permission, false);
        }
        return hasPermission0(permission);
    }

    /**
     * Sends plugin message to the player.
     *
     * @param   message
     *          Plugin message to send
     */
    public void sendPluginMessage(@NotNull OutgoingMessage message) {
        CpuManager.getPluginMessageEncodeThread().execute(new PluginMessageEncodeTask(this, message));
    }

    @Override
    public boolean isVanished() {
        if (!VanishIntegration.getHandlers().isEmpty()) {
            for (VanishIntegration integration : VanishIntegration.getHandlers()) {
                if (integration.isVanished(this)) return true;
            }
            return false;
        }
        return vanished;
    }

    @NotNull
    public Map<String, Object> dump() {
        Map<String, Object> map = super.dump();
        map.put("bridge data", new LinkedHashMap<String, Object>() {{
            put("Vanished", vanished);
            put("Disguised", disguised);
            put("Has invisibility potion", invisibilityPotion);
            put("Gamemode", gamemode);
            put("Permissions", permissions);
            if (bridgeConnected) {
                put("Bridge connection", "Connected");
            } else {
                char versionRequired = TabConstants.PLUGIN_MESSAGE_CHANNEL_NAME.charAt(TabConstants.PLUGIN_MESSAGE_CHANNEL_NAME.length()-1);
                put("Bridge connection", "Not connected (requires Bridge version " + versionRequired + ".x.x installed)");
            }
            put("Placeholders", placeholders);
        }});
        return map;
    }
}