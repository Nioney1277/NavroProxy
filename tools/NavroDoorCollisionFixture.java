package ac.navro.anticheat.data.block;

import ac.navro.anticheat.collision.BedrockCollision;
import ac.navro.anticheat.compensated.world.CompensatedWorldImpl;
import ac.navro.anticheat.player.NavroPlayer;
import cn.nukkit.Server;
import cn.nukkit.block.*;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.*;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.ByteOrder;
import java.util.*;
import java.util.zip.*;


public class NavroDoorCollisionFixture {
    public static class World extends CompensatedWorldImpl {
        NavroBlockState partner;
        World(){super(null);}
        @Override public int getMinY(){return -64;}
        @Override public NavroBlockState getBlockState(int x,int y,int z,int layer){return partner;}
    }
    public static void main(String[] args)throws Exception{
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var u=(Unsafe)f.get(null);
        f=Server.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,u.allocateInstance(Server.class));Block.init();
        var p=(NavroPlayer)u.allocateInstance(NavroPlayer.class);var w=(World)u.allocateInstance(World.class);
        f=NavroPlayer.class.getDeclaredField("compensatedWorld");f.setAccessible(true);f.set(p,w);
        var groups=new TreeMap<String,List<NavroBlockState>>();
        try(var in=new GZIPInputStream(Server.class.getClassLoader().getResourceAsStream("runtime_block_states_2193.dat"))){
            var palette=(ListTag<?>)NBTIO.readTag(in,ByteOrder.BIG_ENDIAN,false);
            for(Object o:palette.getAll()){
                var t=(CompoundTag)o;var b=Block.get(t.getInt("id"),t.getShort("data")&Block.DATA_MASK);
                if(!(b instanceof BlockDoor))continue;
                var network=new NetworkBlockState(t.getInt("runtimeId"),b.getFullId(),t.clone().remove("id").remove("data").remove("runtimeId").remove("stateOverload"));
                groups.computeIfAbsent(network.identifier(),ignored->new ArrayList<>()).add(new NavroBlockState(b,network,new BlockVector3(),0));
            }
        }
        int count=groups.values().stream().mapToInt(a->a.size()*a.size()).sum();
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))){
            out.writeInt(0x4e444331);out.writeInt(count);
            for(var list:groups.values())for(var current:list)for(var neighbor:list){
                w.partner=neighbor;
                var boxes=BedrockCollision.getCollisionBox(p,null,new BlockVector3(),current);
                out.writeInt(current.getNetworkState().networkId());out.writeInt(neighbor.getNetworkState().networkId());out.writeInt(boxes.size());
                for(var b:boxes){out.writeFloat(b.minX);out.writeFloat(b.minY);out.writeFloat(b.minZ);out.writeFloat(b.maxX);out.writeFloat(b.maxY);out.writeFloat(b.maxZ);}
            }
        }
        System.out.println("Original door collision neighbor cases: "+count);
    }
}
