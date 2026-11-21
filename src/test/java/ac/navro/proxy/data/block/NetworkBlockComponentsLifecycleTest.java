package ac.navro.proxy.data.block;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static ac.navro.proxy.data.block.NetworkBlockComponentsTest.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkBlockComponentsLifecycleTest {
    private record Definition(int runtimeId) implements BlockDefinition {
        public int getRuntimeId() { return runtimeId; }
    }

    private static SessionState session(NbtMap collision, float friction) {
        return session(collision, friction, NbtMap.EMPTY);
    }

    private static SessionState session(NbtMap collision, float friction, NbtMap transformation) {
        var session = new SessionState();
        session.setProtocolVersion(2193);
        session.player().setProtocolVersion(2193);
        var start = new StartGamePacket();
        start.setRuntimeEntityId(7);
        start.setUniqueEntityId(70);
        start.setPlayerPosition(Vector3f.from(8, 64, 8));
        start.setBlockPalette(new NbtList<>(NbtType.COMPOUND,
                state("minecraft:air", NbtMap.EMPTY), state("test:platform", NbtMap.EMPTY)));
        start.getBlockProperties().add(definition("test:platform", components(collision).toBuilder()
                .putCompound("minecraft:friction", NbtMap.builder().putFloat("value", friction).build())
                .putCompound("minecraft:transformation", transformation).build()));
        session.observeBackend(start);
        for (int chunkX = -1; chunkX <= 1; chunkX++) for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
            session.world().put(chunkX, chunkZ, CompensatedWorld.CachedChunk.network(-64, 320, 24, 0));
        }
        session.observeClient(new SetLocalPlayerAsInitializedPacket());
        session.player().inLoadingScreen = false;
        session.player().sinceLoadingScreen = 20;
        session.player().setCollisionResult(false, false, true);
        session.setDetectionEnabled(true);
        return session;
    }

    @Test void declaredFragmentsReachBothWorldQueryPathsWithoutBeingMergedIntoACube() {
        var session = session(collision(box(0, 0, 0, 4, 16, 16), box(12, 0, 0, 16, 8, 16)), .4F);
        session.world().updateBlock(8, 63, 8, 0, 1);
        var expected = List.of(new Box(8, 63, 8, 8.25F, 64, 9), new Box(8.75F, 63, 8, 9, 63.5F, 9));
        var query = new Box(7.5F, 62, 7.5F, 9.5F, 65, 9.5F);
        equalBoxes(expected, session.world().collisionEntries(query, 0, session.player().collisionPhysics).stream().map(entry -> entry.shape()).toList());
        equalBoxes(expected, session.world().collisionCellFragments(8, 63, 8, query, session.player().collisionPhysics).stream().map(entry -> entry.shape()).toList());
        assertTrue(session.world().wireBlock(1).collisionKnown());
    }

    @Test void customBlockUpdatesOnlyEnterTheVisibleCollisionWorldAfterTheirAcknowledgment() {
        var session = session(collision(box(0, 0, 0, 16, 8, 16)), .4F);
        session.player().getLatencyUtil().queue(77, true);
        var update = new UpdateBlockPacket();
        update.setBlockPosition(org.cloudburstmc.math.vector.Vector3i.from(8, 63, 8));
        update.setDataLayer(0);
        update.setDefinition(new Definition(1));
        session.observeBackend(update);
        var query = new Box(8, 63, 8, 9, 64, 9);
        assertTrue(session.world().collisionCellFragments(8, 63, 8, query, session.player().collisionPhysics).isEmpty());
        assertFalse(session.player().getLatencyUtil().onResponse(76));
        assertTrue(session.player().getLatencyUtil().onResponse(77));
        equalBoxes(List.of(new Box(8, 63, 8, 9, 63.5F, 9)), session.world()
                .collisionCellFragments(8, 63, 8, query, session.player().collisionPhysics).stream().map(entry -> entry.shape()).toList());
    }

    @Test void missingCollisionComponentsStayUnknownAcrossWorldAndMovementQueries() {
        var session = session(NbtMap.EMPTY, .4F);
        session.world().updateBlock(8, 63, 8, 0, 1);
        assertFalse(session.world().wireBlock(1).collisionKnown());
        assertTrue(session.world().collisionCellFragments(8, 63, 8, null, session.player().collisionPhysics).isEmpty());
    }

    @Test void canonicalPredictionUsesTheReceivedCustomFloorGeometryAndFriction() {
        var custom = session(collision(box(0, 0, 0, 16, 16, 16)), .02F);
        var reference = AuthInputPacketsTest.session();
        int ice = NetworkBlockStateRegistry.forProtocol(2193, false).states().values().stream()
                .filter(value -> value.is("minecraft:ice")).findFirst().orElseThrow().networkId();
        for (int chunkX = -1; chunkX <= 1; chunkX++) for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
            for (int localX = 0; localX < 16; localX++) for (int localZ = 0; localZ < 16; localZ++) {
                custom.world().updateBlock((chunkX << 4) + localX, 63, (chunkZ << 4) + localZ, 0, 1);
                reference.world().updateBlock((chunkX << 4) + localX, 63, (chunkZ << 4) + localZ, 0, ice);
            }
        }
        for (var current : List.of(custom, reference)) current.player().setDisabledChecks(Set.of("Prediction", "DebugOffset"));
        for (int tick = 10; tick < 18; tick++) {
            for (var current : List.of(reference, custom)) {
                var input = AuthInputPacketsTest.input(tick, 8, 64, 8, new Vec3(0, -.0784F, 0));
                input.setMotion(org.cloudburstmc.math.vector.Vector2f.from(0, 1));
                input.setRawMoveVector(org.cloudburstmc.math.vector.Vector2f.from(0, 1));
                current.observeClient(input);
            }
            AuthInputPacketsTest.vector(reference.player().position(), custom.player().position());
            AuthInputPacketsTest.vector(reference.player().velocity(), custom.player().velocity());
            assertEquals(reference.player().onGround(), custom.player().onGround());
        }
        assertTrue(custom.player().position().z > 8);
        assertEquals(64, custom.player().position().y);
    }
    @Test void transformedCollisionIsFoundWhenItsSourceCellIsOutsideTheOrdinarySearch() {
        for (float translation : new float[]{-3F, 3F}) {
            var session = session(collision(box(0, 0, 0, 16, 16, 16)), .4F,
                    NbtMap.builder().putFloat("TX", translation).putFloat("SY", .5F).build());
            session.world().updateBlock(8, 63, 8, 0, 1);
            float x = 8 + translation;
            var query = new Box(x + .25F, 63.4F, 8.25F, x + .75F, 63.6F, 8.75F);
            var expected = List.of(new Box(x, 63.25F, 8, x + 1, 63.75F, 9));
            equalBoxes(expected, session.world().collisionEntries(query, 0, session.player().collisionPhysics)
                    .stream().map(entry -> entry.shape()).toList());
            equalBoxes(expected, session.world().collisionCellFragments(8, 63, 8, query, session.player().collisionPhysics)
                    .stream().map(entry -> entry.shape()).toList());
        }
    }

}
