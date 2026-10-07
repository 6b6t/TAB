package me.neznamy.tab.shared.features.proxy.message;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import lombok.ToString;
import me.neznamy.tab.shared.features.proxy.ProxySupport;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.chat.component.TabTextComponent;
import me.neznamy.tab.shared.chat.TabTextColor;
import me.neznamy.tab.shared.patch6b6t.PatchStats;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Message sent by another proxy to load multiple players.
 */
@ToString
public class Load extends ProxyMessage {

    @NotNull private final List<PlayerJoin> decodedPlayers;
    private long requestId = -1, snapshot = -1;
    private int chunkIndex, chunkCount;
    public void setSnapshot(long request, long sequence) { requestId = request; snapshot = sequence; setSequence(sequence); }


    /**
     * Creates new instance from given players.
     *
     * @param   players
     *          Players to encode
     */
    public Load(@NotNull TabPlayer[] players) {
        decodedPlayers = Arrays.stream(players).map(PlayerJoin::new).collect(Collectors.toList());
    }

    /**
     * [6b6t patch 6b6t.4] Encoded size of the players in one Load. The message is sent base64 encoded (4/3 larger)
     * through delivery4j's writeUTF, which allows 65,535 bytes; the rest is the header.
     */
    private static final int MAX_PLAYER_BYTES = 45_000;

    private Load(@NotNull List<PlayerJoin> decodedPlayers) {
        this.decodedPlayers = decodedPlayers;
    }

    /**
     * [6b6t patch 6b6t.4] Splits the players into Loads that each fit into one message. One Load of all players
     * (about 1.5 KB per player with a signed skin) was longer than 64 KB from about 40 players on, and delivery4j
     * drops such a message silently (it encodes in a background task whose error nobody reads): a proxy that
     * restarted never got the players who were already on the other proxy.
     *
     * @param   players
     *          players to send
     * @return  Loads to send, at least one
     */
    @NotNull
    public static List<Load> split(@NotNull TabPlayer[] players) {
        return splitPlayers(Arrays.stream(players).map(PlayerJoin::new).collect(Collectors.toList()));
    }

    public static List<Load> splitPlayers(List<PlayerJoin> players) {
        List<Load> loads = new ArrayList<>();
        List<PlayerJoin> part = new ArrayList<>();
        int size = 0;
        boolean complete = true;
        for (PlayerJoin join : players) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            try {
                join.write(out);
            } catch (AssertionError unencodable) {
                // Guava wraps DataOutputStream.writeUTF failures in AssertionError.
                if (!(unencodable.getCause() instanceof java.io.UTFDataFormatException)) throw unencodable;
                skipped(join, "PlayerJoin cannot be encoded with writeUTF");
                complete = false;
                continue;
            }
            int bytes = out.toByteArray().length;
            if (bytes > MAX_PLAYER_BYTES) {
                Load singleton = new Load(java.util.Collections.singletonList(join));
                if (!fitsEnvelope(singleton)) {
                    skipped(join, "PlayerJoin exceeds complete Load wire limit: " + bytes + " bytes");
                    complete = false;
                    continue;
                }
                if (!part.isEmpty()) {
                    loads.add(new Load(part));
                    part = new ArrayList<>();
                    size = 0;
                }
                loads.add(singleton);
                continue;
            }
            if (!part.isEmpty() && size + bytes > MAX_PLAYER_BYTES) {
                loads.add(new Load(part));
                part = new ArrayList<>();
                size = 0;
            }
            part.add(join);
            size += bytes;
        }
        if (!part.isEmpty() || loads.isEmpty()) loads.add(new Load(part));
        // Zero count keeps an incomplete response non-authoritative, even after every sent chunk arrives.
        for (int i = 0; i < loads.size(); i++) { loads.get(i).chunkIndex = i; loads.get(i).chunkCount = complete ? loads.size() : 0; }
        return loads;
    }

    /** Test the complete ProxySupport envelope, whose UUID and message name have fixed UTF sizes. */
    private static boolean fitsEnvelope(Load load) {
        ByteArrayDataOutput envelope = ByteStreams.newDataOutput();
        envelope.writeUTF("00000000-0000-0000-0000-000000000000");
        envelope.writeUTF(Load.class.getSimpleName());
        load.write(envelope);
        envelope.writeLong(0); // ProxyMessage origin sequence
        // Base64 is ASCII, so its character count equals its modified-UTF byte count.
        return 4L * ((envelope.toByteArray().length + 2L) / 3L) <= 65535;
    }

    private static void skipped(PlayerJoin join, String reason) {
        PatchStats.loadEntriesSkipped.incrementAndGet();
        TAB.getInstance().getPlatform().logInfo(new TabTextComponent(
                "[TAB-6b6t] Skipped Load entry " + join.getSubjectId() + ": " + reason
                        + "; response cannot authorize deletion", (TabTextColor) null));
    }

    /**
     * Creates new instance and reads data from byte input.
     *
     * @param   in
     *          Input stream to read from
     */
    public Load(@NotNull ByteArrayDataInput in) {
        decodedPlayers = new ArrayList<>();
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            decodedPlayers.add(new PlayerJoin(in));
        }
        try {
            requestId = in.readLong(); snapshot = in.readLong(); chunkIndex = in.readInt(); chunkCount = in.readInt();
        } catch (IllegalStateException legacyEnd) { requestId = -1; snapshot = -1; chunkCount = 0; }
    }

    @Override
    public void write(@NotNull ByteArrayDataOutput out) {
        out.writeInt(decodedPlayers.size());
        for (PlayerJoin player : decodedPlayers) {
            player.write(out);
        }
        out.writeLong(requestId); out.writeLong(snapshot); out.writeInt(chunkIndex); out.writeInt(chunkCount);
    }

    @Override
    public void process(@NotNull ProxySupport proxySupport) {
        java.util.Set<java.util.UUID> members = new java.util.HashSet<>();
        for (PlayerJoin join : decodedPlayers) {
            members.add(join.getSubjectId());
            join.setSequence(snapshot);
            // [6b6t patch] every player in the list goes through the stale message guard like a single join
            join.setSourceProxy(getSourceProxy());
            if (proxySupport.acceptMessage(join)) {
                join.process(proxySupport);
            }
        }
        proxySupport.noteLoaded(getSourceProxy(), requestId, snapshot, chunkIndex, chunkCount, members);
    }
}
