package me.neznamy.tab.shared.patch6b6t;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [6b6t patch] Counters of the 6b6t TAB patch.
 * <p>
 * Printed as one console line every few minutes (deltas since the last line), for example
 * <pre>[TAB-6b6t] 5m: modify-missing=0 register-duplicate=2 ... local=812 remote=640</pre>
 * The line is printed even when every counter is 0, so the alert script can also use it as a heartbeat.
 * Velocity logs INFO to stdout, which Loki collects.
 */
public final class PatchStats {

    /** "Tried to modify non-existing team" (the August errors.log flood) */
    public static final AtomicLong modifyMissing = new AtomicLong();

    /** "Tried to register duplicated team" */
    public static final AtomicLong registerDuplicate = new AtomicLong();

    /** "Tried to unregister non-existing team" */
    public static final AtomicLong unregisterMissing = new AtomicLong();

    /** Proxy messages dropped because they came from a session that no longer owns the player */
    public static final AtomicLong staleDropped = new AtomicLong();

    /** Remote copies removed because the same player joined this proxy */
    public static final AtomicLong copiesRetired = new AtomicLong();

    /** Remote copies replaced because the player joined from another proxy instance */
    public static final AtomicLong originReplaced = new AtomicLong();

    /** Remote team registered while processing a property update (was missing for the viewer) */
    public static final AtomicLong inlineRegistered = new AtomicLong();

    /** An entry was put into a second team for the same viewer (client moved it) */
    public static final AtomicLong entryConflicts = new AtomicLong();

    /** Entry put back into its previous team after the team that took it was removed */
    public static final AtomicLong entryRestored = new AtomicLong();

    /** Repairs made by the periodic audit */
    public static final AtomicLong auditRepaired = new AtomicLong();

    /** Copies removed because their origin proxy stopped sending heartbeats */
    public static final AtomicLong ghostsRemoved = new AtomicLong();

    /** Log files rotated */
    public static final AtomicLong logRotations = new AtomicLong();

    /** [6b6t patch 6b6t.4] Requests for the players of another proxy because our copies differed from its heartbeat digest */
    public static final AtomicLong resyncRequested = new AtomicLong();

    private static final Map<String, AtomicLong> ALL = new LinkedHashMap<>();
    private static final Map<String, Long> LAST = new LinkedHashMap<>();

    static {
        ALL.put("modify-missing", modifyMissing);
        ALL.put("register-duplicate", registerDuplicate);
        ALL.put("unregister-missing", unregisterMissing);
        ALL.put("stale-dropped", staleDropped);
        ALL.put("copies-retired", copiesRetired);
        ALL.put("origin-replaced", originReplaced);
        ALL.put("inline-registered", inlineRegistered);
        ALL.put("entry-conflicts", entryConflicts);
        ALL.put("entry-restored", entryRestored);
        ALL.put("audit-repaired", auditRepaired);
        ALL.put("resync-requested", resyncRequested);
        ALL.put("ghosts-removed", ghostsRemoved);
        ALL.put("log-rotations", logRotations);
    }

    private PatchStats() {
    }

    /**
     * Builds the console line with deltas since the previous call.
     *
     * @param   intervalMinutes
     *          interval shown in the line
     * @param   local
     *          number of players on this proxy
     * @param   remote
     *          number of players on other proxies known to this proxy
     * @return  line to print
     */
    @NotNull
    public static synchronized String deltaLine(int intervalMinutes, int local, int remote) {
        StringBuilder sb = new StringBuilder("[TAB-6b6t] ").append(intervalMinutes).append("m:");
        for (Map.Entry<String, AtomicLong> e : ALL.entrySet()) {
            long now = e.getValue().get();
            long before = LAST.getOrDefault(e.getKey(), 0L);
            LAST.put(e.getKey(), now);
            sb.append(' ').append(e.getKey()).append('=').append(now - before);
        }
        sb.append(" local=").append(local).append(" remote=").append(remote);
        return sb.toString();
    }
}
