package ac.navro.proxy.compensated.world.base;

import ac.navro.proxy.data.FluidState;
import ac.navro.proxy.data.block.NativeFluidBlockProperties;
import ac.navro.proxy.prediction.fluid.FluidBlockCollision;
import ac.navro.anticheat.util.math.Direction;
import ac.navro.proxy.prediction.live.NativeIds.BlockID;


import ac.navro.anticheat.collision.CollisionShapeRegistry;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.world.*;
import ac.navro.proxy.util.ItemUtil;
import ac.navro.proxy.collision.block.ShulkerCollisionState;
import ac.navro.proxy.collision.CollisionPhysicsState;
import ac.navro.proxy.collision.CollisionShapeEntry;
import ac.navro.anticheat.collision.util.CuboidBlockIterator;
import org.cloudburstmc.math.vector.Vector3i;
import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.proxy.data.block.NativePistonProperties;
import ac.navro.proxy.data.block.NativeBlockProperties;
import ac.navro.proxy.collision.block.PistonBlock;
import ac.navro.proxy.collision.block.PistonArmBlock;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import java.util.HashMap;
import java.util.Map;
import java.util.List;




public class CompensatedWorld {
    private java.util.function.Supplier<ac.navro.proxy.collision.BedrockCollision.Actor> collisionActor = () -> null;
    public void setCollisionActor(java.util.function.Supplier<ac.navro.proxy.collision.BedrockCollision.Actor> actor) {
        collisionActor = java.util.Objects.requireNonNull(actor);
    }
    private final CollisionPhysicsState collisionPhysics;
    public CompensatedWorld() { this(new CollisionPhysicsState()); }
    public CompensatedWorld(CollisionPhysicsState collisionPhysics) { this(collisionPhysics, null); }

    public CompensatedWorld(CollisionPhysicsState collisionPhysics, java.util.function.IntSupplier nativeServerTick) {
        this.collisionPhysics = collisionPhysics;
        this.nativeServerTick = nativeServerTick;
    }
    private java.util.function.IntSupplier nativeServerTick;
    private java.util.function.BooleanSupplier nativeClockAvailable=()->true;
    public void setNativeServerClock(java.util.function.IntSupplier tick,java.util.function.BooleanSupplier available) {
        nativeServerTick=java.util.Objects.requireNonNull(tick);
        nativeClockAvailable=java.util.Objects.requireNonNull(available);
    }
    public boolean hasNativeServerTick(){return nativeServerTick!=null&&nativeClockAvailable.getAsBoolean();}
    public int nativeServerTick(){return java.util.Objects.requireNonNull(nativeServerTick).getAsInt();}
    public void clearNativeServerClock() {
        nativeServerTick=null;
        recentPlacementSupports.clear();
        placementSupportHistoryTick=Integer.MIN_VALUE;
    }
    private final Map<Vector3i, Integer> recentPlacementSupports = new HashMap<>();
    private int placementSupportHistoryTick = Integer.MIN_VALUE;

    public boolean hadRecentPlacementSupport(Vector3i position) {
        Integer changedAt = recentPlacementSupports.get(position);
        return hasNativeServerTick() && changedAt != null && nativeServerTick.getAsInt() - changedAt < 2;
    }

    private void rememberPlacementSupport(int x, int y, int z, int newNetworkId) {
        if (!hasNativeServerTick() || !isChunkLoaded(x, z) || y < getMinY() || y >= getHeightY()) return;
        int oldNetworkId = getRawBlockAt(x, y, z, 0);
        if (oldNetworkId == newNetworkId) return;
        var old = java.util.Objects.requireNonNull(networkBlockState(oldNetworkId), "Block palette is not initialized");
        if (ac.navro.proxy.util.block.BlockPlacementPhysics.isAirOrLiquid(old.blockId())
                || old.is("minecraft:cave_air") || old.is("minecraft:void_air")) return;
        int tick = nativeServerTick.getAsInt();
        if (placementSupportHistoryTick != tick) {
            recentPlacementSupports.values().removeIf(changedAt -> tick - changedAt >= 2);
            placementSupportHistoryTick = tick;
        }
        recentPlacementSupports.put(Vector3i.from(x, y, z), tick);
    }
    public CollisionPhysicsState collisionPhysics() { return collisionPhysics; }
    public record BlockUpdate(int x, int y, int z, int dimension, int layer,
                              int networkId, String identifier, BlockDefinition definition) {}
    private final Map<Long, CachedChunk> chunks = new HashMap<>();
    private final BucketPredictionTracker bucketPredictions = new BucketPredictionTracker();
    private final Map<Integer, WireBlock> palette = new HashMap<>();
    private long blockBreakEpoch;
    private int dimension;
    private int radius;
    private org.cloudburstmc.math.vector.Vector3i radiusCenter;
    private long blockRegistryChecksum;
    private boolean hashedBlockIds;
    private int protocolVersion = -1;
    private int airNetworkId;
    private NetworkBlockStateRegistry blockStates;
    private NetworkBlockStateRegistry.RuntimePalette runtimeBlockPalette;
    private final Map<Integer, int[]> dimensionBounds = new HashMap<>();

    private final Map<Vector3i, ShulkerCollisionState> shulkerCollisions = new HashMap<>();

