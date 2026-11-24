package ac.navro.proxy.collision;

import ac.navro.anticheat.util.math.Box;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class NavroMovementCollisionReferenceTest {
    private static void shapes(List<Box> expected,List<CollisionShapeEntry> actual,String label) {
        assertEquals(expected.size(),actual.size(),label);
        for(int i=0;i<expected.size();i++)assertTrue(expected.get(i).equals(actual.get(i).shape()),
                label+" box="+i+" expected="+expected.get(i)+" actual="+actual.get(i).shape());
    }

    @Test void originalNavroStairCornersConnectionsAndJavaFallbacksReachMovementAndSupportQueries() throws Exception {
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-movement-collision.bin.gz")))){
            assertEquals(0x4e4d4331,in.readInt());
            int protocol=in.readInt(),count=in.readInt();
            assertTrue(count>10000);
            var world=new CompensatedWorld();world.setProtocolVersion(protocol);world.apply(new StartGamePacket());
            world.put(0,0,CompensatedWorld.CachedChunk.network(-64,320,24,world.airNetworkId()));
            var query=new Box(8,64,8,9,65,9);
            int[][] adjacent={{0,0,-1},{1,0,0},{0,0,1},{-1,0,0},{0,1,0},{0,-1,0}};
            for(int index=0;index<count;index++){
                for(var p:adjacent)world.updateBlock(8+p[0],64+p[1],8+p[2],0,world.airNetworkId());
                int center=in.readInt();world.updateBlock(8,64,8,0,center);
                int neighbors=in.readUnsignedByte();
                for(int i=0;i<neighbors;i++){
                    int x=in.readByte(),y=in.readByte(),z=in.readByte(),id=in.readInt();
                    world.updateBlock(8+x,64+y,8+z,0,id);
                }
                int n=in.readUnsignedShort();var expected=new ArrayList<Box>(n);
                for(int i=0;i<n;i++)expected.add(new Box(in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat()));
                world.collisionPhysics().reset();
                String label="case="+index+" "+world.networkBlockState(center).stateTag();
                shapes(expected,world.collisionCellFragments(8,64,8,null,world.collisionPhysics()),label+" support");
                shapes(expected.stream().filter(box->box.intersects(query)).toList(),
                        world.movementCollisionEntries(query,world.dimension(),world.collisionPhysics()),label+" movement");
            }
            assertEquals(-1,in.read());
        }
    }
}
