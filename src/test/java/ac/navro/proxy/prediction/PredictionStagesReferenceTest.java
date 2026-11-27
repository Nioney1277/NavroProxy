package ac.navro.proxy.prediction;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;


import ac.navro.proxy.prediction.RiptidePhysics;
import ac.navro.proxy.prediction.StickyBlockPhysics;
import ac.navro.proxy.prediction.engine.impl.GroundAndAirPredictionEngine;
import ac.navro.proxy.prediction.ticker.impl.PlayerTicker;


import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class PredictionStagesReferenceTest {
    static Vec3 read(DataInputStream in)throws IOException{return new Vec3(in.readFloat(),in.readFloat(),in.readFloat());}
    static void same(Vec3 expected,Vec3 value){
        assertEquals(Float.floatToRawIntBits(expected.x),Float.floatToRawIntBits(value.x));
        assertEquals(Float.floatToRawIntBits(expected.y),Float.floatToRawIntBits(value.y));
        assertEquals(Float.floatToRawIntBits(expected.z),Float.floatToRawIntBits(value.z));
    }
    @Test void originalStagesMatchExceptTheRemovedSprintInputRewrite()throws Exception{
        var s=AuthInputPacketsTest.session();var p=s.authInputPackets().context();p.begin();
        var registry=NetworkBlockStateRegistry.forProtocol(2193,false);
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-prediction-stages-reference.bin.gz")))){
            assertEquals(0x4e505331,in.readInt());int count=in.readInt();assertEquals(4096,count);
            for(int i=0;i<count;i++){
                boolean sprint=in.readBoolean(),swim=in.readBoolean(),sneak=in.readBoolean(),crawl=in.readBoolean(),water=in.readBoolean();
                p.ticksSinceCanSlowdown=in.readInt();p.doingInventoryAction=in.readBoolean();boolean stop=in.readBoolean();p.usingItem.snapshot(in.readFloat());p.yaw=in.readFloat();
                Vec3 raw=read(in),velocity=read(in);s.player().snapshotPredictionMovementState(sprint,swim);p.flags.capture();p.flags.set("SNEAKING",sneak);p.flags.set("CRAWLING",crawl);
                p.fluidPrediction.wasCrawling=crawl;p.fluidPrediction.inputActive=true;p.fluidPrediction.waterTravel=water;
                p.inputData=stop?Set.of(PlayerAuthInputData.STOP_SNEAKING):Set.of();
                Vec3 nativeTrial=read(in),nativeCommit=read(in);
                Vec3 trial=PlayerTicker.scaleMovementInput(p,raw,false),commit=PlayerTicker.scaleMovementInput(p,raw,true);

                boolean nativeForcedForward=sprint&&!swim&&!p.doingInventoryAction&&raw.z<=0;
                if(!nativeForcedForward){same(nativeTrial,trial);same(nativeCommit,commit);}
                else {
                    assertEquals(Math.signum(raw.z),Math.signum(trial.z));
                    assertEquals(Math.signum(raw.x),Math.signum(trial.x));same(trial,commit);
                }
                assertEquals(in.readInt(),p.ticksSinceCanSlowdown);
                p.onGround=in.readBoolean();int nativeId=in.readInt();var state=registry.states().values().stream().filter(b->b.blockId()==nativeId && b.blockData()==0).findFirst().orElseThrow();
                s.world().updateBlock(8,63,8,0,state.networkId());p.input=raw;p.velocity=velocity.clone();p.horizontalCollision=false;p.bounceGravityCorrection=null;
                var engine=new GroundAndAirPredictionEngine(p);same(read(in),engine.travel(velocity));engine.finalizeMovement();same(read(in),p.velocity);
                var correction=new StickyBlockPhysics.GravityCorrection(in.readFloat(),in.readFloat());float gravity=in.readFloat();assertEquals(Float.floatToRawIntBits(in.readFloat()),Float.floatToRawIntBits(StickyBlockPhysics.resolveGravityDelta(correction,gravity)));
                float pitch=in.readFloat();int level=in.readInt();boolean inWater=in.readBoolean(),head=in.readBoolean();same(read(in),RiptidePhysics.impulse(velocity,pitch,p.yaw,level,p.onGround,inWater,head));
            }
            assertEquals(-1,in.read());
        }
    }
}
