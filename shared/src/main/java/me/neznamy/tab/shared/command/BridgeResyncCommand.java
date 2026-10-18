package me.neznamy.tab.shared.command;

import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.patch6b6t.BridgeRequests;
import me.neznamy.tab.shared.patch6b6t.PatchSettings;
import me.neznamy.tab.shared.patch6b6t.PatchStats;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.proxy.ProxyTabPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * [6b6t patch 6b6t.8] Handler for "/btab bridgeresync &lt;all|missing|server &lt;name&gt;|player &lt;name&gt;|&lt;name&gt;&gt;".
 * <p>
 * Asks the backend TAB-Bridge again for the values of the chosen players on this proxy, without a reload and without
 * kicking anyone. The values the proxy has stay until the answers arrive (no raw flash). Requests go out through the
 * bridge request budget ({@link PatchSettings#bridgeRequestsPerSecond} per second).
 */
public class BridgeResyncCommand extends SubCommand {

    /**
     * Constructs new instance
     */
    public BridgeResyncCommand() {
        super("bridgeresync", TabConstants.Permission.COMMAND_RELOAD);
    }

    @Override
    public void execute(@Nullable TabPlayer sender, @NotNull String[] args) {
        BridgeRequests<ProxyTabPlayer> requests = BridgeRequests.get();
        if (requests == null) {
            sendMessage(sender, "&cOnly available on a proxy with TAB loaded.");
            return;
        }
        if (args.length == 0) {
            sendMessage(sender, "&cUsage: /" + TAB.getInstance().getPlatform().getCommand()
                    + " bridgeresync <all|missing|server <name>|player <name>|<name>>");
            return;
        }
        List<ProxyTabPlayer> targets = select(args);
        if (targets == null) {
            sendMessage(sender, "&cNo online player or server named \"" + String.join(" ", args) + "\" on this proxy.");
            return;
        }
        TAB.getInstance().getCPUManager().runTask(() -> {
            for (ProxyTabPlayer p : targets) {
                if (TAB.getInstance().getPlayer(p.getUniqueId()) != p) continue;
                requests.defer(p.getUniqueId(), p);
                PatchStats.bridgeResync.incrementAndGet();
            }
        });
        int rate = PatchSettings.get().bridgeRequestsPerSecond;
        sendMessage(sender, "&aAsking the TAB-Bridge again for " + targets.size() + " player(s) (" + rate
                + " per second, about " + Math.max(1, (targets.size() + rate - 1) / rate) + " s). Nobody is kicked; "
                + "names keep their current look until the answers arrive.");
    }

    /**
     * Picks the players for the arguments.
     *
     * @param   args
     *          command arguments
     * @return  players, or {@code null} if nothing matched
     */
    @Nullable
    static List<ProxyTabPlayer> select(@NotNull String[] args) {
        String mode = args[0].toLowerCase(Locale.ROOT);
        List<ProxyTabPlayer> all = new ArrayList<>();
        for (TabPlayer p : TAB.getInstance().getOnlinePlayers()) {
            if (p instanceof ProxyTabPlayer) all.add((ProxyTabPlayer) p);
        }
        Set<ProxyTabPlayer> out = new LinkedHashSet<>();
        if (args.length == 1 && mode.equals("all")) {
            out.addAll(all);
            return new ArrayList<>(out);
        }
        if (args.length == 1 && mode.equals("missing")) {
            for (ProxyTabPlayer p : all) if (!p.isBridgeConnected()) out.add(p);
            return new ArrayList<>(out);
        }
        if (args.length == 2 && (mode.equals("server") || mode.equals("player"))) {
            if (mode.equals("server")) addServer(all, args[1], out); else addPlayer(all, args[1], out);
            return out.isEmpty() ? null : new ArrayList<>(out);
        }
        if (args.length == 1) {
            addPlayer(all, args[0], out);
            if (out.isEmpty()) addServer(all, args[0], out);
            return out.isEmpty() ? null : new ArrayList<>(out);
        }
        return null;
    }

    private static void addPlayer(@NotNull List<ProxyTabPlayer> all, @NotNull String name, @NotNull Set<ProxyTabPlayer> out) {
        for (ProxyTabPlayer p : all) if (p.getName().equalsIgnoreCase(name)) out.add(p);
    }

    private static void addServer(@NotNull List<ProxyTabPlayer> all, @NotNull String server, @NotNull Set<ProxyTabPlayer> out) {
        for (ProxyTabPlayer p : all) if (p.server != null && p.server.getName().equalsIgnoreCase(server)) out.add(p);
    }

    @Override
    @NotNull
    public List<String> complete(@Nullable TabPlayer sender, @NotNull String[] arguments) {
        if (arguments.length == 1) {
            List<String> options = new ArrayList<>(Arrays.asList("all", "missing", "server", "player"));
            options.addAll(getOnlinePlayers(arguments[0]));
            return getStartingArgument(options, arguments[0]);
        }
        if (arguments.length == 2 && arguments[0].equalsIgnoreCase("player")) return getOnlinePlayers(arguments[1]);
        if (arguments.length == 2 && arguments[0].equalsIgnoreCase("server")) {
            Set<String> servers = new LinkedHashSet<>();
            for (TabPlayer p : TAB.getInstance().getOnlinePlayers()) if (p.server != null) servers.add(p.server.getName());
            return getStartingArgument(servers, arguments[1]);
        }
        return Collections.emptyList();
    }
}
