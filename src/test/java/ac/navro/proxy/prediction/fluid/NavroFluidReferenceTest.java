package ac.navro.proxy.prediction.fluid;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.collision.BedrockCollision;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

/** Expected values execute the original Navro source, without replacement
 * collision predicates or copied expected-value formulas. See the fixture tool.
 */
class NavroFluidReferenceTest {
    private static CompensatedWorld world(){
        var world=new CompensatedWorld();world.setProtocolVersion(2225);world.apply(new StartGamePacket());
        for(int x=-1;x<=0;x++)for(int z=-1;z<=0;z++)
            world.put(x,z,CompensatedWorld.CachedChunk.network(-64,320,24,world.airNetworkId()));
        world.setCollisionActor(()->new BedrockCollision.Actor(64,0,false,false,false));
        return world;
    }
    private static void vector(DataInputStream in,Vec3 actual,String label)throws IOException{
        for(float value:new float[]{actual.x,actual.y,actual.z}){
            int expected=Float.floatToRawIntBits(in.readFloat());
            assertEquals(expected,Float.floatToRawIntBits(value),label);
        }
    }
    @Test void originalFluidBlockingAndSamplingMatchBitForBit()throws Exception{
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-fluid-reference.bin.gz")))){
            assertEquals(0x4e465231,in.readInt());assertEquals(2225,in.readInt());
            int blocks=in.readInt();var world=world();
            var differences=new java.util.ArrayList<String>();
            for(int i=0;i<blocks;i++){
                int id=in.readInt();boolean sturdy=in.readBoolean(),blocksMotion=in.readBoolean();
                var state=world.networkBlockState(id);
                boolean actualSturdy=FluidBlockCollision.isFaceSturdy(world,state);
                boolean actualMotion=FluidBlockCollision.blocksMotion(world,state);
                if(sturdy!=actualSturdy || blocksMotion!=actualMotion)differences.add("native state "+id+" "+state.stateTag()
                        +" sturdy="+sturdy+"/"+actualSturdy+" blocksMotion="+blocksMotion+"/"+actualMotion);
            }
            assertTrue(differences.isEmpty(),()->differences.size()+" native predicate differences: "+differences);
            int scenarios=in.readInt();
            var previous=new java.util.ArrayList<int[]>();
            for(int i=0;i<scenarios;i++){
                for(int[] cell:previous)world.updateBlock(cell[0],cell[1],cell[2],cell[3],world.airNetworkId());
                previous.clear();int cells=in.readInt();
                for(int j=0;j<cells;j++){
                    int[] cell={in.readInt(),in.readInt(),in.readInt(),in.readInt()};
                    world.updateBlock(cell[0],cell[1],cell[2],cell[3],in.readInt());previous.add(cell);
                }
                String label="original liquid scenario "+i;
                var fluid=world.fluidAt(8,64,8,0);
                assertEquals(in.readInt(),fluid.type().ordinal(),label+" fluid");
                assertEquals(in.readFloat(),fluid.height(),label+" height");
                assertEquals(in.readInt(),fluid.level(),label+" depth");
                vector(in,world.flowAt(8,64,8,0),label+" flow");
                var result=LiquidPhysics.sample(world,new Box(8.2F,64,8.2F,8.8F,65.8F,8.8F),false,0);
                assertEquals(in.readInt(),result.selectedType().ordinal(),label+" selection");
                vector(in,result.selectedPush(),label+" push");
                for(var sample:new LiquidPhysics.Sample[]{result.water(),result.lava()}){
                    assertEquals(in.readBoolean(),sample.touching(),label+" touching");
                    assertEquals(in.readFloat(),sample.surfaceHeight(),label+" surface");
                    assertEquals(in.readBoolean(),sample.downwardFlow(),label+" downward");
                    vector(in,sample.accumulatedFlow(),label+" accumulated");
                    vector(in,sample.appliedPush(),label+" applied");
                }
            }
            assertEquals(-1,in.read());
        }
    }
}
