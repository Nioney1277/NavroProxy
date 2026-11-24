package ac.navro.proxy.util.block;

import ac.navro.proxy.ProtocolDefinitions;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.packets.other.NetworkLatencyPackets;
import ac.navro.proxy.state.WireEvent;
import ac.navro.proxy.util.EntityBatchEncoder;
import io.netty.buffer.*;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.protocol.bedrock.*;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.netty.*;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchEncoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.*;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class BlockCorrectionSenderTest {
    private static final Vector3i POS=Vector3i.from(8,64,8);
    /** Drains the real library FIFO explicitly instead of waiting for its scheduled flush. */
    private static final class Peer extends BedrockPeer {
        Peer(EmbeddedChannel channel){super(channel,BedrockServerSession::new);}
        void flushNow(){super.flushPacketQueue();}
    }
    private static final class Fixture implements AutoCloseable {
        final SessionState state=new SessionState();final ProtocolDefinitions definitions=new ProtocolDefinitions();
        final EmbeddedChannel channel=new EmbeddedChannel();final Peer peer;final BedrockServerSession client;
        final EntityBatchEncoder encoder;final BlockCorrectionSender sender;final AtomicBoolean available=new AtomicBoolean(true);
        final BedrockPacketCodec codec=new BedrockPacketCodec_v3();
        Fixture(boolean hash){this(hash,new NoopCompression());}
        Fixture(boolean hash,NoopCompression compression) {
            state.setProtocolVersion(2193);state.player().setProtocolVersion(2193);
            var start=new StartGamePacket();start.setBlockNetworkIdsHashed(hash);start.setPlayerPosition(Vector3f.from(8,64,8));start.setRuntimeEntityId(7);
            state.observeBackend(start);state.world().put(0,0,ac.navro.proxy.compensated.world.base.CompensatedWorld.CachedChunk.network(-64,320,24,state.world().airNetworkId()));
            channel.pipeline().addLast(CompressionCodec.NAME,new CompressionCodec(new SimpleCompressionStrategy(compression),false));
            channel.pipeline().addLast(BedrockBatchEncoder.NAME,new BedrockBatchEncoder());codec.setCodec(Bedrock_v2193.CODEC);
            definitions.configure(codec.getHelper(),false);channel.pipeline().addLast(BedrockPacketCodec.NAME,codec);
            peer=new Peer(channel);client=new BedrockServerSession(peer,0);encoder=EntityBatchEncoder.install(client,state.player().getLatencyUtil());
            state.player().setEntityBatchEncoder(encoder);
            sender=new BlockCorrectionSender(client,definitions,state.player(),available::get,state::onProxyBlockCorrection);
        }
        int id(int block){return state.world().networkIdForLegacy(block<<13);}
        int raw(int layer){return state.world().getRawBlockAt(8,64,8,layer);}
        BedrockBatchWrapper flush(){peer.flushNow();channel.checkException();return channel.readOutbound();}
        List<BedrockPacket> decode(BedrockBatchWrapper batch) {
            var frames=new ArrayList<BedrockPacket>();var raw=batch.getUncompressed().duplicate();
            while(raw.isReadable()) {
                var frame=raw.readSlice(VarInts.readUnsignedInt(raw));int id=VarInts.readUnsignedInt(frame);
                frames.add(Bedrock_v2193.CODEC.tryDecode(codec.getHelper(),frame,id));assertFalse(frame.isReadable());
            }
            return frames;
        }
        void ack(long id){var reply=new NetworkStackLatencyPacket();reply.setTimestamp(id*1_000_000L);assertTrue(NetworkLatencyPackets.onPacket(state.player(),reply));}
        @Override public void close(){available.set(false);encoder.close();channel.finishAndReleaseAll();}
    }
    private static long closing(List<BedrockPacket> packets){return ((NetworkStackLatencyPacket)packets.get(packets.size()-1)).getTimestamp();}
    private static void correction(BedrockPacket value,int network,int layer) {
        assertInstanceOf(UpdateBlockPacket.class,value);var packet=(UpdateBlockPacket)value;
        assertEquals(POS,packet.getBlockPosition());assertEquals(network,packet.getDefinition().getRuntimeId());assertEquals(layer,packet.getDataLayer());
        assertEquals(11,packet.getFlags().stream().mapToInt(f->1<<f.ordinal()).reduce(0,(a,b)->a|b));
    }
    @Test void actualSendQueuePreservesPriorRawPacketAndCommitsBothNewLayersOnlyAtTheirClosingAck() {
        for(boolean hash:new boolean[]{false,true})try(var f=new Fixture(hash)) {
            f.state.world().updateLegacyBlock(POS,0,1<<13);f.state.world().predictBlockBreak(POS);int air=f.raw(0);
            long backendCount=f.state.packetsFromBackend(),backendBlocks=f.state.backendBlockUpdatePackets();
            var original=new SetHealthPacket();original.setHealth(7);var encoded=Unpooled.buffer();
            VarInts.writeUnsignedInt(encoded,Bedrock_v2193.CODEC.getPacketDefinition(SetHealthPacket.class).getId());Bedrock_v2193.CODEC.tryEncode(f.codec.getHelper(),encoded,original);
            var differentObject=new SetHealthPacket();differentObject.setHealth(99);
            f.channel.write(BedrockPacketWrapper.create(0,0,0,differentObject,encoded));
            var earlier=new NetworkStackLatencyPacket();earlier.setTimestamp(900_000_001L);earlier.setFromServer(true);f.client.sendPacket(earlier);
            f.sender.restoreCorrectBlock(POS,f.id(1),f.id(8));
            assertEquals(air,f.raw(0));assertEquals(air,f.raw(1));assertTrue(f.state.player().getLatencyUtil().sentQueue().isEmpty());
            assertEquals(backendCount,f.state.packetsFromBackend());assertEquals(backendBlocks,f.state.backendBlockUpdatePackets());
            assertEquals(2,f.state.events().stream().filter(e->e.direction()==WireEvent.Direction.PROXY).count());
            var batch=f.flush();assertNotNull(batch);
            try {
                var frames=f.decode(batch);assertEquals(5,frames.size());assertEquals(7,((SetHealthPacket)frames.get(0)).getHealth());
                assertEquals(earlier.getTimestamp(),((NetworkStackLatencyPacket)frames.get(1)).getTimestamp());
                correction(frames.get(2),f.id(1),0);correction(frames.get(3),f.id(8),1);
                assertEquals(2,f.state.player().getLatencyUtil().sentQueue().size());assertEquals(air,f.raw(0));
                assertFalse(f.state.player().getLatencyUtil().onResponse(earlier.getTimestamp()));assertEquals(air,f.raw(0));
                f.ack(closing(frames));assertEquals(f.id(1),f.raw(0));assertEquals(f.id(8),f.raw(1));assertFalse(f.state.world().isPredictedBlockBreak(POS));
            } finally {batch.release();}
        }
    }
    @Test void singleStateOverloadUpdatesOnlyLayerZeroAndLaterLocalPredictionsSuppressOldCorrectionAcks() {
        try(var f=new Fixture(true)) {
            f.state.world().updateBlock(8,64,8,1,f.id(8));f.sender.restoreCorrectBlock(POS,f.id(1));
            f.state.world().predictLocalBlock(POS,0,3<<13);int local=f.raw(0);var first=f.flush();
            try{var frames=f.decode(first);assertEquals(2,frames.size());correction(frames.get(0),f.id(1),0);f.ack(closing(frames));}
            finally{first.release();}
            assertEquals(local,f.raw(0));assertEquals(f.id(8),f.raw(1));
            f.sender.restoreCorrectBlock(POS,f.id(1));var current=f.flush();
            try{f.ack(closing(f.decode(current)));}finally{current.release();}
            assertEquals(f.id(1),f.raw(0));assertEquals(f.id(8),f.raw(1));
        }
    }
    @Test void unavailableSessionAndNullPositionDoNotEnqueueOrRegisterCorrections() {
        try(var f=new Fixture(false)) {
            int before=f.state.events().size();f.available.set(false);f.sender.restoreCorrectBlock(POS,f.id(1),f.id(8));
            f.available.set(true);f.sender.restoreCorrectBlock(null,f.id(1),f.id(8));
            assertEquals(before,f.state.events().size());assertFalse(f.encoder.hasPending());assertNull(f.flush());
            f.available.set(false);f.channel.close();f.sender.restoreCorrectBlock(POS,f.id(1));assertFalse(f.encoder.hasPending());
        }
    }
    @Test void missingAckTransportCannotCommitBeforeThePacketIsSentAndObserverFailureDropsItsStagedTask() {
        try(var f=new Fixture(false)) {
            f.state.player().setEntityBatchEncoder(null);
            assertThrows(IllegalStateException.class,()->f.sender.restoreCorrectBlock(POS,f.id(1),f.id(8)));
            assertFalse(f.encoder.hasPending());assertNull(f.flush());f.state.player().setEntityBatchEncoder(f.encoder);
            var failed=new BlockCorrectionSender(f.client,f.definitions,f.state.player(),()->true,packet->{f.state.onProxyBlockCorrection(packet);throw new IllegalStateException("observer failed");});
            assertThrows(IllegalStateException.class,()->failed.restoreCorrectBlock(POS,f.id(1)));
            assertFalse(f.encoder.hasPending());assertTrue(f.state.player().getLatencyUtil().sentQueue().isEmpty());assertNull(f.flush());
        }
    }
    @Test void failedCompressionDoesNotPublishCorrectionAcksOrChangeTheCompensatedWorld() {
        try(var f=new Fixture(false,new NoopCompression(){
            @Override public ByteBuf encode(io.netty.channel.ChannelHandlerContext ctx,ByteBuf msg)throws Exception{throw new IOException("compression failed");}
        })) {
            int old=f.raw(0);f.sender.restoreCorrectBlock(POS,f.id(1),f.id(8));assertNull(f.flush());
            assertEquals(old,f.raw(0));assertEquals(old,f.raw(1));assertTrue(f.state.player().getLatencyUtil().sentQueue().isEmpty());assertTrue(f.encoder.hasPending());
        }
    }
    @Test void completeClickDispatcherSendsAdjacentThenClickedCorrectionsThroughRealAckPipeline() {
        for(boolean hash:new boolean[]{false,true})try(var f=new Fixture(hash)) {
            var w=f.state.world();var inventory=f.state.inventory();var player=f.state.player();
            var held=org.cloudburstmc.protocol.bedrock.data.inventory.ItemData.builder().definition(new org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition("minecraft:stone",12345,false)).count(7).build();inventory.inventoryContainer.set(0,held);
            var validator=new ac.navro.proxy.validator.inventory.ItemTransactionValidator(player,inventory);var packet=new InventoryTransactionPacket();packet.setBlockPosition(POS);packet.setItemInHand(inventory.inventoryContainer.getHeldItemData());packet.setClientInteractPrediction(org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction.PredictedResult.SUCCESS);
            var context=new ac.navro.proxy.validator.inventory.ItemTransactionValidator.ClickBlockContext(){
                public ac.navro.proxy.compensated.world.base.CompensatedWorld world(){return w;}
                public boolean validate(org.cloudburstmc.protocol.bedrock.data.inventory.ItemData p,org.cloudburstmc.protocol.bedrock.data.inventory.ItemData c){return ac.navro.proxy.validator.inventory.ItemTransactionValidator.validate(p,c);}
                public Integer resolvedFace(InventoryTransactionPacket data){return 5;}public boolean sneaking(){return false;}
                public ac.navro.proxy.validator.inventory.ItemTransactionValidator.PlacementChecks checks(){return null;}
                public ac.navro.proxy.validator.inventory.ItemTransactionValidator.ActivationObserver trapdoorSampler(){throw new AssertionError();}
                public int nativeItemId(org.cloudburstmc.protocol.bedrock.data.inventory.ItemData data){return 1;}
                public int nativeItemDamage(org.cloudburstmc.protocol.bedrock.data.inventory.ItemData data){throw new AssertionError();}
                public int nativeBlockId(org.cloudburstmc.protocol.bedrock.data.inventory.ItemData data){return 1;}
                public boolean nativeIsNull(org.cloudburstmc.protocol.bedrock.data.inventory.ItemData data){return false;}
                public boolean nativeCanBePlaced(org.cloudburstmc.protocol.bedrock.data.inventory.ItemData data){throw new AssertionError();}
                public ac.navro.proxy.data.inventory.NativeItemPlacementProperties.Properties placementProperties(org.cloudburstmc.protocol.bedrock.data.inventory.ItemData data){throw new AssertionError();}
                public org.cloudburstmc.protocol.bedrock.data.inventory.ItemData createNativeBucket(int damage){throw new AssertionError();}
                public void restoreCorrectBlock(Vector3i p){f.sender.restoreCorrectBlock(p,f.id(1),f.id(8));}
            };
            int air=w.airNetworkId();long backend=f.state.packetsFromBackend();assertFalse(validator.handleClickBlock(packet,context));assertEquals(5,player.tickSinceBlockResync);assertEquals(air,f.raw(0));assertEquals(7,inventory.inventoryContainer.getHeldItemData().getCount());assertEquals(backend,f.state.packetsFromBackend());
            var batch=f.flush();try{var frames=f.decode(batch);assertEquals(5,frames.size());for(int index=0;index<4;index++){var update=(UpdateBlockPacket)frames.get(index);assertEquals(index<2?Vector3i.from(9,64,8):POS,update.getBlockPosition());assertEquals(index%2,update.getDataLayer());assertEquals(index%2==0?f.id(1):f.id(8),update.getDefinition().getRuntimeId());}assertEquals(air,f.raw(0));
                w.predictLocalBlock(Vector3i.from(9,64,8),0,3<<13);f.ack(closing(frames));assertEquals(f.id(3),w.getRawBlockAt(9,64,8,0));assertEquals(f.id(8),w.getRawBlockAt(9,64,8,1));assertEquals(f.id(1),f.raw(0));assertEquals(f.id(8),f.raw(1));assertEquals(7,inventory.inventoryContainer.getHeldItemData().getCount());
            }finally{batch.release();}
        }
    }

}
