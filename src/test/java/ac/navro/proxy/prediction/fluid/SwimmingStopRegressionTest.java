package ac.navro.proxy.prediction.fluid;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class SwimmingStopRegressionTest {
    @Test void releasingMovementWhileStoppingSwimmingMustNotCreateForwardAcceleration() {
        int water=NetworkBlockStateRegistry.forProtocol(2193,false).states().values().stream()
                .filter(b->b.is("minecraft:water")&&b.blockData()==0).findFirst().orElseThrow().networkId();
        for(float yaw:new float[]{0,90,-175.05063F,137.64517F})for(Vec3 seed:new Vec3[]{Vec3.ZERO,new Vec3(.026455859F,0,-.06517025F)}) {
            var s=AuthInputPacketsTest.session();var alerts=new ArrayList<String>();s.player().setAlertSink(alerts::add);
            var corrections=AuthInputPacketsTest.transport(s);
            for(int x=6;x<=10;x++)for(int y=64;y<=66;y++)for(int z=6;z<=10;z++)s.world().updateBlock(x,y,z,0,water);
            s.player().setPredictionSprintingAction(true);s.player().applySwimmingAction(true);s.player().setVelocity(seed.clone());
            float x=8+seed.x,z=8+seed.z;var velocity=new Vec3(seed.x*.9F,-.005F,seed.z*.9F);
            var stop=AuthInputPacketsTest.input(100,x,64,z,velocity);stop.setRotation(Vector3f.from(23.621643F,yaw,yaw));
            stop.getInputData().add(PlayerAuthInputData.STOP_SWIMMING);
            assertTrue(s.receiveClient(stop,8500),"STOP_SWIMMING yaw="+yaw+", alerts="+alerts);
            assertTrue(s.authInputPackets().lastResult().rawOffset()<1e-4F);
            assertFalse(s.player().clientSwimming());assertFalse(s.player().predictionSwimming());
            assertTrue(s.player().predictionSprinting(),"preserve the authoritative sprint flag while its stop edge is pending");
            assertEquals(0,stop.getMotion().lengthSquared());assertTrue(corrections.isEmpty());assertTrue(alerts.isEmpty());
            var next=AuthInputPacketsTest.input(101,x+velocity.x,64,z+velocity.z,new Vec3(velocity.x*.9F,-.005F,velocity.z*.9F));
            next.setRotation(Vector3f.from(23.621643F,yaw,yaw));next.getInputData().add(PlayerAuthInputData.VERTICAL_COLLISION);
            assertTrue(s.receiveClient(next,8500),alerts.toString());assertTrue(s.authInputPackets().lastResult().rawOffset()<1e-4F);
            assertTrue(corrections.isEmpty());assertTrue(alerts.isEmpty());
        }
    }
    @Test void stoppingSwimmingKeepsLateralAndBackwardInputsInsteadOfReplacingThemWithForward() {
        int water=NetworkBlockStateRegistry.forProtocol(2193,false).states().values().stream()
                .filter(b->b.is("minecraft:water")&&b.blockData()==0).findFirst().orElseThrow().networkId();
        float[][] directions={{-1,0},{1,0},{0,-1}};
        for(float[] move:directions) {
            var s=AuthInputPacketsTest.session();var alerts=new ArrayList<String>();s.player().setAlertSink(alerts::add);
            var corrections=AuthInputPacketsTest.transport(s);
            for(int x=6;x<=10;x++)for(int y=64;y<=66;y++)for(int z=6;z<=10;z++)s.world().updateBlock(x,y,z,0,water);
            s.player().setPredictionSprintingAction(true);s.player().applySwimmingAction(true);s.player().setVelocity(Vec3.ZERO.clone());
            float dx=move[0]*.0196F,dz=move[1]*.0196F;
            var stop=AuthInputPacketsTest.input(100,8+dx,64,8+dz,new Vec3(dx*.9F,-.005F,dz*.9F));
            stop.setMotion(org.cloudburstmc.math.vector.Vector2f.from(move[0],move[1]));
            stop.setRawMoveVector(org.cloudburstmc.math.vector.Vector2f.from(move[0],move[1]));
            stop.getInputData().add(PlayerAuthInputData.STOP_SWIMMING);
            assertTrue(s.receiveClient(stop,8500),"direction="+java.util.Arrays.toString(move)+", alerts="+alerts);
            assertTrue(s.authInputPackets().lastResult().rawOffset()<1e-4F);
            assertTrue(corrections.isEmpty());assertTrue(alerts.isEmpty());
        }
    }
}
