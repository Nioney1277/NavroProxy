package ac.navro.proxy.nethernet;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetherNetBridgeFramingTest {
    @Test
    void preservesDirectionsAndOriginalBatchBytesInOneGroup() throws Exception {
        var group = NetherNetInspectServer.readBridgeGroup(input(
                new NetherNetInspectServer.BridgeBatch(1, new byte[]{10, 20}),
                new NetherNetInspectServer.BridgeBatch(0, new byte[]{30}),
                new NetherNetInspectServer.BridgeBatch(2, new byte[0]),
                new NetherNetInspectServer.BridgeBatch(1, new byte[]{40})), 4);

        assertEquals(List.of(1, 0, 2, 1), group.stream().map(NetherNetInspectServer.BridgeBatch::direction).toList());
        assertArrayEquals(new byte[]{10, 20}, group.get(0).payload());
        assertArrayEquals(new byte[]{30}, group.get(1).payload());
        assertEquals(0, group.get(2).payload().length);
        assertArrayEquals(new byte[]{40}, group.get(3).payload());
    }

    @Test
    void rejectsInvalidCountDirectionAndTickPayload() throws Exception {
        assertThrows(IOException.class, () -> NetherNetInspectServer.readBridgeGroup(input(), 0));
        assertThrows(IOException.class, () -> NetherNetInspectServer.readBridgeGroup(input(), 65));
        assertThrows(IOException.class, () -> NetherNetInspectServer.readBridgeGroup(
                input(new NetherNetInspectServer.BridgeBatch(3, new byte[]{1})), 1));
        assertThrows(IOException.class, () -> NetherNetInspectServer.readBridgeGroup(
                input(new NetherNetInspectServer.BridgeBatch(2, new byte[]{1})), 1));
    }

    @Test
    void boundsTheAggregateWhileAllowingOneLargeNativeBatch() throws Exception {
        var large = new NetherNetInspectServer.BridgeBatch(1, new byte[2 * 1024 * 1024]);
        var small = new NetherNetInspectServer.BridgeBatch(0, new byte[]{1});
        assertThrows(IOException.class, () -> NetherNetInspectServer.readBridgeGroup(input(large, small), 2));
        var standalone = new NetherNetInspectServer.BridgeBatch(1, new byte[large.payload().length + 1]);
        assertEquals(standalone.payload().length,
                NetherNetInspectServer.readBridgeGroup(input(standalone), 1).getFirst().payload().length);
    }

    @Test
    void rejectsTruncatedGroupBeforeDispatch() {
        var truncated = new DataInputStream(new ByteArrayInputStream(new byte[]{1, 0, 0, 0, 2, 10}));
        assertThrows(EOFException.class, () -> NetherNetInspectServer.readBridgeGroup(truncated, 1));
    }

    private static DataInputStream input(NetherNetInspectServer.BridgeBatch... batches) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            for (var batch : batches) {
                output.writeByte(batch.direction());
                output.writeInt(batch.payload().length);
                output.write(batch.payload());
            }
        }
        return new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }
}
