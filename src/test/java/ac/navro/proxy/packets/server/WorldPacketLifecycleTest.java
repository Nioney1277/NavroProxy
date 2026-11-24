package ac.navro.proxy.packets.server;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.packets.other.NetworkLatencyPackets;
import ac.navro.proxy.util.EntityBatchEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.BlockChangeEntry;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchEncoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.*;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import static org.junit.jupiter.api.Assertions.*;

class WorldPacketLifecycleTest {
    private record Definition(int runtimeId) implements BlockDefinition {
        @Override public int getRuntimeId() { return runtimeId; }
    }
    private static final Definition STONE = new Definition(200), WATER = new Definition(300);

    private static final class Fixture implements AutoCloseable {
        final SessionState state = new SessionState();
        final EmbeddedChannel channel = new EmbeddedChannel();
        final EntityBatchEncoder encoder;
        Fixture() { this(false, true); }
        Fixture(boolean hashed, boolean syntheticPalette) {this(hashed,syntheticPalette,2193);}
        Fixture(boolean hashed, boolean syntheticPalette,int protocol) {
            state.setProtocolVersion(protocol);
            state.player().setProtocolVersion(protocol);
            StartGamePacket start = new StartGamePacket();
            start.setBlockNetworkIdsHashed(hashed);
            if (syntheticPalette) start.setBlockPalette(new org.cloudburstmc.nbt.NbtList<>(org.cloudburstmc.nbt.NbtType.COMPOUND,
                    org.cloudburstmc.nbt.NbtMap.builder().putString("name", "minecraft:air")
                            .putCompound("states", org.cloudburstmc.nbt.NbtMap.EMPTY).build()));
            start.setRuntimeEntityId(7);
            start.setPlayerPosition(Vector3f.from(0, 64, 0));
            state.observeBackend(start);
            channel.pipeline().addLast(CompressionCodec.NAME,
                    new CompressionCodec(new SimpleCompressionStrategy(new NoopCompression()), false));
            channel.pipeline().addLast(BedrockBatchEncoder.NAME, new BedrockBatchEncoder());
            BedrockPacketCodec codec = new BedrockPacketCodec_v3();
            codec.setCodec(ac.navro.proxy.SupportedCodecs.forProtocol(protocol));
            codec.getHelper().setBlockDefinitions(new DefinitionRegistry<BlockDefinition>() {
                @Override public BlockDefinition getDefinition(int id) { return new Definition(id); }
                @Override public boolean isRegistered(BlockDefinition value) { return true; }
            });
            channel.pipeline().addLast(BedrockPacketCodec.NAME, codec);
            BedrockServerSession session = new BedrockServerSession(new BedrockPeer(channel, BedrockServerSession::new), 0);
            encoder = EntityBatchEncoder.install(session, state.player().getLatencyUtil());
            state.player().setEntityBatchEncoder(encoder);
        }
        void send(BedrockPacket packet) {
            state.observeBackend(packet);
            channel.write(BedrockPacketWrapper.create(0, 0, 0, packet, null));
        }
        long flush() {
            channel.flush(); channel.checkException();
            BedrockBatchWrapper batch;
            while ((batch = channel.readOutbound()) != null) batch.release();
            return state.player().getLatencyUtil().sentQueue().getLast().id();
        }
        void ack(long id) {
            NetworkStackLatencyPacket response = new NetworkStackLatencyPacket();
            response.setTimestamp(id * 1_000_000L);
            assertTrue(NetworkLatencyPackets.onPacket(state.player(), response));
        }
        void load(int x, int z) { send(chunk(x, z, 0, -4, 200, 0)); ack(flush()); }
        int raw(int x, int y, int z, int layer) { return state.world().getRawBlockAt(x, y, z, layer); }
        @Override public void close() { encoder.close(); channel.finishAndReleaseAll(); }
    }

    private static void section(ByteBuf data, int version, int y, int... ids) {
        data.writeByte(version).writeByte(ids.length);
        if (version >= 9) data.writeByte(y);
        for (int id : ids) { data.writeByte(1); VarInts.writeInt(data, id); }
    }
    private static LevelChunkPacket chunk(int x, int z, int dimension, int y, int... ids) {
        LevelChunkPacket packet = new LevelChunkPacket();
        packet.setChunkX(x); packet.setChunkZ(z); packet.setDimension(dimension);
        packet.setSubChunksLength(1);
        ByteBuf data = Unpooled.buffer(); section(data, 9, y, ids); packet.setData(data);
        return packet;
    }
    private static UpdateBlockPacket block(int x, int y, int z, int layer, BlockDefinition definition) {
        UpdateBlockPacket packet = new UpdateBlockPacket();
        packet.setBlockPosition(Vector3i.from(x, y, z)); packet.setDataLayer(layer); packet.setDefinition(definition);
        return packet;
    }
    private static ChangeDimensionPacket dimension(int dimension, int screen) {
        ChangeDimensionPacket packet = new ChangeDimensionPacket();
        packet.setDimension(dimension); packet.setLoadingScreenId(screen); packet.setPosition(Vector3f.from(8, 70, 8));
        return packet;
    }

    @Test void fullChunksWaitForClosingAckAndOwnDecodedDataAfterPacketRelease() {
        try (Fixture f = new Fixture()) {
            LevelChunkPacket packet = chunk(0, 0, 0, -4, 200, 300);
            ByteBuf source = packet.getData().retain();
            try {
                f.send(packet);
                assertFalse(f.state.isChunkLoaded(0, 0, 0));
                long marker = f.flush();
                assertFalse(f.state.isChunkLoaded(0, 0, 0));
                source.setZero(0, source.capacity());
                f.ack(marker);
                assertTrue(f.state.isChunkLoaded(0, 0, 0));
                assertEquals(200, f.raw(1, -64, 1, 0));
                assertEquals(300, f.raw(1, -64, 1, 1));
            } finally { source.release(); }
            f.send(chunk(0, 0, 0, -3, 400));
            f.ack(f.flush());
            assertEquals(0, f.raw(1, -64, 1, 0));
            assertEquals(0, f.raw(1, -64, 1, 1));
            assertEquals(400, f.raw(1, -48, 1, 0));
        }
    }

