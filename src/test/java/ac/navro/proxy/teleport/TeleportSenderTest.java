package ac.navro.proxy.teleport;

import ac.navro.proxy.*;
import ac.navro.proxy.packets.other.NetworkLatencyPackets;
import ac.navro.proxy.util.EntityBatchEncoder;
import ac.navro.anticheat.data.*;
import ac.navro.anticheat.util.math.*;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.*;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.netty.*;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchEncoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.*;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TeleportSenderTest {
    static class Peer extends BedrockPeer{Peer(EmbeddedChannel c){super(c,BedrockServerSession::new);}void flushNow(){super.flushPacketQueue();}}
    static class Fixture implements AutoCloseable{
        final SessionState state;final EmbeddedChannel channel=new EmbeddedChannel();final Peer peer;
        final BedrockServerSession client;final EntityBatchEncoder encoder;final TeleportSender sender;final AtomicBoolean available=new AtomicBoolean(true);
        Fixture(){this(new SessionState());}
        Fixture(SessionState state){
            this.state=state;
            if(state.player().runtimeId()<0){var start=new StartGamePacket();start.setRuntimeEntityId(7);start.setUniqueEntityId(9002);start.setPlayerPosition(Vector3f.from(8,64,8));state.observeBackend(start);}state.player().setProtocolVersion(2193);
            channel.pipeline().addLast(CompressionCodec.NAME,new CompressionCodec(new SimpleCompressionStrategy(new NoopCompression()),false));
            channel.pipeline().addLast(BedrockBatchEncoder.NAME,new BedrockBatchEncoder());var codec=new BedrockPacketCodec_v3();codec.setCodec(Bedrock_v2193.CODEC);channel.pipeline().addLast(BedrockPacketCodec.NAME,codec);
            peer=new Peer(channel);client=new BedrockServerSession(peer,0);encoder=EntityBatchEncoder.install(client,state.player().getLatencyUtil());state.player().setEntityBatchEncoder(encoder);
            sender=new TeleportSender(client,state,available::get);state.setTeleportTransport(sender::send);state.setSoftCorrectionTransport(sender::send);state.player().setTimerTeleport(sender::teleportToLastValid);
        }
        List<BedrockPacket> flush(){channel.runPendingTasks();peer.flushNow();channel.checkException();BedrockBatchWrapper batch=channel.readOutbound();assertNotNull(batch);var result=new ArrayList<BedrockPacket>();
            try{var b=batch.getUncompressed().duplicate();while(b.isReadable()){var frame=b.readSlice(VarInts.readUnsignedInt(b));int id=VarInts.readUnsignedInt(frame);result.add(Bedrock_v2193.CODEC.tryDecode(Bedrock_v2193.CODEC.createHelper(),frame,id));assertEquals(0,frame.readableBytes());}}finally{batch.release();}return result;
        }
        void ack(List<BedrockPacket> packets){var marker=(NetworkStackLatencyPacket)packets.get(packets.size()-1);var response=new NetworkStackLatencyPacket();response.setTimestamp(marker.getTimestamp()*1_000_000L);assertTrue(NetworkLatencyPackets.onPacket(state.player(),response));}
        void input(long tick,Vector3f wire,boolean handled){var p=new PlayerAuthInputPacket();p.setTick(tick);p.setPosition(wire);p.setRotation(Vector3f.ZERO);p.setDelta(Vector3f.ZERO);if(handled)p.getInputData().add(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.HANDLE_TELEPORT);state.observeClient(p);}
        @Override public void close(){encoder.close();channel.finishAndReleaseAll();}
    }
    @Test void timerHookSendsOneOriginalTeleportAndWaitsForClosingAckThenClientConfirmation(){
        try(var f=new Fixture()){
            f.state.player().requestTeleportToLastValid();assertTrue(f.state.teleports().isTeleporting());assertFalse(f.state.teleports().getQueuedTeleports().peek().isAccepted());
            f.state.player().requestTeleportToLastValid();var packets=f.flush();assertEquals(2,packets.size());var move=(MovePlayerPacket)packets.get(0);assertEquals(7,move.getRuntimeEntityId());assertEquals(MovePlayerPacket.Mode.TELEPORT,move.getMode());assertEquals(MovePlayerPacket.TeleportationCause.BEHAVIOR,move.getTeleportationCause());assertFalse(move.isOnGround());assertEquals(64+1.62001F,move.getPosition().getY());
            f.input(1,move.getPosition(),true);assertTrue(f.state.teleports().isTeleporting());f.ack(packets);assertTrue(f.state.teleports().getQueuedTeleports().peek().isAccepted());assertTrue(f.state.teleports().isTeleporting());
            f.input(2,move.getPosition(),true);assertFalse(f.state.teleports().isTeleporting());assertEquals(64,f.state.player().position().y);assertEquals(Vec3.ZERO.x,f.state.player().velocity().x);assertEquals(0,f.state.player().velocity().y);
        }
    }
    @Test void acknowledgedMismatchResetsToTargetAndReissuesOnlyTheLastOutstandingTeleport(){
        try(var f=new Fixture()){
            f.state.player().requestTeleportToLastValid();var first=f.flush();var target=((MovePlayerPacket)first.get(0)).getPosition();f.ack(first);f.input(1,Vector3f.from(99,99,99),false);
            assertEquals(1,f.state.teleports().getQueuedTeleports().size());assertFalse(f.state.teleports().getQueuedTeleports().peek().isAccepted());var again=f.flush();assertEquals(target,((MovePlayerPacket)again.get(0)).getPosition());assertEquals(64,f.state.player().position().y);
            f.ack(again);f.input(2,target,true);assertFalse(f.state.teleports().isTeleporting());
        }
    }
    @Test void normalMoveIsReencodedAsTeleportAndResetUsesNearTargetWithoutHandleFlag(){
        try(var f=new Fixture()){
            var normal=new MovePlayerPacket();normal.setRuntimeEntityId(7);normal.setMode(MovePlayerPacket.Mode.NORMAL);normal.setPosition(Vector3f.from(8,65.62001F,8));normal.setRotation(Vector3f.ZERO);f.state.observeBackend(normal);assertEquals(MovePlayerPacket.Mode.TELEPORT,normal.getMode());f.client.sendPacket(normal);var packets=f.flush();f.ack(packets);f.input(1,normal.getPosition(),true);assertFalse(f.state.teleports().isTeleporting());
            var reset=new MovePlayerPacket();reset.setRuntimeEntityId(7);reset.setMode(MovePlayerPacket.Mode.RESPAWN);reset.setPosition(Vector3f.from(10,65.62001F,10));reset.setRotation(Vector3f.ZERO);f.state.observeBackend(reset);f.client.sendPacket(reset);var second=f.flush();f.ack(second);f.input(2,reset.getPosition().add(0.125F,0,0),false);assertFalse(f.state.teleports().isTeleporting());assertEquals(10.125F,f.state.player().position().x);
        }
    }
    @Test void rawInputCannotReplaceTheValidatedAnchorAndUnavailableTransportDoesNotQueue(){
        try(var f=new Fixture()){
            var original=f.state.teleports().getLastKnowValid().clone();f.input(1,Vector3f.from(1000,1000,1000),false);assertEquals(original.x,f.state.teleports().getLastKnowValid().x);assertEquals(original.y,f.state.teleports().getLastKnowValid().y);
            var box=new Box(20,70,30,20.58F,71.49F,30.58F);var snapshot=new MovementAabbSnapshot(EntityDimensions.fixed(0.58F,1.49F),new Vec3(20.29F,70,30.29F),box,9);f.state.acceptValidatedMovementBaseline(snapshot,1.62001F);assertEquals(box.feetCenter().x,f.state.teleports().getLastKnowValid().x);assertEquals(71.62001F,f.state.teleports().getLastKnowValid().y);
            f.available.set(false);f.state.player().requestTeleportToLastValid();assertFalse(f.state.teleports().isTeleporting());assertFalse(f.encoder.hasPending());
        }
    }
}
