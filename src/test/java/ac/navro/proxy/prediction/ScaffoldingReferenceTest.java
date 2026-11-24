package ac.navro.proxy.prediction;

import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.collision.BedrockCollision;
import ac.navro.proxy.collision.ScaffoldingCollision;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import ac.navro.proxy.prediction.engine.impl.GroundAndAirPredictionEngine;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.util.HashSet;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class ScaffoldingReferenceTest {
    private static Vec3 read(DataInputStream in)throws Exception{return new Vec3(in.readFloat(),in.readFloat(),in.readFloat());}
    private static void vector(DataInputStream in,Vec3 actual,String label)throws Exception{
        for(float value:new float[]{actual.x,actual.y,actual.z})assertEquals(Float.floatToRawIntBits(in.readFloat()),Float.floatToRawIntBits(value),label);
    }
    @Test void originalInputMotionFlagsAndCollisionMatchExactly()throws Exception{
        var session=AuthInputPacketsTest.session();var player=session.authInputPackets().context();player.begin();
        var world=session.world();
        PlayerAuthInputData[] actions={PlayerAuthInputData.SNEAK_DOWN,PlayerAuthInputData.SNEAKING,
                PlayerAuthInputData.SNEAK_TOGGLE_DOWN,PlayerAuthInputData.DESCEND_BLOCK,
                PlayerAuthInputData.ASCEND_BLOCK,PlayerAuthInputData.JUMPING,PlayerAuthInputData.AUTO_JUMPING_IN_WATER};
        InputMode[] modes={InputMode.UNDEFINED,InputMode.MOUSE,InputMode.TOUCH,InputMode.GAMEPAD};
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-scaffolding-reference.bin.gz")))){
            assertEquals(0x4e534331,in.readInt());int count=in.readInt();assertEquals(4096,count);
            for(int index=0;index<count;index++){
                int mode=in.readInt(),mask=in.readInt(),hold=in.readInt();
                boolean over=in.readBoolean(),inside=in.readBoolean(),ascendable=in.readBoolean(),
                        flying=in.readBoolean(),climbing=in.readBoolean(),overScaffold=in.readBoolean();
                Vec3 velocity=read(in);player.flags.capture();session.player().setFlying(flying);
                player.flags.set("OVER_DESCENDABLE_BLOCK",over);player.flags.set("IN_SCAFFOLDING",inside);
                player.flags.set("IN_ASCENDABLE_BLOCK",ascendable);player.flags.set("OVER_SCAFFOLDING",overScaffold);
                player.scaffoldingSneakHoldTicks=hold;player.climbJumpDelay=3;player.inputMode=modes[mode];
                player.inputData=new HashSet<>();
                for(int bit=0;bit<actions.length;bit++)if((mask&(1<<bit))!=0)player.inputData.add(actions[bit]);
                String label="original scaffold input "+index;
                ScaffoldingCollision.prepareInput(player);
                assertEquals(in.readInt(),player.scaffoldingSneakHoldTicks,label+" hold");
                assertEquals(in.readBoolean(),player.scaffoldingSneakDown,label+" sneakDown");
                assertEquals(in.readBoolean(),player.scaffoldingSneaking,label+" sneaking");
                assertEquals(in.readBoolean(),player.scaffoldDescend,label+" descend");
                assertEquals(in.readBoolean(),ScaffoldingCollision.shouldDescend(player),label+" flag");
                vector(in,ScaffoldingCollision.applyAcceptedDescend(player,velocity),label+" descend velocity");
                var jump=ClimbableBlockPhysics.applyBlockJump(player,velocity,climbing);
                assertEquals(in.readBoolean(),jump!=null,label+" jump");if(jump!=null)vector(in,jump,label+" jump velocity");
                ClimbableBlockPhysics.recordAcceptedJump(player);assertEquals(in.readInt(),player.climbJumpDelay,label+" jump delay");
                player.velocity=ScaffoldingCollision.applyAcceptedDescend(player,velocity);
                new GroundAndAirPredictionEngine(player).finalizeMovement();vector(in,player.velocity,label+" gravity/drag");
            }
            count=in.readInt();assertEquals(384,count);
            for(int index=0;index<count;index++){
                world.clearChunks();boolean loaded=in.readBoolean(),boots=in.readBoolean();float x=in.readFloat(),y=in.readFloat();
                if(loaded)world.put(0,0,CompensatedWorld.CachedChunk.network(-64,320,24,world.airNetworkId()));
                int cells=in.readInt();
                for(int cell=0;cell<cells;cell++)world.updateLegacyBlock(Vector3i.from(in.readInt(),in.readInt(),in.readInt()),0,in.readInt());
                player.boundingBox=new Box(x,y,8.7F,x+.3F,y+1.8F,9F);player.position=player.boundingBox.feetCenter();
                ClimbableBlockPhysics.refreshFlags(player,player.compensatedWorld,boots);
                for(String flag:new String[]{"IN_SCAFFOLDING","OVER_SCAFFOLDING","IN_ASCENDABLE_BLOCK","OVER_DESCENDABLE_BLOCK"})
                    assertEquals(in.readBoolean(),player.flags.has(flag),"original scaffold world "+index+" "+flag);
            }
            world.clearChunks();world.put(0,0,CompensatedWorld.CachedChunk.network(-64,320,24,world.airNetworkId()));
            count=in.readInt();assertEquals(64,count);
            for(int index=0;index<count;index++){
                boolean descend=in.readBoolean();float minY=in.readFloat();int meta=in.readInt();
                world.updateLegacyBlock(Vector3i.from(8,64,8),0,420<<13|meta);
                var state=world.networkBlockState(world.getRawBlockAt(8,64,8,0));
                var actor=new BedrockCollision.Actor(minY,0,false,descend,false);
                var actual=BedrockCollision.getCollisionBox(world,8,64,8,state,actor);int boxes=in.readInt();
                assertEquals(boxes,actual.size(),"original scaffold collision "+index);
                for(int box=0;box<boxes;box++){
                    var expected=new Box(in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat());
                    assertTrue(expected.equals(actual.get(box)),"original scaffold collision "+index);
                }
            }
            assertEquals(-1,in.read());
        }
    }
}
