package me.neznamy.tab.shared;

import lombok.Getter;
import me.neznamy.tab.api.event.TabEvent;
import me.neznamy.tab.shared.chat.TabTextColor;
import me.neznamy.tab.shared.chat.component.TabTextComponent;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

import me.neznamy.tab.shared.patch6b6t.PatchSettings;
import me.neznamy.tab.shared.patch6b6t.PatchStats;

/**
 * An error assistant to print internal errors into error file
 * and warn user about misconfiguration
 */

public class ErrorManager {

    /** Date format used in error messages */
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy - HH:mm:ss - ");

    /** errors.log file for internal plugin errors */
    @Getter private final File errorLog;

    /** anti-override.log file when some plugin or server itself attempts to override the plugin */
    @Getter private final File antiOverrideLog;

    /** placeholder-errors.log file for errors thrown by placeholders */
    private final File placeholderErrorLog;

    /**
     * Constructs new instance.
     *
     * @param   dataFolder
     *          Data folder for error files
     */
    public ErrorManager(@NotNull File dataFolder) {
        errorLog = new File(dataFolder, "errors.log");
        antiOverrideLog = new File(dataFolder, "anti-override.log");
        placeholderErrorLog = new File(dataFolder, "placeholder-errors.log");
    }

    /**
     * Prints error message and stack trace into errors.log file
     *
     * @param   message
     *          message to print
     * @param   t
     *          thrown error
     */
    public void printError(@Nullable String message, @Nullable Throwable t) {
        printError(message, t, false, errorLog);
    }

    /**
     * Prints error message and stack trace into specified file
     *
     * @param   message
     *          message to print
     * @param   t
     *          thrown error
     * @param   intoConsoleToo
     *          if the message should be printed into console as well or not
     * @param   file
     *          file to print error to
     */
    public void printError(@Nullable String message, @Nullable Throwable t, boolean intoConsoleToo, @NotNull File file) {
        List<String> lines = t == null ? Collections.emptyList() : throwableToList(t, false);
        printError(message, lines, intoConsoleToo, file);
    }

    /**
     * [6b6t patch] Renames a full log to {@code <base>-<yyyyMMdd'T'HHmmss'Z'>.log} (UTC) and deletes the oldest
     * rotated files of the same base beyond {@code keep}. Names end in ".log" on purpose: proxy-1's start-up
     * rsync excludes *.log, so rotated files are neither copied from proxy-0 nor deleted there. Only files
     * matching the strict pattern are ever deleted (hand-made files like errors-full-until-20260815.log are kept).
     *
     * @param   file
     *          full log file
     * @param   keep
     *          number of rotated files to keep
     * @return  {@code true} if rotated
     */
    public static synchronized boolean rotate(@NotNull File file, int keep) {
        File dir = file.getAbsoluteFile().getParentFile();
        String name = file.getName();
        String base = name.endsWith(".log") ? name.substring(0, name.length() - 4) : name;
        SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        stamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        String ts = stamp.format(new Date());
        Pattern rotated = Pattern.compile("^" + Pattern.quote(base) + "-(\\d{8}T\\d{6}Z)(?:-(\\d{1,3}))?\\.log$");
        // Several rotations within one second (never expected with 16 MB files): add an increasing suffix
        long suffix = -1;
        File[] sameSecond = dir.listFiles((d, n) -> {
            java.util.regex.Matcher m = rotated.matcher(n);
            return m.matches() && m.group(1).equals(ts);
        });
        if (sameSecond != null) {
            // plain name counts as 0, "-N" as N
            for (File f : sameSecond) suffix = Math.max(suffix, Math.max(0, rotationOrder(rotated, f.getName())[1]));
        }
        File target = new File(dir, base + "-" + ts + (suffix < 0 ? "" : "-" + (suffix + 1)) + ".log");
        if (target.exists()) return false; // never overwrite (a rename would replace it silently)
        try {
            try {
                Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(file.toPath(), target.toPath());
            }
        } catch (IOException e) {
            return false;
        }
        PatchStats.logRotations.incrementAndGet();
        File[] old = dir.listFiles((d, n) -> rotated.matcher(n).matches());
        if (old != null && old.length > keep) {
            // Oldest first: by timestamp, then by suffix (no suffix = first)
            Arrays.sort(old, Comparator.comparing((File f) -> rotationOrder(rotated, f.getName())[0])
                    .thenComparingLong(f -> rotationOrder(rotated, f.getName())[1]));
            for (int i = 0; i < old.length - keep; i++) {
                //noinspection ResultOfMethodCallIgnored
                old[i].delete();
            }
        }
        try {
            TAB.getInstance().getPlatform().logInfo(new TabTextComponent("[TAB-6b6t] rotated " + name + " -> " + target.getName(), (TabTextColor) null));
        } catch (Throwable ignored) {
            // Not loaded (unit tests)
        }
        return true;
    }