    @Test void bothBlockPacketTypesAreAckBoundAndEntryPositionsAreAbsolute() {
        try (Fixture f = new Fixture()) {
            f.load(2, -3);
            UpdateBlockPacket first = block(33, -60, -47, 0, WATER);
            f.send(first); long one = f.flush();
            assertEquals(200, f.raw(33, -60, -47, 0));
            first.setBlockPosition(Vector3i.ZERO); first.setDefinition(STONE);
            UpdateSubChunkBlocksPacket second = new UpdateSubChunkBlocksPacket();
            second.setPosition(Vector3i.from(2, -4, -3));
            second.getStandardBlocks().add(new BlockChangeEntry(Vector3i.from(34, -60, -47), WATER, 0, 0,
                    BlockChangeEntry.MessageType.NONE));
            second.getExtraBlocks().add(new BlockChangeEntry(Vector3i.from(34, -60, -47), STONE, 0, 0,
                    BlockChangeEntry.MessageType.NONE));
            f.send(second); long two = f.flush();
            second.getStandardBlocks().clear(); second.getExtraBlocks().clear();
            f.ack(one);
            assertEquals(300, f.raw(33, -60, -47, 0));
            assertEquals(200, f.raw(34, -60, -47, 0));
            f.ack(two);
            assertEquals(300, f.raw(34, -60, -47, 0));
            assertEquals(200, f.raw(34, -60, -47, 1));
            // Navro never creates an unloaded chunk from a standalone update.
            f.send(block(80, 64, 80, 0, WATER)); f.ack(f.flush());
            assertFalse(f.state.isChunkLoaded(5, 5, 0));
        }
    }

    @Test void delayedChunkAndBlockAcksPreserveLaterLocalBucketChanges() {
        try (Fixture f = new Fixture()) {
            f.load(0, 0);
            f.send(chunk(0, 0, 0, -4, 400, 0));
            f.send(block(1, -60, 1, 0, STONE));
            long old = f.flush();
            f.state.world().predictBucketNetworkBlock(1, -60, 1, 0, 300);
            f.ack(old);
            assertEquals(300, f.raw(1, -60, 1, 0));
            assertEquals(400, f.raw(2, -60, 1, 0));
            f.send(block(1, -60, 1, 0, STONE)); f.ack(f.flush());
            assertEquals(200, f.raw(1, -60, 1, 0));
        }
    }

    @Test void dimensionAndNewChunksApplyInAckOrderWithoutOldPacketsChangingDimension() {
        try (Fixture f = new Fixture()) {
            f.load(0, 0);
            long epoch = f.state.world().getBlockBreakEpoch();
            ChangeDimensionPacket change = dimension(1, 91);
            f.send(change); f.send(chunk(0, 0, 1, 0, 300));
            long marker = f.flush();
            change.setDimension(2); change.setLoadingScreenId(999);
            assertEquals(0, f.state.world().dimension());
            assertEquals(200, f.raw(1, -64, 1, 0));
            f.ack(marker);
            assertEquals(1, f.state.world().dimension());
            assertEquals(1, f.state.player().dimension());
            assertEquals(epoch + 2, f.state.world().getBlockBreakEpoch());
            assertEquals(91, f.state.player().currentLoadingScreen);
            assertTrue(f.state.player().inLoadingScreen);
            assertEquals(300, f.raw(1, 0, 1, 0));
            assertFalse(f.state.isChunkLoaded(0, 0, 0));
            f.send(chunk(0, 0, 0, -4, 400)); f.ack(f.flush());
            assertEquals(1, f.state.world().dimension());
            assertEquals(300, f.raw(1, 0, 1, 0));
            f.send(dimension(1, 92)); f.ack(f.flush());
            assertEquals(0, f.state.world().chunkCount());
            assertEquals(91, f.state.player().currentLoadingScreen);
        }
    }

    @Test void publisherDoesNotEvictPacketVisibleChunksUsingAnApproximateCircle() {
        try (Fixture f = new Fixture()) {
            f.load(0, 0); f.load(0, 65536); f.load(65536, 0);
            assertEquals(3, f.state.world().chunkCount());
            NetworkChunkPublisherUpdatePacket publisher = new NetworkChunkPublisherUpdatePacket();
            publisher.setPosition(Vector3i.from(1000, 64, 1000)); publisher.setRadius(16);
            f.send(publisher); long marker = f.flush();
            assertNull(f.state.world().getRadiusCenter());
            f.ack(marker);
            assertEquals(publisher.getPosition(), f.state.world().getRadiusCenter());
            assertEquals(16, f.state.world().getRadius());
            assertEquals(3, f.state.world().chunkCount());
        }
    }

    @Test void nativeNukkitEncodedPalettesMatchAllCellsForEveryBitArrayVersionAndBothChunkFormats() throws Exception {
        try (var in = new DataInputStream(getClass().getResourceAsStream("/nukkit-network-chunks.bin"))) {
            assertEquals(0x4E434831, in.readInt());
            int cases = in.readInt(); assertEquals(18, cases);
            for (int c = 0; c < cases; c++) {
                int version = in.readInt(), bits = in.readInt();
                int length = in.readInt();
                ByteBuf data = Unpooled.wrappedBuffer(in.readNBytes(length));
                try {
                    LevelChunkPacket packet = new LevelChunkPacket(); packet.setData(data); packet.setSubChunksLength(1);
                    int readerIndex = data.readerIndex();
                    var chunk = ServerChunkPackets.readLevelChunk(packet, -80, 336, 100);
                    assertEquals(readerIndex, data.readerIndex());
                    for (int layer = 0; layer < 2; layer++) for (int i = 0; i < 4096; i++)
                        assertEquals(in.readInt(), chunk.getFullBlock(i >>> 8, -80 + (i & 15), (i >>> 4) & 15, layer),
                                "native version=" + version + " bits=" + bits + " index=" + i);
                    assertEquals(100, chunk.getFullBlock(0, -64, 0, 0));
                } finally { data.release(); }
            }
            assertEquals(-1, in.read());
        }
    }

