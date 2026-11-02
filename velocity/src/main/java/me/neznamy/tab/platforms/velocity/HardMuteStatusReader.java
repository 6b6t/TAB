package me.neznamy.tab.platforms.velocity;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.velocitypowered.api.scheduler.ScheduledTask;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.features.globalplayerlist.GlobalPlayerList;
import me.neznamy.tab.shared.features.globalplayerlist.HardMuteStatus;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Reads hard-mute state from AsyncChat's authoritative table using read-only credentials. */
final class HardMuteStatusReader implements AutoCloseable {

    private static final long REFRESH_SECONDS = 1;
    private final VelocityTAB plugin;
    private final Logger logger;
    private HikariDataSource dataSource;
    private ScheduledTask task;
    private boolean warningLogged;
    private volatile Set<UUID> previous = Collections.emptySet();

    HardMuteStatusReader(VelocityTAB plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    void start() {
        String host = env("HOST");
        String database = env("DATABASE");
        String username = env("USERNAME");
        String password = env("PASSWORD");
        if (host == null || database == null || username == null || password == null) {
            warnOnce("AsyncChat database settings are unavailable; TAB hard-mute filtering is disabled.");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(System.getenv().getOrDefault("CONFIG_ASYNCCHAT_DATABASE_PORT", "3306"));
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            warnOnce("AsyncChat database port is invalid; TAB hard-mute filtering is disabled.");
            return;
        }
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            HikariConfig config = new HikariConfig();
            config.setPoolName("TAB-HardMute-Pool");
            config.setJdbcUrl(
                    "jdbc:mysql://" + host + ":" + port + "/" + database + "?connectTimeout=2000&socketTimeout=2000");
            config.setUsername(username);
            config.setPassword(password);
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(0);
            config.setReadOnly(true);
            config.setConnectionTimeout(2_000);
            config.setInitializationFailTimeout(-1);
            dataSource = new HikariDataSource(config);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("AsyncChat database settings could not initialize; TAB hard-mute filtering is disabled.");
            return;
        }
        refresh();
        task = plugin.getServer().getScheduler().buildTask(plugin, this::refresh)
                .delay(REFRESH_SECONDS, TimeUnit.SECONDS)
                .repeat(REFRESH_SECONDS, TimeUnit.SECONDS)
                .schedule();
    }

    private static String env(String suffix) {
        String value = System.getenv("CONFIG_ASYNCCHAT_DATABASE_" + suffix);
        return value == null || value.isBlank() ? null : value;
    }

    private synchronized void refresh() {
        HikariDataSource source = dataSource;
        if (source == null || source.isClosed()) return;
        Set<UUID> current = new HashSet<>();
        try (var connection = source.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT player_uuid FROM asyncchat_uuid_mutes WHERE hard_mute = TRUE")) {
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    try {
                        current.add(UUID.fromString(rows.getString(1)));
                    } catch (IllegalArgumentException malformedUuid) {
                        logger.warn("Ignoring malformed UUID in AsyncChat hard-mute table.");
                    }
                }
            }
            warningLogged = false;
        } catch (SQLException exception) {
            warnOnce("AsyncChat mute status could not be read; TAB hard-mute filtering is temporarily fail-open.");
            current.clear();
        }
        Set<UUID> changed = new HashSet<>(previous);
        changed.addAll(current);
        changed.removeIf(id -> previous.contains(id) == current.contains(id));
        previous = new HashSet<>(current);
        for (UUID id : changed) HardMuteStatus.setMuted(id, current.contains(id));
        if (!changed.isEmpty()) {
            logger.info("TAB hard-mute: {} player(s) hidden", current.size());
        }
        if (TAB.getInstance() == null || TAB.getInstance().isPluginDisabled()) return;
        TAB.getInstance().getCpu().runMeasuredTask("HardMute", "refresh", () -> {
            // Reconcile the full snapshot, not only changed UUIDs. A viewer may join or switch
            // after a mute was recorded and therefore never receive its transition packet.
            for (var velocityPlayer : plugin.getServer().getAllPlayers()) {
                reconcileViewer(velocityPlayer, current);
            }
            for (UUID id : changed) {
                GlobalPlayerList global = TAB.getInstance().getFeatureManager()
                        .getFeature(TabConstants.Feature.GLOBAL_PLAYER_LIST);
                boolean muted = current.contains(id);
                if (global != null) global.onHardMuteStatusChange(id, muted);
                // The feature callback handles normal/global list entries, but the backend
                // snapshot can contain an entry which has already been marked unlisted by a
                // previous reconciliation. Restore that existing entry directly on unmute;
                // never remove/re-add it (which would flicker player entities and skins).
                if (!muted) {
                    for (var velocityPlayer : plugin.getServer().getAllPlayers()) {
                        if (velocityPlayer.getUniqueId().equals(id)) continue;
                        var viewer = TAB.getInstance().getPlayer(velocityPlayer.getUniqueId());
                        var target = TAB.getInstance().getPlayer(id);
                        if (viewer != null && target != null && viewer.getTabList().containsEntry(id)
                                && (global == null || global.shouldSee(viewer, target))) {
                            viewer.getTabList().updateListed(id, true);
                        }
                    }
                }
            }
        });
    }

    void reconcileViewer(com.velocitypowered.api.proxy.Player velocityPlayer) {
        Set<UUID> current = previous;
        if (TAB.getInstance() == null || TAB.getInstance().isPluginDisabled()) return;
        reconcileViewer(velocityPlayer, current);
    }

    private void reconcileViewer(com.velocitypowered.api.proxy.Player velocityPlayer, Set<UUID> muted) {
        var viewer = TAB.getInstance().getPlayer(velocityPlayer.getUniqueId());
        if (viewer == null) return;
        for (UUID id : muted) {
            if (!viewer.getUniqueId().equals(id) && viewer.getTabList().containsEntry(id)) {
                viewer.getTabList().updateListed(id, false);
            }
        }
    }

    private void warnOnce(String message) {
        if (warningLogged) return;
        warningLogged = true;
        logger.warn(message);
    }

    @Override
    public void close() {
        if (task != null) task.cancel();
        if (dataSource != null) dataSource.close();
    }
}
