package ac.navro.proxy.check.impl.place;

import ac.navro.proxy.util.block.PlacementContext;

import ac.navro.anticheat.util.math.*;
import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.proxy.packets.input.PacketEvent;
import ac.navro.proxy.state.WirePlayerState;
import com.google.gson.*;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static ac.navro.proxy.util.block.PlacementContext.*;


class RotationPipelineReferenceTest {
    static final class Context implements PlacementContext {
        final Set<Cell> stones=new HashSet<>(Set.of(new Cell(0,65,2)));final List<Runnable> expiries=new ArrayList<>();
        final IdentityHashMap<InventoryTransactionPacket,UseItemData> views=new IdentityHashMap<>();
        int applied,blocks,slots;
        public long epoch(){return 0;}public Object levelIdentity(){return null;}public boolean available(){return true;}public long runtimeId(){return 0;}
        public Vec3 acceptedMotion(){return null;}public GameType gameType(){return GameType.SURVIVAL;}
        public int minY(){return -64;}public int maxY(){return 320;}public boolean chunkLoaded(int x,int z){return true;}
        public BlockView block(Cell p){boolean stone=stones.contains(p);return new BlockView(NetworkBlockState.legacy(stone?1<<13:0),stone?1:0,0,!stone,!stone,false,stone,
                stone?new Box(p.x(),p.y(),p.z(),p.x()+1,p.y()+1,p.z()+1):null);}
        public UseItemData placementData(InventoryTransactionPacket packet){return views.computeIfAbsent(packet,p->new UseItemData(new Cell(0,65,2),Direction.NORTH,new ItemView(1,0,true,true,1,false),0,
                p.getClientInteractPrediction()==null?1:p.getClientInteractPrediction().ordinal(),p.getTriggerType()==null?0:p.getTriggerType().ordinal(),0,null,new Vec3(1000,1000,1000)));}
        public InventoryTransactionPacket embeddedTransaction(PlayerAuthInputPacket input){
            if(input.getItemUseTransaction()==null)return null;
            var p=new InventoryTransactionPacket();p.setTransactionType(org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType.ITEM_USE);return p;
        }
        public void scheduleExpiry(Runnable task,int ticks){assertEquals(20,ticks);expiries.add(task);}
        public void applyPlacement(InventoryTransactionPacket packet){applied++;}public void resyncPlacement(Cell target,Cell placed){blocks++;}public void resyncSlot(int slot){slots++;}
        public boolean debugEnabled(){return false;}public void debug(String message){ }
    }
    static final class Probe extends RotationPlace {
        int flags;String verbose;
        Probe(Context context){super(new WirePlayerState(),context);}
        @Override public void fail(String verbose){flags++;this.verbose=verbose;}
    }
    static int pending(RotationPlace check)throws Exception{var f=RotationPlace.class.getDeclaredField("pending");f.setAccessible(true);return ((Collection<?>)f.get(check)).size();}
    @Test void completeReceiveMonitorQueueAndCameraDecisionsMatchCurrentOriginalAcrossContinuousSessions()throws Exception {
        var gson=new GsonBuilder().serializeNulls().create();int rows=0,flagged=0,applied=0;
        try(var in=new BufferedReader(new InputStreamReader(new GZIPInputStream(getClass().getResourceAsStream("/navro-rotation-pipeline.jsonl.gz")),StandardCharsets.UTF_8))) {
            var header=JsonParser.parseString(in.readLine()).getAsJsonObject();assertEquals("NRP1",header.get("magic").getAsString());
            for(int session=0;session<header.get("sessions").getAsInt();session++) {
                var context=new Context();var check=new Probe(context);
                for(int step=0;step<header.get("steps").getAsInt();step++) {
                    var row=JsonParser.parseString(in.readLine()).getAsJsonObject();int op=row.get("op").getAsInt();PacketEvent event=null;
                    if(op==0||op==5){
                        var input=new PlayerAuthInputPacket();input.setTick(row.get("tick").getAsLong());input.setPosition(Vector3f.from(.5,65.62F,.5));input.setRotation(Vector3f.from(70,180,180));
                        input.setInteractRotation(Vector2f.from(0,row.get("angle").getAsFloat()));boolean touch=row.get("touch").getAsBoolean();
                        input.setInputMode(touch?InputMode.TOUCH:InputMode.MOUSE);input.setInputInteractionModel(touch?InputInteractionModel.CLASSIC:InputInteractionModel.CROSSHAIR);
                        int camera=row.get("camera").getAsInt();input.setCameraOrientation(camera==0?null:Vector3f.from(0,0,camera));
                        if(row.get("withEmbedded").getAsBoolean())input.setItemUseTransaction(new org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction());
                        event=new PacketEvent(input);check.onPacketReceive(event);if(op==5)event.setCancelled(true);check.finishPacket(event);
                    }else if(op==1){
                        var packet=new InventoryTransactionPacket();packet.setTransactionType(org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType.ITEM_USE);
                        packet.setClientInteractPrediction(org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction.PredictedResult.values()[row.get("prediction").getAsInt()]);
                        packet.setTriggerType(org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction.TriggerType.values()[row.get("trigger").getAsInt()]);
                        event=new PacketEvent(packet);check.onPacketReceive(event);check.finishPacket(event);
                    }else if(op==2)List.copyOf(context.expiries).forEach(Runnable::run);
                    else if(op==3)context.stones.add(new Cell(0,65,1));
                    else if(op==4)check.onPacketSend(new PacketEvent(new CorrectPlayerMovePredictionPacket()));
                    var actual=new LinkedHashMap<String,Object>();actual.put("flags",check.flags);actual.put("blockResyncs",context.blocks);actual.put("slotResyncs",context.slots);
                    actual.put("applied",context.applied);actual.put("nativePlacements",context.applied);actual.put("pending",pending(check));actual.put("verbose",check.verbose);
                    actual.put("cancelled",event!=null&&event.isCancelled());actual.put("embedded",event!=null&&event.getPacket() instanceof PlayerAuthInputPacket input&&input.getItemUseTransaction()!=null);
                    assertEquals(row.get("state"),gson.toJsonTree(actual),"session="+session+" step="+step+" op="+op);rows++;
                }flagged+=check.flags;applied+=context.applied;
            }assertNull(in.readLine());
        }assertEquals(576,rows);assertTrue(flagged>0);assertTrue(applied>0);
    }
}