    @Test void modernEmptyStartGamePaletteResolvesRealAirWaterAndCollisionInBothIdModes() {
        for (boolean hashed : new boolean[]{false, true}) try (Fixture f = new Fixture(hashed, false)) {
            var registry = ac.navro.proxy.data.block.NetworkBlockStateRegistry.forProtocol(2193, hashed);
            var stone = registry.states().values().stream().filter(state -> state.is("minecraft:stone"))
                    .findFirst().orElseThrow();
            var water = registry.states().values().stream().filter(state -> state.is("minecraft:water")
                    && state.intProperty("liquid_depth", -1) == 0).findFirst().orElseThrow();
            int air = registry.airNetworkId();
            assertEquals(air, f.state.world().airNetworkId());
            assertNotEquals(0, air);
            f.send(chunk(0, 0, 0, -4, stone.networkId(), air));
            assertFalse(f.state.isChunkLoaded(0, 0, 0));
            f.ack(f.flush());
            assertEquals("minecraft:stone", f.state.world().blockIdentifierAt(1, -64, 1, 0, 0));
            assertEquals("minecraft:air", f.state.world().blockIdentifierAt(1, -48, 1, 0, 0));
            var query = new ac.navro.anticheat.util.math.Box(1.1F, -63.9F, 1.1F, 1.9F, -63.1F, 1.9F);
            assertFalse(f.state.world().collisionShapes(query, 0).isEmpty());
            f.send(block(1, -64, 1, 0, new Definition(water.networkId())));
            long marker = f.flush();
            assertFalse(f.state.world().fluidAt(1, -64, 1, 0).isFluid());
            f.ack(marker);
            assertEquals(ac.navro.proxy.data.FluidState.Type.WATER, f.state.world().fluidAt(1, -64, 1, 0).type());
            assertEquals(0, f.state.world().fluidAt(1, -64, 1, 0).level());
            assertTrue(f.state.world().collisionShapes(query, 0).isEmpty());
        }
    }

    @Test void unknownExplicitStateCannotBorrowTheNumericIdsVanillaCollision() {
        try (Fixture f = new Fixture()) {
            var source = ac.navro.proxy.data.block.NetworkBlockStateRegistry.forProtocol(2193, false);
            var stone = source.states().values().stream().filter(state -> state.is("minecraft:stone")).findFirst().orElseThrow();
            var entries = new java.util.ArrayList<org.cloudburstmc.nbt.NbtMap>();
            for (int i = 0; i <= stone.networkId(); i++) entries.add(org.cloudburstmc.nbt.NbtMap.builder()
                    .putString("name", i == stone.networkId() ? "example:unknown" : "minecraft:air")
                    .putCompound("states", org.cloudburstmc.nbt.NbtMap.EMPTY).build());
            StartGamePacket start = new StartGamePacket(); start.setPlayerPosition(Vector3f.from(0, 64, 0));
            start.setBlockPalette(new org.cloudburstmc.nbt.NbtList<>(org.cloudburstmc.nbt.NbtType.COMPOUND, entries));
            f.state.observeBackend(start);
            assertFalse(f.state.world().wireBlock(stone.networkId()).collisionKnown());
        }
    }

    private static org.cloudburstmc.nbt.NbtMap shulkerTag(int facing) {
        return org.cloudburstmc.nbt.NbtMap.builder().putString("id", "ShulkerBox").putByte("facing", (byte) facing)
                .putByteArray("payload", new byte[]{1, 2}).build();
    }
    private static BlockEntityDataPacket entityData(int x, int y, int z, org.cloudburstmc.nbt.NbtMap tag) {
        var packet = new BlockEntityDataPacket(); packet.setBlockPosition(Vector3i.from(x, y, z)); packet.setData(tag);
        return packet;
    }
    private static BlockEventPacket blockEvent(int x, int y, int z, int type, int count) {
        var packet = new BlockEventPacket(); packet.setBlockPosition(Vector3i.from(x, y, z));
        packet.setEventType(type); packet.setEventData(count); return packet;
    }
    private static int shulkerId(Fixture f) {
        return ac.navro.proxy.data.block.NetworkBlockStateRegistry.forProtocol(2193, false).states().values().stream()
                .filter(state -> state.identifierContains("shulker_box")).findFirst().orElseThrow().networkId();
    }
    private static void loadShulker(Fixture f) {
        f.send(chunk(0, 0, 0, -4, shulkerId(f))); f.ack(f.flush());
    }

    @Test void blockEntityAndOpenEventsWaitForTheirOwnAckAndOwnTheirNbt() {
        try (Fixture f = new Fixture(false, false)) {
            loadShulker(f);
            var tag = shulkerTag(5);
            BlockEntityDataPacket packet = entityData(1, -64, 1, tag);
            f.send(packet); long first = f.flush();
            assertNull(f.state.world().getBlockEntityTag(1, -64, 1));
            tag.getByteArray("payload")[0] = 9;
            packet.setBlockPosition(Vector3i.ZERO); packet.setData(shulkerTag(0));
            f.send(blockEvent(1, -64, 1, 1, 1)); long second = f.flush();
            f.ack(first);
            assertEquals(5, f.state.world().getBlockEntityTag(1, -64, 1).getByte("facing"));
            assertEquals(1, f.state.world().getBlockEntityTag(1, -64, 1).getByteArray("payload")[0]);
            assertTrue(f.state.world().snapshotShulkerFrames().isEmpty());
            f.state.world().getShulkerCollision(Vector3i.from(1, -64, 1));
            f.state.world().tickCollisionBlockActors();
            assertEquals(0, f.state.world().snapshotShulkerFrames().get(0).openness());
            f.ack(second);
            f.state.world().tickCollisionBlockActors();
            assertEquals(0.05F, f.state.world().snapshotShulkerFrames().get(0).openness());
            assertEquals(2, f.state.world().snapshotShulkerFrames().get(0).shape().maxX);
            f.state.world().tickCollisionBlockActors();
            assertTrue(f.state.world().snapshotShulkerFrames().get(0).shape().maxX > 2);
            f.send(entityData(80, -64, 80, shulkerTag(5))); f.ack(f.flush());
            assertNull(f.state.world().getBlockEntityTag(80, -64, 80));
        }
    }

