package ac.navro.proxy.data.block;

import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.validator.blockbreak.ServerBreakBlockValidator;
import ac.navro.proxy.data.vanilla.GameType;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.util.block.BlockActions;
import ac.navro.proxy.util.block.BlockActionText;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.nbt.*;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class BlockBreakReferenceTest {
    private static final int AIR=100;
    private static Vector3i position(DataInputStream in)throws Exception {
        return in.readBoolean()?Vector3i.from(in.readInt(),in.readInt(),in.readInt()):null;
    }
    private static List<PlayerBlockActionData> actions(DataInputStream in)throws Exception {
        var result=new ArrayList<PlayerBlockActionData>();
        for(int i=0,n=in.readInt();i<n;i++) {
            if(!in.readBoolean()){result.add(null);continue;}
            var a=new PlayerBlockActionData();int type=in.readInt();a.setAction(type<0?null:PlayerActionType.values()[type]);
            a.setBlockPosition(position(in));a.setFace(in.readInt());result.add(a);
        }
        return result;
    }
    private static List<String> strings(DataInputStream in)throws Exception {
        var result=new ArrayList<String>();for(int i=0,n=in.readInt();i<n;i++)result.add(in.readUTF());return result;
    }
    private static final class World extends CompensatedWorld {
        final List<String> events=new ArrayList<>();
        World(){put(0,0,CachedChunk.network(-64,320,24,AIR));}
        @Override public int getRawBlockAt(int x,int y,int z,int layer){
            var chunk=getChunk(x>>4,z>>4);
            return chunk==null||y<getMinY()||y>=getHeightY()?AIR:chunk.getFullBlock(x&15,y,z&15,layer);
        }
        @Override public boolean predictBlockBreak(Vector3i pos){
            events.add("predict:"+BlockActionText.position(pos));return predictNetworkBlockBreak(pos.getX(),pos.getY(),pos.getZ(),AIR);
        }
        @Override public void beginBlockBreak(Vector3i pos){events.add("begin:"+BlockActionText.position(pos));super.beginBlockBreak(pos);}
    }
    private static final class Context implements ServerBreakBlockValidator.Context {
        final World world;final Vec3 position;final GameType mode;final List<BlockBreakState> specs;
        final List<String> logs=new ArrayList<>();final List<Vector3i> reads=new ArrayList<>();
        int packetMode;boolean decoded;List<PlayerBlockActionData> initial;
        LinkedHashMap<PlayerActionType,PlayerBlockActionData> map;
        Context(World world,Vec3 position,GameType mode,List<BlockBreakState> specs){this.world=world;this.position=position;this.mode=mode;this.specs=specs;}
        void prepare(int mode,List<PlayerBlockActionData> input,PlayerAuthInputPacket packet) {
            packetMode=mode;decoded=mode!=2;initial=input;map=new LinkedHashMap<>();
            for(var a:input)map.put(a==null?null:a.getAction(),a);
            packet.getPlayerActions().addAll(mode==2?BlockActions.read(map):input);
        }
        @Override public CompensatedWorld world(){return world;}
        @Override public Vec3 position(){return position;}
        @Override public GameType gameType(){return mode;}
        @Override public String name(){return "fixture";}
        @Override public void restoreCorrectBlock(Vector3i pos){world.events.add("restore:"+BlockActionText.position(pos));}
        @Override public java.util.function.Consumer<String> debugLogger(){return logs::add;}
        @Override public BlockBreakState blockState(Vector3i pos,int layer){
            reads.add(pos);return specs.get(world.getRawBlockAt(pos.getX(),pos.getY(),pos.getZ(),layer)-AIR);
        }
        @Override public Iterable<PlayerBlockActionData> rawActions(PlayerAuthInputPacket packet){return map.values();}
        @Override public List<PlayerBlockActionData> readActions(PlayerAuthInputPacket packet){
            return decoded?BlockActions.read(packet):BlockActions.read(map);
        }
        @Override public List<PlayerBlockActionData> writeActions(PlayerAuthInputPacket packet,List<PlayerBlockActionData> valid){
            if(packetMode==0)return BlockActions.write(packet,valid);
            if(decoded&&!initial.isEmpty()&&initial.equals(valid))return valid;
            map=new LinkedHashMap<>();for(var a:valid)map.put(a.getAction(),a);decoded=false;
            var actual=new ArrayList<>(map.values());BlockActions.write(packet,actual);return actual;
        }
    }
    @Test void continuousNativeSessionsMatchForwardingPredictionsReadsCorrectionsAndAllDebugText()throws Exception {
        var active=ServerBreakBlockValidator.class.getDeclaredField("breakingData");active.setAccessible(true);
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-block-break.bin.gz")))) {
            assertEquals(0x4E425231,in.readInt());var specs=new ArrayList<BlockBreakState>();
            for(int i=0,n=in.readInt();i<n;i++) {
                int full=in.readInt();String name=in.readUTF();byte[] bytes=in.readNBytes(in.readInt());NbtMap properties;
                try(var reader=NbtUtils.createReader(new ByteArrayInputStream(bytes))){properties=(NbtMap)reader.readTag();}
                var state=new NetworkBlockState(AIR+i,full,NbtMap.builder().putString("name",name).putCompound("states",properties).build());
                specs.add(new BlockBreakState(state,NativeBlockProperties.forState(state)));
            }
            int count=in.readInt();assertEquals(384,count);
            for(int scenario=0;scenario<count;scenario++) {
                var w=new World();var context=new Context(w,new Vec3(in.readFloat(),in.readFloat(),in.readFloat()),GameType.from(in.readInt()),specs);
                var points=new ArrayList<Vector3i>();
                for(int i=0,n=in.readInt();i<n;i++) {
                    var pos=position(in);points.add(pos);w.updateBlock(pos.getX(),pos.getY(),pos.getZ(),0,in.readInt());
                }
                var validator=new ServerBreakBlockValidator(context);
                for(int step=0,n=in.readInt();step<n;step++) {
                    String label="scenario="+scenario+" step="+step;
                    int change=in.readInt();
                    if(change==1)w.clearChunks();if(change==2)w.setDimension(1);
                    if(change==3)w.updateBlock(8,64,8,0,AIR+1);
                    if(change==4)w.applyAcknowledgedBlock(8,64,8,0,AIR+1,w.bucketPredictionRevision());
                    int mode=in.readInt();var input=actions(in);var packet=new PlayerAuthInputPacket();context.prepare(mode,input,packet);
                    if(in.readBoolean())packet.getInputData().add(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS);
                    packet.setTick(in.readLong());if(scenario%13!=0){packet.setPosition(Vector3f.from(-0F,65.62F,8.25F));packet.setRotation(Vector3f.from(30.25F,0,-180.5F));}
                    w.events.clear();context.logs.clear();context.reads.clear();validator.handle(packet);
                    assertEquals(actions(in),context.readActions(packet),label+" forwarded");
                    assertEquals(in.readBoolean(),packet.getInputData().contains(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS),label+" flag");
                    assertEquals(in.readUTF(),String.valueOf(active.get(validator)),label+" operation");
                    assertEquals(strings(in),w.events,label+" begin/predict/restore order");
                    assertEquals(strings(in),context.logs,label+" exact native text");
                    var reads=new ArrayList<Vector3i>();for(int i=0,size=in.readInt();i<size;i++)reads.add(position(in));
                    assertEquals(reads,context.reads,label+" state reads");
                    assertEquals(in.readLong(),w.bucketPredictionRevision(),label+" revision");assertEquals(in.readLong(),w.getBlockBreakEpoch(),label+" epoch");
                    for(var pos:points) {
                        assertEquals(in.readInt(),w.getRawBlockAt(pos.getX(),pos.getY(),pos.getZ(),0),label+" raw "+pos);
                        assertEquals(in.readBoolean(),w.isPredictedBlockBreak(pos),label+" prediction "+pos);
                    }
                }
            }
            assertEquals(-1,in.read());
        }
    }
}
