package ac.navro.proxy.packets.server;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.ProtocolDefinitions;
import ac.navro.proxy.packets.other.NetworkLatencyPackets;
import ac.navro.proxy.util.EntityBatchEncoder;
import ac.navro.proxy.util.block.BlockUtil;
import io.netty.buffer.*;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.*;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.netty.*;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchEncoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.*;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.util.OptionalBoolean;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GameTypePacketLifecycleTest {
    private static final class Fixture implements AutoCloseable {
        final SessionState state=new SessionState();final EmbeddedChannel channel=new EmbeddedChannel();
        final EntityBatchEncoder encoder;final BedrockPacketCodec codec=new BedrockPacketCodec_v3();
        Fixture() {
            state.setProtocolVersion(2193);state.player().setProtocolVersion(2193);var identity=new StartGamePacket();identity.setRuntimeEntityId(7);identity.setUniqueEntityId(99);state.player().apply(identity);
            channel.pipeline().addLast(CompressionCodec.NAME,new CompressionCodec(new SimpleCompressionStrategy(new NoopCompression()),false));
            channel.pipeline().addLast(BedrockBatchEncoder.NAME,new BedrockBatchEncoder());codec.setCodec(Bedrock_v2193.CODEC);new ProtocolDefinitions().configure(codec.getHelper(),false);
            channel.pipeline().addLast(BedrockPacketCodec.NAME,codec);var session=new BedrockServerSession(new BedrockPeer(channel,BedrockServerSession::new),0);
            encoder=EntityBatchEncoder.install(session,state.player().getLatencyUtil());state.player().setEntityBatchEncoder(encoder);
        }
        void send(BedrockPacket packet){state.observeBackend(packet);channel.write(BedrockPacketWrapper.create(0,0,0,packet,null));}
        long flush() {
            channel.flush();channel.checkException();BedrockBatchWrapper batch=channel.readOutbound();assertNotNull(batch);
            try {
                var raw=batch.getUncompressed().duplicate();long marker=-1;
                while(raw.isReadable()) {
                    var frame=raw.readSlice(VarInts.readUnsignedInt(raw));int id=VarInts.readUnsignedInt(frame);
                    var decoded=Bedrock_v2193.CODEC.tryDecode(codec.getHelper(),frame,id);assertFalse(frame.isReadable());
                    if(decoded instanceof NetworkStackLatencyPacket latency)marker=latency.getTimestamp();
                }
                assertTrue(marker>0);return marker;
            } finally {batch.release();}
        }
        void ack(long marker){var reply=new NetworkStackLatencyPacket();reply.setTimestamp(marker*1_000_000L);assertTrue(NetworkLatencyPackets.onPacket(state.player(),reply));}
        @Override public void close(){encoder.close();channel.finishAndReleaseAll();}
    }
    private static StartGamePacket start(org.cloudburstmc.protocol.bedrock.data.GameType mode)throws Exception {
        var packet=new StartGamePacket();
        // Valid dummy level settings for a real codec round-trip; no production state is inferred from these values.
        for(var field:StartGamePacket.class.getDeclaredFields()) {
            if(java.lang.reflect.Modifier.isStatic(field.getModifiers())||java.lang.reflect.Modifier.isFinal(field.getModifiers()))continue;
            field.setAccessible(true);if(field.get(packet)!=null)continue;Class<?> type=field.getType();
            if(type==String.class||type==CharSequence.class)field.set(packet,"");
            else if(type.isEnum())field.set(packet,type.getEnumConstants()[0]);
            else if(type==Vector3f.class)field.set(packet,Vector3f.ZERO);
            else if(type==Vector2f.class)field.set(packet,Vector2f.ZERO);
            else if(type==Vector3i.class)field.set(packet,Vector3i.ZERO);
            else if(type==NbtMap.class)field.set(packet,NbtMap.EMPTY);
            else if(type==UUID.class)field.set(packet,new UUID(0,0));
            else if(type==OptionalBoolean.class)field.set(packet,OptionalBoolean.empty());
        }
        packet.setPlayerGameType(mode);packet.setRuntimeEntityId(7);packet.setUniqueEntityId(99);return packet;
    }
    @Test void startGameAndLaterModePacketsWaitForTheirOwnActualClosingMarkers()throws Exception {
        try(var f=new Fixture()) {
            assertEquals(GameType.DEFAULT,f.state.player().gameType());
            f.send(start(org.cloudburstmc.protocol.bedrock.data.GameType.CREATIVE));assertEquals(GameType.DEFAULT,f.state.player().gameType());long first=f.flush();
            var set=new SetPlayerGameTypePacket();set.setGamemode(0);f.send(set);long second=f.flush();
            assertEquals(GameType.DEFAULT,f.state.player().gameType());f.ack(first);assertEquals(GameType.CREATIVE,f.state.player().gameType());
            f.ack(second);assertEquals(GameType.SURVIVAL,f.state.player().gameType());
            var wrong=new UpdatePlayerGameTypePacket();wrong.setEntityId(99);wrong.setGameType(org.cloudburstmc.protocol.bedrock.data.GameType.CREATIVE);f.send(wrong);
            f.ack(f.flush());assertEquals(GameType.SURVIVAL,f.state.player().gameType());
            var right=new UpdatePlayerGameTypePacket();right.setEntityId(7);right.setGameType(org.cloudburstmc.protocol.bedrock.data.GameType.SPECTATOR);f.send(right);
            long own=f.flush();assertEquals(GameType.SURVIVAL,f.state.player().gameType());f.ack(own);assertEquals(GameType.SPECTATOR,f.state.player().gameType());
        }
    }
    @Test void acknowledgedCreativeRuleUsesCanonicalModeAndRemainsLiveAcrossMovementReplay() {
        try(var f=new Fixture()) {
            var frame=f.state.player().snapshot(null);var set=new SetPlayerGameTypePacket();set.setGamemode(1);f.send(set);long marker=f.flush();
            assertFalse(BlockUtil.determineCanBreak(f.state.player().gameType(),ac.navro.proxy.data.block.NativeBlockProperties.forBlock(7,0)));
            f.ack(marker);assertTrue(BlockUtil.determineCanBreak(f.state.player().gameType(),ac.navro.proxy.data.block.NativeBlockProperties.forBlock(7,0)));
            f.state.player().restore(frame,null);assertEquals(GameType.CREATIVE,f.state.player().gameType());
        }
    }
    @Test void invalidSetConversionAndDiagnosticEventNamesFollowTheOriginalAcknowledgmentOrder() {
        try(var f=new Fixture()) {
            var trace=new ArrayList<String>();f.state.player().setGameTypeEventSink((name,values)->trace.add(name+Arrays.toString(values)));
            var invalid=new SetPlayerGameTypePacket();invalid.setGamemode(7);f.send(invalid);long marker=f.flush();
            assertEquals(List.of("gameTypeUpdateQueued[gameMode, 7]"),trace);f.ack(marker);assertEquals(GameType.DEFAULT,f.state.player().gameType());assertEquals(1,trace.size());
            var set=new SetPlayerGameTypePacket();set.setGamemode(4);f.send(set);f.ack(f.flush());assertEquals(GameType.CREATIVE_VIEWER,f.state.player().gameType());
            assertEquals("gameTypeUpdateAcknowledged[gameMode, 4]",trace.get(trace.size()-1));
            var update=new UpdatePlayerGameTypePacket();update.setEntityId(7);update.setGameType(org.cloudburstmc.protocol.bedrock.data.GameType.ADVENTURE);f.send(update);f.ack(f.flush());
            assertEquals("gameTypeUpdateQueued[gameMode, ADVENTURE, source, update-player-game-type]",trace.get(trace.size()-1));
        }
    }
}