    @Test void lidQueriesFindExtendedShapesAcrossBlockBoundariesAndNeverAdvanceAnimation() {
        try (Fixture f = new Fixture(false, false)) {
            loadShulker(f);
            var pos = Vector3i.from(1, -64, 1);
            var query = new ac.navro.anticheat.util.math.Box(2.01F, -63.9F, 1.1F, 2.05F, -63.1F, 1.9F);
            // A shulker with no block-entity tag has no actor collision in Navro.
            assertTrue(f.state.world().getShulkerCollision(pos).isEmpty());
            f.send(entityData(1, -64, 1, shulkerTag(5)));
            f.send(blockEvent(1, -64, 1, 1, 1)); f.ack(f.flush());
            f.state.world().tickCollisionBlockActors();
            f.state.world().tickCollisionBlockActors();
            var before = f.state.world().snapshotShulkerFrames().get(0);
            for (int i = 0; i < 10; i++) {
                var shapes = f.state.world().collisionShapes(query, 0);
                assertFalse(shapes.isEmpty());
                assertTrue(shapes.stream().anyMatch(shape -> shape.equals(before.shape())));
            }
            assertEquals(before.openness(), f.state.world().snapshotShulkerFrames().get(0).openness());
            assertSame(before.shape(), f.state.world().snapshotShulkerFrames().get(0).shape());
            assertTrue(f.state.world().collisionShapes(query, 1).isEmpty());
        }
    }

    @Test void actorTickRunsOncePerInitializedInputAndIgnoresOtherBlockEventTypes() {
        try (Fixture f = new Fixture(false, false)) {
            loadShulker(f);
            f.send(entityData(1, -64, 1, shulkerTag(5)));
            f.send(blockEvent(1, -64, 1, 1, 1)); f.ack(f.flush());
            var input = new PlayerAuthInputPacket(); input.setPosition(Vector3f.from(0, 64, 0));
            input.setRotation(Vector3f.ZERO); input.setDelta(Vector3f.ZERO); input.setTick(1);
            f.state.observeClient(input);
            assertEquals(0, f.state.world().snapshotShulkerFrames().get(0).openness());
            var initialized = new SetLocalPlayerAsInitializedPacket(); initialized.setRuntimeEntityId(7);
            f.state.observeClient(initialized);
            input.setTick(2); f.state.observeClient(input);
            assertEquals(0.05F, f.state.world().snapshotShulkerFrames().get(0).openness());
            f.send(blockEvent(1, -64, 1, 2, 0)); f.flush();
            input.setTick(3); f.state.observeClient(input);
            assertEquals(0.1F, f.state.world().snapshotShulkerFrames().get(0).openness());
            f.send(blockEvent(1, -64, 1, 1, 0)); f.ack(f.flush());
            input.setTick(4); f.state.observeClient(input);
            assertEquals(0.05F, f.state.world().snapshotShulkerFrames().get(0).openness());
        }
    }

    @Test void replacingBlocksChunksAndDimensionsClearActorStateAtTheOriginalLifecycleBoundaries() {
        try (Fixture f = new Fixture(false, false)) {
            loadShulker(f);
            f.send(entityData(1, -64, 1, shulkerTag(5)));
            f.send(blockEvent(1, -64, 1, 1, 1)); f.ack(f.flush());
            f.state.world().tickCollisionBlockActors();
            assertEquals(1, f.state.world().snapshotShulkerFrames().size());
            var registry = ac.navro.proxy.data.block.NetworkBlockStateRegistry.forProtocol(2193, false);
            int stoneId = registry.states().values().stream().filter(state -> state.is("minecraft:stone")).findFirst().orElseThrow().networkId();
            f.send(block(1, -64, 1, 1, new Definition(stoneId))); f.ack(f.flush());
            assertEquals(1, f.state.world().snapshotShulkerFrames().size());
            f.send(block(1, -64, 1, 0, new Definition(stoneId))); long marker = f.flush();
            assertEquals(1, f.state.world().snapshotShulkerFrames().size());
            f.ack(marker);
            assertTrue(f.state.world().snapshotShulkerFrames().isEmpty());
            f.send(block(1, -64, 1, 0, new Definition(shulkerId(f))));
            f.send(blockEvent(1, -64, 1, 1, 1)); f.ack(f.flush());
            assertEquals(1, f.state.world().snapshotShulkerFrames().size());
            loadShulker(f); // Whole chunk replacement clears both NBT and the old animation.
            assertNull(f.state.world().getBlockEntityTag(1, -64, 1));
            assertTrue(f.state.world().snapshotShulkerFrames().isEmpty());
            f.send(entityData(1, -64, 1, shulkerTag(1)));
            f.send(blockEvent(1, -64, 1, 1, 1)); f.ack(f.flush());
            f.send(dimension(1, 32)); marker = f.flush();
            assertEquals(1, f.state.world().snapshotShulkerFrames().size());
            f.ack(marker);
            assertTrue(f.state.world().snapshotShulkerFrames().isEmpty());
        }
    }

