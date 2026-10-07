package me.neznamy.tab.shared.features.proxy.message;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import lombok.ToString;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.data.Server;
import me.neznamy.tab.shared.features.globalplayerlist.GlobalPlayerList;
import me.neznamy.tab.shared.features.proxy.ProxyPlayer;
import me.neznamy.tab.shared.features.proxy.ProxySupport;
import me.neznamy.tab.shared.features.proxy.QueuedData;
import me.neznamy.tab.shared.features.proxy.StaleMessageGuard;
import me.neznamy.tab.shared.patch6b6t.PatchStats;
import me.neznamy.tab.shared.platform.TabList;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Message sent by another proxy when a player joins.
 */
@ToString
public class PlayerJoin extends ProxyMessage {

    @NotNull private final UUID uniqueId;
    @NotNull private final UUID tablistId;
    @NotNull private final String name;
    @NotNull private final Server server;
    private final boolean vanished;
    private final boolean staff;
    @Nullable private final TabList.Skin skin;

    /**
     * Creates new instance from given player data.
     *
     * @param   encodedPlayer
     *          Player data to encode
     */
    public PlayerJoin(@NotNull TabPlayer encodedPlayer) {
        uniqueId = encodedPlayer.getUniqueId();
        tablistId = encodedPlayer.getTablistId();
        name = encodedPlayer.getName();
        server = encodedPlayer.server;
        vanished = encodedPlayer.isVanished();
        staff = encodedPlayer.hasPermission(TabConstants.Permission.STAFF);
        skin = encodedPlayer.getTabList().getSkin();
    }

    /**
     * Creates new instance and reads data from byte input.
     *
     * @param   in
     *          Input stream to read from
     */
    public PlayerJoin(@NotNull ByteArrayDataInput in) {
        uniqueId = readUUID(in);
        tablistId = readUUID(in);
        name = in.readUTF();
        server = Server.byName(in.readUTF());
        vanished = in.readBoolean();
        staff = in.readBoolean();
        skin = readSkin(in);
    }

    @Override
    public void write(@NotNull ByteArrayDataOutput out) {
        writeUUID(out, uniqueId);
        writeUUID(out, tablistId);
        out.writeUTF(name);
        out.writeUTF(server.getName());
        out.writeBoolean(vanished);
        out.writeBoolean(staff);
        writeSkin(out, skin);
    }


    /** [6b6t patch] Player this message is about */
    @Override
    @NotNull
    public UUID getSubjectId() {
        return uniqueId;
    }

    /** [6b6t patch] Kind for the stale message guard */
    @Override
    @NotNull
    public StaleMessageGuard.Kind getGuardKind() {
        return StaleMessageGuard.Kind.JOIN;
    }

    @Override
    public void process(@NotNull ProxySupport proxySupport) {
        ProxyPlayer decodedPlayer = new ProxyPlayer(uniqueId, tablistId, name, server, vanished, staff, skin);
        decodedPlayer.setStateSequence(getSequence());
        decodedPlayer.setSourceProxy(getSourceProxy()); // [6b6t patch] remember which proxy owns this copy
        ProxyPlayer existing = proxySupport.getProxyPlayers().get(uniqueId);
        if (existing != null) {
            // [6b6t patch 6b6t.4] A join of a copy we already have comes from a Load (answer to "send me your players").
            // Only a sequenced snapshot at least as new as the last state update can refresh it.
            // Legacy Loads have no ordering barrier and retain .3's add-only behavior.
            if (Objects.equals(existing.getSourceProxy(), getSourceProxy())
                    && getSequence() >= 0 && getSequence() >= existing.getStateSequence()) {
                refresh(existing, proxySupport);
                existing.setStateSequence(getSequence());
            }
            TAB.getInstance().debug("[Proxy Support] The proxy player " + decodedPlayer.getName() + " is already connected, cannot process join.");
            return;
        }
        proxySupport.getProxyPlayers().put(decodedPlayer.getUniqueId(), decodedPlayer);
        QueuedData data = proxySupport.getQueuedData().remove(decodedPlayer.getUniqueId());
        // [6b6t patch] only take data queued by the same proxy (data of another proxy belongs to another session)
        if (data != null && (data.getSourceProxy() == null || data.getSourceProxy().equals(getSourceProxy()))) {
            decodedPlayer.setStateSequence(Math.max(getSequence(), Math.max(data.getServerSequence(), data.getVanishSequence())));
            if ((getSequence() < 0 || data.getServerSequence() >= getSequence()) && data.getServer() != null) decodedPlayer.setServer(data.getServer()); // [6b6t patch 6b6t.4] switch that overtook the join
            decodedPlayer.setBelowname(data.getBelowname());
            decodedPlayer.setTabFormat(data.getTabFormat());
            decodedPlayer.setNametag(data.getNametag());
            decodedPlayer.setPlayerlist(data.getPlayerlist());
            // [6b6t patch] upstream applied the default "false" even when no vanish update was queued
            if ((getSequence() < 0 || data.getVanishSequence() >= getSequence()) && data.isVanishedSet()) decodedPlayer.setVanished(data.isVanished());
        }
        if (TAB.getInstance().getPlayer(decodedPlayer.getUniqueId()) == null) {
            TAB.getInstance().getFeatureManager().onJoin(decodedPlayer);
        }
    }

    /**
     * [6b6t patch 6b6t.4] Applies the server and vanish state of this join to an existing copy of the same proxy.
     * Runs on the Processing Thread (Load has no custom thread).
     *
     * @param   copy
     *          existing copy of this player from the same proxy
     * @param   proxySupport
     *          proxy support feature
     */
    private void refresh(@NotNull ProxyPlayer copy, @NotNull ProxySupport proxySupport) {
        if (copy.server != server) {
            new ServerSwitch(uniqueId, server).process(proxySupport);
            GlobalPlayerList global = TAB.getInstance().getFeatureManager().getFeature(TabConstants.Feature.GLOBAL_PLAYER_LIST);
            if (global != null) global.restoreSameServerEntries(copy);
            PatchStats.auditRepaired.incrementAndGet();
        }
        if (copy.isVanished() != vanished) {
            copy.setVanished(vanished);
            TAB.getInstance().getFeatureManager().onVanishStatusChange(copy);
            PatchStats.auditRepaired.incrementAndGet();
        }
    }
}
