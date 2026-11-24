package ac.navro.proxy.collision.block;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Direction;
import ac.navro.proxy.collision.ProtocolCollisionWorld;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NativePistonProperties;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PistonWorldLifecycleTest {
    private static CompensatedWorld world(int protocol,boolean hash) {
        var w=new CompensatedWorld();w.setProtocolVersion(protocol);
        var start=new StartGamePacket();start.setBlockNetworkIdsHashed(hash);w.apply(start);
        w.put(0,0,CompensatedWorld.CachedChunk.network(-64,320,24,w.airNetworkId()));return w;
    }
    private static int head(int protocol,boolean hash,Direction face) {
        return NetworkBlockStateRegistry.forProtocol(protocol,hash).states().values().stream()
                .filter(s->s.is("minecraft:piston_arm_collision")&&NativePistonProperties.headFacing(s)==face)
                .findFirst().orElseThrow().networkId();
    }
    private static Vector3i base(Vector3i pos,Direction face) {
        return Vector3i.from(pos.getX()-face.getXOffset(),pos.getY()-face.getYOffset(),pos.getZ()-face.getZOffset());
    }
    private static void boxes(List<Box> expected,List<Box> actual) {
        assertEquals(expected.size(),actual.size());
        for(int i=0;i<expected.size();i++)assertTrue(expected.get(i).equals(actual.get(i)),"shape="+i);
    }
    @Test void actualWorldQueriesSelectAcknowledgedNativeHeadDirectionForEveryFaceAndPaletteMode() {
        for(int protocol:new int[]{924,944,975,1001,2168,2169,2193,2225})for(boolean hash:new boolean[]{false,true})for(Direction face:Direction.values()) {
            var w=world(protocol,hash);var pos=Vector3i.from(8,64,8);var base=base(pos,face);
            w.updateBlock(pos.getX(),pos.getY(),pos.getZ(),0,head(protocol,hash,face));
            w.updateBlockEntityTag(base.getX(),base.getY(),base.getZ(),NbtMap.builder().putInt("State",1).putDouble("LastProgress",0.5).putFloat("Progress",0).build());
            var query=new Box(6,62,6,10,67,10);
            var expected=PistonArmBlock.getCollisionShape(w,pos,null,face).stream().map(b->b.offset(8,64,8)).toList();
            var actual=new ProtocolCollisionWorld(w,0).blockEntries(query).stream().map(e->e.shape()).toList();
            boxes(expected,actual);assertEquals(3,actual.size());
        }
    }
    @Test void armBaseExtendingOutsideHeadBlockIsFoundAcrossQueryBoundary() {
        var w=world(2193,false);w.updateBlock(15,64,15,0,head(2193,false,Direction.EAST));
        var query=new Box(14.76F,64.4F,15.4F,14.99F,64.6F,15.6F);
        var shapes=new ProtocolCollisionWorld(w,0).blockEntries(query);
        assertEquals(1,shapes.size());
        assertTrue(shapes.get(0).shape().equals(new Box(14.75F,64.3125F,15.3125F,15,64.6875F,15.6875F)));
    }
    @Test void ownedHistoricalQueryKeepsItsPistonShapeWhileNewQueriesUseCurrentNbtAsInNavro() {
        var w=world(2193,false);var pos=Vector3i.from(8,64,8);var base=base(pos,Direction.EAST);
        w.updateBlock(8,64,8,0,head(2193,false,Direction.EAST));
        w.updateBlockEntityTag(base.getX(),base.getY(),base.getZ(),NbtMap.builder().putByte("State",(byte)1).putFloat("LastProgress",0).build());
        w.collisionPhysics().begin(w,Map.of(),1,null);
        var context=new ProtocolCollisionWorld(w,0);var query=new Box(6,62,6,10,67,10);
        var before=context.blockEntries(query);assertEquals(2,before.size());var saved=w.collisionPhysics().snapshot();
        w.updateBlockEntityTag(base.getX(),base.getY(),base.getZ(),NbtMap.builder().putByte("State",(byte)0).build());
        assertSame(before,context.blockEntries(query));
        w.collisionPhysics().begin(w,Map.of(),2,null);assertTrue(context.blockEntries(query).isEmpty());
        var replay=context.replay(1,saved);assertEquals(before,replay.blockEntries(query));
        assertTrue(replay.blockEntries(new Box(6.1F,62,6,10,67,10)).isEmpty());
        assertTrue(context.blockEntries(query).isEmpty());assertEquals(2,w.collisionPhysics().inputTick());
    }
    @Test void pistonBasesKeepOriginalFullCubeAndMovingPlaceholdersNeverContributeCollision() {
        var registry=NetworkBlockStateRegistry.forProtocol(2193,false);
        for(String name:new String[]{"minecraft:piston","minecraft:sticky_piston","minecraft:moving_block"}) {
            var state=registry.states().values().stream().filter(s->s.is(name)).findFirst().orElseThrow();
            var w=world(2193,false);w.updateBlock(8,64,8,0,state.networkId());
            w.updateBlockEntityTag(8,64,8,NbtMap.builder().putInt("State",0).putFloat("Progress",0).build());
            var shapes=new ProtocolCollisionWorld(w,0).blockEntries(new Box(7,63,7,10,66,10));
            if(name.equals("minecraft:moving_block"))assertTrue(shapes.isEmpty());
            else {assertEquals(1,shapes.size());assertTrue(shapes.get(0).shape().equals(new Box(8,64,8,9,65,9)));}
        }
    }
}
