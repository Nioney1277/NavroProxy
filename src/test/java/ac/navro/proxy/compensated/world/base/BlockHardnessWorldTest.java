package ac.navro.proxy.compensated.world.base;

import ac.navro.proxy.data.block.BlockStateEncoding;
import ac.navro.proxy.data.block.NativeBlockProperties;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.proxy.util.LatencyUtil;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockHardnessWorldTest {
    @Test void worldPropertiesFollowAcknowledgedBlockChangesForRuntimeAndHashPalettes() {
        var pos=Vector3i.from(8,64,8);
        for(int protocol:new int[]{924,944,975,1001,2168,2169,2193,2225})for(boolean hash:new boolean[]{false,true}) {
            var w=new CompensatedWorld();w.setProtocolVersion(protocol);
            var start=new StartGamePacket();start.setBlockNetworkIdsHashed(hash);w.apply(start);
            w.put(0,0,CompensatedWorld.CachedChunk.network(-64,320,24,w.airNetworkId()));
            var latency=new LatencyUtil(null);
            assertEquals(NativeBlockProperties.forBlock(0,0),w.getNativeBlockProperties(pos,0));
            var registry=NetworkBlockStateRegistry.forProtocol(protocol,hash);
            var bedrock=registry.states().values().stream().filter(s->s.is("minecraft:bedrock")).findFirst().orElseThrow();
            latency.queue(10,true,()->w.applyAcknowledgedBlock(8,64,8,0,bedrock.networkId(),w.bucketPredictionRevision()));
            assertEquals(0,w.getNativeBlockProperties(pos,0).hardness());
            assertTrue(latency.onResponse(10));assertEquals(-1,w.getNativeBlockProperties(pos,0).hardness());
            assertEquals(0,w.getNativeBlockProperties(pos,1).hardness());
            var stone=registry.states().values().stream().filter(s->s.is("minecraft:stone")).findFirst().orElseThrow();
            long old=w.bucketPredictionRevision();
            w.predictLocalBlock(pos,0,BlockStateEncoding.fullId(1,0));
            latency.queue(11,true,()->w.applyAcknowledgedBlock(8,64,8,0,bedrock.networkId(),old));
            assertTrue(latency.onResponse(11));assertEquals(1.5,w.getNativeBlockProperties(pos,0).hardness());
            long current=w.bucketPredictionRevision();
            latency.queue(12,true,()->w.applyAcknowledgedBlock(8,64,8,0,stone.networkId(),current));
            assertTrue(latency.onResponse(12));assertEquals(1.5,w.getNativeBlockProperties(pos,0).hardness());
            w.removeFromCache(0,0);assertEquals(0,w.getNativeBlockProperties(pos,0).hardness());
        }
    }
    @Test void missingConnectionPaletteCannotSupplyGuessedProperties() {
        var w=new CompensatedWorld();
        assertThrows(IllegalStateException.class,()->w.getNativeBlockProperties(Vector3i.ZERO,0));
    }
}
