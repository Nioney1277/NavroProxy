package ac.navro.proxy.packets.input;

import ac.navro.anticheat.util.math.*;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.proxy.teleport.data.CorrectionResetData;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class AuthInputPacketsTest {
    public static SessionState session(){ return session(ac.navro.proxy.protocol.BackendType.NAVRO); }
    public static SessionState session(ac.navro.proxy.protocol.BackendType backendType){
        var s=new SessionState(backendType);s.setProtocolVersion(2193);s.player().setProtocolVersion(2193);
        var start=new StartGamePacket();start.setRuntimeEntityId(7);start.setUniqueEntityId(70);start.setPlayerPosition(Vector3f.from(8,64,8));s.observeBackend(start);
        int stone=NetworkBlockStateRegistry.forProtocol(2193,false).states().values().stream().filter(v->v.is("minecraft:stone")).findFirst().orElseThrow().networkId();
        for(int cx=-1;cx<=1;cx++)for(int cz=-1;cz<=1;cz++){
            s.world().put(cx,cz,CompensatedWorld.CachedChunk.network(-64,320,24,s.world().airNetworkId()));
            for(int x=0;x<16;x++)for(int z=0;z<16;z++)s.world().updateBlock((cx<<4)+x,63,(cz<<4)+z,0,stone);
        }
        if (backendType == ac.navro.proxy.protocol.BackendType.BDS) {
            var ready = new RespawnPacket(); ready.setState(RespawnPacket.State.SERVER_READY);
            ready.setPosition(Vector3f.from(8,65.62001F,8));
            s.player().getLatencyUtil().queue(700,true); s.observeBackend(ready);
            s.player().getLatencyUtil().onResponse(700);
        }
        s.observeClient(new SetLocalPlayerAsInitializedPacket());s.player().inLoadingScreen=false;s.player().sinceLoadingScreen=20;
        s.player().setCollisionResult(false,false,true);s.setDetectionEnabled(true);
        return s;
    }
    public static PlayerAuthInputPacket input(long tick,float x,float y,float z,Vec3 delta){
        var p=new PlayerAuthInputPacket();p.setTick(tick);p.setPosition(Vector3f.from(x,y+1.62001F,z));p.setRotation(Vector3f.ZERO);p.setDelta(delta.toVector3f());p.setMotion(Vector2f.ZERO);p.setRawMoveVector(Vector2f.ZERO);p.setInputMode(InputMode.MOUSE);return p;
    }
    public static void vector(Vec3 expected,Vec3 value){assertEquals(expected.x,value.x);assertEquals(expected.y,value.y);assertEquals(expected.z,value.z);}
    public static List<MovePlayerPacket> transport(SessionState s){
        var moves=new ArrayList<MovePlayerPacket>();s.setTeleportTransport(p->{s.player().getLatencyUtil().queue(1000L+moves.size(),true);s.onProxyTeleport(p);moves.add(p);return true;});
        s.player().setTimerTeleport(()->s.teleports().teleport(s.teleports().getLastKnowValid()));return moves;
    }
    @Test void canonicalTravelCollidesBeforeGravityAndMaintainsGroundOverConsecutiveInputs(){
        var s=session();var alerts=new ArrayList<String>();s.player().setAlertSink(alerts::add);
        for(int tick=10;tick<70;tick++){
            var packet=input(tick,8,64,8,new Vec3(0,-0.0784F,0));s.observeClient(packet);
            assertEquals(64,s.player().position().y);assertTrue(s.player().onGround());assertEquals(-0.0784F,s.player().velocity().y);
            assertEquals(64+1.62001F,s.teleports().getLastKnowValid().y);assertFalse(s.teleports().isTeleporting());
            assertEquals(-0.0784F,packet.getDelta().getY());assertTrue(s.authInputPackets().lastResult().rawOffset()<1e-4F);
        }
        assertTrue(alerts.isEmpty(),alerts.toString());
        assertEquals(20,s.tickSnapshots().size());
    }
    @Test void timerUsesAdvancedCanonicalAnchorRatherThanSpawnOrSpoofedInput(){
        var s=session();var moves=transport(s);
        float moved=8+0.098F;
        var p=input(10,8,64,moved,new Vec3(0,-0.0784F,0.098F*0.54600006F));p.setMotion(Vector2f.from(0,1));p.setRawMoveVector(Vector2f.from(0,1));s.observeClient(p);
        assertTrue(moves.isEmpty());assertEquals(moved,s.teleports().getLastKnowValid().z);
        s.player().requestTeleportToLastValid();assertEquals(1,moves.size());assertEquals(moved,moves.get(0).getPosition().getZ());
        var anchor=s.teleports().getLastKnowValid().clone();s.observeClient(input(11,1000,1000,1000,Vec3.ZERO));vector(anchor,s.teleports().getLastKnowValid());
    }
    @Test void predictionFailureKeepsCanonicalAnchorAndHardResetNeedsTargetConfirmation(){
        var s=session();var moves=transport(s);var alerts=new ArrayList<String>();s.player().setAlertSink(alerts::add);
        s.observeClient(input(10,80,64,8,Vec3.ZERO));assertEquals(1,moves.size());assertEquals(8,moves.get(0).getPosition().getX());
        var reset=assertInstanceOf(CorrectionResetData.class,s.teleports().getQueuedTeleports().peek());assertTrue(s.authInputPackets().lastResult().rawOffset()>70);
        assertTrue(alerts.stream().anyMatch(a->a.contains("Horizonal")),alerts.toString());
        s.observeClient(input(11,80,64,8,Vec3.ZERO));assertSame(reset,s.teleports().getQueuedTeleports().peek());
        var confirmation=input(12,8,64,8,Vec3.ZERO);confirmation.setPosition(moves.get(0).getPosition());confirmation.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);

        s.observeClient(confirmation);assertFalse(s.teleports().isTeleporting());assertEquals(8,s.player().position().x);assertEquals(64,s.player().position().y);assertEquals(0,s.player().velocity().y);
    }
    @Test void externalVelocityOnlyBecomesCandidateAfterItsOwnAcknowledgment(){
        var s=session();s.player().getLatencyUtil().queue(90,true);
        var m=new SetEntityMotionPacket();m.setRuntimeEntityId(7);m.setMotion(Vector3f.from(.4,.3,0));m.setTick(99);s.observeBackend(m);
        assertEquals(0,m.getTick());assertNull(s.authInputPackets().context().certainVelocity);assertEquals(0,s.player().velocity().x);
        assertTrue(s.player().getLatencyUtil().onResponse(90));assertNotNull(s.authInputPackets().context().certainVelocity);
        vector(new Vec3(.4,.3,0),s.authInputPackets().context().certainVelocity.getVelocity());
    }
    @Test void rewindCarriesMotionAndConsumesOnlyTheIncludedExternalImpulse(){
        var s=session();var moves=transport(s);var motion=new ArrayList<SetEntityMotionPacket>();s.setPredictionMotionTransport(p->{motion.add(p);return true;});
        s.observeClient(input(10,80,64,8,Vec3.ZERO));assertEquals(1,motion.size());assertEquals(-.0784F,motion.get(0).getMotion().getY());
        var newer=new SetEntityMotionPacket();newer.setRuntimeEntityId(7);newer.setMotion(Vector3f.from(.6,.2,0));s.observeBackend(newer);s.player().getLatencyUtil().onOrderedResponse();var expected=s.authInputPackets().context().certainVelocity;
        var c=input(11,8,64,8,Vec3.ZERO);c.setPosition(moves.get(0).getPosition());c.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);s.observeClient(c);
        assertEquals(-.0784F,s.player().velocity().y);assertSame(expected,s.authInputPackets().context().certainVelocity);
    }
    @Test void inspectionAndLoadingDoNotTurnRawInputIntoAValidatedAnchor(){
        var s=session();s.setDetectionEnabled(false);var anchor=s.teleports().getLastKnowValid().clone();s.observeClient(input(10,1000,1000,1000,Vec3.ZERO));vector(anchor,s.teleports().getLastKnowValid());
        s=session();s.player().inLoadingScreen=true;anchor=s.teleports().getLastKnowValid().clone();s.observeClient(input(10,1000,1000,1000,Vec3.ZERO));vector(anchor,s.teleports().getLastKnowValid());
    }
    @Test void ordinaryTeleportInvalidatesEarlierMotionAcknowledgments(){
        var s=session();var moves=transport(s);s.player().getLatencyUtil().queue(90,true);
        var motion=new SetEntityMotionPacket();motion.setRuntimeEntityId(7);motion.setMotion(Vector3f.from(.7,.3,0));s.observeBackend(motion);
        s.player().requestTeleportToLastValid();assertEquals(1,moves.size());
        s.player().getLatencyUtil().onResponse(90);assertNull(s.authInputPackets().context().certainVelocity);
    }
    @Test void inputOwnsOneCollisionBeginAndTheCommittedMovementVector(){
        var s=session();var p=input(10,8,64,8.098F,new Vec3(0,-.0784F,.098F*.54600006F));p.setMotion(Vector2f.from(0,1));p.setRawMoveVector(Vector2f.from(0,1));s.observeClient(p);
        assertTrue(s.authInputPackets().context().afterCollision.z>0.09F);assertEquals(s.authInputPackets().context().afterCollision.z,s.player().afterCollision().z);
        assertEquals(s.player().position().z,s.player().previousPosition().z);
    }
    @Test void waterAndLavaRunTheirOriginalPostCollisionGravityStages(){
        for(String material:List.of("minecraft:water","minecraft:lava")){
            var s=session();int liquid=NetworkBlockStateRegistry.forProtocol(2193,false).states().values().stream().filter(b->b.is(material)&&b.blockData()==0).findFirst().orElseThrow().networkId();
            for(int x=7;x<=9;x++)for(int y=64;y<=66;y++)for(int z=7;z<=9;z++)s.world().updateBlock(x,y,z,0,liquid);
            float expected=material.endsWith(":water")?-.005F:-.02F;
            s.observeClient(input(10,8,64,8,new Vec3(0,expected,0)));
            assertEquals(64,s.player().position().y);assertEquals(expected,s.player().velocity().y);assertTrue(s.authInputPackets().lastResult().rawOffset()<1e-4F);
        }
    }
    @Test void jumpingUsesGroundImpulseBeforeCollisionAndGravityAfterCollision(){
        var s=session();var p=input(10,8,64+.42F,8,new Vec3(0,(.42F-.08F)*.98F,0));p.getInputData().add(PlayerAuthInputData.START_JUMPING);s.observeClient(p);
        assertEquals(64+.42F,s.player().position().y);assertEquals((.42F-.08F)*.98F,s.player().velocity().y);assertFalse(s.player().onGround());assertTrue(s.authInputPackets().lastResult().rawOffset()<1e-4F);
    }
    @Test void nativeSpinAttackFlagBindsToSdkDamageNearbyMobsWithoutChangingTheWireBit(){
        var s=session();var p=s.authInputPackets().context();p.begin();p.getFlagTracker().set("SPIN_ATTACK",true);
        assertTrue(s.player().predictionFlag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.DAMAGE_NEARBY_MOBS));
        p.stopRiptide();assertFalse(s.player().predictionFlag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.DAMAGE_NEARBY_MOBS));
    }
    @Test void glideBoostUsesClosingAckAndPreservesTheNativeInfiniteDurationBranch(){
        var s=session();s.player().getLatencyUtil().queue(90,true);var effect=new MovementEffectPacket();effect.setEntityRuntimeId(7);effect.setEffectType(MovementEffectType.GLIDE_BOOST);effect.setDuration(Integer.MAX_VALUE);s.observeBackend(effect);
        assertEquals(Integer.MIN_VALUE,effect.getTick());assertEquals(0,s.player().glideBoostTicks());s.player().getLatencyUtil().onResponse(90);assertEquals(1,s.player().glideBoostTicks());
    }
    @Test void failedCorrectionTransportDoesNotFreezePredictionOrConsumeExternalMotion(){
        var s=session();s.observeClient(input(10,80,64,8,Vec3.ZERO));assertFalse(s.teleports().isTeleporting());assertEquals(8,s.teleports().getLastKnowValid().x);
        assertTrue(s.player().pendingCorrectionRequests()>0);
    }

    @Test void partialColumnAvailabilityUsesTheCanonicalBodyAndSupportingSection(){
        var s=session();var world=s.world();world.put(0,0,CompensatedWorld.CachedChunk.partialNetwork(-64,320,24,world.airNetworkId()));
        var p=s.authInputPackets().context();p.begin();int[][] empty=new int[2][4096];for(int[] layer:empty)java.util.Arrays.fill(layer,world.airNetworkId());
        world.applyAcknowledgedSection(0,4,0,empty,0);assertFalse(p.compensatedWorld.isChunkLoadedAt(8,8));
        world.applyAcknowledgedSection(0,3,0,empty,0);assertTrue(p.compensatedWorld.isChunkLoadedAt(8,8));assertFalse(world.isSectionKnown(8,90,8));
    }
}