    public NbtMap getBlockEntityTag(int x, int y, int z) {
        CachedChunk chunk = getChunk(x >> 4, z >> 4);
        return chunk == null ? null : chunk.getBlockEntityTag(x, y, z);
    }
    public void updateBlockEntityTag(int x, int y, int z, NbtMap tag) {
        CachedChunk chunk = getChunk(x >> 4, z >> 4);
        if (chunk != null) chunk.setBlockEntityTag(x, y, z, tag);
    }
    public List<Box> getShulkerCollision(Vector3i pos) { return getShulkerCollision(pos, collisionPhysics); }
    private List<Box> getShulkerCollision(Vector3i pos, CollisionPhysicsState state) {
        if (state.replaying()) {
            for (var frame : state.shulkers())
                if (frame.x() == pos.getX() && frame.y() == pos.getY() && frame.z() == pos.getZ()) return List.of(frame.shape());
        }
        NbtMap tag = getBlockEntityTag(pos.getX(), pos.getY(), pos.getZ());
        if (tag == null) return List.of();
        ShulkerCollisionState actor = shulkerCollisions.computeIfAbsent(pos, key -> new ShulkerCollisionState(key, tag));
        actor.load(tag);
        return List.of(actor.shape());
    }
    private boolean isShulkerBox(int x, int y, int z) {
        NetworkBlockState state = networkBlockState(getRawBlockAt(x, y, z, 0));

        return state != null && (state.blockId() == 218 || state.blockId() == 205
                || state.identifierContains("shulker_box"));
    }
    public void handleCollisionBlockEvent(int x, int y, int z, int type, int data) {
        if (type != 1 || !isShulkerBox(x, y, z)) return;
        Vector3i pos = Vector3i.from(x, y, z);
        getShulkerCollision(pos);
        ShulkerCollisionState actor = shulkerCollisions.get(pos);
        if (actor != null) actor.setOpenCount(data);
    }
    public void tickCollisionBlockActors() {
        collisionPhysics.endInput();
        shulkerCollisions.entrySet().removeIf(entry -> {
            Vector3i pos = entry.getKey();
            return !isChunkLoaded(pos.getX(), pos.getZ()) || !isShulkerBox(pos.getX(), pos.getY(), pos.getZ());
        });
        shulkerCollisions.values().forEach(ShulkerCollisionState::tick);
    }
    public List<ShulkerCollisionState.Frame> snapshotShulkerFrames() {
        return shulkerCollisions.values().stream().map(ShulkerCollisionState::snapshot).toList();
    }

    public void apply(org.cloudburstmc.protocol.bedrock.packet.DimensionDataPacket packet) {
        for (var definition : packet.getDefinitions()) {
            final int id = switch (definition.getId()) {
                case "minecraft:overworld" -> 0;
                case "minecraft:nether" -> 1;
                case "minecraft:the_end" -> 2;
                default -> -1;
            };
            if (id >= 0) dimensionBounds.put(id, new int[]{definition.getMinimumHeight(), definition.getMaximumHeight()});
        }
    }
    public int minY(int id) {
        int[] bounds = dimensionBounds.get(id);
        return bounds == null ? minYForDimension(id) : bounds[0];
    }
    public int maxY(int id) {
        int[] bounds = dimensionBounds.get(id);
        return bounds == null ? maxYForDimension(id) : bounds[1];
    }

    public void setProtocolVersion(int version) { protocolVersion = version; }
    public void setRuntimeBlockPalette(NetworkBlockStateRegistry.RuntimePalette palette) { runtimeBlockPalette = palette; }
    public void apply(StartGamePacket packet) {
        var registry = NetworkBlockStateRegistry.fromStartGame(protocolVersion, packet, runtimeBlockPalette);
        clear();
        dimension = packet.getDimensionId();
        blockRegistryChecksum = packet.getBlockRegistryChecksum();
        hashedBlockIds = packet.isBlockNetworkIdsHashed();
        blockStates = registry;
        airNetworkId = blockStates.airNetworkId();
    }

    public void put(int x, int z, CachedChunk chunk) {
        shulkerCollisions.keySet().removeIf(pos -> (pos.getX() >> 4) == x && (pos.getZ() >> 4) == z);
        if (chunk != null) {
            bucketPredictions.observeChunk(x, z,
                    key -> chunk.getFullBlock(key.x() & 15, key.y(), key.z() & 15, key.layer()));
            this.chunks.put(chunkKey(x, z), chunk);
        }
    }


    public void putAcknowledgedChunk(int x, int z, CachedChunk chunk, long sentRevision) {
        if (chunk != null) {
            bucketPredictions.acknowledgeChunk(x, z, sentRevision,
                    key -> chunk.getFullBlock(key.x() & 15, key.y(), key.z() & 15, key.layer()));
        }
        final CachedChunk previous = getChunk(x, z);
        if (chunk != null && previous != null) {
            for (final var entry : bucketPredictions.blocks().entrySet()) {
                final var key = entry.getKey();
                if ((key.x() >> 4) == x && (key.z() >> 4) == z
                        && entry.getValue().revision() > sentRevision) {
                    chunk.setFullBlock(key.x() & 15, key.y(), key.z() & 15, key.layer(),
                            previous.getFullBlock(key.x() & 15, key.y(), key.z() & 15, key.layer()));
                }
            }
        }
        put(x, z, chunk);
    }


    public void clearChunks() {
        chunks.clear();
        bucketPredictions.clear();
        blockBreakEpoch++;
        recentPlacementSupports.clear();
    }
    public long getBlockBreakEpoch() { return blockBreakEpoch; }
    public long bucketPredictionRevision() { return bucketPredictions.revision(); }

