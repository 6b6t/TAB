package me.neznamy.tab.shared.patch6b6t;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * [6b6t patch] Switches for the 6b6t changes (TAB 6.1.0-6b6t.N).
 * <p>
 * Read from the optional file {@code plugins/tab/6b6t-patch.properties}. The plugin never creates
 * or rewrites that file. A missing file or a bad value means the default below (every fix on).
 * Re-read on every TAB load, so {@code /tab reload} applies a change.
 */
public final class PatchSettings {

    /** File name inside TAB's data folder */
    public static final String FILE_NAME = "6b6t-patch.properties";

    /** Current settings, replaced on every load */
    private static volatile PatchSettings current = new PatchSettings(new Properties(), new ArrayList<>());

    /** Drop stale proxy messages from a session that is no longer the owner of the player (fix 2) */
    public final boolean staleGuard;

    /** How long to remember the origin of a retired remote copy */
    public final long tombstoneTtlMillis;

    /** Restore the previous team of an entry when the team that took it away is removed (fix 3, layer A) */
    public final boolean entryRestore;

    /** Periodic team audit on the NameTag thread (fix 3, layer B) */
    public final boolean auditEnabled;

    /** Time budget of one audit tick (one tick per second) */
    public final long auditSliceNanos;

    /** Players who joined or switched server less than this ago are skipped by the audit */
    public final long auditGraceMillis;

    /** Remove copies of an origin proxy that stopped sending heartbeats (patched proxies only) */
    public final boolean ghostGc;

    /** Rotate errors.log / placeholder-errors.log / anti-override.log instead of stopping at 16 MB (fix 6) */
    public final boolean logRotation;

    /** Number of rotated files kept per log */
    public final int rotationKeep;

    /** Interval of the "[TAB-6b6t]" counter line in the console, 0 = off */
    public final int statsIntervalMinutes;

    /** Hide bots from the global playerlist of viewers with LuckPerms meta botsfilter-hide-tab=true (6b6t.3) */
    public final boolean botTabFilter;

    private PatchSettings(@NotNull Properties p, @NotNull List<String> warnings) {
        staleGuard = bool(p, "stale-guard", true, warnings);
        tombstoneTtlMillis = 1000L * integer(p, "tombstone-ttl-seconds", 120, 5, 3600, warnings);
        entryRestore = bool(p, "entry-restore", true, warnings);
        auditEnabled = bool(p, "audit-enabled", true, warnings);
        auditSliceNanos = 1_000_000L * integer(p, "audit-slice-ms", 5, 1, 50, warnings);
        auditGraceMillis = 1000L * integer(p, "audit-grace-seconds", 10, 1, 300, warnings);
        ghostGc = bool(p, "ghost-gc", true, warnings);
        logRotation = bool(p, "log-rotation", true, warnings);
        rotationKeep = integer(p, "rotation-keep", 3, 1, 50, warnings);
        statsIntervalMinutes = integer(p, "stats-interval-minutes", 5, 0, 1440, warnings);
        botTabFilter = bool(p, "bot-tab-filter", true, warnings);
    }

    /**
     * Returns the current settings.
     *
     * @return  current settings
     */
    @NotNull
    public static PatchSettings get() {
        return current;
    }

    /**
     * (Re)loads settings from the data folder. Never throws.
     *
     * @param   dataFolder
     *          TAB's data folder
     * @return  warnings to print (bad values, unreadable file), empty if none
     */
    @NotNull
    public static List<String> load(@Nullable File dataFolder) {
        List<String> warnings = new ArrayList<>();
        Properties properties = new Properties();
        if (dataFolder != null) {
            File file = new File(dataFolder, FILE_NAME);
            if (file.isFile()) {
                try (InputStream in = new FileInputStream(file)) {
                    properties.load(in);
                } catch (Exception e) {
                    warnings.add("Could not read " + FILE_NAME + " (" + e + "), using defaults");
                    properties = new Properties();
                }
            }
        }
        current = new PatchSettings(properties, warnings);
        return warnings;
    }

    /**
     * Replaces the settings (unit tests only).
     *
     * @param   properties
     *          properties to use
     */
    public static void setForTests(@NotNull Properties properties) {
        current = new PatchSettings(properties, new ArrayList<>());
    }

    private static boolean bool(@NotNull Properties p, @NotNull String key, boolean def, @NotNull List<String> warnings) {
        String value = p.getProperty(key);
        if (value == null) return def;
        value = value.trim();
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        warnings.add("Invalid value \"" + value + "\" for " + key + " in " + FILE_NAME + ", using " + def);
        return def;
    }

    private static int integer(@NotNull Properties p, @NotNull String key, int def, int min, int max, @NotNull List<String> warnings) {
        String value = p.getProperty(key);
        if (value == null) return def;
        try {
            int i = Integer.parseInt(value.trim());
            if (i >= min && i <= max) return i;
        } catch (NumberFormatException ignored) {
            // handled below
        }
        warnings.add("Invalid value \"" + value + "\" for " + key + " in " + FILE_NAME + " (allowed " + min + "-" + max + "), using " + def);
        return def;
    }
}