    private static UpdateAbilitiesPacket abilities(long id, org.cloudburstmc.protocol.bedrock.data.Ability... values) {
        var packet = new UpdateAbilitiesPacket(); packet.setUniqueEntityId(id);
        packet.setPlayerPermission(org.cloudburstmc.protocol.bedrock.data.PlayerPermission.MEMBER);
        packet.setCommandPermission(org.cloudburstmc.protocol.bedrock.data.command.CommandPermission.ANY);
        var layer = new org.cloudburstmc.protocol.bedrock.data.AbilityLayer();
        layer.setLayerType(org.cloudburstmc.protocol.bedrock.data.AbilityLayer.Type.BASE);
        layer.getAbilitiesSet().addAll(java.util.List.of(values)); layer.getAbilityValues().addAll(java.util.List.of(values));
        packet.getAbilityLayers().add(layer); return packet;
    }
    private static void flightInput(Fixture f, long tick, org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData... actions) {
        var input = new PlayerAuthInputPacket(); input.setTick(tick); input.setPosition(Vector3f.from(0, 64, 0));
        input.setRotation(Vector3f.ZERO); input.setDelta(Vector3f.ZERO); input.getInputData().addAll(java.util.List.of(actions));
        f.state.observeClient(input);
    }
    @Test void abilitiesWaitForAckAndMayFlyDoesNotBecomeFlyingWithoutTheOriginalInputTransition() {
        try (Fixture f = new Fixture()) {
            var player = f.state.player();
            var update = abilities(7, org.cloudburstmc.protocol.bedrock.data.Ability.MAY_FLY);
            f.send(update); long marker = f.flush();
            update.getAbilityLayers().get(0).getAbilityValues().clear();
            assertTrue(player.abilities().isEmpty()); assertFalse(player.flying());
            f.ack(marker);
            assertTrue(player.abilities().contains(org.cloudburstmc.protocol.bedrock.data.Ability.MAY_FLY));
            assertFalse(player.flying()); assertFalse(player.wasFlying());
            assertTrue(player.isMovementExempted());
            flightInput(f, 1, org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.START_FLYING);
            assertTrue(player.flying()); assertFalse(player.wasFlying());
            var saved = player.snapshot(null);
            flightInput(f, 2, org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.STOP_FLYING);
            assertFalse(player.flying()); assertTrue(player.wasFlying());
            f.send(abilities(7)); f.ack(f.flush());
            assertTrue(player.abilities().isEmpty()); assertFalse(player.flying()); assertFalse(player.wasFlying());
            flightInput(f, 3, org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.START_FLYING);
            assertFalse(player.flying());
            player.restore(saved, null);
            assertTrue(player.flying()); assertFalse(player.wasFlying());
            assertTrue(player.abilities().contains(org.cloudburstmc.protocol.bedrock.data.Ability.MAY_FLY));
        }
    }

    @Test void serverFlyingStateMergesLayersAndOtherEntitiesCannotAlterLocalAbilities() {
        try (Fixture f = new Fixture()) {
            var player = f.state.player();
            f.send(abilities(99, org.cloudburstmc.protocol.bedrock.data.Ability.FLYING)); f.ack(f.flush());
            assertFalse(player.flying()); assertTrue(player.abilities().isEmpty());
            var update = abilities(7, org.cloudburstmc.protocol.bedrock.data.Ability.MAY_FLY);
            var second = new org.cloudburstmc.protocol.bedrock.data.AbilityLayer();
            second.setLayerType(org.cloudburstmc.protocol.bedrock.data.AbilityLayer.Type.COMMANDS);
            second.getAbilitiesSet().add(org.cloudburstmc.protocol.bedrock.data.Ability.FLYING);
            second.getAbilityValues().add(org.cloudburstmc.protocol.bedrock.data.Ability.FLYING);
            update.getAbilityLayers().add(second);
            f.send(update); f.ack(f.flush());
            assertTrue(player.flying()); assertFalse(player.wasFlying());
            assertEquals(2, player.abilities().size());
            f.send(abilities(7, org.cloudburstmc.protocol.bedrock.data.Ability.MAY_FLY)); f.ack(f.flush());
            assertTrue(player.flying()); assertTrue(player.wasFlying());
            f.send(dimension(1, 83)); f.ack(f.flush());
            assertFalse(player.flying()); assertFalse(player.wasFlying());
        }
    }

    @Test void customDimensionBoundsUseNegotiatedServerMinAndExclusiveMax() {
        try (Fixture f = new Fixture()) {
            DimensionDataPacket dimensions = new DimensionDataPacket();
            dimensions.getDefinitions().add(new org.cloudburstmc.protocol.bedrock.data.definitions.DimensionDefinition(
                    "minecraft:overworld", 336, -80, 5, 0, new java.util.UUID(0, 0), "minecraft:plains", 0, false));
            f.state.observeBackend(dimensions);
            assertEquals(-80, f.state.world().getMinY());
            assertEquals(336, f.state.world().getHeightY());
            f.send(chunk(0, 0, 0, -5, 300)); f.ack(f.flush());
            assertEquals(300, f.raw(1, -80, 1, 0));
            assertEquals(0, f.raw(1, -81, 1, 0));
            assertEquals(0, f.raw(1, 336, 1, 0));
            f.send(block(1, 335, 1, 0, STONE)); f.ack(f.flush());
            assertEquals(200, f.raw(1, 335, 1, 0));
        }
    }

