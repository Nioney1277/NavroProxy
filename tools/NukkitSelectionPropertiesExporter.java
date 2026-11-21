import ac.navro.anticheat.data.block.NavroBlockState;
import ac.navro.anticheat.data.block.NetworkBlockState;
import cn.nukkit.*;
import cn.nukkit.block.*;
import cn.nukkit.level.Level;
import cn.nukkit.level.format.LevelProvider;
import cn.nukkit.math.BlockVector3;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;




public final class NukkitSelectionPropertiesExporter {
    static final class MissingWorld extends RuntimeException { }
    public static final class World extends Level {
        public World(){super(null,"fixture","",(Class<? extends LevelProvider>)null);}
        @Override public Block getBlock(int x,int y,int z){throw new MissingWorld();}
        @Override public Block getBlock(int x,int y,int z,int layer){throw new MissingWorld();}
        @Override public Block getBlock(int x,int y,int z,int layer,boolean load){throw new MissingWorld();}
    }
    record Facts(boolean known,int id,int damage,boolean slab,boolean sturdy,boolean present,List<Double> bounds) {
        void write(DataOutputStream out)throws IOException {
            out.writeBoolean(known);out.writeInt(id);out.writeInt(damage);out.writeBoolean(slab);out.writeBoolean(sturdy);out.writeBoolean(present);
            if(present)for(double value:bounds)out.writeDouble(value);
        }
    }
    record Run(int end,Facts facts) { }
    static Facts facts(int id,int meta,World world) {
        Block b=Block.get(id,meta,world,0,0,0,0);
        try {
            var bounds=b.getBoundingBox();boolean sturdy=new NavroBlockState(b,NetworkBlockState.legacy((id<<Block.DATA_BITS)|meta),new BlockVector3(),0).isFaceSturdy(null);
            return new Facts(true,b.getId(),b.getDamage()==meta?-1:b.getDamage(),b instanceof BlockSlab,sturdy,bounds!=null,bounds==null?List.of():
                    List.of(bounds.getMinX(),bounds.getMinY(),bounds.getMinZ(),bounds.getMaxX(),bounds.getMaxY(),bounds.getMaxZ()));
        } catch(RuntimeException e) {return new Facts(false,b.getId(),b.getDamage()==meta?-1:b.getDamage(),b instanceof BlockSlab,false,false,List.of());}
    }
    public static void main(String[] args)throws Exception {
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var u=(Unsafe)f.get(null);
        f=Server.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,u.allocateInstance(Server.class));
        Block.init();var world=(World)u.allocateInstance(World.class);long known=0,unknown=0,ranges=0;
        f=Block.class.getDeclaredField("statePrototypes");f.setAccessible(true);Object[] caches=(Object[])f.get(null);
        var temporary=Path.of(args[0]+".tmp");
        try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary)))) {
            out.writeInt(0x4e535031);out.writeInt(2048);out.writeInt(Block.DATA_MASK+1);
            for(int id=0;id<2048;id++) {
                var rows=new ArrayList<Run>();Facts previous=null;
                for(int meta=0;meta<=Block.DATA_MASK;meta++) {
                    Facts value=facts(id,meta,world);if(previous!=null&&!previous.equals(value))rows.add(new Run(meta-1,previous));previous=value;
                    if(value.known)known++;else unknown++;
                }
                rows.add(new Run(Block.DATA_MASK,previous));out.writeInt(rows.size());for(Run run:rows){out.writeShort(run.end);run.facts.write(out);}ranges+=rows.size();
                for(Object cache:caches)if(cache instanceof Map<?,?> map)map.clear();
                if((id+1)%128==0)System.out.println("Native selection states "+(id+1)+"/2048 known="+known+" unknown="+unknown);
            }
        }
        Files.move(temporary,Path.of(args[0]),StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Native selection inputs="+(known+unknown)+" known="+known+" unknown="+unknown+" ranges="+ranges);
        if(args.length>1)try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(Path.of(args[1]))))) {
            out.writeInt(0x4e535231);out.writeInt(2048*8);
            for(int id=0;id<2048;id++)for(int meta:new int[]{0,1,3,7,15,31,63,8191}) {
                out.writeInt(id);out.writeInt(meta);facts(id,meta,world).write(out);
            }
        }
    }
}
