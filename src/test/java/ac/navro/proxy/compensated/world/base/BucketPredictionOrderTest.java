package ac.navro.proxy.compensated.world.base;

import ac.navro.proxy.util.LatencyUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BucketPredictionOrderTest {
    private static final int AIR = 100, SNOW = 200, WATER = 300, FLOWING_WATER = 301;
    private static final int X = 136, Y = 66, Z = 52;


    private static final class World extends CompensatedWorld {
        World() { super(); put(X >> 4, Z >> 4, emptyChunk()); }

        @Override public void updateBlock(int x, int y, int z, int layer, int networkId) {
            final CachedChunk chunk = getChunk(x >> 4, z >> 4);
            if (chunk != null) chunk.setFullBlock(x & 15, y, z & 15, layer, networkId);
        }

        int state(int x, int y, int z, int layer) {
            return getChunk(x >> 4, z >> 4).getFullBlock(x & 15, y, z & 15, layer);
        }
    }

    private static CompensatedWorld.CachedChunk emptyChunk() {
        return CompensatedWorld.CachedChunk.network(-64, 320, 24, AIR);
    }

    @Test void oldPlacementAckCannotRestoreSnowAfterPickupButCurrentAckCanCorrectIt() {
        final World world = new World();
        final LatencyUtil latency = new LatencyUtil(null);
        world.predictBucketNetworkBlock(X, Y, Z, 0, SNOW);
        final long placement = world.bucketPredictionRevision();
        latency.queue(3574, true, () -> world.applyAcknowledgedBlock(X, Y, Z, 0, SNOW, placement));
        world.predictBucketNetworkBlock(X, Y, Z, 0, AIR);
        final long pickup = world.bucketPredictionRevision();

        latency.queue(3576, true, () -> world.applyAcknowledgedBlock(X, Y, Z, 0, SNOW, pickup));
        assertEquals(AIR, world.state(X, Y, Z, 0));
        assertTrue(latency.onResponse(3574));
        assertEquals(AIR, world.state(X, Y, Z, 0));
        assertTrue(latency.onResponse(3576));
        assertEquals(SNOW, world.state(X, Y, Z, 0));
    }

    @Test void laterAckReleasesBothPacketsWithoutDiscardingSuccessfulPickup() {
        final World world = new World();
        final LatencyUtil latency = new LatencyUtil(null);
        world.predictBucketNetworkBlock(X, Y, Z, 0, SNOW);
        final long placement = world.bucketPredictionRevision();
        latency.queue(10, true, () -> world.applyAcknowledgedBlock(X, Y, Z, 0, SNOW, placement));
        world.predictBucketNetworkBlock(X, Y, Z, 0, AIR);
        final long pickup = world.bucketPredictionRevision();
        latency.queue(11, true, () -> world.applyAcknowledgedBlock(X, Y, Z, 0, AIR, pickup));
        assertFalse(latency.onResponse(99));
        assertEquals(AIR, world.state(X, Y, Z, 0));
        assertTrue(latency.onResponse(11));
        assertEquals(AIR, world.state(X, Y, Z, 0));
        assertTrue(latency.sentQueue().isEmpty());
    }

    @Test void bucketPredictionDoesNotSuppressUpdatesToOtherCoordinatesOrLayers() {
        final World world = new World();
        final long before = world.bucketPredictionRevision();
        world.predictBucketNetworkBlock(X, Y, Z, 0, WATER);
        assertTrue(world.applyAcknowledgedBlock(X + 1, Y, Z, 0, SNOW, before));
        assertTrue(world.applyAcknowledgedBlock(X, Y, Z, 1, WATER, before));
        assertEquals(WATER, world.state(X, Y, Z, 0));
        assertEquals(SNOW, world.state(X + 1, Y, Z, 0));
        assertEquals(WATER, world.state(X, Y, Z, 1));
    }

    @Test void successiveServerUpdatesFollowingSamePredictionStillApply() {
        final World world = new World();
        world.predictBucketNetworkBlock(X, Y, Z, 0, WATER);
        final long sent = world.bucketPredictionRevision();
        assertTrue(world.applyAcknowledgedBlock(X, Y, Z, 0, WATER, sent));
        assertTrue(world.applyAcknowledgedBlock(X, Y, Z, 0, FLOWING_WATER, sent));
        assertEquals(FLOWING_WATER, world.state(X, Y, Z, 0));
    }

    @Test void delayedFullChunkPreservesNewPickupAndStillLoadsOtherBlocks() {
        final World world = new World();
        world.predictBucketNetworkBlock(X, Y, Z, 0, SNOW);
        final long sent = world.bucketPredictionRevision();
        final var packetChunk = emptyChunk();
        packetChunk.setFullBlock(X & 15, Y, Z & 15, 0, SNOW);
        packetChunk.setFullBlock((X + 1) & 15, Y, Z & 15, 0, WATER);
        world.predictBucketNetworkBlock(X, Y, Z, 0, AIR);
        world.putAcknowledgedChunk(X >> 4, Z >> 4, packetChunk, sent);
        assertEquals(AIR, world.state(X, Y, Z, 0));
        assertEquals(WATER, world.state(X + 1, Y, Z, 0));

        final var correction = emptyChunk();
        correction.setFullBlock(X & 15, Y, Z & 15, 0, SNOW);
        world.putAcknowledgedChunk(X >> 4, Z >> 4, correction, world.bucketPredictionRevision());
        assertEquals(SNOW, world.state(X, Y, Z, 0));
    }

    @Test void unloadedChunkCannotGainPredictedBlocksAndEvictionDropsOldHistory() {
        final World world = new World();
        world.predictBucketNetworkBlock(X, Y, Z, 0, SNOW);
        world.removeFromCache(X >> 4, Z >> 4);
        final long before = world.bucketPredictionRevision();
        world.predictBucketNetworkBlock(X, Y, Z, 0, WATER);
        assertEquals(before, world.bucketPredictionRevision());
        assertFalse(world.isChunkLoaded(X, Z));
        world.put(X >> 4, Z >> 4, emptyChunk());
        assertTrue(world.applyAcknowledgedBlock(X, Y, Z, 0, AIR, 0));
        world.predictBucketNetworkBlock(X, Y, Z, 0, SNOW);
        world.clearChunks();
        world.put(X >> 4, Z >> 4, emptyChunk());
        assertTrue(world.applyAcknowledgedBlock(X, Y, Z, 0, WATER, 0));
    }
}