    @Test void decoderCommitsCompleteSectionsAndPreservesSignedOutOfOrderSectionY() {
        ByteBuf data = Unpooled.buffer();
        try {
            section(data, 9, 19, 300);
            section(data, 9, -5, 200);
            section(data, 9, -4, 400);
            data.writeByte(9).writeByte(2).writeByte(-3).writeByte(1);
            VarInts.writeInt(data, 500); // Second layer is truncated: do not publish half of this section.
            LevelChunkPacket packet = new LevelChunkPacket(); packet.setData(data); packet.setSubChunksLength(4);
            var chunk = ServerChunkPackets.readLevelChunk(packet, -64, 320, 100);
            assertEquals(300, chunk.getFullBlock(0, 319, 0, 0));
            assertEquals(400, chunk.getFullBlock(0, -64, 0, 0));
            assertEquals(100, chunk.getFullBlock(0, -48, 0, 0));
            assertEquals(100, chunk.getFullBlock(0, -64, 0, 1));
        } finally { data.release(); }
    }
    private static int eastPistonHead(Fixture f) {
        return ac.navro.proxy.data.block.NetworkBlockStateRegistry.forProtocol(2193, false).states().values().stream()
                .filter(state -> state.is("minecraft:piston_arm_collision")
                        && ac.navro.proxy.data.block.NativePistonProperties.headFacing(state)
                        == ac.navro.anticheat.util.math.Direction.EAST)
                .findFirst().orElseThrow().networkId();
    }

    @Test void pistonArmProgressWaitsForItsBatchAckAndReadsFrozenBaseNbt() {
        try (Fixture f = new Fixture(false, false)) {
            f.send(chunk(0, 0, 0, -4, f.state.world().airNetworkId())); f.ack(f.flush());
            var query = new ac.navro.anticheat.util.math.Box(1.7F, -64.1F, 0.9F, 3.1F, -62.9F, 2.1F);
            var context = f.state.collisionWorld();
            f.send(block(2, -64, 1, 0, new Definition(eastPistonHead(f)))); long headAck = f.flush();
            assertTrue(context.blockEntries(query).isEmpty());
            f.ack(headAck); assertEquals(3, context.blockEntries(query).size());
            var payload = new byte[]{1, 2};
            var tag = org.cloudburstmc.nbt.NbtMap.builder().putInt("State", 1)
                    .putFloat("LastProgress", 0).putFloat("Progress", 1).putByteArray("payload", payload).build();
            var packet = entityData(1, -64, 1, tag);
            f.send(packet); long expanding = f.flush();
            assertEquals(3, context.blockEntries(query).size());
            payload[0] = 99;
            packet.setBlockPosition(Vector3i.ZERO); packet.setData(org.cloudburstmc.nbt.NbtMap.EMPTY);
            f.ack(expanding);
            var zero = context.blockEntries(query); assertEquals(2, zero.size());
            assertEquals(1, f.state.world().getBlockEntityTag(1, -64, 1).getByteArray("payload")[0]);
            f.send(entityData(1, -64, 1, org.cloudburstmc.nbt.NbtMap.builder().putLong("State", 3L)
                    .putDouble("Progress", 0.5).putFloat("LastProgress", 0).build()));
            long retracting = f.flush(); assertEquals(2, context.blockEntries(query).size());
            f.ack(retracting);
            var half = context.blockEntries(query); assertEquals(3, half.size());
            assertEquals(2.5F, half.get(2).shape().maxX);
            f.send(entityData(1, -64, 1, org.cloudburstmc.nbt.NbtMap.builder().putInt("State", 0).build()));
            long retracted = f.flush(); assertEquals(3, context.blockEntries(query).size());
            f.ack(retracted); assertTrue(context.blockEntries(query).isEmpty());
        }
    }

    @Test void replacingPistonChunkClearsItsBaseActorTagBeforeAReplacementHeadUsesStaticGeometry() {
        try (Fixture f = new Fixture(false, false)) {
            f.send(chunk(0, 0, 0, -4, f.state.world().airNetworkId())); f.ack(f.flush());
            f.send(block(2, -64, 1, 0, new Definition(eastPistonHead(f))));
            f.send(entityData(1, -64, 1, org.cloudburstmc.nbt.NbtMap.builder().putInt("State", 0).build()));
            f.ack(f.flush());
            var query = new ac.navro.anticheat.util.math.Box(1.7F, -64.1F, 0.9F, 3.1F, -62.9F, 2.1F);
            assertTrue(f.state.collisionWorld().blockEntries(query).isEmpty());
            f.send(chunk(0, 0, 0, -4, f.state.world().airNetworkId())); f.ack(f.flush());
            assertNull(f.state.world().getBlockEntityTag(1, -64, 1));
            f.send(block(2, -64, 1, 0, new Definition(eastPistonHead(f)))); f.ack(f.flush());
            assertEquals(3, f.state.collisionWorld().blockEntries(query).size());
        }
    }

    @Test void specialInteractionNbtReadsWaitForActualBatchAcksAndClearWithDimension() {
        for(boolean hash:new boolean[]{false,true})try(Fixture f=new Fixture(hash,false)) {
            var pos=Vector3i.from(1,-64,1);var w=f.state.world();
            f.send(chunk(0,0,0,-4,w.networkIdForLegacy(449<<13)));f.ack(f.flush());
            var validator=new ac.navro.proxy.validator.inventory.ItemTransactionValidator(f.state.player(),f.state.inventory());
            var vault=ac.navro.proxy.data.block.NetworkBlockState.legacy(569<<13);
            assertFalse(validator.lecternHasBook(w,pos));assertFalse(validator.isVaultActive(w,pos,vault));
            f.send(entityData(1,-64,1,org.cloudburstmc.nbt.NbtMap.builder().putCompound("book",org.cloudburstmc.nbt.NbtMap.EMPTY).putString("vault_state","inactive").build()));long first=f.flush();
            f.send(entityData(1,-64,1,org.cloudburstmc.nbt.NbtMap.builder().putByte("hasBook",(byte)0).putString("vault_state","active").build()));long second=f.flush();
            assertFalse(validator.lecternHasBook(w,pos));assertFalse(validator.isVaultActive(w,pos,vault));
            f.ack(first);assertTrue(validator.lecternHasBook(w,pos));assertFalse(validator.isVaultActive(w,pos,vault));
            f.ack(second);assertFalse(validator.lecternHasBook(w,pos));assertTrue(validator.isVaultActive(w,pos,vault));
            f.send(dimension(1,44));f.ack(f.flush());assertFalse(validator.lecternHasBook(w,pos));assertFalse(validator.isVaultActive(w,pos,vault));
        }
    }


