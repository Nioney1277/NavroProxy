package ac.navro.proxy.compensated.world.base;

import ac.navro.proxy.data.block.BlockStateEncoding;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.proxy.data.block.NativePlacementProperties;
import ac.navro.proxy.util.block.BlockPlacementPhysics;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import static org.junit.jupiter.api.Assertions.*;

class LocalBlockActionTest {
    private static CompensatedWorld world(int protocol,boolean hashed) {
        var world=new CompensatedWorld();world.setProtocolVersion(protocol);
        var start=new StartGamePacket();start.setBlockNetworkIdsHashed(hashed);world.apply(start);return world;
    }
    private static void chunk(CompensatedWorld w,int x,int z) {
        w.put(x>>4,z>>4,CompensatedWorld.CachedChunk.network(-64,320,24,w.airNetworkId()));
    }
    @Test void nativeTargetSelectionPreservesReplaceableAndNeighborRulesForEveryFace() throws Exception {
        try(var in=new DataInputStream(getClass().getResourceAsStream("/navro-placement-targets.bin"))) {
            assertEquals(0x4E504F31,in.readInt());assertEquals(BlockStateEncoding.DATA_BITS,in.readInt());
            int count=in.readInt();assertEquals(432,count);
            for(int i=0;i<count;i++) {
                var clicked=Vector3i.from(in.readInt(),in.readInt(),in.readInt());int face=in.readInt();
                int old=in.readInt();boolean replaceable=in.readBoolean();int placed=in.readInt();
                var target=Vector3i.from(in.readInt(),in.readInt(),in.readInt());
                assertEquals(target,BlockPlacementPhysics.target(clicked,face,replaceable),"case="+i);
                var nativeState=NetworkBlockState.legacy(old);
                assertEquals(replaceable,NativePlacementProperties.canBeReplaced(nativeState),"native replaceability case="+i);
                assertEquals(target,BlockPlacementPhysics.target(clicked,face,nativeState),"native target case="+i);
                for(boolean hashed:new boolean[]{false,true}) {
                    var w=world(2193,hashed);chunk(w,clicked.getX(),clicked.getZ());
                    if((target.getX()>>4)!=(clicked.getX()>>4)||(target.getZ()>>4)!=(clicked.getZ()>>4))
                        chunk(w,target.getX(),target.getZ());
                    w.updateLegacyBlock(clicked,0,old);long sent=w.bucketPredictionRevision();
                    BlockPlacementPhysics.predict(w,clicked,face,nativeState,placed);
                    if(target.getY()<w.getMinY()||target.getY()>=w.getHeightY()) {
                        assertEquals(w.airNetworkId(),w.getRawBlockAt(target.getX(),target.getY(),target.getZ(),0));
                        assertEquals(sent,w.bucketPredictionRevision());continue;
                    }
                    assertEquals(w.networkIdForLegacy(placed),w.getRawBlockAt(target.getX(),target.getY(),target.getZ(),0));
                    assertFalse(w.applyAcknowledgedBlock(target.getX(),target.getY(),target.getZ(),0,w.networkIdForLegacy(old),sent));
                    assertTrue(w.applyAcknowledgedBlock(target.getX(),target.getY(),target.getZ(),0,w.networkIdForLegacy(old),w.bucketPredictionRevision()));
                }
            }
            assertEquals(-1,in.read());
        }
    }
    @Test void genericLegacyWritesAndBucketWritesPreserveLayerBoundsAndNetworkHashMode() {
        for(int protocol:new int[]{924,944,975,1001,2168,2169,2192,2193,2225})for(boolean hash:new boolean[]{false,true}) {
            var w=world(protocol,hash);chunk(w,-1,-17);
            int water=BlockStateEncoding.fullId(8,3);
            w.predictBucketBlock(Vector3i.from(-1,-64,-17),1,water);
            var registry=NetworkBlockStateRegistry.forProtocol(protocol,hash);
            assertEquals(registry.networkIdForLegacy(water),w.getRawBlockAt(-1,-64,-17,1));
            assertEquals(w.airNetworkId(),w.getRawBlockAt(-1,-64,-17,0));
            long before=w.bucketPredictionRevision();
            w.predictLocalBlock(Vector3i.from(-1,320,-17),0,water);
            w.predictLocalBlock(Vector3i.from(-1,-65,-17),0,water);
            w.predictLocalBlock(Vector3i.from(100,64,100),0,water);
            w.predictLocalBlock(Vector3i.from(-1,64,-17),2,water);
            assertEquals(before,w.bucketPredictionRevision());
            assertEquals(w.airNetworkId(),w.getRawBlockAt(-1,320,-17,0));
            w.updateBlock(-1,64,-17,0,139,4096);
            assertEquals(registry.networkIdForLegacy(BlockStateEncoding.fullId(139,4096)),w.getRawBlockAt(-1,64,-17,0));
        }
    }
    @Test void absentLegacyMappingDoesNotWriteAnInventedBlockOrConsumeRevision() {
        var w=new CompensatedWorld();
        assertThrows(IllegalStateException.class,()->w.predictLocalBlock(Vector3i.ZERO,0,0));
        assertEquals(0,w.bucketPredictionRevision());
    }
}