    public void ensurePartialColumn(int x,int z){
        if(getChunk(x,z)==null)put(x,z,CachedChunk.partialNetwork(getMinY(),getHeightY(),(getHeightY()-getMinY())>>4,airNetworkId));
    }
    public boolean isSectionKnown(int blockX,int y,int blockZ){
        CachedChunk chunk=getChunk(blockX>>4,blockZ>>4);
        return chunk!=null && (y<getMinY()||y>=getHeightY()||chunk.isSectionKnown((y-getMinY())>>4));
    }
    public boolean areSectionsKnown(int blockX,int blockZ,int fromY,int toY){
        if(getChunk(blockX>>4,blockZ>>4)==null)return false;
        int low=Math.max(fromY,getMinY()),high=Math.min(toY,getHeightY()-1);
        for(int section=low>>4;section<=(high>>4);section++)if(!isSectionKnown(blockX,section<<4,blockZ))return false;
        return true;
    }

    public boolean applyAcknowledgedSection(int x,int sectionY,int z,int[][] layers,long sentRevision){
        int index=sectionY-(getMinY()>>4);if(index<0||index>=((getHeightY()-getMinY())>>4))return false;
        ensurePartialColumn(x,z);CachedChunk chunk=getChunk(x,z);
        var protectedBlocks=new HashMap<BucketPredictionTracker.BlockKey,Integer>();
        for(var entry:bucketPredictions.blocks().entrySet()){
            var key=entry.getKey();if((key.x()>>4)==x&&(key.z()>>4)==z&&(key.y()>>4)==sectionY&&entry.getValue().revision()>sentRevision)
                protectedBlocks.put(key,chunk.getFullBlock(key.x()&15,key.y(),key.z()&15,key.layer()));
        }
        if(layers==null)chunk.invalidateSection(index);else{
            chunk.setNetworkSection(index,layers);
            bucketPredictions.acknowledgeSection(x,sectionY,z,sentRevision,key->chunk.getFullBlock(key.x()&15,key.y(),key.z()&15,key.layer()));
        }
        for(var entry:protectedBlocks.entrySet()){var key=entry.getKey();chunk.setFullBlock(key.x()&15,key.y(),key.z()&15,key.layer(),entry.getValue());}
        bucketPredictions.observeSection(x,sectionY,z,key->chunk.getFullBlock(key.x()&15,key.y(),key.z()&15,key.layer()));
        shulkerCollisions.keySet().removeIf(pos->(pos.getX()>>4)==x&&(pos.getY()>>4)==sectionY&&(pos.getZ()>>4)==z);
        chunk.clearBlockEntitiesInSection(sectionY);return true;
    }
    public void removeFromCache(int x, int z) {
        shulkerCollisions.keySet().removeIf(pos -> (pos.getX() >> 4) == x && (pos.getZ() >> 4) == z);
        chunks.remove(chunkKey(x, z));
        recentPlacementSupports.keySet().removeIf(pos -> (pos.getX() >> 4) == x && (pos.getZ() >> 4) == z);
        bucketPredictions.retainChunks((cx, cz) -> cx != x || cz != z);
    }
    public void retainNukkitUsedChunks(long[] hashes) {
        java.util.Set<Long> retained = new java.util.HashSet<>(hashes.length * 2 + 1);

        for (long hash : hashes) retained.add(chunkKey((int) (hash >> 32), (int) hash));
        chunks.keySet().removeIf(key -> !retained.contains(key));
        recentPlacementSupports.keySet().removeIf(pos -> !retained.contains(chunkKey(pos.getX() >> 4, pos.getZ() >> 4)));
        shulkerCollisions.keySet().removeIf(pos -> !retained.contains(chunkKey(pos.getX() >> 4, pos.getZ() >> 4)));
        bucketPredictions.retainChunks((x, z) -> retained.contains(chunkKey(x, z)));
    }
    public boolean applyAcknowledgedBlock(int x, int y, int z, int layer, int networkId, long sentRevision) {
        if (bucketPredictions.superseded(x, y, z, layer, sentRevision)) return false;
        if (layer == 0) rememberPlacementSupport(x, y, z, networkId);
        bucketPredictions.acknowledge(x, y, z, layer, networkId, sentRevision);
        updateBlock(x, y, z, layer, networkId);
        return true;
    }
    public void updateBlock(int x, int y, int z, int layer, int networkId) {
        if (isChunkLoaded(x >> 4, z >> 4, dimension) && y >= getMinY() && y < getHeightY() && layer >= 0 && layer <= 1)
            bucketPredictions.observeBlock(x, y, z, layer, networkId);
        if (layer == 0) {
            NetworkBlockState next = networkBlockState(networkId);
            if (next == null || !(next.blockId() == 218 || next.blockId() == 205 || next.identifierContains("shulker_box")))
                shulkerCollisions.remove(Vector3i.from(x, y, z));
        }
        CachedChunk chunk = getChunk(x >> 4, z >> 4);
        if (chunk != null) chunk.setFullBlock(x & 15, y, z & 15, layer, networkId);
    }
    public boolean predictBlockBreak(Vector3i position) {
        return predictNetworkBlockBreak(position.getX(), position.getY(), position.getZ(), airNetworkId);
    }

    protected boolean predictNetworkBlockBreak(int x, int y, int z, int airNetworkId) {
        if (!isChunkLoaded(x, z) || y < getMinY() || y >= getHeightY()) return false;
        rememberPlacementSupport(x, y, z, airNetworkId);
        bucketPredictions.predictBreak(x, y, z, 0, airNetworkId);
        updateBlock(x, y, z, 0, airNetworkId);
        return true;
    }