    private static SubChunkPacket sub(int dimension,Vector3i center,Vector3i offset,int format,int... ids){
        var packet=new SubChunkPacket();packet.setDimension(dimension);packet.setCenterPosition(center);
        var data=new org.cloudburstmc.protocol.bedrock.data.SubChunkData();data.setPosition(offset);data.setResult(org.cloudburstmc.protocol.bedrock.data.SubChunkRequestResult.SUCCESS);
        data.setHeightMapType(org.cloudburstmc.protocol.bedrock.data.HeightMapDataType.NO_DATA);data.setRenderHeightMapType(org.cloudburstmc.protocol.bedrock.data.HeightMapDataType.NO_DATA);
        ByteBuf body=Unpooled.buffer();section(body,format,center.getY()+offset.getY(),ids);data.setData(body);packet.getSubChunks().add(data);return packet;
    }
    @Test void streamedColumnAndRelativeSubchunkSectionsRemainUnknownUntilTheirOwnClosingAck(){
        try(Fixture f=new Fixture()){
            var root=new LevelChunkPacket();root.setChunkX(2);root.setChunkZ(-3);root.setDimension(0);root.setRequestSubChunks(true);root.setSubChunkLimit(24);root.setData(Unpooled.EMPTY_BUFFER);
            f.send(root);long first=f.flush();assertNull(f.state.world().getChunk(2,-3));
            f.ack(first);assertNotNull(f.state.world().getChunk(2,-3));assertFalse(f.state.world().isSectionKnown(33,-60,-47));
            var packet=sub(0,Vector3i.from(1,0,-2),Vector3i.from(1,-4,-1),9,200,300);var bytes=packet.getSubChunks().get(0).getData().retain();
            try{f.send(packet);long marker=f.flush();bytes.setZero(0,bytes.capacity());assertFalse(f.state.world().isSectionKnown(33,-60,-47));f.ack(marker);
                assertEquals(200,f.raw(33,-60,-47,0));assertEquals(300,f.raw(33,-60,-47,1));assertTrue(f.state.world().isSectionKnown(33,-60,-47));assertFalse(f.state.world().isSectionKnown(33,-40,-47));
            }finally{bytes.release();}
        }
    }
    @Test void subchunkReplacementTouchesOneSectionAndPreservesNewerLocalPredictions(){
        try(Fixture f=new Fixture()){
            f.load(0,0);f.state.world().updateBlock(1,-40,1,0,400);
            var packet=sub(0,Vector3i.ZERO,Vector3i.from(0,-4,0),8,400,0);f.send(packet);long marker=f.flush();
            f.state.world().predictBucketNetworkBlock(1,-60,1,0,300);f.ack(marker);
            assertEquals(300,f.raw(1,-60,1,0));assertEquals(400,f.raw(2,-60,1,0));assertEquals(400,f.raw(1,-40,1,0));
        }
    }
    @Test void allAirAndFailedSubchunkResultsDoNotShareTheSameMeaning(){
        try(Fixture f=new Fixture()){
            f.load(0,0);var packet=sub(0,Vector3i.ZERO,Vector3i.from(0,-4,0),9,300);packet.getSubChunks().get(0).setResult(org.cloudburstmc.protocol.bedrock.data.SubChunkRequestResult.CHUNK_NOT_FOUND);
            f.send(packet);f.ack(f.flush());assertEquals(200,f.raw(1,-60,1,0));
            var empty=sub(0,Vector3i.ZERO,Vector3i.from(0,-4,0),9,300);empty.getSubChunks().get(0).setResult(org.cloudburstmc.protocol.bedrock.data.SubChunkRequestResult.SUCCESS_ALL_AIR);
            f.send(empty);long marker=f.flush();assertEquals(200,f.raw(1,-60,1,0));f.ack(marker);assertEquals(0,f.raw(1,-60,1,0));assertTrue(f.state.world().isSectionKnown(1,-60,1));
        }
    }
    @Test void cachedSubchunkWaitsForBlobAckAndReplaysLaterAcknowledgedBlockUpdates(){
        try(Fixture f=new Fixture()){
            f.load(0,0);var cached=sub(0,Vector3i.ZERO,Vector3i.from(0,-4,0),9,400);cached.setCacheEnabled(true);cached.getSubChunks().get(0).getData().release();cached.getSubChunks().get(0).setData(null);cached.getSubChunks().get(0).setBlobId(999L);
            f.send(cached);long header=f.flush();assertEquals(200,f.raw(1,-60,1,0));f.ack(header);assertFalse(f.state.world().isSectionKnown(1,-60,1));
            f.send(block(1,-60,1,0,WATER));f.ack(f.flush());assertEquals(300,f.raw(1,-60,1,0));
            var response=new ClientCacheMissResponsePacket();ByteBuf payload=Unpooled.buffer();section(payload,9,-4,400);response.getBlobs().put(999L,payload);f.send(response);long marker=f.flush();assertFalse(f.state.world().isSectionKnown(1,-60,1));f.ack(marker);
            assertTrue(f.state.world().isSectionKnown(1,-60,1));assertEquals(300,f.raw(1,-60,1,0));assertEquals(400,f.raw(2,-60,1,0));
        }
    }
    @Test void cachedFullChunkUsesAcknowledgedBlobsAndDoesNotDecodeBiomeBytesAsBlocks(){
        try(Fixture f=new Fixture()){
            var packet=chunk(0,0,0,-4,400);packet.setCachingEnabled(true);packet.getBlobIds().add(888);packet.getBlobIds().add(889);
            f.send(packet);f.ack(f.flush());assertFalse(f.state.world().isSectionKnown(1,-60,1));
            var blobs=new ClientCacheMissResponsePacket();ByteBuf payload=Unpooled.buffer();section(payload,9,-4,300,200);blobs.getBlobs().put(888L,payload);f.send(blobs);long ack=f.flush();assertFalse(f.state.world().isSectionKnown(1,-60,1));f.ack(ack);
            assertEquals(300,f.raw(1,-60,1,0));assertEquals(200,f.raw(1,-60,1,1));
        }
    }
    @Test void oldDimensionBlobWaitersCannotPopulateAReusedNumericDimension(){
        try(Fixture f=new Fixture()){
            var cached=sub(0,Vector3i.ZERO,Vector3i.from(0,-4,0),9,400);cached.setCacheEnabled(true);cached.getSubChunks().get(0).getData().release();cached.getSubChunks().get(0).setData(null);cached.getSubChunks().get(0).setBlobId(777L);
            f.send(cached);f.ack(f.flush());f.send(dimension(1,40));f.ack(f.flush());f.send(dimension(0,41));f.ack(f.flush());
            var blobs=new ClientCacheMissResponsePacket();ByteBuf payload=Unpooled.buffer();section(payload,9,-4,300);blobs.getBlobs().put(777L,payload);f.send(blobs);f.ack(f.flush());assertNull(f.state.world().getChunk(0,0));
        }
    }
    @Test void diagnosticsCorrelateWorldCommitsWithTheActualClosingMarkerAndKeepFootRemovalAckBound(){
        try(Fixture f=new Fixture()){
            var records=new java.util.ArrayList<java.util.Map<String,Object>>();f.state.player().setWorldDiagnostics(new ac.navro.proxy.diagnostics.WorldCollisionDiagnostics(f.state.player(),f.state.world(),"test",records::add));
            f.send(chunk(0,0,0,3,200));f.ack(f.flush());f.state.player().inLoadingScreen=false;f.state.player().sinceLoadingScreen=20;
            f.send(block(0,63,0,0,new Definition(0)));long marker=f.flush();assertEquals(200,f.raw(0,63,0,0));assertEquals(0,f.state.player().tickSinceBlockResync);
            assertFalse(records.stream().anyMatch(e->"block_applied".equals(e.get("event"))));f.ack(marker);assertEquals(0,f.raw(0,63,0,0));
            assertTrue(records.stream().anyMatch(e->"world_ack".equals(e.get("event"))&&java.util.Objects.equals(marker,e.get("ackId"))));
            assertTrue(records.stream().anyMatch(e->"block_applied".equals(e.get("event"))&&java.util.Objects.equals(200,e.get("from"))&&java.util.Objects.equals(0,e.get("stored"))));
            assertNull(f.state.player().getLatencyUtil().currentAcknowledgmentId());
        }
    }

