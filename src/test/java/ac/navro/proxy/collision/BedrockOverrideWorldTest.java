package ac.navro.proxy.collision;

import ac.navro.anticheat.util.math.*;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.io.DataInputStream;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class BedrockOverrideWorldTest {
    private static int block(String name){return NetworkBlockStateRegistry.forProtocol(2193,false).states().values().stream()
            .filter(b->b.is(name)&&b.blockData()==0).findFirst().orElseThrow().networkId();}
    @Test void scaffoldingAndPowderSnowKeepLivePlayerDependentCollisionRules() {
        var s=AuthInputPacketsTest.session();s.player().rebasePosition(new Vec3(8.5F,65,8.5F),false);
        s.world().updateBlock(8,64,8,0,block("minecraft:scaffolding"));
        var shapes=s.world().collisionCellFragments(8,64,8,null,s.player().collisionPhysics);
        assertEquals(1,shapes.size());assertEquals(64.875F,shapes.get(0).shape().minY);
        s.player().trackedFlags().set(71,true);
        assertTrue(s.world().collisionCellFragments(8,64,8,null,s.player().collisionPhysics).isEmpty());
        s.world().updateBlock(8,64,8,0,block("minecraft:powder_snow"));
        s.player().trackedFlags().set(71,false);
        assertTrue(s.world().collisionCellFragments(8,64,8,null,s.player().collisionPhysics).isEmpty());
        s.authInputPackets().context().fallDistance=3;
        shapes=s.world().collisionCellFragments(8,64,8,null,s.player().collisionPhysics);
        assertEquals(1,shapes.size());assertEquals(64.9F,shapes.get(0).shape().maxY);
        s.authInputPackets().context().fallDistance=0;
        var boots=org.cloudburstmc.protocol.bedrock.data.inventory.ItemData.builder().count(1)
                .definition(new org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition(){
                    public String getIdentifier(){return "minecraft:leather_boots";}
                    public int getRuntimeId(){return 301;}public boolean isComponentBased(){return false;}}).build();
        s.inventory().armorContainer.set(3,boots);
        shapes=s.world().collisionCellFragments(8,64,8,null,s.player().collisionPhysics);
        assertEquals(1,shapes.size());assertEquals(65,shapes.get(0).shape().maxY);
        s.player().rebasePosition(new Vec3(8.5F,64.5F,8.5F),false);
        assertTrue(s.world().collisionCellFragments(8,64,8,null,s.player().collisionPhysics).isEmpty());
    }
    @Test void doorCollisionUsesTheActualPairedHalfLikeOriginalNavro() throws Exception {
        var s=AuthInputPacketsTest.session();int cases=0;
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-door-collision.bin.gz")))){
            assertEquals(0x4e444331,in.readInt());int count=in.readInt();
            for(int i=0;i<count;i++){
                int current=in.readInt(),partner=in.readInt(),n=in.readInt();
                var state=s.world().networkBlockState(current);boolean top=state.booleanProperty("upper_block_bit",(state.blockData()&8)!=0);
                s.world().updateBlock(8,64,8,0,current);s.world().updateBlock(8,top?63:65,8,0,partner);
                var actual=s.world().collisionCellFragments(8,64,8,null,s.player().collisionPhysics);
                assertEquals(n,actual.size(),"native door case="+i);
                for(int j=0;j<n;j++){
                    var expected=new Box(in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat()).offset(8,64,8);
                    assertTrue(expected.equals(actual.get(j).shape()),"native door case="+i+", state="+state.stateTag()+", expected="+expected+", actual="+actual.get(j).shape());
                }
                cases++;
            }
        }
        assertEquals(21504,cases);
    }
    @Test void honeyTopUsesNavroFullHeightInQueriesSupportsAndActualFallingCollision() {
        var s=AuthInputPacketsTest.session();
        int honey=NetworkBlockStateRegistry.forProtocol(2193,false).states().values().stream()
                .filter(b->b.is("minecraft:honey_block")).findFirst().orElseThrow().networkId();
        s.world().updateBlock(8,64,8,0,honey);
        Box expected=new Box(8.0625F,64,8.0625F,8.9375F,65,8.9375F);
        var query=new Box(8,64,8,9,66,9);
        var entries=s.collisionWorld().blockEntries(query);
        assertEquals(1,entries.size());assertTrue(expected.equals(entries.get(0).shape()),entries.toString());
        var fragments=s.world().collisionCellFragments(8,64,8,null,s.player().collisionPhysics);
        assertEquals(1,fragments.size());assertTrue(expected.equals(fragments.get(0).shape()));
        s.player().rebasePosition(new Vec3(8.5F,65.5F,8.5F),false);
        var resolved=Collider.collideAndUpdateMove(s.player(),s.collisionWorld(),new Vec3(0,-1,0));
        assertEquals(-.5F,resolved.movement.y);assertEquals(65,resolved.aabb.minY);
    }
    @Test void originalStaticOverridesReachTheWorldInsteadOfTheGenericGeyserTable() {
        for(int protocol:new int[]{844,944,975,1001,2168,2193,2225}){
            var w=new CompensatedWorld();w.setProtocolVersion(protocol);
            w.apply(new org.cloudburstmc.protocol.bedrock.packet.StartGamePacket());
            w.put(0,0,CompensatedWorld.CachedChunk.network(-64,320,24,w.airNetworkId()));
            String[] names={"minecraft:honey_block","minecraft:lectern","minecraft:cactus","minecraft:brewing_stand"};
            List<List<Box>> expected=List.of(List.of(new Box(.0625F,0,.0625F,.9375F,1,.9375F)),
                    List.of(new Box(0,0,0,1,.9F,1)),List.of(new Box(.0625F,0,.0625F,.9375F,1,.9375F)),
                    List.of(new Box(0,0,0,1,.125F,1),new Box(.4375F,0,.4375F,.5625F,.875F,.5625F)));
            for(int n=0;n<names.length;n++){
                final String name=names[n];int id=NetworkBlockStateRegistry.forProtocol(protocol,false).states().values().stream()
                        .filter(b->b.is(name)&&b.blockData()==0).findFirst().orElseThrow().networkId();
                w.updateBlock(8,64,8,0,id);
                var actual=w.collisionCellFragments(8,64,8,null,w.collisionPhysics());
                assertEquals(expected.get(n).size(),actual.size(),name+" protocol="+protocol);
                for(int j=0;j<actual.size();j++)assertTrue(expected.get(n).get(j).offset(8,64,8).equals(actual.get(j).shape()),name+" protocol="+protocol);
            }
        }
    }
}
