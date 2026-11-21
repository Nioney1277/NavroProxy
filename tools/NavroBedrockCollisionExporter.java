package ac.navro.anticheat.data.block;

import ac.navro.anticheat.collision.BedrockCollision;
import ac.navro.anticheat.util.math.Box;
import cn.nukkit.Server;
import cn.nukkit.block.*;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.*;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;





public final class NavroBedrockCollisionExporter {
    public static void main(String[] args) throws Exception {
        var f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        var u = (Unsafe)f.get(null);
        f = Server.class.getDeclaredField("instance"); f.setAccessible(true); f.set(null,u.allocateInstance(Server.class));
        Block.init();
        Path output = Path.of(args[0]); Files.createDirectories(output);
        for (int protocol : new int[]{844,944,975,1001,2168,2193}) {
            String resource = "runtime_block_states_"+protocol+".dat";
            ListTag<?> palette;
            try (var in = new GZIPInputStream(Server.class.getClassLoader().getResourceAsStream(resource))) {
                palette = (ListTag<?>)NBTIO.readTag(in,ByteOrder.BIG_ENDIAN,false);
            }
            int overrides=0;
            try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(output.resolve("bedrock-collisions-"+protocol+".bin.gz"))))) {
                out.writeInt(0x4e424331); out.writeInt(palette.size());
                for (Object object : palette.getAll()) {
                    var tag=(CompoundTag)object;
                    int id=tag.getInt("id"), meta=tag.getShort("data") & Block.DATA_MASK;
                    var network = new NetworkBlockState(tag.getInt("runtimeId"),id<<Block.DATA_BITS|meta,
                            tag.clone().remove("id").remove("data").remove("runtimeId").remove("stateOverload"));
                    Block block=Block.get(id,meta);
                    var state=new NavroBlockState(block,network,new BlockVector3(),0);
                    int kind=0;
                    if (block instanceof BlockDoor || network.identifierContains("_door")) kind=2;
                    if (block instanceof BlockScaffolding || network.is("minecraft:scaffolding")) kind=3;
                    if (block instanceof BlockPowderSnow || network.is("minecraft:powder_snow")) kind=4;
                    if (block instanceof BlockBamboo || network.is("minecraft:bamboo")
                            || id==BlockID.POINTED_DRIPSTONE || network.is("minecraft:pointed_dripstone")) kind=5;
                    List<Box> boxes=kind==0?BedrockCollision.getCollisionBox(null,null,new BlockVector3(),state):null;
                    if (kind==0 && boxes!=null) kind=1;
                    out.writeInt(tag.getInt("runtimeId"));out.writeByte(kind);
                    out.writeShort(boxes==null?0:boxes.size());
                    if(boxes!=null)for(Box b:boxes){out.writeFloat(b.minX);out.writeFloat(b.minY);out.writeFloat(b.minZ);out.writeFloat(b.maxX);out.writeFloat(b.maxY);out.writeFloat(b.maxZ);}
                    if(kind!=0)overrides++;
                }
            }
            System.out.println(protocol+": "+palette.size()+" palette states, "+overrides+" original overrides/context tags");
        }
    }
}
