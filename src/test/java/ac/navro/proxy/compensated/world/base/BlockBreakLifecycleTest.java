package ac.navro.proxy.compensated.world.base;

import ac.navro.proxy.util.LatencyUtil;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockBreakLifecycleTest {
    private static final Vector3i FIRST=Vector3i.from(8,64,8),SECOND=Vector3i.from(9,64,8);
    private static CompensatedWorld world(){
        var w=new CompensatedWorld();w.setProtocolVersion(2193);w.apply(new StartGamePacket());w.put(0,0,chunk(w));
        w.updateLegacyBlock(FIRST,0,1<<13);w.updateLegacyBlock(SECOND,0,1<<13);return w;
    }
    private static CompensatedWorld.CachedChunk chunk(CompensatedWorld w){return CompensatedWorld.CachedChunk.network(-64,320,24,w.airNetworkId());}
    private static BucketPredictionTracker.State state(CompensatedWorld w,Vector3i p)throws Exception {
        var f=CompensatedWorld.class.getDeclaredField("bucketPredictions");f.setAccessible(true);
        return ((BucketPredictionTracker)f.get(w)).blocks().get(new BucketPredictionTracker.BlockKey(p.getX(),p.getY(),p.getZ(),0)).state();
    }
    @Test void separateBreaksConfirmOnlyAtTheirOwnRevisionsAndCurrentCorrectionsRejectThem()throws Exception {
        var w=world();long old=w.bucketPredictionRevision();assertTrue(w.predictBlockBreak(FIRST));long first=w.bucketPredictionRevision();
        assertTrue(w.predictBlockBreak(SECOND));assertTrue(w.isPredictedBlockBreak(FIRST));assertTrue(w.isPredictedBlockBreak(SECOND));
        assertFalse(w.applyAcknowledgedBlock(8,64,8,0,w.networkIdForLegacy(1<<13),old));
        assertEquals(BucketPredictionTracker.State.BREAK_PENDING,state(w,FIRST));
        assertTrue(w.applyAcknowledgedBlock(8,64,8,0,w.airNetworkId(),first));
        assertEquals(BucketPredictionTracker.State.BREAK_CONFIRMED,state(w,FIRST));
        assertEquals(BucketPredictionTracker.State.BREAK_PENDING,state(w,SECOND));
        assertTrue(w.applyAcknowledgedBlock(8,64,8,0,w.networkIdForLegacy(1<<13),first));assertFalse(w.isPredictedBlockBreak(FIRST));
    }
    @Test void delayedWholeChunkPreservesLaterAirAndCurrentSnapshotSettlesEachCoordinate()throws Exception {
        var w=world();long old=w.bucketPredictionRevision();var stale=chunk(w);stale.setFullBlock(8,64,8,0,w.networkIdForLegacy(1<<13));
        w.predictBlockBreak(FIRST);w.predictBlockBreak(SECOND);w.putAcknowledgedChunk(0,0,stale,old);
        assertEquals(BucketPredictionTracker.State.BREAK_PENDING,state(w,FIRST));assertTrue(w.isPredictedBlockBreak(FIRST));
        var current=chunk(w);current.setFullBlock(9,64,8,0,w.networkIdForLegacy(1<<13));
        w.putAcknowledgedChunk(0,0,current,w.bucketPredictionRevision());
        assertEquals(BucketPredictionTracker.State.BREAK_CONFIRMED,state(w,FIRST));assertFalse(w.isPredictedBlockBreak(SECOND));
    }
    @Test void newOperationsAndActualReplacementDoNotTreatNaturalAirAsAnAcceptedBreak() {
        var w=world();w.predictBlockBreak(FIRST);w.predictBlockBreak(SECOND);w.beginBlockBreak(FIRST);
        assertFalse(w.isPredictedBlockBreak(FIRST));assertTrue(w.isPredictedBlockBreak(SECOND));
        w.updateLegacyBlock(SECOND,0,1<<13);w.updateLegacyBlock(SECOND,0,0);assertFalse(w.isPredictedBlockBreak(SECOND));
        w.predictBlockBreak(FIRST);w.predictBucketNetworkBlock(8,64,8,0,w.airNetworkId());assertFalse(w.isPredictedBlockBreak(FIRST));
    }
    @Test void realLatencyQueueConfirmsAcceptedBreakOnlyAfterItsServerSnapshotIsAcknowledged()throws Exception {
        var w=world();var latency=new LatencyUtil(null);w.predictBlockBreak(FIRST);long revision=w.bucketPredictionRevision();
        latency.queue(10,true,()->w.applyAcknowledgedBlock(8,64,8,0,w.airNetworkId(),revision));
        assertEquals(BucketPredictionTracker.State.BREAK_PENDING,state(w,FIRST));
        assertTrue(latency.onResponse(10));assertEquals(BucketPredictionTracker.State.BREAK_CONFIRMED,state(w,FIRST));
    }
    @Test void evictionRetainingResetDimensionAndHeightBoundsDiscardTheRightBreaks() {
        var w=world();w.predictBlockBreak(FIRST);long revision=w.bucketPredictionRevision();
        assertFalse(w.predictBlockBreak(Vector3i.from(8,-65,8)));assertFalse(w.predictBlockBreak(Vector3i.from(8,320,8)));
        assertEquals(revision,w.bucketPredictionRevision());w.retainNukkitUsedChunks(new long[0]);assertFalse(w.isPredictedBlockBreak(FIRST));
        assertFalse(w.predictBlockBreak(FIRST));w.put(0,0,chunk(w));w.predictBlockBreak(FIRST);long epoch=w.getBlockBreakEpoch();
        w.clearChunks();assertEquals(epoch+1,w.getBlockBreakEpoch());assertFalse(w.isPredictedBlockBreak(FIRST));
        w.put(0,0,chunk(w));w.predictBlockBreak(FIRST);w.setDimension(1);assertFalse(w.isPredictedBlockBreak(FIRST));
        w.predictBlockBreak(FIRST);w.removeFromCache(0,0);assertFalse(w.isPredictedBlockBreak(FIRST));
    }
}