    public boolean isPredictedBlockBreak(Vector3i position) {
        final int x = position.getX(), y = position.getY(), z = position.getZ();
        return isChunkLoaded(x, z) && y >= getMinY() && y < getHeightY()
                && bucketPredictions.isPredictedBreak(x, y, z, 0, getRawBlockAt(x, y, z, 0));
    }

    public void beginBlockBreak(Vector3i position) {
        bucketPredictions.beginBreak(position.getX(), position.getY(), position.getZ(), 0);
    }

    public void predictBucketNetworkBlock(int x, int y, int z, int layer, int networkId) {
        if (!isChunkLoaded(x >> 4, z >> 4, dimension) || y < getMinY() || y >= getHeightY() || layer < 0 || layer > 1) return;
        bucketPredictions.predict(x, y, z, layer, networkId);
        updateBlock(x, y, z, layer, networkId);
    }


    public void predictLocalBlock(Vector3i position, int layer, int legacyFullId) {
        predictBucketNetworkBlock(position.getX(), position.getY(), position.getZ(), layer,
                networkIdForLegacy(legacyFullId));
    }
    public void predictBucketBlock(Vector3i position, int layer, int legacyFullId) {
        predictLocalBlock(position, layer, legacyFullId);
    }
    public void updateLegacyBlock(Vector3i position, int layer, int legacyFullId) {
        updateLegacyBlock(position.getX(), position.getY(), position.getZ(), layer, legacyFullId);
    }
    public void updateLegacyBlock(int x, int y, int z, int layer, int legacyFullId) {
        updateBlock(x, y, z, layer, networkIdForLegacy(legacyFullId));
    }
    public void updateBlock(int x, int y, int z, int layer, int blockId, int blockData) {
        updateLegacyBlock(x, y, z, layer, ac.navro.proxy.data.block.BlockStateEncoding.fullId(blockId, blockData));
    }
    public int networkIdForLegacy(int legacyFullId) {
        if (blockStates == null) throw new IllegalStateException("Block palette is not initialized");
        int networkId = blockStates.networkIdForLegacy(legacyFullId);
        if (networkId == -1) throw new IllegalStateException("No native legacy mapping in this connection palette");
        return networkId;
    }
    public int getRawBlockAt(int x, int y, int z, int layer) {
        CachedChunk chunk = getChunk(x >> 4, z >> 4);
        return chunk == null || y < getMinY() || y >= getHeightY()
                ? airNetworkId : chunk.getFullBlock(x & 15, y, z & 15, layer);
    }
    public CachedChunk getChunk(int x, int z) { return chunks.get(chunkKey(x, z)); }
    public CachedChunk chunk(int x, int z, int requestedDimension) {
        return dimension == requestedDimension ? getChunk(x, z) : null;
    }
    public BlockUpdate block(int x, int y, int z, int requestedDimension, int layer) {
        if (dimension != requestedDimension || getChunk(x >> 4, z >> 4) == null) return null;
        int runtimeId = getRawBlockAt(x, y, z, layer);
        WireBlock wire = wireBlock(runtimeId);
        return new BlockUpdate(x, y, z, dimension, layer, runtimeId,
                wire == null ? "" : wire.identifier(), new WireBlockDefinition(runtimeId));
    }

    public static int minYForDimension(int dimension) { return dimension == 0 ? -64 : 0; }
    public static int maxYForDimension(int dimension) {
        return switch (dimension) { case 0 -> 320; case 1 -> 128; case 2 -> 256; default -> 320; };
    }
    public int getMinY() { return minY(dimension); }
    public int getHeightY() { return maxY(dimension); }
    public int airNetworkId() { return airNetworkId; }
    public void setRadius(int value) { radius = value; }
    public int getRadius() { return radius; }
    public void setRadiusCenter(org.cloudburstmc.math.vector.Vector3i value) { radiusCenter = value; }
    public org.cloudburstmc.math.vector.Vector3i getRadiusCenter() { return radiusCenter; }
    public FluidState fluidAt(int x, int y, int z, int dimension) {
        FluidState overlay = fluidState(block(x, y, z, dimension, 1));
        return overlay.type() == FluidState.Type.WATER ? overlay : fluidState(block(x, y, z, dimension, 0));
    }

    private FluidState fluidState(BlockUpdate update) {
        if (update == null) return FluidState.EMPTY;
        NetworkBlockState state = networkBlockState(update.networkId());
        if (state == null) return FluidState.EMPTY;
        var nativeState = NativeFluidBlockProperties.forState(protocolVersion, state);
        boolean water = nativeState != null && nativeState.has(NativeFluidBlockProperties.WATER)
                || state.is("minecraft:water") || state.is("minecraft:flowing_water") || state.is("minecraft:bubble_column");
        boolean lava = nativeState != null && nativeState.has(NativeFluidBlockProperties.LAVA)
                || state.is("minecraft:lava") || state.is("minecraft:flowing_lava");
        if (!water && !lava) return FluidState.EMPTY;
        int level = state.intProperty("liquid_depth", nativeState == null ? state.blockData() : nativeState.damage());
        float height = level == 0 || level >= 8 ? 8.0F / 9.0F : (8.0F - level) / 9.0F;
        return new FluidState(water ? FluidState.Type.WATER : FluidState.Type.LAVA, height, level);
    }

    public boolean hasChunksAt(int minX, int minZ, int maxX, int maxZ, int dimension) {
        for (int x=minX>>4; x<=(maxX>>4); x++) for (int z=minZ>>4; z<=(maxZ>>4); z++) if (!isChunkLoaded(x,z,dimension)) return false;
        return true;
    }


