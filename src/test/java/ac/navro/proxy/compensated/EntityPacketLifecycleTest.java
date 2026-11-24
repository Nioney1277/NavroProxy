package ac.navro.proxy.compensated;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.compensated.cache.entity.EntityCache;
import ac.navro.proxy.collision.ProtocolCollisionWorld;
import ac.navro.proxy.packets.other.NetworkLatencyPackets;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.util.EntityBatchEncoder;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchEncoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.NoopCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EntityPacketLifecycleTest {
    private static final class Fixture implements AutoCloseable {
        final SessionState state = new SessionState();
        final EmbeddedChannel channel = new EmbeddedChannel();
        final EntityBatchEncoder encoder;
        Fixture() {
            StartGamePacket start = new StartGamePacket();
            start.setRuntimeEntityId(1);
            start.setPlayerPosition(Vector3f.from(0, 64, 0));
            state.observeBackend(start);
            state.player().setProtocolVersion(2193);
            channel.pipeline().addLast(CompressionCodec.NAME,
                    new CompressionCodec(new SimpleCompressionStrategy(new NoopCompression()), false));
            channel.pipeline().addLast(BedrockBatchEncoder.NAME, new BedrockBatchEncoder());
            BedrockPacketCodec codec = new BedrockPacketCodec_v3();
            codec.setCodec(Bedrock_v2193.CODEC);
            channel.pipeline().addLast(BedrockPacketCodec.NAME, codec);
            BedrockServerSession session = new BedrockServerSession(new BedrockPeer(channel, BedrockServerSession::new), 0);
            encoder = EntityBatchEncoder.install(session, state.player().getLatencyUtil());
            state.player().setEntityBatchEncoder(encoder);
        }
        void send(BedrockPacket packet) {
            state.observeBackend(packet);
            channel.write(BedrockPacketWrapper.create(0, 0, 0, packet, null));
            state.onBackendPacketQueued(packet);
        }
        long flush() {
            channel.flush();
            channel.checkException();
            BedrockBatchWrapper batch;
            while ((batch = channel.readOutbound()) != null) batch.release();
            return state.player().getLatencyUtil().sentQueue().getLast().id();
        }
        void ack(long id) {
            NetworkStackLatencyPacket packet = new NetworkStackLatencyPacket();
            packet.setTimestamp(id * 1_000_000L);
            assertTrue(NetworkLatencyPackets.onPacket(state.player(), packet));
        }
        @Override public void close() { encoder.close(); channel.finishAndReleaseAll(); }
    }

    private static AddEntityPacket add(long runtime, long unique, String identifier, float x, float y, float z) {
        AddEntityPacket packet = new AddEntityPacket();
        packet.setRuntimeEntityId(runtime);
        packet.setUniqueEntityId(unique);
        packet.setIdentifier(identifier);
        packet.setPosition(Vector3f.from(x, y, z));
        packet.setMotion(Vector3f.ZERO);
        packet.setRotation(org.cloudburstmc.math.vector.Vector2f.ZERO);
        packet.getMetadata().put(EntityDataTypes.WIDTH, 0.6F);
        packet.getMetadata().put(EntityDataTypes.HEIGHT, 1.8F);
        packet.getMetadata().setFlag(EntityFlag.COLLIDABLE, true);
        return packet;
    }

    private static void tick(EntityCache entity, int count) {
        for (int i = 0; i < count; i++) entity.getCurrent().tick();
    }

    @Test void deltaAxesMissingYRetainTheExactSentFootHeightWithoutSubtractingTheBoatOffsetAgain() {
        try (Fixture fixture = new Fixture()) {
            fixture.send(add(2, 200, "minecraft:boat", 0, 64.375F, 0));
            var entity = fixture.state.entity(2);
            fixture.ack(fixture.flush());
            for (int mask = 1; mask < 8; mask++) {
                float previousY = entity.getServerPosition().y;
                var delta = new MoveEntityDeltaPacket();
                delta.setRuntimeEntityId(2);
                if ((mask & 1) != 0) delta.getFlags().add(MoveEntityDeltaPacket.Flag.HAS_X);
                if ((mask & 2) != 0) delta.getFlags().add(MoveEntityDeltaPacket.Flag.HAS_Y);
                if ((mask & 4) != 0) delta.getFlags().add(MoveEntityDeltaPacket.Flag.HAS_Z);
                delta.setX(mask);
                delta.setY(70.375F + mask);
                delta.setZ(-mask);
                fixture.send(delta);
                float expectedY = (mask & 2) != 0 ? 70F + mask : previousY;
                assertEquals(Float.floatToRawIntBits(expectedY), Float.floatToRawIntBits(entity.getServerPosition().y));
                fixture.ack(fixture.flush());
                tick(entity, 6);
                assertEquals(expectedY, entity.position().y());
            }
        }
    }

    @Test void wireRegistrySuppliesTheTypeMissingFromModernAddEntityWithoutInventingDimensions() {
        try (Fixture fixture = new Fixture()) {
            var identifiers = new AvailableEntityIdentifiersPacket();
            identifiers.setIdentifiers(org.cloudburstmc.nbt.NbtMap.builder().putList("idlist", org.cloudburstmc.nbt.NbtType.COMPOUND,
                    List.of(org.cloudburstmc.nbt.NbtMap.builder().putString("id", "minecraft:minecart").putInt("rid", 84).build())).build());
            fixture.state.observeBackend(identifiers);
            var spawn = add(2, 200, "minecraft:minecart", 0, 64, 0);
            spawn.setEntityType(0);
            fixture.send(spawn);
            assertEquals(84, fixture.state.entity(2).entityType());
            assertTrue(fixture.state.entity(2).isMinecart());
            assertEquals(.6F, fixture.state.entity(2).getDimensions().width());
            fixture.ack(fixture.flush());

            var unknown = add(3, 300, "minecraft:minecart", 0, 64, 0);
            unknown.getMetadata().remove(EntityDataTypes.HEIGHT);
            fixture.state.observeBackend(unknown);
            assertNull(fixture.state.entity(3));
        }
    }

    @Test void remoteAttributesPropertiesAndMotionWaitForTheirOwnClosingMarker() {
        try (Fixture fixture = new Fixture()) {
            fixture.send(add(2, 200, "minecraft:zombie", 0, 64, 0));
            var entity = fixture.state.entity(2);
            fixture.ack(fixture.flush());
            var attributes = new UpdateAttributesPacket();
            attributes.setRuntimeEntityId(2);
            var modifiers = new java.util.ArrayList<org.cloudburstmc.protocol.bedrock.data.attribute.AttributeModifierData>();
            modifiers.add(new org.cloudburstmc.protocol.bedrock.data.attribute.AttributeModifierData("test-id", "test", .5F,
                    org.cloudburstmc.protocol.bedrock.data.attribute.AttributeOperation.ADDITION, 0, false));
            attributes.getAttributes().add(new org.cloudburstmc.protocol.bedrock.data.AttributeData("minecraft:health", 0, 20, 17, 20, modifiers));
            var metadata = new SetEntityDataPacket();
            metadata.setRuntimeEntityId(2);
            metadata.getProperties().getIntProperties().add(new org.cloudburstmc.protocol.bedrock.data.entity.IntEntityProperty(2, 17));
            metadata.getProperties().getFloatProperties().add(new org.cloudburstmc.protocol.bedrock.data.entity.FloatEntityProperty(3, .5F));
            var motion = new SetEntityMotionPacket();
            motion.setRuntimeEntityId(2);
            motion.setMotion(Vector3f.from(.25F, .5F, -.75F));
            fixture.send(attributes);
            fixture.send(metadata);
            fixture.send(motion);
            long marker = fixture.flush();
            attributes.getAttributes().clear();
            modifiers.clear();
            metadata.getProperties().getIntProperties().clear();
            metadata.getProperties().getFloatProperties().clear();
            motion.setMotion(Vector3f.ZERO);
            assertTrue(entity.attributes().isEmpty());
            assertTrue(entity.properties().getIntProperties().isEmpty());
            assertTrue(entity.properties().getFloatProperties().isEmpty());
            assertEquals(0, entity.motion().x());
            assertEquals(0, entity.motion().y());
            fixture.ack(marker);
            assertEquals(17, entity.attributes().getFirst().getValue());
            assertEquals(.5F, entity.attributes().getFirst().getModifiers().getFirst().getAmount());
            assertEquals(17, entity.properties().getIntProperties().getFirst().getValue());
            assertEquals(.5F, entity.properties().getFloatProperties().getFirst().getValue());
            assertEquals(new ac.navro.proxy.state.WireVec3(.25F, .5F, -.75F), entity.motion());
        }
    }

    @Test void partialPropertyAndAttributePacketsPreserveValuesAbsentFromTheUpdate() {
        try (Fixture fixture = new Fixture()) {
            var spawn = add(2, 200, "minecraft:zombie", 0, 64, 0);
            spawn.getAttributes().add(new org.cloudburstmc.protocol.bedrock.data.AttributeData("minecraft:health", 0, 20, 20, 20));
            spawn.getAttributes().add(new org.cloudburstmc.protocol.bedrock.data.AttributeData("minecraft:movement", 0, 1, .1F, .1F));
            spawn.getProperties().getIntProperties().add(new org.cloudburstmc.protocol.bedrock.data.entity.IntEntityProperty(0, 1));
            spawn.getProperties().getFloatProperties().add(new org.cloudburstmc.protocol.bedrock.data.entity.FloatEntityProperty(1, .2F));
            fixture.send(spawn);
            fixture.ack(fixture.flush());
            var entity = fixture.state.entity(2);
            var update = new UpdateAttributesPacket();
            update.setRuntimeEntityId(2);
            update.getAttributes().add(new org.cloudburstmc.protocol.bedrock.data.AttributeData("minecraft:health", 0, 20, 17, 20));
            var metadata = new SetEntityDataPacket();
            metadata.setRuntimeEntityId(2);
            metadata.getProperties().getIntProperties().add(new org.cloudburstmc.protocol.bedrock.data.entity.IntEntityProperty(0, 2));
            fixture.send(update);
            fixture.send(metadata);
            long marker = fixture.flush();
            assertEquals(20, entity.attributes().getFirst().getValue());
            assertEquals(1, entity.properties().getIntProperties().getFirst().getValue());
            fixture.ack(marker);
            assertEquals(2, entity.attributes().size());
            assertEquals(17, entity.attributes().getFirst().getValue());
            assertEquals(.1F, entity.attributes().get(1).getValue());
            assertEquals(2, entity.properties().getIntProperties().getFirst().getValue());
            assertEquals(.2F, entity.properties().getFloatProperties().getFirst().getValue());

            update = new UpdateAttributesPacket();
            update.setRuntimeEntityId(2);
            metadata = new SetEntityDataPacket();
            metadata.setRuntimeEntityId(2);
            metadata.getMetadata().put(EntityDataTypes.NAME, "updated name");
            fixture.send(update);
            fixture.send(metadata);
            fixture.ack(fixture.flush());
            assertEquals(2, entity.attributes().size());
            assertEquals(2, entity.properties().getIntProperties().getFirst().getValue());
            assertEquals(.2F, entity.properties().getFloatProperties().getFirst().getValue());
        }
    }

    @Test void positionAndMetadataPacketsCommitInIndependentOutboundBatchOrder() {
        try (Fixture fixture = new Fixture()) {
            fixture.send(add(2, 200, "minecraft:zombie", 0, 64, 0));
            var entity = fixture.state.entity(2);
            fixture.ack(fixture.flush());
            var first = new SetEntityMotionPacket();
            first.setRuntimeEntityId(2);
            first.setMotion(Vector3f.from(.25F, 0, 0));
            fixture.send(first);
            long firstMarker = fixture.flush();
            var second = new SetEntityMotionPacket();
            second.setRuntimeEntityId(2);
            second.setMotion(Vector3f.from(.5F, 0, 0));
            fixture.send(second);
            long secondMarker = fixture.flush();
            fixture.ack(firstMarker);
            assertEquals(.25F, entity.motion().x());
            fixture.ack(secondMarker);
            assertEquals(.5F, entity.motion().x());
        }
    }

    @Test void oldMetadataPropertiesAttributesAndMotionCannotMutateAnIdReusedEntity() {
        try (Fixture fixture = new Fixture()) {
            fixture.send(add(2, 200, "minecraft:zombie", 0, 64, 0));
            var previous = fixture.state.entity(2);
            fixture.ack(fixture.flush());
            var metadata = new SetEntityDataPacket();
            metadata.setRuntimeEntityId(2);
            metadata.getMetadata().put(EntityDataTypes.WIDTH, 4F);
            metadata.getProperties().getIntProperties().add(new org.cloudburstmc.protocol.bedrock.data.entity.IntEntityProperty(0, 4));
            fixture.send(metadata);
            var attributes = new UpdateAttributesPacket();
            attributes.setRuntimeEntityId(2);
            attributes.getAttributes().add(new org.cloudburstmc.protocol.bedrock.data.AttributeData("minecraft:health", 0, 20, 3, 20));
            fixture.send(attributes);
            var motion = new SetEntityMotionPacket();
            motion.setRuntimeEntityId(2);
            motion.setMotion(Vector3f.from(4, 0, 0));
            fixture.send(motion);
            long oldMarker = fixture.flush();
            fixture.send(add(2, 201, "minecraft:zombie", 50, 64, 0));
            var replacement = fixture.state.entity(2);
            fixture.flush();
            fixture.ack(oldMarker);
            for (var entity : List.of(previous, replacement)) {
                assertEquals(.6F, entity.getDimensions().width());
                assertTrue(entity.properties().getIntProperties().isEmpty());
                assertTrue(entity.attributes().isEmpty());
                assertEquals(0, entity.motion().x());
            }
            assertEquals(50, replacement.position().x());
        }
    }

    @Test void spawnPropertiesAreDetachedFromTheReusableWireObject() {
        try (Fixture fixture = new Fixture()) {
            var spawn = add(2, 200, "minecraft:zombie", 0, 64, 0);
            spawn.getProperties().getIntProperties().add(new org.cloudburstmc.protocol.bedrock.data.entity.IntEntityProperty(0, 9));
            fixture.send(spawn);
            fixture.flush();
            spawn.getProperties().getIntProperties().clear();
            assertEquals(9, fixture.state.entity(2).properties().getIntProperties().getFirst().getValue());
        }
    }

    @Test void zeroOffsetMovePlayerUsesTheSameQueuedTargetAndAckInterpolationAsOtherEntityMovement() {
        try (Fixture fixture = new Fixture()) {
            fixture.send(add(2, 200, "minecraft:zombie", 0, 64, 0));
            var entity = fixture.state.entity(2);
            fixture.ack(fixture.flush());
            var move = new MovePlayerPacket();
            move.setRuntimeEntityId(2);
            move.setPosition(Vector3f.from(6, 64, 0));
            move.setRotation(Vector3f.ZERO);
            move.setMode(MovePlayerPacket.Mode.NORMAL);
            fixture.send(move);
            long marker = fixture.flush();
            assertEquals(6, entity.getServerPosition().x);
            assertEquals(0, entity.position().x());
            fixture.ack(marker);
            tick(entity, 1);
            assertEquals(1, entity.position().x());
            tick(entity, 5);
            assertEquals(6, entity.position().x());

            move = new MovePlayerPacket();
            move.setRuntimeEntityId(2);
            move.setPosition(Vector3f.from(900, 900, 900));
            move.setRotation(Vector3f.ZERO);
            move.setMode(MovePlayerPacket.Mode.HEAD_ROTATION);
            fixture.send(move);
            fixture.flush();
            assertEquals(6, entity.getServerPosition().x);
        }
    }

    @Test void mountLinkUsesVehicleUniqueIdAndOnlyItsClosingMarkerCanClearPendingTeleports() {
        try(Fixture f=new Fixture()) {
            f.state.observeBackend(add(2,200,"minecraft:boat",0,64,0));
            f.state.teleports().getQueuedTeleports().add(new ac.navro.proxy.teleport.data.TeleportData(Vec3.ZERO));
            var nativeMarker=new NetworkStackLatencyPacket();nativeMarker.setTimestamp(800_000_004L);nativeMarker.setFromServer(true);
            f.channel.write(BedrockPacketWrapper.create(0,0,0,nativeMarker,null));
            var link=new SetEntityLinkPacket();link.setEntityLink(new org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData(200,1,
                    org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData.Type.RIDER,false,false,0));
            f.send(link);assertNull(f.state.player().vehicleData);assertTrue(f.state.player().getLatencyUtil().sentQueue().isEmpty());
            long closing=f.flush();var markers=List.copyOf(f.state.player().getLatencyUtil().sentQueue());assertEquals(2,markers.size());
            assertFalse(f.state.player().getLatencyUtil().onResponse(markers.get(0).id()));
            assertNull(f.state.player().vehicleData);assertEquals(1,f.state.teleports().getQueuedTeleports().size());
            f.ack(closing);assertEquals(2,f.state.player().vehicleData.vehicleRuntimeId);
            assertEquals(.375F,f.state.playerYOffset());assertTrue(f.state.teleports().getQueuedTeleports().isEmpty());
        }
    }

    @Test void modernDeltaReplacesPresentAxesAndKeepsTheLastSentTargetForAbsentAxes() {
        try (Fixture f = new Fixture()) {
            f.send(add(2, 200, "minecraft:zombie", 10, 64, 30));
            EntityCache entity = f.state.entity(2);
            f.ack(f.flush());
            MoveEntityDeltaPacket first = new MoveEntityDeltaPacket();
            first.setRuntimeEntityId(2);
            first.getFlags().add(MoveEntityDeltaPacket.Flag.HAS_X);
            first.setX(22);
            first.setY(999);
            f.send(first);
            MoveEntityDeltaPacket second = new MoveEntityDeltaPacket();
            second.setRuntimeEntityId(2);
            second.getFlags().add(MoveEntityDeltaPacket.Flag.HAS_Z);
            second.setZ(42);
            f.send(second);
            assertEquals(22, entity.getServerPosition().x);
            assertEquals(64, entity.getServerPosition().y);
            assertEquals(42, entity.getServerPosition().z);
            assertEquals(10, entity.position().x());
            assertEquals(0, entity.getCurrent().getInterpolator().getStep());
            f.ack(f.flush());
            assertEquals(10, entity.position().x());
            assertEquals(6, entity.getCurrent().getInterpolator().getStep());
            assertEquals(2, entity.appliedPositionUpdateSequence());
            tick(entity, 1);
            assertEquals(12, entity.position().x());
            assertEquals(32, entity.position().z());
            tick(entity, 5);
            assertEquals(22, entity.position().x());
            assertEquals(42, entity.position().z());
            assertEquals(64, entity.collisionBox().minY);
        }
    }

    @Test void boatBaseOffsetAndAbsoluteAckResetUseFeetRatherThanBoxCenter() {
        try (Fixture f = new Fixture()) {
            f.send(add(2, 200, "minecraft:boat", 1, 64.375F, 1));
            EntityCache boat = f.state.entity(2);
            assertEquals(64, boat.position().y());
            assertEquals(64, boat.collisionBox().minY);
            assertEquals(1.4F, boat.getDimensions().width());
            assertEquals(0.455F, boat.getDimensions().height());
            f.ack(f.flush());
            MoveEntityAbsolutePacket move = new MoveEntityAbsolutePacket();
            move.setRuntimeEntityId(2);
            move.setPosition(Vector3f.from(2, 70.375F, 2));
            move.setRotation(Vector3f.ZERO);
            f.send(move);
            long ack = f.flush();
            assertEquals(64, boat.position().y());
            f.ack(ack);
            assertEquals(70, boat.position().y());
            assertEquals(70, boat.getCurrent().getPrevPos().y);
            assertEquals(70, boat.collisionBox().minY);
        }
    }

    @Test void rotationOnlyUpdateDoesNotAdvancePositionAndOldAckCannotMoveReplacementEntity() {
        try (Fixture f = new Fixture()) {
            f.send(add(2, 200, "minecraft:zombie", 0, 64, 0));
            EntityCache original = f.state.entity(2);
            f.ack(f.flush());
            MoveEntityDeltaPacket rotation = new MoveEntityDeltaPacket();
            rotation.setRuntimeEntityId(2);
            rotation.getFlags().add(MoveEntityDeltaPacket.Flag.HAS_YAW);
            rotation.setYaw(30);
            f.send(rotation);
            assertEquals(0, original.queuedPositionUpdateSequence());
            f.ack(f.flush());
            MoveEntityDeltaPacket move = new MoveEntityDeltaPacket();
            move.setRuntimeEntityId(2);
            move.getFlags().add(MoveEntityDeltaPacket.Flag.HAS_X);
            move.setX(10);
            f.send(move);
            long oldAck = f.flush();
            f.send(add(2, 201, "minecraft:zombie", 50, 64, 0));
            EntityCache replacement = f.state.entity(2);
            f.flush();
            f.ack(oldAck);
            assertEquals(50, replacement.position().x());
            assertEquals(0, replacement.getCurrent().getInterpolator().getStep());
            assertEquals(0, original.appliedPositionUpdateSequence());
        }
    }

    @Test void metadataAndRemovalWaitForAckAndDimensionsRetainNavrosDefinitionFallbackOrder() {
        try (Fixture f = new Fixture()) {
            f.send(add(2, 200, "minecraft:zombie", 0, 64, 0));
            EntityCache entity = f.state.entity(2);
            f.ack(f.flush());
            SetEntityDataPacket metadata = new SetEntityDataPacket();
            metadata.setRuntimeEntityId(2);
            metadata.getMetadata().put(EntityDataTypes.WIDTH, 0.9F);
            metadata.getMetadata().put(EntityDataTypes.HEIGHT, 1.2F);
            metadata.getMetadata().put(EntityDataTypes.SCALE, 2F);
            f.send(metadata);
            assertEquals(1.8F, entity.getDimensions().height());
            f.ack(f.flush());
            assertEquals(1.2F, entity.getDimensions().width(), "height update resets width to its definition before scale");
            assertEquals(2.4F, entity.getDimensions().height());
            assertEquals(Boolean.TRUE, entity.collidableFlag());
            RemoveEntityPacket remove = new RemoveEntityPacket();
            remove.setUniqueEntityId(200);
            f.send(remove);
            long id = f.flush();
            assertSame(entity, f.state.entity(2));
            f.ack(id);
            assertNull(f.state.entity(2));
            assertEquals(1, f.state.player().runtimeId());
        }
    }

    @Test void missingDimensionsNeverCreateAGenericEntityBoxAndPlayerSpawnUsesFixedDefinition() {
        try (Fixture f = new Fixture()) {
            AddEntityPacket unknown = add(2, 200, "test:custom", 0, 64, 0);
            unknown.getMetadata().clear();
            f.state.observeBackend(unknown);
            assertNull(f.state.entity(2));
            AddPlayerPacket player = new AddPlayerPacket();
            player.setRuntimeEntityId(3);
            player.setUniqueEntityId(300);
            player.setPosition(Vector3f.from(1, 64, 1));
            player.setMotion(Vector3f.ZERO);
            f.state.observeBackend(player);
            EntityCache remote = f.state.entity(3);
            assertEquals(64, remote.collisionBox().minY);
            assertEquals(0.6F, remote.getDimensions().width());
            assertEquals(1.8F, remote.getDimensions().height());
            remote.interpolate(new Vec3(4, 64, 1), true);
            tick(remote, 1);
            assertEquals(2, remote.position().x());
            tick(remote, 2);
            assertEquals(4, remote.position().x());
            assertEquals(1, f.state.player().runtimeId());
        }
    }

    @Test void solidQueryKeepsProviderOrderAndAppliesDimensionStackableAndFallingFilters() {
        WirePlayerState player = new WirePlayerState();
        Map<Long, EntityCache> entities = new LinkedHashMap<>();
        for (int index = 0; index < 5; index++) {
            EntityCache entity = new EntityCache(player, index + 2, index + 2, 0,
                    index == 3 || index == 4 ? "minecraft:falling_block" : "minecraft:zombie",
                    0.6F, index == 3 ? 1 : 1.8F, 0, false, index == 0 ? 1 : 0);
            entity.init();
            entity.interpolate(new Vec3(index == 1 ? 1 : 0, 0, 0), false);
            EntityDataMap flags = new EntityDataMap();
            flags.setFlag(EntityFlag.COLLIDABLE, index != 2);
            flags.setFlag(EntityFlag.STACKABLE, index == 2);
            entity.metadata(flags);
            entities.put(entity.runtimeId(), entity);
        }
        Box actor = new Box(-0.3F, 1, -0.3F, 0.3F, 2.8F, 0.3F);
        var world = new ac.navro.proxy.compensated.world.base.CompensatedWorld();
        var collision = new ProtocolCollisionWorld(world, 0, entities, 1, false);
        List<Box> shapes = collision.nearbyEntityShapes(actor);
        assertEquals(2, shapes.size());
        assertEquals(1F, shapes.get(0).feetCenter().x);
        assertEquals(1F, shapes.get(1).maxY);
        assertEquals(3, new ProtocolCollisionWorld(world, 0, entities, 1, true).nearbyEntityShapes(actor).size());
    }

    @Test void partialFlagWordUpdatesPreserveTheAbsentWordAndRejectNonFiniteDimensions() {
        try (Fixture f = new Fixture()) {
            AddEntityPacket spawn = add(2, 200, "minecraft:zombie", 0, 64, 0);
            spawn.getMetadata().setFlag(EntityFlag.STACKABLE, true);
            f.send(spawn);
            f.ack(f.flush());
            EntityCache entity = f.state.entity(2);
            SetEntityDataPacket baseWord = new SetEntityDataPacket();
            baseWord.setRuntimeEntityId(2);
            baseWord.getMetadata().setFlag(EntityFlag.STACKABLE, false);
            baseWord.getMetadata().put(EntityDataTypes.WIDTH, Float.NaN);
            f.send(baseWord);
            f.ack(f.flush());
            assertFalse(entity.stackable());
            assertEquals(Boolean.TRUE, entity.collidableFlag());
            assertEquals(0.6F, entity.getDimensions().width());
            SetEntityDataPacket extendedWord = new SetEntityDataPacket();
            extendedWord.setRuntimeEntityId(2);
            extendedWord.getMetadata().setFlag(EntityFlag.COLLIDABLE, false);
            f.send(extendedWord);
            f.ack(f.flush());
            assertEquals(Boolean.FALSE, entity.collidableFlag());
            assertFalse(entity.stackable());
        }
    }
}