    /**
     * [6b6t patch] Sort key of a rotated file name: [timestamp as number, suffix (-1 if none)].
     */
    private static long[] rotationOrder(@NotNull Pattern rotated, @NotNull String name) {
        java.util.regex.Matcher m = rotated.matcher(name);
        if (!m.matches()) return new long[]{0, -1};
        long ts = Long.parseLong(m.group(1).replace("T", "").replace("Z", ""));
        return new long[]{ts, m.group(2) == null ? -1 : Long.parseLong(m.group(2))};
    }

    /**
     * Converts throwable into a list of lines.
     *
     * @param   t
     *          Throwable to print
     * @param   nested
     *          Whether this throwable is nested or not
     * @return  List of lines from given throwable
     */
    private List<String> throwableToList(@NotNull Throwable t, boolean nested) {
        List<String> list = new ArrayList<>();
        String causedText = nested ? "Caused by: " : "";
        list.add(causedText + t.getClass().getName() + ": " + t.getMessage());
        for (StackTraceElement ste : t.getStackTrace()) {
            list.add("\tat " + ste.toString());
        }
        if (t.getCause() != null) {
            list.addAll(throwableToList(t.getCause(), true));
        }
        return list;
    }

    /**
     * Prints error message and stack trace into specified file
     *
     * @param   message
     *          message to print
     * @param   error
     *          thrown error
     * @param   intoConsoleToo
     *          if the message should be printed into console as well or not
     * @param   file
     *          file to print error to
     */
    public synchronized void printError(@Nullable String message, @NotNull List<String> error, boolean intoConsoleToo, @NotNull File file) {
        try {
            // [6b6t patch] Fix 6: rotate at 16 MB instead of silently stopping to log forever.
            // If the rotation fails, the old size guards below still stop writing, so the disk cannot fill.
            if (PatchSettings.get().logRotation && file.length() >= TabConstants.MAX_LOG_SIZE) {
                rotate(file, PatchSettings.get().rotationKeep);
            }
            if (!file.exists()) Files.createFile(file.toPath());
            try (BufferedWriter buf = new BufferedWriter(new FileWriter(file, true))) {
                if (message != null) {
                    if (file.length() < TabConstants.MAX_LOG_SIZE)
                        buf.write(dateFormat.format(new Date()) + "[TAB v" + ProjectVariables.PLUGIN_VERSION + "] " + message + System.lineSeparator());
                    if (intoConsoleToo || TAB.getInstance().getConfiguration().getConfig().isDebugMode())
                        TAB.getInstance().getPlatform().logWarn(new TabTextComponent(message, TabTextColor.RED));
                }
                for (String line : error) {
                    if (file.length() < TabConstants.MAX_LOG_SIZE)
                        buf.write(dateFormat.format(new Date()) + line + System.lineSeparator());
                    if (intoConsoleToo || TAB.getInstance().getConfiguration().getConfig().isDebugMode())
                        TAB.getInstance().getPlatform().logWarn(new TabTextComponent(line, TabTextColor.RED));
                }
            }
        } catch (IOException ex) {
            List<String> lines = new ArrayList<>();
            lines.add("An error occurred when printing error message into file");
            lines.addAll(throwableToList(ex, false));
            lines.add("Original error: " + message);
            lines.addAll(error);
            for (String line : lines) {
                TAB.getInstance().getPlatform().logWarn(new TabTextComponent(line, TabTextColor.RED));
            }
        }
    }

    @NotNull
    private Throwable getRootCause(@NotNull Throwable throwable) {
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        return rootCause;
    }

    /**
     * Prints error message thrown by placeholder and stack trace into placeholder-errors.log file
     *
     * @param   message
     *          message to print
     * @param   t
     *          thrown error
     */
    public void placeholderError(@Nullable String message, @Nullable Throwable t) {
        printError(message, t, false, placeholderErrorLog);
    }

    /**
     * Prints error message thrown by placeholder and stack trace into placeholder-errors.log file
     *
     * @param   message
     *          message to print
     * @param   t
     *          thrown stack trace elements
     */
    public void placeholderError(@Nullable String message, @NotNull List<String> t) {
        printError(message, t, false, placeholderErrorLog);
    }

    /**
     * Prints error message and stack trace into errors.log file as well as the console
     *
     * @param   message
     *          message to print
     * @param   t
     *          thrown error
     */
    public void criticalError(@Nullable String message, @Nullable Throwable t) {
        printError(message, t, true, errorLog);
    }

