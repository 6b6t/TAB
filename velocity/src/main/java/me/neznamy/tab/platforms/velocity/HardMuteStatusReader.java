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

    private static final long REFRESH_SECONDS = 2;
    private final VelocityTAB plugin;
    private final Logger logger;
    private HikariDataSource dataSource;
    private ScheduledTask task;
    private boolean warningLogged;
    private Set<UUID> previous = Collections.emptySet();

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
        } catch (RuntimeException exception) {
            warnOnce("AsyncChat database settings could not initialize; TAB hard-mute filtering is disabled.");
            return;
        }
        task = plugin.getServer().getScheduler().buildTask(plugin, this::refresh)
                .repeat(REFRESH_SECONDS, TimeUnit.SECONDS)
                .schedule();
        refresh();
    }

    private static String env(String suffix) {
        String value = System.getenv("CONFIG_ASYNCCHAT_DATABASE_" + suffix);
        return value == null || value.isBlank() ? null : value;
    }

    private void refresh() {
        HikariDataSource source = dataSource;
        if (source == null || source.isClosed()) return;
        Set<UUID> current = new HashSet<>();
        try (var connection = source.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT player_uuid FROM asyncchat_uuid_mutes WHERE hard_mute = TRUE AND expires_at > ?")) {
            statement.setLong(1, System.currentTimeMillis());
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
        if (TAB.getInstance() == null || TAB.getInstance().isPluginDisabled()) return;
        GlobalPlayerList global = TAB.getInstance().getFeatureManager()
                .getFeature(TabConstants.Feature.GLOBAL_PLAYER_LIST);
        for (UUID id : changed) {
            if (global != null) global.onHardMuteStatusChange(id, current.contains(id));
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
