package ac.navro.proxy.backend;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.protocol.BackendType;
import ac.navro.proxy.SupportedCodecs;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BackendSyncLifecycleTest {
    static ScriptMessagePacket sample(String nonce,long sequence,int tick) {
        var p=new ScriptMessagePacket();p.setChannel(BackendStateSync.CHANNEL);p.setMessage("tick|"+nonce+"|"+sequence+"|"+tick);return p;
    }
    @Test void realStartGameNegotiatesEveryBackendWithoutAnEngineNameAndClockRemainsAnObservation() {
        for(var type:BackendType.values()) {
            var state=AuthInputPacketsTest.session(type);var outbound=new ArrayList<org.cloudburstmc.protocol.bedrock.packet.BedrockPacket>();state.setBackendSyncTransport(outbound::add);
            var start=new StartGamePacket();start.setRuntimeEntityId(7);start.setUniqueEntityId(70);start.setPlayerPosition(Vector3f.from(8,64,8));start.setServerEngine("Custom engine name");
            state.observeBackend(start);assertEquals(1,outbound.size());var hello=assertInstanceOf(ScriptMessagePacket.class,outbound.get(0));
            assertFalse(state.world().hasNativeServerTick());String nonce=hello.getMessage().substring(6);
            state.observeBackend(sample(nonce,0,421));assertEquals(421,state.backendSync().tick());assertTrue(state.world().hasNativeServerTick());
            assertEquals(8,state.player().position().x);assertEquals(64,state.player().position().y);
            assertFalse(state.teleports().isTeleporting());
            assertTrue(state.backendInventories().generation()>0);
            // Reconnect/new StartGame invalidates old clock, even if old frames arrive later.
            state.observeBackend(start);assertFalse(state.world().hasNativeServerTick());state.observeBackend(sample(nonce,1,422));assertFalse(state.world().hasNativeServerTick());
        }
    }
    @Test void decodedNativeTransactionSupplementsUseExistingInventoryAckCommitAndFullItemIdentity() throws Exception {
        try(var reader=new java.io.InputStreamReader(getClass().getResourceAsStream("/native-transaction-supplement.json"))) {
            var data=com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();assertEquals(2193,data.get("protocol").getAsInt());
            var state=AuthInputPacketsTest.session();
            var open=new ContainerOpenPacket();open.setId((byte)5);open.setType(org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType.CONTAINER);open.setBlockPosition(Vector3i.from(8,64,8));state.observeBackend(open);
            var codec=SupportedCodecs.forProtocol(2193);var helper=codec.createHelper();new ac.navro.proxy.ProtocolDefinitions().configure(helper,true);
            var expected=new HashMap<Integer,Integer>();
            state.player().getLatencyUtil().queue(601,true);
            for(var element:data.getAsJsonArray("slots")) {
                var row=element.getAsJsonObject();byte[] bytes=Base64.getDecoder().decode(row.get("bytes").getAsString());var buffer=io.netty.buffer.Unpooled.wrappedBuffer(bytes);
                try {
                    int id=org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(buffer);
                    var packet=assertInstanceOf(InventorySlotPacket.class,codec.tryDecode(helper,buffer,id));
                    assertEquals(0,buffer.readableBytes());assertEquals(row.get("container").getAsInt(),packet.getContainerId());
                    state.backendInventories().markPending(5,packet.getSlot());state.observeBackend(packet);
                    assertEquals(row.get("count").getAsInt(),state.backendInventories().slot(5,packet.getSlot()).getCount());
                    assertFalse(state.backendInventories().pending(5,packet.getSlot()));
                    assertTrue(state.inventory().openContainer.get(packet.getSlot()).isEmpty(),"Client-visible compensated cache waits for the real closing ACK");
                    expected.put(packet.getSlot(),row.get("count").getAsInt());
                } finally {buffer.release();}
            }
            assertEquals(2,expected.size());state.player().getLatencyUtil().onResponse(601);
            expected.forEach((slot,count)->assertEquals(count.intValue(),state.inventory().openContainer.get(slot).count()));
        }
    }
    @Test void stoppedClockDisablesCapabilityAndMalformedTrafficDoesNotRefreshIt() {
        var state=AuthInputPacketsTest.session();var clock=new java.util.concurrent.atomic.AtomicLong(10_000_000_000L);
        var sync=new BackendStateSync(state,clock::get);String nonce=sync.begin().getMessage().substring(6);
        sync.receive(sample(nonce,0,42));assertTrue(state.world().hasNativeServerTick());
        assertTrue(state.player().checkEnabled(ac.navro.proxy.check.impl.place.AirLiquidPlace.class));
        assertTrue(state.player().checkEnabled(ac.navro.proxy.check.impl.place.RotationPlace.class));
        clock.addAndGet(1_000_000_001L);assertFalse(state.world().hasNativeServerTick());
        assertFalse(state.player().checkEnabled(ac.navro.proxy.check.impl.place.AirLiquidPlace.class));
        assertFalse(state.player().checkEnabled(ac.navro.proxy.check.impl.place.RotationPlace.class));
        sync.receive(sample("wrong",1,43));assertFalse(state.world().hasNativeServerTick());
        sync.receive(sample(nonce,0,99));assertFalse(state.world().hasNativeServerTick());
        sync.receive(sample(nonce,1,43));assertTrue(state.world().hasNativeServerTick());assertEquals(43,sync.tick());
    }

    @Test void nativeStackResponseUsesFullSupplementWithItsOwnClosingAck() throws Exception {
        try (var reader = new java.io.InputStreamReader(getClass().getResourceAsStream("/native-stack-response-supplement.json"))) {
            var fixture = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            int container = fixture.get("container").getAsInt();
            int slot = fixture.get("slot").getAsInt();
            int count = fixture.get("count").getAsInt();
            var state = AuthInputPacketsTest.session();
            var open = new ContainerOpenPacket();
            open.setId((byte) container);
            open.setType(org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType.CONTAINER);
            open.setBlockPosition(Vector3i.ZERO);
            state.observeBackend(open);
            var codec = SupportedCodecs.forProtocol(fixture.get("protocol").getAsInt());
            var helper = codec.createHelper();
            new ac.navro.proxy.ProtocolDefinitions().configure(helper, true);
            state.player().getLatencyUtil().queue(611, true);
            var responseBuffer = io.netty.buffer.Unpooled.wrappedBuffer(Base64.getDecoder().decode(fixture.get("response").getAsString()));
            try {
                int packetId = org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(responseBuffer);
                state.observeBackend(assertInstanceOf(ItemStackResponsePacket.class, codec.tryDecode(helper, responseBuffer, packetId)));
                assertFalse(responseBuffer.isReadable());
            } finally {
                responseBuffer.release();
            }
            assertTrue(state.backendInventories().pending(container, slot));
            assertTrue(state.player().getLatencyUtil().onResponse(611));
            assertTrue(state.inventory().openContainer.get(slot).isEmpty());

            state.player().getLatencyUtil().queue(612, true);
            var supplementBuffer = io.netty.buffer.Unpooled.wrappedBuffer(Base64.getDecoder().decode(fixture.get("supplement").getAsString()));
            try {
                int packetId = org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(supplementBuffer);
                state.observeBackend(assertInstanceOf(InventorySlotPacket.class, codec.tryDecode(helper, supplementBuffer, packetId)));
                assertFalse(supplementBuffer.isReadable());
            } finally {
                supplementBuffer.release();
            }
            assertTrue(state.inventoryResponseSync().pending().isEmpty());
            var actual = state.backendInventories().slot(container, slot);
            assertEquals(count, actual.getCount());
            assertEquals(fixture.get("stackId").getAsInt(), actual.getNetId());
            assertEquals(fixture.get("name").getAsString(), actual.getTag().getCompound("display").getString("Name"));
            assertTrue(state.inventory().openContainer.get(slot).isEmpty());
            assertTrue(state.player().getLatencyUtil().onResponse(612));
            assertEquals(actual.getTag(), state.inventory().openContainer.get(slot).getData().getTag());
        }
    }

    @Test void nativeWorkstationSlotsUpdateTheCorrectOpenWindowOnlyAfterTheSupplementAck() throws Exception {
        try (var reader = new java.io.InputStreamReader(getClass().getResourceAsStream("/native-workstation-supplements.json"))) {
            var codec = SupportedCodecs.forProtocol(2193);
            var helper = codec.createHelper();
            new ac.navro.proxy.ProtocolDefinitions().configure(helper, true);
            for (var element : com.google.gson.JsonParser.parseReader(reader).getAsJsonArray()) {
                var fixture = element.getAsJsonObject();
                int slot = fixture.get("slot").getAsInt();
                int stackId = fixture.get("stackId").getAsInt();
                var state = AuthInputPacketsTest.session();
                var type = ContainerType.from(fixture.get("windowType").getAsInt());
                var open = openWorkstation(type);

                state.player().getLatencyUtil().queue(621, true);
                state.observeBackend(open);
                var response = assertInstanceOf(ItemStackResponsePacket.class,
                        decode(codec, helper, fixture.get("response").getAsString()));
                state.observeBackend(response);
                assertTrue(state.backendInventories().pending(124, slot));
                assertFalse(state.backendInventories().pending(Byte.toUnsignedInt(open.getId()), slot));
                assertTrue(state.player().getLatencyUtil().onResponse(621));
                assertTrue(state.inventory().openContainer.get(slot).isEmpty());
                assertTrue(state.inventory().hudContainer.get(slot).isEmpty());

                var supplement = assertInstanceOf(InventorySlotPacket.class,
                        decode(codec, helper, fixture.get("supplement").getAsString()));
                state.player().getLatencyUtil().queue(622, true);
                state.observeBackend(supplement);
                assertTrue(state.inventoryResponseSync().pending().isEmpty());
                assertEquals(stackId, state.backendInventories().slot(124, slot).getNetId());
                assertTrue(state.inventory().hudContainer.get(slot).isEmpty());
                assertTrue(state.player().getLatencyUtil().onResponse(622));
                var hudItem = state.inventory().hudContainer.get(slot).getData();
                assertEquals(stackId, hudItem.getNetId());
                assertEquals(fixture.get("name").getAsString(), hudItem.getTag().getCompound("display").getString("Name"));
                if (slot == 50) assertTrue(state.inventory().openContainer.get(slot).isEmpty());
                else assertEquals(hudItem, state.inventory().openContainer.get(slot).getData());
            }
        }
    }

    @Test void workstationChangeClearsPendingUiSlotsButKeepsTheCursor() {
        var state = AuthInputPacketsTest.session();
        state.observeBackend(openWorkstation(ContainerType.SMITHING_TABLE));
        state.backendInventories().markPending(124, 0);
        state.backendInventories().markPending(124, 53);
        state.observeBackend(openWorkstation(ContainerType.ENCHANTMENT));
        assertTrue(state.backendInventories().pending(124, 0));
        assertFalse(state.backendInventories().pending(124, 53));
    }

    private static ContainerOpenPacket openWorkstation(ContainerType type) {
        var open = new ContainerOpenPacket();
        open.setId((byte) (type == ContainerType.WORKBENCH ? -1 : 5));
        open.setType(type);
        open.setBlockPosition(Vector3i.ZERO);
        return open;
    }

    private static BedrockPacket decode(BedrockCodec codec, BedrockCodecHelper helper, String base64) throws Exception {
        var buffer = io.netty.buffer.Unpooled.wrappedBuffer(Base64.getDecoder().decode(base64));
        try {
            int id = org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(buffer);
            var packet = codec.tryDecode(helper, buffer, id);
            assertFalse(buffer.isReadable());
            return packet;
        } finally {
            buffer.release();
        }
    }
}
