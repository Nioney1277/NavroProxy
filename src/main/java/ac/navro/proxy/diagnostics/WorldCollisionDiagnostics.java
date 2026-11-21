package ac.navro.proxy.diagnostics;

import ac.navro.anticheat.util.math.*;
import ac.navro.proxy.collision.CollisionShapeEntry;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.proxy.prediction.PredictionSample;
import ac.navro.proxy.state.WirePlayerState;
import com.google.gson.Gson;
import org.cloudburstmc.protocol.bedrock.packet.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;


public final class WorldCollisionDiagnostics {
    private final WirePlayerState player;
    private final CompensatedWorld world;
    private final Consumer<Map<String,Object>> sink;
    private final String session;
    private final Deque<Map<String,Object>> history=new ArrayDeque<>();
    private long sequence;
    private Map<String,Object> rawInput=Map.of();
    private final Deque<Map<String,Object>> recentInputs=new ArrayDeque<>();
    private int motionTraceInputs;


    public synchronized void beginMotionTrace() {
        if (motionTraceInputs == 0) {
            for (var input : recentInputs) event("motion_input_before",fields("rawInput",input));
        }
        motionTraceInputs = 40;
    }

    public WorldCollisionDiagnostics(WirePlayerState player,CompensatedWorld world,String session,Consumer<Map<String,Object>> sink){
        this.player=player;this.world=world;this.session=session;this.sink=sink;
    }
    public static Consumer<Map<String,Object>> fileSink(){return FileSink.INSTANCE::offer;}
    public static String logPath(){return FileSink.PATH.toAbsolutePath().toString();}
    public static Map<String,Object> fields(Object... pairs){
        var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;
    }
    public synchronized long event(String event,Map<String,Object> data){
        try{return record(event,data,true);}catch(Throwable ignored){return -1;}
    }
    public Map<String,Object> movementState(){
        return fields("flags",new TreeSet<>(player.trackedFlags().cloneFlags()),
                "acknowledgedDead",player.acknowledgedDead,"awaitingRespawnHealth",player.awaitingRespawnHealth(),
                "movementAttribute",movementAttribute(),
                "clientSprinting",player.clientSprinting(),"serverSprinting",player.serverSprinting(),"predictionSprinting",player.predictionSprinting(),
                "clientSwimming",player.clientSwimming(),"predictionSwimming",player.predictionSwimming(),
                "crawling",player.clientCrawling(),"predictionCrawling",player.predictionCrawling(),"gliding",player.gliding(),"sneaking",player.sneaking(),
                "flying",player.flying(),"wasFlying",player.wasFlying(),"horizontalPose",player.horizontalPoseApplied(),
                "dimensions",String.valueOf(player.dimensions()),"box",box(player.boundingBox()),
                "pendingDimensions",String.valueOf(player.fluidPrediction().pendingPoseDimensions),
                "readyDimensions",String.valueOf(player.fluidPrediction().readyPoseDimensions),"readyBeforeMovement",player.fluidPrediction().readyPoseBeforeMovement);
    }
    private Map<String,Object> movementAttribute(){
        var attribute=player.attributes().get("minecraft:movement");if(attribute==null)return Map.of();

        var snapshot=attribute.clone();
        return fields("base",snapshot.getBaseValue(),"value",snapshot.getValue(),"modifiers",snapshot.getModifiers());
    }
    public void movementAttributes(String event,UpdateAttributesPacket packet){
        if(packet.getRuntimeEntityId()!=player.runtimeId())return;
        try{event(event,fields("packetTick",packet.getTick(),"ackId",player.getLatencyUtil().currentAcknowledgmentId(),
                "attributes",packet.getAttributes().stream().map(a->fields("name",a.getName(),"default",a.getDefaultValue(),"value",a.getValue(),"modifiers",a.getModifiers())).toList(),
                "movementState",movementState()));}catch(Throwable ignored){}
    }
    public void movementMetadata(String event,long revision,org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap update){
        movementMetadata(event,revision,update,-1);
    }
    public void movementMetadata(String event,long revision,org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap update,long sourceTick){
        try{event(event,fields("metadataRevision",revision,"packetTick",sourceTick,"ackId",player.getLatencyUtil().currentAcknowledgmentId(),
                "baseFlags",ac.navro.proxy.util.EntityMetadataUtil.hasBaseFlags(update),
                "extendedFlags",ac.navro.proxy.util.EntityMetadataUtil.hasExtendedFlags(update),
                "metadataFlags",ac.navro.proxy.util.EntityMetadataUtil.copyFlags(update),
                "metadata",update.toString(),"movementState",movementState()));}catch(Throwable ignored){}
    }
    public void predictionSyncReceived(MovementPredictionSyncPacket packet, boolean accepted) {
        event("clientPredictionSyncReceived", fields("accepted", accepted,
                "reportedUniqueId", packet.getUniqueEntityId(), "expectedUniqueId", player.uniqueId(),
                "modelRuntimeId", player.runtimeId(),
                "reportedFlags", new TreeSet<>(ac.navro.proxy.packets.input.ClientMovementPredictionSyncPacket.copyFlags(packet)),
                "reportedDimensions", String.valueOf(packet.getBoundingBox()), "reportedSpeed", number(packet.getSpeed()),
                "reportedExtraScalars", List.of(number(packet.getUnknown1()), number(packet.getUnknown2()), number(packet.getUnknown3())),
                "reportedFlying", packet.isFlying(), "pendingReport", predictionReport(player.predictionSync().pending())));
    }

