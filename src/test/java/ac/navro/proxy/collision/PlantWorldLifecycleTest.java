package ac.navro.proxy.collision;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class PlantWorldLifecycleTest {
    private static CompensatedWorld world() {
        var w=new CompensatedWorld();w.setProtocolVersion(2193);w.apply(new StartGamePacket());
        w.put(-12,-18,CompensatedWorld.CachedChunk.network(-64,320,24,w.airNetworkId()));return w;
    }
    private static int block(String name) {
        return NetworkBlockStateRegistry.forProtocol(2193,false).states().values().stream()
                .filter(s->s.is(name)&&s.blockData()==0).findFirst().orElseThrow().networkId();
    }
    @Test void recordedBambooAndDripstoneContactsUseWorldOffsetsAndOriginalCollisionSolver() {
        Box[] actors={new Box(-189.76558F,73,-274.28217F,-189.1856F,74.8F,-273.70215F),
                new Box(-190.2486F,73,-274.92972F,-189.66861F,74.8F,-274.3497F),
                new Box(-190.11446F,73,-272.27F,-189.53447F,74.8F,-271.68997F),
                new Box(-189.22282F,73,-272.73898F,-188.64284F,74.8F,-272.15897F)};
        Vec3[] requests={new Vec3(-0.0119820265F,-0.0784F,-0.20464692F),new Vec3(0.20980805F,-0.0784F,0.0117755765F),
                new Vec3(0.14157507F,-0.0784F,-0.1365194F),new Vec3(-0.17916784F,-0.0784F,-0.0034340008F)};
        Vec3[] expected={new Vec3(-189.48756F,73,-274.16833F),new Vec3(-189.84F,73,-274.62793F),
                new Vec3(-189.68289F,73,-272.06415F),new Vec3(-189.01418F,73,-272.45242F)};
        for(int i=0;i<4;i++) {
            var w=world();int z=i<2?-275:-273;
            int id=block(i<2?"minecraft:bamboo":"minecraft:pointed_dripstone");
            w.updateBlock(-190,73,z,0,id);if(i<2)w.updateBlock(-190,74,z,0,id);
            var context=new ProtocolCollisionWorld(w,0);
            var shapes=new ArrayList<>(context.collect(new Box(-192,73,-277,-187,77,-270),actors[i]));
            assertEquals(i<2?2:1,shapes.size());
            var plants=java.util.List.copyOf(shapes);shapes.add(new Box(-220,72,-300,-170,73,-250));
            var result=ac.navro.anticheat.collision.ProtocolCollisionSolver.solveResolved(requests[i],actors[i],shapes,new Vec3(1,1,1),0.5625F,true);
            Vec3 feet=result.aabb.feetCenter();
            assertEquals(expected[i].x,feet.x,3.1E-5F);assertEquals(expected[i].y,feet.y,3.1E-5F);assertEquals(expected[i].z,feet.z,3.1E-5F);
            assertFalse(result.aabb.intersects(plants.get(0)));
        }
    }
    @Test void historicalPlantQueriesStayOwnedByTheirInputAndCurrentWorldReadsReplacement() {
        var w=world();int id=block("minecraft:bamboo");w.updateBlock(-190,73,-275,0,id);
        var state=w.collisionPhysics();state.begin(w,java.util.Map.of(),1,null);
        Box query=new Box(-191,73,-276,-189,75,-274);
        var context=new ProtocolCollisionWorld(w,0);var original=context.blockEntries(query);assertEquals(1,original.size());
        var saved=state.snapshot();w.updateBlock(-190,73,-275,0,w.airNetworkId());
        assertSame(original,context.blockEntries(query));
        state.begin(w,java.util.Map.of(),2,null);assertTrue(context.blockEntries(query).isEmpty());
        assertEquals(1,context.replay(1,saved).blockEntries(query).size());
        assertTrue(context.blockEntries(query).isEmpty());
    }
    @Test void artificialFloorUsesNativeMinYMinusFortyOneAndRequiresLoadedChunk() {
        var w=world();Box query=new Box(-191,-105.2F,-276,-190,-103.8F,-275);
        var entries=new ProtocolCollisionWorld(w,0).blockEntries(query);assertEquals(1,entries.size());
        assertTrue(entries.get(0).shape().equals(new Box(-191,-105,-276,-190,-104,-275)));
        assertEquals("minecraft:air",entries.get(0).block().identifier());
        w.removeFromCache(-12,-18);assertTrue(new ProtocolCollisionWorld(w,0).blockEntries(query).isEmpty());
    }
}