    @Test void inlineAcknowledgedBlobCanBeReusedByAnotherSubchunkHeader(){
        try(Fixture f=new Fixture()){
            var first=sub(0,Vector3i.ZERO,Vector3i.from(0,-4,0),9,300);first.setCacheEnabled(true);first.getSubChunks().get(0).setBlobId(666L);f.send(first);f.ack(f.flush());
            var reused=sub(0,Vector3i.ZERO,Vector3i.from(1,-4,0),9,400);reused.setCacheEnabled(true);reused.getSubChunks().get(0).setBlobId(666L);reused.getSubChunks().get(0).getData().release();reused.getSubChunks().get(0).setData(null);
            f.send(reused);long marker=f.flush();assertNull(f.state.world().getChunk(1,0));f.ack(marker);assertEquals(300,f.raw(17,-60,1,0));assertTrue(f.state.world().isSectionKnown(17,-60,1));
        }
    }
    @Test void invalidSubchunkPayloadIsUnknownAndHasAnExplicitDiagnostic(){
        try(Fixture f=new Fixture()){
            f.load(0,0);var records=new java.util.ArrayList<java.util.Map<String,Object>>();f.state.player().setWorldDiagnostics(new ac.navro.proxy.diagnostics.WorldCollisionDiagnostics(f.state.player(),f.state.world(),"bad-section",records::add));
            var packet=sub(0,Vector3i.ZERO,Vector3i.from(0,-4,0),9,300);packet.getSubChunks().get(0).getData().setByte(2,-3);f.send(packet);long marker=f.flush();assertEquals(200,f.raw(1,-60,1,0));f.ack(marker);
            assertFalse(f.state.world().isSectionKnown(1,-60,1));assertTrue(records.stream().anyMatch(e->"subchunk_parse_error".equals(e.get("event"))));
        }
    }
    @Test void frozenNativeSubchunkBytesDecodeAcrossAllSupportedLayoutsThenWaitForAck()throws Exception{
        try(var in=new DataInputStream(new java.util.zip.GZIPInputStream(getClass().getResourceAsStream("/native-subchunks.bin.gz")))){
            assertEquals(0x4e534331,in.readInt());int count=in.readInt();assertEquals(56,count);
            for(int i=0;i<count;i++){
                int protocol=in.readInt();boolean air=in.readBoolean();ByteBuf bytes=Unpooled.wrappedBuffer(in.readNBytes(in.readInt()));
                var codec=ac.navro.proxy.SupportedCodecs.forProtocol(protocol);SubChunkPacket packet;
                try{int id=VarInts.readUnsignedInt(bytes);packet=(SubChunkPacket)codec.tryDecode(codec.createHelper(),bytes,id);assertFalse(bytes.isReadable());}finally{bytes.release();}
                try(Fixture f=new Fixture(false,true,protocol)){
                    f.send(packet);long ack=f.flush();assertNull(f.state.world().getChunk(2,-5));f.ack(ack);
                    assertEquals(air?0:200,f.raw(33,-60,-79,0),"native packet "+i);assertEquals(air?0:300,f.raw(33,-60,-79,1),"native packet "+i);assertTrue(f.state.world().isSectionKnown(33,-60,-79));
                }
            }
            assertEquals(-1,in.read());
        }
    }
}