    /**
     * Prints error message when permission plugin throws error when retrieving group.
     *
     * @param   pluginName
     *          Name of permission plugin
     * @param   player
     *          Player whose group failed to retrieve
     * @param   t
     *          Thrown error
     */
    public void groupRetrieveException(@NotNull String pluginName, @NotNull TabPlayer player, Throwable t) {
        printError("Permission system " + pluginName + " threw an exception when getting group of " + player.getName(),
                t, false, errorLog);
    }

    /**
     * Prints error message when permission plugin returned null group.
     *
     * @param   pluginName
     *          Name of permission plugin
     * @param   player
     *          Player who null group was returned for
     */
    public void nullGroupReturned(@NotNull String pluginName, @NotNull TabPlayer player) {
        printError("Permission system " + pluginName + " returned null group for player " + player.getName(),
                Collections.emptyList(), false, errorLog);
    }

    /**
     * Prints error message when parse command throws an error.
     *
     * @param   placeholder
     *          Placeholder input that threw the error
     * @param   target
     *          Player the placeholder was parsed for
     * @param   t
     *          Thrown error
     */
    public void parseCommandError(@NotNull String placeholder, @NotNull TabPlayer target, @NotNull Throwable t) {
        printError("Placeholder " + placeholder + " threw an exception when parsing for player " + target.getName(),
                t, true, errorLog);
    }

    /**
     * Prints error message when RedidSupport received message with unknown action.
     *
     * @param   action
     *          Message action
     */
    public void unknownProxyMessage(@NotNull String action) {
        printError("ProxySupport received unknown action: \"" + action +
                "\". Does it come from a feature enabled on another proxy, but not here?",
                Collections.emptyList(), false, errorLog);
    }

    /**
     * Prints error message when MineSkin download failed with an error.
     *
     * @param   id
     *          Skin that failed to download
     * @param   t
     *          Thrown error
     */
    public void mineSkinDownloadError(@NotNull String id, @NotNull Throwable t) {
        printError("Failed to download skin \"" + id + "\" from MineSkin: " + t.getMessage(),
                Collections.emptyList(), true, errorLog);
    }

    /**
     * Prints error message when player skin download failed with an error.
     *
     * @param   name
     *          Player name that failed to download
     * @param   t
     *          Thrown error
     */
    public void playerSkinDownloadError(@NotNull String name, @NotNull Throwable t) {
        printError("Failed to download skin of player \"" + name + "\": " + t.getMessage(),
                Collections.emptyList(), true, errorLog);
    }

    /**
     * Prints error message when texture skin download failed with an error.
     *
     * @param   texture
     *          Texture that failed to download
     * @param   t
     *          Thrown error
     */
    public void textureSkinDownloadError(@NotNull String texture, @NotNull Throwable t) {
        printError("Failed to download skin from texture \"" + texture + "\": " + t.getMessage(),
                Collections.emptyList(), true, errorLog);
    }

    /**
     * Prints error message when a task throws an error.
     *
     * @param   t
     *          Thrown error
     */
    public void taskThrewError(@NotNull Throwable t) {
        printError("An error was thrown when executing task", t, false, errorLog);
    }

    /**
     * Prints error message if a MySQL connection fails.
     *
     * @param   t
     *          Thrown error
     */
    public void mysqlConnectionFailed(@NotNull Throwable t) {
        Throwable root = getRootCause(t);
        printError("Failed to connect to MySQL: " + root.getClass().getName() + ": " + root.getMessage(), Collections.emptyList(), true, errorLog);
    }

    /**
     * Prints error message if a MySQL query fails.
     *
     * @param   t
     *          Thrown error
     */
    public void mysqlQueryFailed(@NotNull Throwable t) {
        Throwable root = getRootCause(t);
        printError("Failed to execute MySQL query due to error: " + root.getClass().getName() + ": " + root.getMessage(), Collections.emptyList(), false, errorLog);
    }

    /**
     * Prints error message if errors were thrown when firing a TAB event.
     *
     * @param   event
     *          Event that threw errors
     * @param   exceptions
     *          Errors thrown
     */
    public void errorFiringEvent(@NotNull TabEvent event, @NotNull Collection<Throwable> exceptions) {
        printError("Some errors occurred whilst trying to fire event " + event, Collections.emptyList(), false, errorLog);
        int i = 0;
        for (Throwable exception : exceptions) {
            printError("#" + i++ + ": \n", exception, false, errorLog);
        }
    }

    public void redisBungeeMessageSendFail(@NotNull Exception e) {
        printError("Failed to deliver message through RedisBungee due to an error ", e, false, errorLog);
    }

    public void redisBungeeRegisterFail(@NotNull Exception e) {
        printError("Failed to register TAB channel in RedisBungee due to an error ", e, false, errorLog);
    }

    /**
     * Prints anti-override message into anti-override.log file
     *
     * @param   message
     *          message to print
     */
    public void logAntiOverride(@NotNull String message) {
        printError(message, Collections.emptyList(), false, antiOverrideLog);
    }
}