package ac.navro.anticheat.compensated.world.base;

import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.player.data.PlayerData;
import ac.navro.anticheat.compensated.world.CompensatedWorldImpl;
import ac.navro.anticheat.collision.CollisionPhysicsState;
import ac.navro.anticheat.data.block.NetworkBlockStateRegistry;
import cn.nukkit.*;
import cn.nukkit.block.Block;
import cn.nukkit.level.GlobalBlockPalette;
import cn.nukkit.math.BlockVector3;
import sun.misc.Unsafe;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroPlacementSupportFixture {
    public static final class Session extends Player {
        Server clock;GameVersion version;
        public Session(){super(null,0L,null);}
        @Override public Server getServer(){return clock;}
        @Override public GameVersion getGameVersion(){return version;}
    }
    private static final class World extends CompensatedWorldImpl {
        World(NavroPlayer player){super(player);}
        @Override protected void updateCachedBlock(int x,int y,int z,int layer,int network){
            var chunk=getChunk(x>>4,z>>4);if(chunk!=null)chunk.setFullBlock(x&15,y,z&15,layer,network);
        }
    }
    private static void field(Object object,Class<?> type,String name,Object value)throws Exception{
        var f=type.getDeclaredField(name);f.setAccessible(true);f.set(object,value);
    }
    private static Object field(Object object,String name)throws Exception{
        var f=CompensatedWorld.class.getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private static CompensatedWorld.CachedChunk chunk(int air){return CompensatedWorld.CachedChunk.network(-64,320,24,air);}
    private static void pos(DataOutputStream out,BlockVector3 p)throws Exception{out.writeInt(p.x);out.writeInt(p.y);out.writeInt(p.z);}
    public static void main(String[] args)throws Exception{
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);Unsafe unsafe=(Unsafe)f.get(null);
        field(null,Server.class,"instance",unsafe.allocateInstance(Server.class));Block.init();
        BlockVector3[] points={new BlockVector3(8,64,8),new BlockVector3(9,64,8),new BlockVector3(24,64,8),new BlockVector3(8,-65,8),new BlockVector3(8,320,8)};
        var random=new Random(0x4E535550504F52L);
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))){
            out.writeInt(0x4E535031);out.writeInt(4);
            for(int protocol:new int[]{944,2192})for(boolean hash:new boolean[]{false,true}){
                GlobalBlockPalette.setUseHashedBlockNetworkIds(hash);
                var nativePlayer=(NavroPlayer)unsafe.allocateInstance(NavroPlayer.class);var session=(Session)unsafe.allocateInstance(Session.class);
                session.clock=(Server)unsafe.allocateInstance(Server.class);session.version=GameVersion.byProtocol(protocol,false);
                field(nativePlayer,NavroPlayer.class,"session",session);field(nativePlayer,PlayerData.class,"collisionPhysics",new CollisionPhysicsState());
                World world=new World(nativePlayer);field(nativePlayer,NavroPlayer.class,"compensatedWorld",world);
                int[] networks=new int[4];int[] ids={0,1,8,7};for(int i=0;i<4;i++)networks[i]=NetworkBlockStateRegistry.networkIdForLegacy(nativePlayer,ids[i]<<Block.DATA_BITS);
                out.writeInt(protocol);out.writeBoolean(hash);for(int network:networks)out.writeInt(network);
                out.writeInt(points.length);for(var point:points)pos(out,point);
                world.put(0,0,chunk(networks[0]));world.put(1,0,chunk(networks[0]));
                int steps=640;out.writeInt(steps);
                for(int i=0;i<steps;i++){
                    int base=switch((i/80)%4){case 0->100;case 1->Integer.MAX_VALUE-3;case 2->Integer.MIN_VALUE;default->-10;};
                    int tick=base+(i%80)/4;field(session.clock,Server.class,"tickCounter",tick);
                    int op=i%20;int index=op<10?0:random.nextInt(points.length);var point=points[index];
                    int layer=op<10?0:random.nextInt(2);int network=networks[random.nextInt(networks.length)];
                    long revision=world.bucketPredictionRevision()-(op==16?1:0);
                    out.writeInt(tick);out.writeInt(op);out.writeInt(index);out.writeInt(layer);out.writeInt(network);out.writeLong(revision);
                    boolean accepted=true;
                    switch(op){
                        case 0->{world.put(0,0,chunk(networks[0]));world.put(1,0,chunk(networks[0]));}
                        case 1,7->world.updateBlock(point,0,networks[1]);
                        case 2,5,12->accepted=world.predictBlockBreak(point);
                        case 3,8,16->accepted=world.applyAcknowledgedBlock(point.x,point.y,point.z,layer,network,revision);
                        case 4->accepted=world.applyAcknowledgedBlock(point.x,point.y,point.z,0,networks[1],revision);
                        case 6->accepted=world.applyAcknowledgedBlock(point.x,point.y,point.z,0,networks[0],revision);
                        case 9,13->world.predictBucketNetworkBlock(point.x,point.y,point.z,layer,network);
                        case 10->world.beginBlockBreak(point);
                        case 11->world.updateBlock(point,layer,network);
                        case 14->world.removeFromCache(point.x>>4,point.z>>4);
                        case 15->world.retainNukkitUsedChunks(new long[]{0L});
                        case 17->world.clearChunks();
                        case 18->world.setDimension(world.getDimension()==0?1:0);
                        default->{ }
                    }
                    out.writeBoolean(accepted);out.writeInt((int)field(world,"placementSupportHistoryTick"));
                    @SuppressWarnings("unchecked") var history=(Map<BlockVector3,Integer>)field(world,"recentPlacementSupports");
                    var ordered=new ArrayList<>(history.keySet());ordered.sort(Comparator.comparingInt((BlockVector3 p)->p.x).thenComparingInt(p->p.y).thenComparingInt(p->p.z));
                    out.writeInt(ordered.size());for(var p:ordered){pos(out,p);out.writeInt(history.get(p));}
                    out.writeLong(world.bucketPredictionRevision());out.writeLong(world.getBlockBreakEpoch());
                    for(var p:points){out.writeBoolean(world.hadRecentPlacementSupport(p));out.writeInt(world.getRawBlockAt(p.x,p.y,p.z,0));out.writeBoolean(world.isPredictedBlockBreak(p));}
                }
            }
        }
        System.out.println("Native support history: 2560 operations, runtime/hash palettes, native server ticks and signed overflow, cache and ACK lifecycle.");
    }
}
