package ac.navro.proxy.check.impl.prediction;

import ac.navro.anticheat.util.math.*;
import ac.navro.proxy.collision.CollisionShapeEntry;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.proxy.diagnostics.WorldCollisionDiagnostics;
import ac.navro.proxy.prediction.PredictionSample;
import ac.navro.proxy.prediction.engine.data.VectorType;
import ac.navro.proxy.state.WirePlayerState;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldCollisionDiagnosticsTest {
    private record Result(List<String> alerts,List<Map<String,Object>> records){}
    private Result run(boolean logging,boolean brokenSink){
        var player=new WirePlayerState();var alerts=new ArrayList<String>();player.setAlertSink(alerts::add);
        Box start=new Box(-.3F,0,-.3F,.3F,1.8F,.3F),wall=new Box(1,0,-1,2,3,1);
        var entry=new CollisionShapeEntry(wall,NetworkBlockState.legacy(1<<13));
        var sample=new PredictionSample(new PredictionSample.World(){
            public boolean isChunkLoadedAt(float x,float z){return true;}
            public List<CollisionShapeEntry> collectBlockCollisionEntries(Box query){return List.of(entry);}
        },new PredictionSample.Teleports(){public boolean isTeleporting(){return false;}public void rewind(long tick){}},()->false);
        sample.tick=12;sample.sinceLoadingScreen=20;sample.maxOffset=1E-4F;sample.bestPossibility=()->VectorType.NORMAL;
        sample.predictionStartBoundingBox=start;sample.boundingBox=start.offset(.7F,0,0);sample.position=new Vec3(.7F,0,0);sample.unvalidatedPosition=new Vec3(1,0,0);
        sample.beforeCollision=new Vec3(1,0,0);sample.afterCollision=new Vec3(.7F,0,0);sample.movementCollisionEntries=List.of(entry);
        var records=new ArrayList<Map<String,Object>>();
        if(logging){
            var trace=new WorldCollisionDiagnostics(player,new CompensatedWorld(),"phase-test",record->{if(brokenSink)throw new IllegalStateException("disk failure");records.add(record);});
            trace.event("world_queued",WorldCollisionDiagnostics.fields("packet","UpdateBlockPacket"));trace.frame(sample,.3F);sample.phaseObserver=trace::phase;
        }
        assertTrue(new Phase(player,sample).check(.3F));return new Result(alerts,records);
    }
    @Test void phaseDiagnosticsContainReplayContextWithoutChangingTheOriginalAlert(){
        Result plain=run(false,false),logged=run(true,false);assertEquals(plain.alerts(),logged.alerts());assertEquals(1,logged.alerts().size());
        var hit=logged.records().stream().filter(r->"phase_hit".equals(r.get("event"))).findFirst().orElseThrow();
        for(String key:List.of("oldBox","clientBox","predictedBox","requested","resolved","hit","nearClient","nearPredicted","history","pendingAcks"))assertTrue(hit.containsKey(key),key);
        assertEquals("entered",hit.get("reason"));assertTrue(new com.google.gson.Gson().toJson(hit).contains("UpdateBlockPacket"));
    }
    @Test void brokenDiagnosticOutputCannotSuppressOrRelabelPhase(){assertEquals(run(false,false).alerts(),run(true,true).alerts());}
}
