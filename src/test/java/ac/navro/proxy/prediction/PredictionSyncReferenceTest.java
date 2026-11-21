package ac.navro.proxy.prediction;

import ac.navro.proxy.SupportedCodecs;
import ac.navro.proxy.packets.input.ClientMovementPredictionSyncPacket;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class PredictionSyncReferenceTest {
    private static JsonElement snapshot(PredictionSyncState.Report report) {
        if (report == null) return JsonNull.INSTANCE;
        var gson = new GsonBuilder().serializeNulls().create();
        var json = gson.toJsonTree(report).getAsJsonObject();
        json.add("flags", gson.toJsonTree(new TreeSet<>(report.flags())));
        return json;
    }

    @Test void originalDecoderAndStateTransitionsMatchAcrossIdentityChangesAndPacketLayouts() throws Exception {
        int rows = 0;
        try (var input = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                getClass().getResourceAsStream("/navro-prediction-sync.jsonl.gz")), StandardCharsets.UTF_8))) {
            var header = JsonParser.parseString(input.readLine()).getAsJsonObject();
            assertEquals("NPS1", header.get("magic").getAsString());
            for (int session = 0; session < header.get("sessions").getAsInt(); session++) {
                var state = new PredictionSyncState();
                for (int step = 0; step < header.get("steps").getAsInt(); step++) {
                    var row = JsonParser.parseString(input.readLine()).getAsJsonObject();
                    int operation = row.get("op").getAsInt();
                    String label = "session=" + session + " step=" + step;
                    if (operation < 3) {
                        var codec = SupportedCodecs.forProtocol(row.get("protocol").getAsInt());
                        var bytes = Unpooled.wrappedBuffer(HexFormat.of().parseHex(row.get("payload").getAsString()));
                        try {
                            var packet = assertInstanceOf(ClientMovementPredictionSyncPacket.class,
                                    codec.tryDecode(codec.createHelper(), bytes, 322));
                            assertFalse(bytes.isReadable(), label);
                            assertEquals(row.get("decodedFlags"), new GsonBuilder().create().toJsonTree(
                                    new TreeSet<>(ClientMovementPredictionSyncPacket.copyFlags(packet))), label);
                            assertEquals(row.get("accepted").getAsBoolean(), state.receive(packet,
                                    row.get("expectedId").getAsLong(), row.get("tick").getAsLong()), label);
                        } finally {
                            bytes.release();
                        }
                    } else if (operation == 3) {
                        assertEquals(row.get("consumed"), snapshot(state.consume()), label);
                    } else {
                        state.clear();
                    }
                    assertEquals(row.get("pending"), snapshot(state.pending()), label);
                    rows++;
                }
            }
            assertNull(input.readLine());
        }
        assertEquals(512, rows);
    }
}