    public void predictionSyncAtInput(long sourceTick, ac.navro.proxy.prediction.PredictionSyncState.Report report) {
        event("clientPredictionSyncAtInput", fields("sourceTick", sourceTick, "report", predictionReport(report),
                "canonicalState", movementState()));
    }

    private static Map<String, Object> predictionReport(ac.navro.proxy.prediction.PredictionSyncState.Report report) {
        if (report == null) return null;
        return fields("sequence", report.sequence(), "receivedAfterTick", report.receivedAfterTick(),
                "uniqueEntityId", report.uniqueEntityId(), "flags", new TreeSet<>(report.flags()),
                "scale", number(report.scale()), "width", number(report.width()), "height", number(report.height()),
                "speed", number(report.speed()), "underwaterSpeed", number(report.underwaterSpeed()),
                "lavaSpeed", number(report.lavaSpeed()), "jumpStrength", number(report.jumpStrength()),
                "health", number(report.health()), "hunger", number(report.hunger()),
                "unknown1", number(report.unknown1()), "unknown2", number(report.unknown2()),
                "unknown3", number(report.unknown3()), "flying", report.flying());
    }

    private long record(String event,Map<String,Object> data,boolean emit){
        var entry=fields("time",java.time.Instant.now().toString(),"session",session,"seq",++sequence,
                "event",event,"player",player.getName(),"tick",player.tick(),"protocol",player.protocolVersion(),
                "dimension",world.dimension(),"epoch",world.getBlockBreakEpoch(),"localRevision",world.bucketPredictionRevision());
        entry.putAll(data);var lean=new LinkedHashMap<>(entry);lean.remove("selectedShapes");history.addLast(Collections.unmodifiableMap(lean));while(history.size()>128)history.removeFirst();
        if(emit)sink.accept(entry);return sequence;
    }
    public void client(BedrockPacket packet){
        try{
            if(packet instanceof PlayerAuthInputPacket input){
                synchronized(this){rawInput=fields("tick",input.getTick(),"position",input.getPosition().toString(),"delta",String.valueOf(input.getDelta()),"input",input.getInputData().toString(),
                        "motion",String.valueOf(input.getMotion()),"rawMoveVector",String.valueOf(input.getRawMoveVector()),"rotation",String.valueOf(input.getRotation()));
                    recentInputs.addLast(Map.copyOf(rawInput));while(recentInputs.size()>8)recentInputs.removeFirst();
                    if(motionTraceInputs>0){event("motion_input_raw",fields("rawInput",rawInput));motionTraceInputs--;}}
                if(input.getPlayerActions()!=null&&!input.getPlayerActions().isEmpty())event("client_block_actions",fields("actions",input.getPlayerActions().toString()));
            }else if(packet instanceof NetworkStackLatencyPacket latency){
                synchronized(this){if(motionTraceInputs>0)event("motion_latency_response",fields("timestamp",latency.getTimestamp()));}
            }else if(packet instanceof PlayerActionPacket action){
                event("client_action",fields("action",String.valueOf(action.getAction()),"position",String.valueOf(action.getBlockPosition()),"face",action.getFace()));
            }else if(packet instanceof InventoryTransactionPacket transaction){
                event("client_transaction",fields("type",String.valueOf(transaction.getTransactionType()),"action",transaction.getActionType(),"position",String.valueOf(transaction.getBlockPosition())));
            }
        }catch(Throwable ignored){}
    }
    private Map<String,Object> movement(PredictionSample sample,float offset){
        var data=fields("offset",number(offset),"maxOffset",number(sample.getMaxOffset()),"rawInput",rawInput,
                "oldBox",box(sample.predictionStartBoundingBox),"clientBox",box(sample.boxAtProjectedPosition(sample.unvalidatedPosition)),
                "predictedBox",box(sample.boundingBox),"position",vec(sample.position),"clientFeet",vec(sample.unvalidatedPosition),
                "requested",vec(sample.beforeCollision),"resolved",vec(sample.afterCollision),"velocity",vec(sample.velocity),
                "clientDelta",vec(sample.unvalidatedTickEnd),"onGround",sample.onGround,"verticalCollision",sample.verticalCollision,
                "loading",player.inLoadingScreen,"sinceLoading",sample.sinceLoadingScreen,"blockResync",sample.tickSinceBlockResync,
                "worldMinY",world.getMinY(),"worldMaxY",world.getHeightY(),"airNetworkId",world.airNetworkId(),"insideUnloaded",sample.insideUnloadedChunk,"candidate",sample.bestPossibility==null?null:sample.bestPossibility.getType().toString(),
                "disabledChecks",sample.disabledChecks.toString(),"input",sample.inputData.toString(),"encoderAttached",player.getEntityBatchEncoder()!=null);
        var pending=new ArrayList<Object>();int count=0;for(var ack:player.getLatencyUtil().sentQueue()){
            if(count++==12)break;pending.add(fields("id",ack.id(),"ours",ack.ours(),"ageMs",System.currentTimeMillis()-ack.ms()));
        }
        data.put("movementState",movementState());data.put("pendingAcks",pending);var previous=player.getLatencyUtil().prevAcceptedLatency;
        data.put("lastAcceptedAck",previous==null?null:previous.id());return data;
    }
    public synchronized void terrainWait(PredictionSample sample){
        try{record("collision_wait_terrain",movement(sample,Float.NaN),true);}catch(Throwable ignored){}
    }
    public synchronized void frame(PredictionSample sample,float offset){
        try{
            Map<String,Object> data=movement(sample,offset);boolean emit=offset>=sample.getMaxOffset()
                    || sample.beforeCollision.squaredDistanceTo(sample.afterCollision)>1.0E-10F || motionTraceInputs>0;
            if(emit)data.put("selectedShapes",shapes(sample.movementCollisionEntries,24));
            record("collision_frame",data,emit);
        }catch(Throwable ignored){}
    }
    public synchronized void phase(PredictionSample sample,String reason,float offset,CollisionShapeEntry hit){
        try{
            Map<String,Object> data=movement(sample,offset);data.put("reason",reason);data.put("hit",shape(hit));
            data.put("selectedShapes",shapes(sample.movementCollisionEntries,64));data.put("nearClient",near(sample.unvalidatedPosition));
            data.put("nearPredicted",near(sample.position));var recent=new ArrayList<>(history);data.put("history",recent.subList(Math.max(0,recent.size()-48),recent.size()));

            var entry=fields("time",java.time.Instant.now().toString(),"session",session,"seq",++sequence,
                    "event","phase_hit","player",player.getName(),"tick",sample.tick,"dimension",world.dimension(),"epoch",world.getBlockBreakEpoch());
            entry.putAll(data);sink.accept(entry);
        }catch(Throwable ignored){}
    }
    private List<Object> near(Vec3 position){
        var result=new ArrayList<Object>();if(!Float.isFinite(position.x)||!Float.isFinite(position.y)||!Float.isFinite(position.z))return result;
        long bx=(long)Math.floor(position.x),by=(long)Math.floor(position.y),bz=(long)Math.floor(position.z);
        for(int dy=-1;dy<=2;dy++)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            long x=bx+dx,y=by+dy,z=bz+dz;if(x<Integer.MIN_VALUE||x>Integer.MAX_VALUE||y<Integer.MIN_VALUE||y>Integer.MAX_VALUE||z<Integer.MIN_VALUE||z>Integer.MAX_VALUE)continue;
            int id=world.getRawBlockAt((int)x,(int)y,(int)z,0);
            var cell=fields("position",List.of(x,y,z),"networkId",id,"columnLoaded",world.isChunkLoaded((int)x,(int)z),"sectionKnown",world.isSectionKnown((int)x,(int)y,(int)z));
            try{cell.put("state",state(world.networkBlockState(id)));}catch(RuntimeException error){cell.put("stateError",error.toString());}
            result.add(cell);
        }
        return result;
    }
    private static List<Object> shapes(List<CollisionShapeEntry> entries,int limit){var result=new ArrayList<Object>();for(var entry:entries){if(result.size()==limit)break;result.add(shape(entry));}return result;}
    private static Map<String,Object> shape(CollisionShapeEntry entry){return fields("box",box(entry.shape()),"block",state(entry.block()));}
    private static Map<String,Object> state(NetworkBlockState state){return state==null?null:fields("networkId",state.networkId(),"name",state.identifier(),"legacyFullId",state.legacyFullId(),"metadata",state.blockData(),"state",state.stateTag());}
    private static Object number(float f){return Float.isFinite(f)?f:Float.toString(f);}
    private static List<Object> vec(Vec3 v){return List.of(number(v.x),number(v.y),number(v.z));}
    private static List<Object> box(Box b){return List.of(number(b.minX),number(b.minY),number(b.minZ),number(b.maxX),number(b.maxY),number(b.maxZ));}


    private static final class FileSink {
        static final Path PATH=Path.of(System.getProperty("navro.worldCollisionLog","logs/world-collision.jsonl"));
        static final FileSink INSTANCE=new FileSink();
        final ArrayBlockingQueue<Map<String,Object>> queue=new ArrayBlockingQueue<>(256);
        final AtomicLong dropped=new AtomicLong();final Thread worker;volatile boolean stopping;
        FileSink(){worker=new Thread(this::run,"navro-world-collision-log");worker.setDaemon(true);worker.start();Runtime.getRuntime().addShutdownHook(new Thread(()->{stopping=true;try{worker.join(3000);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}},"navro-world-log-close"));}
        void offer(Map<String,Object> entry){if(!queue.offer(entry)){
            dropped.incrementAndGet();if("phase_hit".equals(entry.get("event"))){queue.poll();if(!queue.offer(entry))dropped.incrementAndGet();}
        }}
        void run(){
            Gson gson=new Gson();BufferedWriter writer=null;long size=0;
            try{
                Files.createDirectories(PATH.toAbsolutePath().getParent());size=Files.exists(PATH)?Files.size(PATH):0;
                writer=Files.newBufferedWriter(PATH,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                while(!stopping||!queue.isEmpty()){
                    Map<String,Object> entry=queue.poll(250,TimeUnit.MILLISECONDS);if(entry==null){writer.flush();continue;}
                    long lost=dropped.getAndSet(0);if(lost>0){entry=new LinkedHashMap<>(entry);entry.put("droppedLogRecords",lost);}
                    String line=gson.toJson(entry);long bytes=line.getBytes(StandardCharsets.UTF_8).length+1L;
                    if(size+bytes>8L*1024*1024){writer.close();Path one=Path.of(PATH+".1"),two=Path.of(PATH+".2");if(Files.exists(one))Files.move(one,two,StandardCopyOption.REPLACE_EXISTING);if(Files.exists(PATH))Files.move(PATH,one,StandardCopyOption.REPLACE_EXISTING);writer=Files.newBufferedWriter(PATH,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);size=0;}
                    writer.write(line);writer.newLine();size+=bytes;if(queue.isEmpty())writer.flush();
                }
            }catch(Exception error){System.err.println("[NavroProxy] World/collision diagnostic output failed: "+error);}
            finally{if(writer!=null)try{writer.close();}catch(IOException ignored){}}
        }
    }
}