    public Vec3 flowAt(int x, int y, int z, int dimension) {
        FluidState current = fluidAt(x, y, z, dimension);
        if (!current.isFluid()) return Vec3.ZERO;
        NetworkBlockState base = networkBlockState(getRawBlockAt(x, y, z, 0));
        var nativeBase = NativeFluidBlockProperties.forState(protocolVersion, base);
        if (nativeBase != null && nativeBase.id() == BlockID.BUBBLE_COLUMN) return new Vec3(0, 0, 0);
        Vec3 flow = new Vec3(0, 0, 0);
        int decay = current.effectiveFlowDecay();
        for (Direction direction : FLUID_HORIZONTAL_DIRECTIONS) {
            int nx = x + direction.getXOffset(), nz = z + direction.getZOffset();
            FluidState next = fluidAt(nx, y, nz, dimension);
            int neighborDecay = next.type() == current.type() ? next.effectiveFlowDecay() : -1;
            if (neighborDecay < 0) {
                if (!blocksMotion(nx, y, nz, dimension)) {
                    FluidState below = fluidAt(nx, y - 1, nz, dimension);
                    if (below.type() == current.type()) {
                        neighborDecay = below.effectiveFlowDecay();
                        if (neighborDecay >= 0) {
                            int difference = neighborDecay - (decay - 8);
                            flow = flow.add(direction.getXOffset() * difference,
                                    direction.getYOffset() * difference, direction.getZOffset() * difference);
                        }
                    }
                }
            } else {
                int difference = neighborDecay - decay;
                flow = flow.add(direction.getXOffset() * difference,
                        direction.getYOffset() * difference, direction.getZOffset() * difference);
            }
        }
        if (current.level() >= 8) {
            for (Direction direction : FLUID_HORIZONTAL_DIRECTIONS) {
                int nx = x + direction.getXOffset(), nz = z + direction.getZOffset();
                if (isSolidFluidFace(current, nx, y, nz, dimension, direction)
                        || isSolidFluidFace(current, nx, y + 1, nz, dimension, direction)) {
                    flow = flow.normalize().add(0, -6, 0);
                    break;
                }
            }
        }
        return flow.normalize();
    }

    private static final Direction[] FLUID_HORIZONTAL_DIRECTIONS = {
            Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST
    };

    private boolean blocksMotion(int x, int y, int z, int dimension) {
        BlockUpdate update = block(x, y, z, dimension, 0);
        return update != null && FluidBlockCollision.blocksMotion(this, networkBlockState(update.networkId()));
    }

    private boolean isSolidFluidFace(FluidState fluid, int x, int y, int z, int dimension, Direction direction) {
        if (fluidAt(x, y, z, dimension).type() == fluid.type()) return false;
        if (direction == Direction.UP) return true;
        BlockUpdate update = block(x, y, z, dimension, 0);
        if (update == null) return false;
        NetworkBlockState state = networkBlockState(update.networkId());
        var nativeState = NativeFluidBlockProperties.forState(protocolVersion, state);
        if (nativeState != null) {
            int id = nativeState.id();
            if (id == BlockID.ICE || id == BlockID.FROSTED_ICE || id == BlockID.BLUE_ICE || id == BlockID.PACKED_ICE) return false;
        }
        return FluidBlockCollision.isFaceSturdy(this, state);
    }

    public ac.navro.proxy.collision.BedrockCollision.Actor fluidCollisionActor() {
        return collisionActor.get();
    }

    public WireBlock wireBlock(int networkId) {
        WireBlock cached = palette.get(networkId);
        if (cached != null || blockStates == null) return cached;
        NetworkBlockState state = blockStates.resolve(networkId);
        if (state == null) return null;
        NbtMap tag = state.stateTag();

        List<Box> shape = blockStates.hasComponents(state) ? blockStates.declaredCollision(state)
                : protocolVersion < 0 ? null : CollisionShapeRegistry.forProtocol(protocolVersion).shapeForState(tag);
        if (shape == null && protocolVersion >= 0 && !blockStates.hasComponents(state)) {
            var nativeState = NativeFluidBlockProperties.forState(protocolVersion, state);
            if (nativeState != null) shape = nativeState.generic();
        }
        WireBlock wire = new WireBlock(state.networkId(), state.identifier(), tag, shape);
        palette.put(networkId, wire);
        return wire;
    }
    public NativeBlockProperties.Properties getNativeBlockProperties(Vector3i position, int layer) {
        NetworkBlockState state = networkBlockState(getRawBlockAt(position.getX(), position.getY(), position.getZ(), layer));
        if (state == null) throw new IllegalStateException("Block palette is not initialized");
        return NativeBlockProperties.forState(state);
    }

    public NetworkBlockState networkBlockState(int networkId) {
        return blockStates == null ? null : blockStates.resolve(networkId);
    }

    public String blockIdentifierAt(int x, int y, int z, int dimension, int layer) {
        BlockUpdate update = block(x, y, z, dimension, layer);
        return update == null ? "" : update.identifier();
    }

    public String blockStateStringAt(int x, int y, int z, int dimension, int layer, String key) {
        BlockUpdate update = block(x, y, z, dimension, layer);
        NetworkBlockState state = update == null ? null : networkBlockState(update.networkId());
        return state == null ? null : state.stringProperty(key, null);
    }

    public boolean blockBooleanPropertyAt(int x, int y, int z, int dimension,
                                          int layer, String key, boolean fallback) {
        BlockUpdate update = block(x, y, z, dimension, layer);
        NetworkBlockState state = update == null ? null : networkBlockState(update.networkId());
        return state == null ? fallback : state.booleanProperty(key, fallback);
    }

