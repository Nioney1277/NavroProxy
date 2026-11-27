package ac.navro.proxy.prediction;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.collision.CollisionShapeEntry;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BlockRestitutionCollisionTest {
    private static NetworkBlockState block(int id) {
        return NetworkBlockStateRegistry.forProtocol(2225,false).states().values().stream()
                .filter(state->state.blockId()==id).findFirst().orElseThrow();
    }

    @Test void recordedBedWithWoolAboveKeepsNormalFallingSpeedAndDoesNotFlagTheFollowingInput() {
        var state=new SessionState();state.setProtocolVersion(2225);state.player().setProtocolVersion(2225);
        var start=new StartGamePacket();start.setRuntimeEntityId(7);start.setUniqueEntityId(70);
        start.setPlayerPosition(Vector3f.from(305.3F,5.0244246F,252.5642F));state.observeBackend(start);
        var world=state.world();world.put(19,15,CompensatedWorld.CachedChunk.network(-64,320,24,world.airNetworkId()));
        world.updateBlock(304,4,252,0,18896);world.updateBlock(304,5,252,0,13455);
        world.updateBlock(305,3,252,0,block(1).networkId());
        state.observeClient(new SetLocalPlayerAsInitializedPacket());
        state.player().inLoadingScreen=false;state.player().sinceLoadingScreen=20;
        state.player().commitInputTick(4591);
        state.player().setCollisionResult(true,false,false);
        state.player().setVelocity(new Vec3(0,-.22768848F,-.027137278F));
        state.setDetectionEnabled(true);state.authInputPackets().reset();
        var alerts=new ArrayList<String>();state.player().setAlertSink(alerts::add);
        var corrections=AuthInputPacketsTest.transport(state);
        float[][] recorded={
                {4.7967362F,252.53725F,-.3015347F,-.024526918F},
                {4.4952016F,252.51291F,-.37390402F,-.02215149F}};
        for(int index=0;index<recorded.length;index++){
            float[] frame=recorded[index];
            var input=AuthInputPacketsTest.input(4605+index,305.3F,frame[0],frame[1],new Vec3(0,frame[2],frame[3]));
            input.setRotation(Vector3f.from(41.605682F,89.4603F,89.4603F));
            input.setMotion(Vector2f.from(0,1));input.setRawMoveVector(Vector2f.from(0,1));
            input.getInputData().addAll(List.of(PlayerAuthInputData.JUMP_DOWN,PlayerAuthInputData.JUMPING,
                    PlayerAuthInputData.UP,PlayerAuthInputData.WANT_UP,PlayerAuthInputData.BLOCK_BREAKING_DELAY_ENABLED,
                    PlayerAuthInputData.HORIZONTAL_COLLISION,PlayerAuthInputData.JUMP_CURRENT_RAW));
            state.observeClient(input);
            var result=state.authInputPackets().lastResult();assertNotNull(result);
            assertTrue(result.rawOffset()<.0001F,result.toString());
            assertEquals(frame[2],state.player().velocity().y,.000001F);
            assertFalse(state.player().verticalCollision());
        }
        assertTrue(alerts.isEmpty(),alerts.toString());assertTrue(corrections.isEmpty(),corrections.toString());
    }

    @Test void horizontalContactBesideABedOrSlimeDoesNotCreateBlockRestitutionOrGravityCorrection() {

        for(int id:new int[]{26,165})for(boolean x:new boolean[]{false,true})for(boolean z:new boolean[]{false,true}){
            var surface=new CollisionShapeEntry(new Box(304,4,252,305,4.5625F,253),block(id));
            var box=new Box(305,4.7967362F,252.23724F,305.59998F,6.596736F,252.83725F);
            var velocity=new Vec3(-.01959913F,-.22768848F,-.026952656F);
            var result=StickyBlockPhysics.restitute(box,List.of(surface),velocity,velocity,
                    new Vec3(x?0:velocity.x,velocity.y,z?0:velocity.z),x,false,z,false);
            assertEquals(velocity.y,result.velocity().y);
            assertNull(result.gravityCorrection());
            assertEquals(-.3015347F,(result.velocity().y+StickyBlockPhysics.resolveGravityDelta(result.gravityCorrection(),-.08F))*.98F);
        }
    }

    @Test void realVerticalBedAndSlimeLandingsRetainTheirNativeCoefficients() {

        for(int id:new int[]{26,165}){
            float coefficient=id==26?.75F:1;
            var surface=new CollisionShapeEntry(new Box(0,0,0,1,.5625F,1),block(id));
            var actor=new Box(.2F,.5625F,.2F,.8F,2.3625F,.8F);
            var down=new Vec3(0,-.4F,0);
            var result=StickyBlockPhysics.restitute(actor,List.of(surface),down,down,new Vec3(0,-.1F,0),false,true,false,false);
            assertEquals(.4F*coefficient,result.velocity().y);
            assertNotNull(result.gravityCorrection());
            var sneaking=StickyBlockPhysics.restitute(actor,List.of(surface),down,down,new Vec3(0,-.1F,0),false,true,false,true);
            assertEquals(0,sneaking.velocity().y);assertNull(sneaking.gravityCorrection());
            var slow=new Vec3(0,-.08F,0);
            var smallFall=StickyBlockPhysics.restitute(actor,List.of(surface),slow,slow,Vec3.ZERO,false,true,false,false);
            assertEquals(0,smallFall.velocity().y);assertNull(smallFall.gravityCorrection());
        }
    }
}
