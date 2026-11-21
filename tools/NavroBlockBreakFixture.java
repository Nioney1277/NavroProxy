package ac.navro.anticheat.compensated.world.base;

import ac.navro.anticheat.data.block.*;

import ac.navro.anticheat.Navro;
import ac.navro.anticheat.compensated.world.CompensatedWorldImpl;
import ac.navro.anticheat.compensated.world.base.CompensatedWorld;
import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.validator.blockbreak.ServerBreakBlockValidator;
import ac.navro.anticheat.packets.input.NavroAuthInputPacket;
import ac.navro.anticheat.util.block.BlockActions;
import ac.navro.anticheat.util.math.Vec3;
import cn.nukkit.*;
import cn.nukkit.block.*;
import cn.nukkit.level.Level;
import cn.nukkit.math.*;
import cn.nukkit.nbt.*;
import cn.nukkit.nbt.tag.*;
import cn.nukkit.network.protocol.*;
import cn.nukkit.network.protocol.types.*;
import cn.nukkit.plugin.*;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.ByteOrder;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroBlockBreakFixture {
    private static final int AIR=100;
    private static final List<String> events=new ArrayList<>(), logs=new ArrayList<>();
    private static final List<BlockVector3> reads=new ArrayList<>();
    private static Unsafe unsafe;
    private record Spec(int full,String name,CompoundTag properties) { }
    private static final List<Spec> specs=new ArrayList<>();
    private static void spec(int id,int data,String name,boolean upper) {
        specs.add(new Spec(id<<Block.DATA_BITS|data,name,new CompoundTag().putBoolean("upper_block_bit",upper)));
    }
    public static final class Session extends Player {
        Level fixtureLevel;
        public Session(){super(null,0L,null);}
        @Override public Level getLevel(){return fixtureLevel;}
        @Override public String getName(){return "fixture";}
        @Override public GameVersion getGameVersion(){return GameVersion.getLastVersion();}
        @Override public boolean dataPacket(DataPacket packet){return true;}
    }
    public static final class NativeLevel extends Level {
        public NativeLevel(){super(null,"","",null);}
        @Override public Block getBlock(int x,int y,int z,int layer){
            if(layer==0)events.add("restore:"+new BlockVector3(x,y,z));
            return new BlockStone();
        }
        @Override public int getBlockRuntimeId(GameVersion version,int x,int y,int z,int layer){return 0;}
    }
    public static final class Logger extends PluginLogger {
        public Logger(){super(null);}
        @Override public void info(String text){logs.add(text);}
    }
    public static final class Plugin extends PluginBase {
        PluginLogger logger;
        @Override public PluginLogger getLogger(){return logger;}
    }
    private static final class World extends CompensatedWorldImpl {
        World(){super(null);put(0,0,chunk());}
        @Override protected void updateCachedBlock(int x,int y,int z,int layer,int network){
            var chunk=getChunk(x>>4,z>>4);if(chunk!=null)chunk.setFullBlock(x&15,y,z&15,layer,network);
        }
        @Override public int getRawBlockAt(int x,int y,int z,int layer){
            var chunk=getChunk(x>>4,z>>4);
            return chunk==null||y<getMinY()||y>=getHeightY()?AIR:chunk.getFullBlock(x&15,y,z&15,layer);
        }
        @Override public NavroBlockState getBlockState(BlockVector3 pos,int layer){
            reads.add(pos.clone());int network=getRawBlockAt(pos.x,pos.y,pos.z,layer);Spec s=specs.get(network-AIR);
            Block block=Block.get(s.full>>Block.DATA_BITS,s.full&Block.DATA_MASK,null,pos.x,pos.y,pos.z,layer);
            return new NavroBlockState(block,networkState(network,s),pos,layer);
        }
        @Override public boolean predictBlockBreak(BlockVector3 pos){events.add("predict:"+pos);return ((CompensatedWorld)this).predictNetworkBlockBreak(pos.x,pos.y,pos.z,AIR);}
        @Override public void beginBlockBreak(BlockVector3 pos){events.add("begin:"+pos);super.beginBlockBreak(pos);}
    }
    private static NetworkBlockState networkState(int network,Spec s) {
        try {
            var constructor=NetworkBlockState.class.getDeclaredConstructor(int.class,int.class,CompoundTag.class);
            constructor.setAccessible(true);
            return constructor.newInstance(network,s.full,new CompoundTag().putString("name",s.name).putCompound("states",s.properties));
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static CompensatedWorld.CachedChunk chunk(){return CompensatedWorld.CachedChunk.network(-64,320,24,AIR);}
    private static final class DecodedPacket extends PlayerAuthInputPacket {
        List<PlayerBlockActionData> decoded;
        DecodedPacket(List<PlayerBlockActionData> input){
            var map=new LinkedHashMap<PlayerActionType,PlayerBlockActionData>();
            for(var a:input)map.put(a==null?null:a.getAction(),a);
            super.setBlockActionData(map);decoded=new ArrayList<>(input);
        }
        @Override public List<PlayerBlockActionData> getDecodedBlockActions(){return decoded;}
        @Override public void setBlockActionData(Map<PlayerActionType,PlayerBlockActionData> map){decoded=List.of();super.setBlockActionData(map);}
    }
    private static void field(Object object,Class<?> type,String name,Object value)throws Exception{
        var f=type.getDeclaredField(name);f.setAccessible(true);f.set(object,value);
    }
    private static void position(DataOutputStream out,BlockVector3 p)throws Exception{
        out.writeBoolean(p!=null);if(p!=null){out.writeInt(p.x);out.writeInt(p.y);out.writeInt(p.z);}
    }
    private static void action(DataOutputStream out,PlayerBlockActionData a)throws Exception{
        out.writeBoolean(a!=null);if(a==null)return;out.writeInt(a.getAction()==null?-1:a.getAction().ordinal());
        position(out,a.getPosition());out.writeInt(a.getFacing());
    }
    private static void actions(DataOutputStream out,List<PlayerBlockActionData> data)throws Exception{out.writeInt(data.size());for(var a:data)action(out,a);}
    private static void strings(DataOutputStream out,List<String> data)throws Exception{out.writeInt(data.size());for(var s:data)out.writeUTF(s);}
    private static PlayerBlockActionData a(int type,BlockVector3 p,int face){return new PlayerBlockActionData(PlayerActionType.fromOrNull(type),p,face);}
    private static List<PlayerBlockActionData> input(int scenario,int step,int mode,Random r,BlockVector3[] points){
        var data=new ArrayList<PlayerBlockActionData>();var p=points[0];var q=points[1];
        if(scenario<24){
            switch(step){
                case 0->data.add(a(0,p,scenario%8-1));
                case 1->{data.add(a(0,p,1));data.add(a(26,p,1));data.add(a(27,p,1));data.add(a(26,p,1));}
                case 2->data.add(a(0,q,1));
                case 3->data.add(a(1,p,-100));
                case 4->{data.add(a(27,q,4));data.add(a(26,q,2));}
                case 5->{data.add(a(0,p,1));data.add(a(26,p,1));data.add(a(0,q,1));data.add(a(26,q,1));if(mode==1)data.add(a(1,q,1));}
                case 6->data.add(a(26,points[3],1));
                default->{data.add(a(0,p,1));data.add(a(1,p,-1));}
            }
            return data;
        }
        int[] types={0,0,1,26,26,27,18,2,4,-1};
        for(int j=0,n=r.nextInt(8);j<n;j++){
            if(mode==1&&r.nextInt(20)==0){data.add(null);continue;}
            BlockVector3 pos=r.nextInt(16)==0?null:points[r.nextInt(points.length)].clone();
            data.add(a(types[r.nextInt(types.length)],pos,r.nextInt(9)-2));
        }
        return data;
    }
    public static void main(String[] args)throws Exception{
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);unsafe=(Unsafe)f.get(null);Block.init();
        spec(0,0,"minecraft:air",false);spec(1,0,"minecraft:stone",false);spec(7,0,"minecraft:bedrock",false);
        spec(8,0,"minecraft:water",false);spec(10,0,"minecraft:lava",false);spec(199,0,"minecraft:frame",false);
        spec(64,0,"minecraft:wooden_door",true);spec(64,8,"minecraft:wooden_door",false);
        spec(71,8,"minecraft:iron_door",false);spec(1,0,"example:stone_door",false);
        spec(1,0,"",false);spec(64,0,"",true);spec(64,8,"example:other_door",false);
        spec(71,0,"example:different_door",true);spec(1,0,"example:stone_door",true);
        spec(1,0,"minecraft:void_air",false);spec(9,0,"minecraft:water",false);spec(11,0,"minecraft:lava",false);
        spec(9999,8191,"example:unknown",false);spec(90,1,"minecraft:portal",false);
        spec(2,1,"minecraft:grass_block",false);spec(64,8191,"minecraft:wooden_door",false);
        spec(1,0,"minecraft:cave_air",false);spec(0,0,"example:not_named_air",false);
        Plugin plugin=(Plugin)unsafe.allocateInstance(Plugin.class);plugin.logger=(Logger)unsafe.allocateInstance(Logger.class);
        field(null,Navro.class,"pluginInstance",plugin);
        var active=ServerBreakBlockValidator.class.getDeclaredField("breakingData");active.setAccessible(true);
        var random=new Random(0x4E4156524F4252L);
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))){
            out.writeInt(0x4E425231);out.writeInt(specs.size());
            for(Spec s:specs){out.writeInt(s.full);out.writeUTF(s.name);byte[] tag=NBTIO.write(s.properties,ByteOrder.BIG_ENDIAN,false);out.writeInt(tag.length);out.write(tag);}
            int count=384;out.writeInt(count);
            for(int scenario=0;scenario<count;scenario++){
                var player=(NavroPlayer)unsafe.allocateInstance(NavroPlayer.class);var world=new World();
                var session=(Session)unsafe.allocateInstance(Session.class);session.fixtureLevel=(NativeLevel)unsafe.allocateInstance(NativeLevel.class);
                field(player,NavroPlayer.class,"session",session);field(player,NavroPlayer.class,"compensatedWorld",world);
                player.position=scenario%11==0?new Vec3(-0.1F,64,8):new Vec3(8.25F,64,8.5F);
                if(scenario%37==0)player.position=new Vec3(Integer.MIN_VALUE,64,Integer.MIN_VALUE);
                player.gameType=GameType.from(scenario%7);player.tick=100;
                out.writeFloat(player.position.x);out.writeFloat(player.position.y);out.writeFloat(player.position.z);out.writeInt(player.gameType.ordinal());
                BlockVector3[] points={new BlockVector3(8,64,8),new BlockVector3(9,64,8),new BlockVector3(8,65,8),new BlockVector3(8,63,8),
                    new BlockVector3(20,64,8),new BlockVector3(21,64,8),new BlockVector3(Integer.MAX_VALUE,64,Integer.MAX_VALUE),new BlockVector3(-1,64,8)};
                out.writeInt(points.length);for(var p:points){position(out,p);int state=scenario<24?scenario:random.nextInt(specs.size());out.writeInt(AIR+state);world.updateBlock(p,0,AIR+state);}
                var validator=new ServerBreakBlockValidator(player);out.writeInt(8);
                for(int step=0;step<8;step++){
                    int change=step==6?1+scenario%4:0;out.writeInt(change);
                    if(change==1)world.clearChunks();
                    if(change==2)world.setDimension(1);
                    if(change==3)world.updateBlock(points[0],0,AIR+1);
                    if(change==4)world.applyAcknowledgedBlock(8,64,8,0,AIR+1,world.bucketPredictionRevision());
                    int mode=(scenario+step)%3;out.writeInt(mode);
                    var input=input(scenario,step,mode,random,points);actions(out,input);
                    PlayerAuthInputPacket packet;
                    if(mode==0){var navro=new NavroAuthInputPacket();navro.setBlockActions(input);packet=navro;}
                    else if(mode==1)packet=new DecodedPacket(input);
                    else{packet=new PlayerAuthInputPacket();var map=new LinkedHashMap<PlayerActionType,PlayerBlockActionData>();for(var a:input)map.put(a.getAction(),a);packet.setBlockActionData(map);}
                    boolean flag=scenario<24||step%5!=0;out.writeBoolean(flag);if(flag)packet.getInputData().add(AuthInputAction.PERFORM_BLOCK_ACTIONS);
                    long tick=100L+step+(scenario%17==0?1000000L:0);packet.setTick(tick);out.writeLong(tick);
                    if(scenario%13!=0){packet.setPosition(new Vector3f(-0F,65.62F,8.25F));packet.setHeadYaw(-180.5F);packet.setPitch(30.25F);}
                    events.clear();logs.clear();reads.clear();validator.handle(packet);
                    actions(out,BlockActions.read(packet));out.writeBoolean(packet.getInputData().contains(AuthInputAction.PERFORM_BLOCK_ACTIONS));
                    out.writeUTF(String.valueOf(active.get(validator)));strings(out,events);strings(out,logs);
                    out.writeInt(reads.size());for(var pos:reads)position(out,pos);
                    out.writeLong(world.bucketPredictionRevision());out.writeLong(world.getBlockBreakEpoch());
                    for(var p:points){out.writeInt(world.getRawBlockAt(p.x,p.y,p.z,0));out.writeBoolean(world.isPredictedBlockBreak(p));}
                }
            }
        }finally{field(null,Navro.class,"pluginInstance",null);}
        System.out.println("Original digging reference: 384 sessions, 3072 packets, ordered / filtered-map / map-only writers, exact logs and cache state.");
    }
}
