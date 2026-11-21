import ac.navro.anticheat.packets.input.ClientMovementPredictionSyncPacket;
import ac.navro.anticheat.prediction.PredictionSyncState;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Random;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;

public final class NavroPredictionSyncFixture {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private static JsonObject snapshot(PredictionSyncState.Report report) {
        if (report == null) return null;
        var json = GSON.toJsonTree(report).getAsJsonObject();
        json.add("flags", GSON.toJsonTree(new TreeSet<>(report.flags())));
        return json;
    }

    private static byte[] payload(Random random, int protocol, long identity) {
        var buffer = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        var flags = BigInteger.ZERO;
        for (int flag : new int[]{1, 3, 4, 32, 57, 64, 114, 128, 191, 223}) {
            if (random.nextBoolean()) flags = flags.setBit(flag);
        }
        do {
            int value = flags.and(BigInteger.valueOf(127)).intValue();
            flags = flags.shiftRight(7);
            buffer.put((byte) (flags.signum() == 0 ? value : value | 128));
        } while (flags.signum() != 0);
        for (int scalar = 0; scalar < (protocol >= 975 ? 12 : 9); scalar++) {
            buffer.putFloat((random.nextInt(2049) - 1024) / 64F);
        }
        long encodedId = (identity << 1) ^ (identity >> 63);
        while ((encodedId & ~127L) != 0) {
            buffer.put((byte) ((encodedId & 127) | 128));
            encodedId >>>= 7;
        }
        buffer.put((byte) encodedId);
        if (protocol >= 786) buffer.put((byte) (random.nextBoolean() ? 1 : 0));
        return java.util.Arrays.copyOf(buffer.array(), buffer.position());
    }

    public static void main(String[] args) throws Exception {
        var random = new Random(0x53594e4353544154L);
        int[] protocols = {776, 786, 975, 2168, 2193, 2225};
        long[] identities = {-42, -1, 7, 70, Long.MIN_VALUE, Long.MAX_VALUE};
        int rows = 0;
        try (var output = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(Path.of(args[0]))), StandardCharsets.UTF_8))) {
            output.write(GSON.toJson(java.util.Map.of("magic", "NPS1", "sessions", 4, "steps", 128)));
            output.newLine();
            for (int session = 0; session < 4; session++) {
                var state = new PredictionSyncState();
                for (int step = 0; step < 128; step++) {
                    int operation = random.nextInt(5);
                    var row = new JsonObject();
                    row.addProperty("op", operation);
                    if (operation < 3) {
                        int protocol = protocols[random.nextInt(protocols.length)];
                        long expected = identities[random.nextInt(identities.length)];
                        long identity = random.nextBoolean() ? expected : identities[random.nextInt(identities.length)];
                        byte[] bytes = payload(random, protocol, identity);
                        var packet = new ClientMovementPredictionSyncPacket();
                        packet.protocol = protocol;
                        packet.setBuffer(bytes, 0);
                        packet.decode();
                        if (packet.getCount() != packet.getOffset()) throw new IllegalStateException("Native decoder left bytes");
                        row.addProperty("protocol", protocol);
                        row.addProperty("payload", HexFormat.of().formatHex(bytes));
                        row.addProperty("expectedId", expected);
                        row.addProperty("tick", step - 5L);
                        row.addProperty("accepted", state.receive(packet, expected, step - 5L));
                        row.add("decodedFlags", GSON.toJsonTree(new TreeSet<>(packet.copyFlags())));
                    } else if (operation == 3) {
                        var consumed = snapshot(state.consume());
                        row.add("consumed", consumed == null ? JsonNull.INSTANCE : consumed);
                    } else {
                        state.clear();
                    }
                    var pending = snapshot(state.pending());
                    row.add("pending", pending == null ? JsonNull.INSTANCE : pending);
                    output.write(GSON.toJson(row));
                    output.newLine();
                    rows++;
                }
            }
        }
        System.out.println("Original decoder and declaration state transitions: " + rows);
    }
}