    public boolean isAirAt(int x, int y, int z, int dimension) {
        String id = blockIdentifierAt(x, y, z, dimension, 0);
        if (dimension == this.dimension && !isChunkLoaded(x >> 4, z >> 4, dimension)) return true;
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air")
                || id.equals("minecraft:void_air");
    }






    public List<Box> collisionShapes(Box query, int requestedDimension) {
        return collisionEntries(query, requestedDimension, collisionPhysics).stream().map(CollisionShapeEntry::shape).toList();
    }
    public List<CollisionShapeEntry> collisionEntries(Box query, int requestedDimension, CollisionPhysicsState state) {
        if (query == null || !query.isValid() || requestedDimension != dimension) return List.of();
        return state.blocks(query, () -> fetchCollisionEntries(query, requestedDimension, state));
    }
    public List<CollisionShapeEntry> movementCollisionEntries(Box query, int requestedDimension, CollisionPhysicsState state) {
        if (query == null || !query.isValid() || requestedDimension != dimension) return List.of();
        return state.movementBlocks(query, () -> fetchCollisionEntries(query, requestedDimension, state));
    }
    private List<CollisionShapeEntry> fetchCollisionEntries(Box query, int requestedDimension, CollisionPhysicsState state) {
        List<CollisionShapeEntry> result = new java.util.ArrayList<>();
        CuboidBlockIterator iterator = CuboidBlockIterator.iterator(
                blockStates == null ? query : blockStates.declaredCollisionQuery(query));
        while (iterator.step()) {
            int x = iterator.getX(), y = iterator.getY(), z = iterator.getZ();
            BlockUpdate update = block(x, y, z, requestedDimension, 0);
            if (update == null) continue;
            NetworkBlockState block = networkBlockState(update.networkId());
            if (y == getMinY() - 41) {
                Box floor = new Box(0, 0, 0, 1, 1, 1).offset(x, y, z);
                if (floor.intersects(query)) result.add(new CollisionShapeEntry(floor, block));
                continue;
            }
            if (addDeclaredCollisions(result, block, update.networkId(), x, y, z, query)) continue;
            if (NativePistonProperties.isMovingBlock(block)) continue;
            if (isShulkerBox(x, y, z)) {
                for (Box shape : getShulkerCollision(Vector3i.from(x, y, z), state))
                    if (shape.intersects(query)) result.add(new CollisionShapeEntry(shape, block));
                continue;
            }
            List<Box> piston = null;
            if (NativePistonProperties.isPiston(block))
                piston = PistonBlock.from(block).getCollisionShape();
            else if (NativePistonProperties.isPistonHead(block))
                piston = PistonArmBlock.getCollisionShape(this, Vector3i.from(x, y, z), block,
                        NativePistonProperties.headFacing(block));
            if (piston != null) {
                for (Box shape : piston) {
                    Box worldShape = shape.offset(x, y, z);
                    if (worldShape.intersects(query)) result.add(new CollisionShapeEntry(worldShape, block));
                }
                continue;
            }
            List<Box> dynamic = FluidBlockCollision.movementCollision(this,Vector3i.from(x,y,z),block);
            if (dynamic != null) {
                for (Box shape : dynamic) {
                    Box worldShape = shape.offset(x,y,z);
                    if (worldShape.intersects(query)) result.add(new CollisionShapeEntry(worldShape,block));
                }
                continue;
            }
            List<Box> plant = null;
            boolean air = block != null && (block.blockId() == 0 || block.is("minecraft:air")
                    || block.is("minecraft:cave_air") || block.is("minecraft:void_air"));
            if (block != null && (block.blockId() == ac.navro.proxy.data.block.NativePlantProperties.BAMBOO
                    || block.is("minecraft:bamboo")))
                plant = air ? List.of() : ac.navro.proxy.collision.PlantCollision.bamboo(Vector3i.from(x, y, z), block);
            else if (block != null && (block.blockId() == ac.navro.proxy.data.block.NativePlantProperties.POINTED_DRIPSTONE
                    || block.is("minecraft:pointed_dripstone")))
                plant = air ? List.of() : ac.navro.proxy.collision.PlantCollision.pointedDripstone(Vector3i.from(x, y, z), block);
            if (plant != null) {
                for (Box shape : plant) {
                    Box worldShape = shape.offset(x, y, z);
                    if (worldShape.intersects(query)) result.add(new CollisionShapeEntry(worldShape, block));
                }
                continue;
            }
            List<Box> overridden = ac.navro.proxy.collision.BedrockCollision.getCollisionBox(
                    this,x,y,z,block,collisionActor.get());
            if (overridden != null) {
                for (Box shape : overridden) {
                    Box worldShape = shape.offset(x,y,z);
                    if (worldShape.intersects(query)) result.add(new CollisionShapeEntry(worldShape,block));
                }
                continue;
            }
            WireBlock wire = wireBlock(update.networkId());
            if (wire == null || !wire.collisionKnown()) continue;
            for (Box shape : wire.collisionShape()) {
                Box worldShape = shape.offset(x, y, z);
                if (worldShape.intersects(query)) result.add(new CollisionShapeEntry(worldShape, block));
            }
        }
        return List.copyOf(result);
    }

