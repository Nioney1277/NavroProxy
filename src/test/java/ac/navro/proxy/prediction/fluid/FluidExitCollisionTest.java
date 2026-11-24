package ac.navro.proxy.prediction.fluid;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import ac.navro.proxy.prediction.PredictionRunner;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Constructed geometry at the production exit-query and travel boundaries.
 * Contract: NavroPlayer.doesNotCollide -> CompensatedWorldImpl.noCollision uses
 * intersecting nearby actors and an ordinary block query, followed by the fluid
 * veto. LivingTicker.travelInFluid sets Y to 0.3F only for a clear exit.
 * These scenarios do not claim to replay legitimate client swimming.
 */
class FluidExitCollisionTest {
    private static int block(String name) {
        return NetworkBlockStateRegistry.forProtocol(2193, false).states().values().stream()
                .filter(b -> b.is(name) && b.blockData() == 0).findFirst().orElseThrow().networkId();
    }

    private static void solidActor(SessionState session, float x, float y, float z) {
        var packet = new AddEntityPacket();
        packet.setRuntimeEntityId(12);
        packet.setUniqueEntityId(120);
        packet.setIdentifier("minecraft:pig");
        packet.setPosition(Vector3f.from(x, y, z));
        packet.setMotion(Vector3f.ZERO);
        packet.setRotation(Vector2f.ZERO);
        packet.getMetadata().put(EntityDataTypes.WIDTH, 0.6F);
        packet.getMetadata().put(EntityDataTypes.HEIGHT, 0.9F);
        packet.getMetadata().setFlag(EntityFlag.COLLIDABLE, true);
        session.observeBackend(packet);
        session.player().getLatencyUtil().onOrderedResponse();
    }

    @Test void nearbySolidOutsideTheExitBoxDoesNotBlockTheExit() {
        var s = AuthInputPacketsTest.session();
        solidActor(s, 9.5F, 64, 8);
        var p = s.authInputPackets().context();
        p.begin();
        assertFalse(s.collisionWorld().nearbyEntityShapes(p.boundingBox).isEmpty());
        assertTrue(p.doesNotCollide(0, 0.6F, 0), "Nearby is not the same as intersecting the exit");
    }

    @Test void intersectingSolidAndActualBlockOrLiquidStillVetoTheExit() {
        var s = AuthInputPacketsTest.session();
        solidActor(s, 8, 64.6F, 8);
        var p = s.authInputPackets().context();
        p.begin();
        assertFalse(p.doesNotCollide(0, 0.6F, 0));
        for (String material : new String[]{"minecraft:stone", "minecraft:water", "minecraft:lava"}) {
            var clear = AuthInputPacketsTest.session();
            clear.world().updateBlock(8, 65, 8, 0, block(material));
            var context = clear.authInputPackets().context();
            context.begin();
            assertFalse(context.doesNotCollide(0, 0.6F, 0), material);
        }
    }

    @Test void movementFetchContainingAnUnrelatedWallCannotBlockAnEmptyExit() {
        var s = AuthInputPacketsTest.session();
        s.world().updateBlock(9, 64, 8, 0, block("minecraft:stone"));
        var p = s.authInputPackets().context();
        p.begin();
        p.collisionPhysics.begin(s.world(), s.entities(), 10, null);
        var movementQuery = new Box(7, 63, 7, 11, 68, 10);
        var movement = s.collisionWorld().movementEntries(movementQuery);
        assertFalse(movement.isEmpty());
        p.collisionPhysics.commitMovement(movementQuery, movement);
        assertTrue(p.doesNotCollide(0, 0.6F, 0), "Exit needs its own block query");
        assertEquals(movement, p.collisionPhysics.snapshot().movementEntries());
        assertTrue(movementQuery.equals(p.collisionPhysics.snapshot().movementQuery()));
    }

    @Test void waterTravelAppliesTheExitImpulseDespiteANonIntersectingNearbySolid() {
        var s = AuthInputPacketsTest.session();
        s.player().rebasePosition(new Vec3(8.7F, 64.5F, 8), false);
        s.player().setCollisionResult(false, false, false);
        s.player().setVelocity(new Vec3(0.4F, 0, 0));
        s.world().updateBlock(8, 64, 8, 0, block("minecraft:water"));
        s.world().updateBlock(9, 64, 8, 0, block("minecraft:stone"));
        solidActor(s, 7, 64.5F, 8);
        var p = s.authInputPackets().context();
        p.begin();
        new PredictionRunner(p).run(10, null);
        assertTrue(p.fluidPrediction.waterTravel);
        assertTrue(p.horizontalCollision, "The real collision solver must reach the wall");
        assertEquals(0.3F, p.velocity.y, "Navro's clear-exit impulse after fluid drag/gravity");
    }
}
