package ac.navro.proxy.compensated.world.base;

import ac.navro.proxy.collision.CollisionPhysicsState;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class PlacementSupportReferenceTest {
    private static Vector3i pos(DataInputStream in)throws Exception{return Vector3i.from(in.readInt(),in.readInt(),in.readInt());}
    private static CompensatedWorld.CachedChunk chunk(int air){return CompensatedWorld.CachedChunk.network(-64,320,24,air);}
    private static Object field(CompensatedWorld world,String name)throws Exception {
        var f=CompensatedWorld.class.getDeclaredField(name);f.setAccessible(true);return f.get(world);
    }
    @Test void nativeServerClockHistoryMatchesEveryOriginalCacheAckAndPredictionOperation()throws Exception {
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-placement-support.bin.gz")))) {
            assertEquals(0x4E535031,in.readInt());int count=in.readInt();assertEquals(4,count);
            for(int run=0;run<count;run++) {
                int protocol=in.readInt();boolean hash=in.readBoolean();int[] networks={in.readInt(),in.readInt(),in.readInt(),in.readInt()};
                var points=new ArrayList<Vector3i>();for(int i=0,n=in.readInt();i<n;i++)points.add(pos(in));
                var tick=new AtomicInteger();var w=new CompensatedWorld(new CollisionPhysicsState(),tick::get);
                w.setProtocolVersion(protocol);var start=new StartGamePacket();start.setBlockNetworkIdsHashed(hash);w.apply(start);
                assertEquals(networks[0],w.airNetworkId());w.put(0,0,chunk(networks[0]));w.put(1,0,chunk(networks[0]));
                for(int i=0,n=in.readInt();i<n;i++) {
                    String label="run="+run+" step="+i;
                    tick.set(in.readInt());int op=in.readInt();var point=points.get(in.readInt());int layer=in.readInt();int network=in.readInt();long revision=in.readLong();
                    int x=point.getX(),y=point.getY(),z=point.getZ();boolean accepted=true;
                    switch(op) {
                        case 0->{w.put(0,0,chunk(networks[0]));w.put(1,0,chunk(networks[0]));}
                        case 1,7->w.updateBlock(x,y,z,0,networks[1]);
                        case 2,5,12->accepted=w.predictBlockBreak(point);
                        case 3,8,16->accepted=w.applyAcknowledgedBlock(x,y,z,layer,network,revision);
                        case 4->accepted=w.applyAcknowledgedBlock(x,y,z,0,networks[1],revision);
                        case 6->accepted=w.applyAcknowledgedBlock(x,y,z,0,networks[0],revision);
                        case 9,13->w.predictBucketNetworkBlock(x,y,z,layer,network);
                        case 10->w.beginBlockBreak(point);
                        case 11->w.updateBlock(x,y,z,layer,network);
                        case 14->w.removeFromCache(x>>4,z>>4);
                        case 15->w.retainNukkitUsedChunks(new long[]{0L});
                        case 17->w.clearChunks();
                        case 18->w.setDimension(w.dimension()==0?1:0);
                        default->{ }
                    }
                    assertEquals(in.readBoolean(),accepted,label+" accepted");
                    assertEquals(in.readInt(),field(w,"placementSupportHistoryTick"),label+" pruning tick");
                    var history=new HashMap<Vector3i,Integer>();for(int j=0,size=in.readInt();j<size;j++)history.put(pos(in),in.readInt());
                    assertEquals(history,field(w,"recentPlacementSupports"),label+" history");
                    assertEquals(in.readLong(),w.bucketPredictionRevision(),label+" revision");assertEquals(in.readLong(),w.getBlockBreakEpoch(),label+" epoch");
                    for(var p:points) {
                        assertEquals(in.readBoolean(),w.hadRecentPlacementSupport(p),label+" support "+p);
                        assertEquals(in.readInt(),w.getRawBlockAt(p.getX(),p.getY(),p.getZ(),0),label+" raw "+p);
                        assertEquals(in.readBoolean(),w.isPredictedBlockBreak(p),label+" identity "+p);
                    }
                }
            }
            assertEquals(-1,in.read());
        }
    }
    @Test void historyUsesTheExplicitNativeTickAndOnlyChangedSolidLayerZeroAnchors() {
        var tick=new AtomicInteger(Integer.MAX_VALUE);var w=new CompensatedWorld(new CollisionPhysicsState(),tick::get);
        w.setProtocolVersion(2193);w.apply(new StartGamePacket());w.put(0,0,chunk(w.airNetworkId()));
        var p=Vector3i.from(8,64,8);int stone=w.networkIdForLegacy(1<<13),water=w.networkIdForLegacy(8<<13);
        w.updateBlock(8,64,8,0,stone);w.predictBlockBreak(p);assertTrue(w.hadRecentPlacementSupport(p));
        tick.set(Integer.MIN_VALUE);assertTrue(w.hadRecentPlacementSupport(p));
        tick.incrementAndGet();assertFalse(w.hadRecentPlacementSupport(p));
        w.clearChunks();w.put(0,0,chunk(w.airNetworkId()));
        w.updateBlock(8,64,8,0,water);w.predictBlockBreak(p);assertFalse(w.hadRecentPlacementSupport(p));
        w.updateBlock(8,64,8,0,stone);w.predictBucketNetworkBlock(8,64,8,0,w.airNetworkId());assertFalse(w.hadRecentPlacementSupport(p));
        w.updateBlock(8,64,8,0,stone);long old=w.bucketPredictionRevision();w.predictBucketNetworkBlock(8,64,8,0,stone);
        assertFalse(w.applyAcknowledgedBlock(8,64,8,0,w.airNetworkId(),old));assertFalse(w.hadRecentPlacementSupport(p));
        assertTrue(w.applyAcknowledgedBlock(8,64,8,1,w.airNetworkId(),w.bucketPredictionRevision()));assertFalse(w.hadRecentPlacementSupport(p));
        assertTrue(w.applyAcknowledgedBlock(8,64,8,0,w.airNetworkId(),w.bucketPredictionRevision()));assertTrue(w.hadRecentPlacementSupport(p));
    }
}