    public List<CollisionShapeEntry> collisionCellFragments(int x,int y,int z,Box query,CollisionPhysicsState state){
        int requestedDimension=dimension;
        List<CollisionShapeEntry> result=new java.util.ArrayList<>();
        for(int once=0;once<1;once++){
            BlockUpdate update = block(x, y, z, requestedDimension, 0);
            if (update == null) continue;
            NetworkBlockState block = networkBlockState(update.networkId());
            if (y == getMinY() - 41) {
                Box floor = new Box(0, 0, 0, 1, 1, 1).offset(x, y, z);
                if ((query == null || floor.intersects(query))) result.add(new CollisionShapeEntry(floor, block));
                continue;
            }
            if (addDeclaredCollisions(result, block, update.networkId(), x, y, z, query)) continue;
            if (NativePistonProperties.isMovingBlock(block)) continue;
            if (isShulkerBox(x, y, z)) {
                for (Box shape : getShulkerCollision(Vector3i.from(x, y, z), state))
                    if ((query == null || shape.intersects(query))) result.add(new CollisionShapeEntry(shape, block));
                continue;
            }
            List<Box> piston = null;
            if (NativePistonProperties.isPiston(block))
                piston = PistonBlock.from(block).getCollisionShape();
            else if (NativePistonProperties.isPistonHead(block))
                piston = PistonArmBlock.getCollisionShape(this, Vector3i.from(x, y, z), block,
                        NativePistonProperties.headFacing(block));
            if (piston != null) {
                for (Box shape : piston) {
                    Box worldShape = shape.offset(x, y, z);
                    if ((query == null || worldShape.intersects(query))) result.add(new CollisionShapeEntry(worldShape, block));
                }
                continue;
            }
            List<Box> dynamic = FluidBlockCollision.movementCollision(this,Vector3i.from(x,y,z),block);
            if (dynamic != null) {
                for (Box shape : dynamic) {
                    Box worldShape = shape.offset(x,y,z);
                    if (query == null || worldShape.intersects(query)) result.add(new CollisionShapeEntry(worldShape,block));
                }
                continue;
            }
            List<Box> plant = null;
            boolean air = block != null && (block.blockId() == 0 || block.is("minecraft:air")
                    || block.is("minecraft:cave_air") || block.is("minecraft:void_air"));
            if (block != null && (block.blockId() == ac.navro.proxy.data.block.NativePlantProperties.BAMBOO
                    || block.is("minecraft:bamboo")))
                plant = air ? List.of() : ac.navro.proxy.collision.PlantCollision.bamboo(Vector3i.from(x, y, z), block);
            else if (block != null && (block.blockId() == ac.navro.proxy.data.block.NativePlantProperties.POINTED_DRIPSTONE
                    || block.is("minecraft:pointed_dripstone")))
                plant = air ? List.of() : ac.navro.proxy.collision.PlantCollision.pointedDripstone(Vector3i.from(x, y, z), block);
            if (plant != null) {
                for (Box shape : plant) {
                    Box worldShape = shape.offset(x, y, z);
                    if ((query == null || worldShape.intersects(query))) result.add(new CollisionShapeEntry(worldShape, block));
                }
                continue;
            }
            List<Box> overridden = ac.navro.proxy.collision.BedrockCollision.getCollisionBox(
                    this,x,y,z,block,collisionActor.get());
            if (overridden != null) {
                for (Box shape : overridden) {
                    Box worldShape = shape.offset(x,y,z);
                    if (query == null || worldShape.intersects(query)) result.add(new CollisionShapeEntry(worldShape,block));
                }
                continue;
            }
            WireBlock wire = wireBlock(update.networkId());
            if (wire == null || !wire.collisionKnown()) continue;
            for (Box shape : wire.collisionShape()) {
                Box worldShape = shape.offset(x, y, z);
                if ((query == null || worldShape.intersects(query))) result.add(new CollisionShapeEntry(worldShape, block));
            }
        }
        return List.copyOf(result);
    }



    private boolean addDeclaredCollisions(List<CollisionShapeEntry> result, NetworkBlockState block, int networkId,
                                          int x, int y, int z, Box query) {
        if (blockStates == null || !blockStates.hasComponents(block)) return false;
        WireBlock wire = wireBlock(networkId);
        if (wire != null && wire.collisionKnown()) for (Box shape : wire.collisionShape()) {
            Box worldShape = shape.offset(x, y, z);
            if (query == null || worldShape.intersects(query)) result.add(new CollisionShapeEntry(worldShape, block));
        }
        return true;
    }

    public Map<Integer, WireBlock> palette() {
        if (blockStates != null) for (int id : blockStates.states().keySet()) wireBlock(id);
        return Map.copyOf(palette);
    }
    public boolean isChunkLoaded(int x, int z, int requestedDimension) {
        return dimension == requestedDimension && chunks.containsKey(chunkKey(x, z));
    }
    public boolean isChunkLoaded(int blockX, int blockZ) { return getChunk(blockX >> 4, blockZ >> 4) != null; }
    public int dimension() { return dimension; }
    public long blockRegistryChecksum() { return blockRegistryChecksum; }
    public boolean hashedBlockIds() { return hashedBlockIds; }
    public int protocolVersion() { return protocolVersion; }
    public int chunkCount() { return chunks.size(); }
    public int blockUpdateCount() {
        int count = 0;
        for (CachedChunk chunk : chunks.values()) for (var section : chunk.networkSections)
            if (section != null && section.layers != null) for (int[] layer : section.layers)
                if (layer != null) count += layer.length;
        return count;
    }
    public void clear() {
        clearChunks();
        palette.clear();
        shulkerCollisions.clear();
        collisionPhysics.reset();
        runtimeBlockPalette = null;
        blockStates = null;
        blockRegistryChecksum = 0;
        hashedBlockIds = false;
        airNetworkId = 0;
        radius = 0;
        radiusCenter = null;
    }
    public void changeDimension(int value) { clearChunks(); setDimension(value); }
    public void setDimension(int value) {
        if (dimension != value) {
            bucketPredictions.clear();
            blockBreakEpoch++;
            recentPlacementSupports.clear();
            shulkerCollisions.clear();
            collisionPhysics.reset();
        }
        dimension = value;
    }
    private static long chunkKey(int x, int z) { return (x & 0xffffffffL) | ((long) z << 32); }
    private record WireBlockDefinition(int runtimeId) implements BlockDefinition {
        @Override public int getRuntimeId() { return runtimeId; }
    }
    public static final class CachedChunk {
        private long loadedTick = -1;

        public long loadedTick() { return loadedTick; }
        public void markLoaded(long tick) { if (loadedTick < 0) loadedTick = Math.max(0, tick); }

        private final int minY;
        private final int maxY;
        private final int airNetworkId;
        private final NetworkSection[] networkSections;
        private final boolean[] knownSections;
        private final Map<Long, NbtMap> blockEntities = new HashMap<>();

        private CachedChunk(int minY, int maxY, int airNetworkId, int sectionCount) {
            this.minY = minY;
            this.maxY = maxY;
            this.airNetworkId = airNetworkId;
            this.networkSections = new NetworkSection[Math.max(0, sectionCount)];
            this.knownSections = new boolean[networkSections.length];java.util.Arrays.fill(knownSections,true);
        }

        public static CachedChunk network(int minY, int maxY, int sectionCount, int airNetworkId) {
            return new CachedChunk(minY, maxY, airNetworkId, sectionCount);
        }
        public static CachedChunk partialNetwork(int minY,int maxY,int sectionCount,int airNetworkId){
            CachedChunk chunk=network(minY,maxY,sectionCount,airNetworkId);java.util.Arrays.fill(chunk.knownSections,false);return chunk;
        }
        public boolean isSectionKnown(int index){return index>=0&&index<knownSections.length&&knownSections[index];}
        public void invalidateSection(int index){if(index>=0&&index<knownSections.length){networkSections[index]=null;knownSections[index]=false;}}
        private void clearBlockEntitiesInSection(int y){blockEntities.keySet().removeIf(key->((key&0xfffL)>>4)==(y&255));}

        public void setNetworkSection(int sectionY, int[][] layers) {
            if (sectionY < 0 || sectionY >= networkSections.length) {
                return;
            }
            networkSections[sectionY] = new NetworkSection(layers, airNetworkId);
            knownSections[sectionY] = true;
        }

        public int getFullBlock(int x, int y, int z, int layer) {
            if (layer < 0 || y < minY || y >= maxY) {
                return airNetworkId;
            }
            final int sectionY = (y - minY) >> 4;
            if (sectionY < 0 || sectionY >= networkSections.length) {
                return airNetworkId;
            }
            final NetworkSection section = networkSections[sectionY];
            return section == null ? airNetworkId : section.getFullBlock(x & 15, y & 15, z & 15, layer);
        }

        void setFullBlock(int x, int y, int z, int layer, int networkId) {
            if (layer < 0 || y < minY || y >= maxY) {
                return;
            }
            final int sectionY = (y - minY) >> 4;
            if (sectionY < 0 || sectionY >= networkSections.length) {
                return;
            }

            NetworkSection section = networkSections[sectionY];
            if (section == null) {


                if (networkId == 0) {
                    return;
                }
                section = NetworkSection.empty(airNetworkId, 2);
                networkSections[sectionY] = section;
            }
            section.setFullBlock(x & 15, y & 15, z & 15, layer, networkId);
        }

        NbtMap getBlockEntityTag(int x, int y, int z) {
            return blockEntities.get(blockKey(x, y, z));
        }

        void setBlockEntityTag(int x, int y, int z, NbtMap tag) {
            long key = blockKey(x, y, z);
            if (tag == null) {
                blockEntities.remove(key);
            } else {
                blockEntities.put(key, ItemUtil.copyTag(tag));
            }
        }

        private static long blockKey(int x, int y, int z) {
            return ((long) x & 0x3ffffffL) << 38
                    | ((long) z & 0x3ffffffL) << 12
                    | (long) y & 0xfffL;
        }

        private static final class NetworkSection {
            private final int[][] layers;
            private final int airNetworkId;

            private NetworkSection(int[][] layers, int airNetworkId) {
                this.layers = layers;
                this.airNetworkId = airNetworkId;
            }

            private static NetworkSection empty(int airNetworkId, int layerCount) {
                int[][] layers = new int[Math.max(0, layerCount)][];
                for (int layer = 0; layer < layers.length; layer++) {
                    layers[layer] = new int[4096];
                    java.util.Arrays.fill(layers[layer], airNetworkId);
                }
                return new NetworkSection(layers, airNetworkId);
            }

            private int getFullBlock(int x, int y, int z, int layer) {
                if (layers == null || layer < 0 || layer >= layers.length) {
                    return airNetworkId;
                }
                final int[] blocks = layers[layer];
                if (blocks == null) {
                    return airNetworkId;
                }
                return blocks[(x << 8) | (z << 4) | y];
            }

            private void setFullBlock(int x, int y, int z, int layer, int networkId) {
                if (layers == null || layer < 0 || layer >= layers.length || layers[layer] == null) {
                    return;
                }
                layers[layer][(x << 8) | (z << 4) | y] = networkId;
            }
        }
    }
}
