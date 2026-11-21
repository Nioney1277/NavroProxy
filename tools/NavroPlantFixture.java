package ac.navro.anticheat.data.block;

import ac.navro.anticheat.collision.PlantCollision;
import ac.navro.anticheat.util.math.Box;
import cn.nukkit.block.*;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import java.io.*;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.zip.GZIPOutputStream;


public final class NavroPlantFixture {
    private static void box(DataOutputStream out, Box b) throws Exception {
        out.writeFloat(b.minX);out.writeFloat(b.minY);out.writeFloat(b.minZ);
        out.writeFloat(b.maxX);out.writeFloat(b.maxY);out.writeFloat(b.maxZ);
    }
    public static void main(String[] args) throws Exception {
        try (var out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))) {
            out.writeInt(0x4E504D31);out.writeInt(Block.DATA_BITS);out.writeInt(BlockID.BAMBOO);out.writeInt(BlockID.POINTED_DRIPSTONE);
            out.writeInt(Block.DATA_MASK + 1);
            for (int meta=0;meta<=Block.DATA_MASK;meta++) {
                out.writeBoolean(new BlockBamboo(meta).isThick());
                var drip = new BlockPointedDripstone(meta);out.writeBoolean(drip.isHanging());
                try { String thickness=drip.getThickness().toString();out.writeBoolean(true);out.writeUTF(thickness); }
                catch (RuntimeException e) { out.writeBoolean(false);out.writeUTF(e.getClass().getSimpleName());out.writeUTF(e.getMessage()); }
            }
        }
        var random = new Random(0x4E4156524F504CL);
        try (var out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[1])))) {
            int count=4000;out.writeInt(0x4E504C31);out.writeInt(count);
            for (int i=0;i<count;i++) {
                boolean bamboo=i%2==0;
                int x=switch(i%7){case 0->-190;case 1->16777217;case 2->Integer.MAX_VALUE;case 3->Integer.MIN_VALUE;default->random.nextInt();};
                int z=switch(i%5){case 0->-275;case 1->16777216;default->random.nextInt();};
                int y=i%401-100;
                int meta=bamboo?random.nextInt(Block.DATA_MASK+1):((i%10)|((i/10%512)<<4));
                Block block=i%13==0?new BlockStone():bamboo?new BlockBamboo(meta):new BlockPointedDripstone(meta);
                var props=new CompoundTag("states");
                switch(i/2%7){
                    case 1->props.putString(bamboo?"bamboo_stalk_thickness":"dripstone_thickness",bamboo?"thick":"frustum");
                    case 2->props.putString(bamboo?"bamboo_stalk_thickness":"dripstone_thickness",bamboo?"thin":"middle").putByte("hanging",1);
                    case 3->props.putString(bamboo?"bamboo_stalk_thickness":"dripstone_thickness",bamboo?"invalid":"base").putString("hanging","true");
                    case 4->props.putInt(bamboo?"bamboo_stalk_thickness":"dripstone_thickness",1).putByte("hanging",0);
                    case 5->props.putString(bamboo?"bamboo_stalk_thickness":"dripstone_thickness",bamboo?"thick":"tip_merge").putByte("hanging",1);
                    case 6->props.putString(bamboo?"bamboo_stalk_thickness":"dripstone_thickness",bamboo?"thin":"merge");
                    default->{ }
                }
                var tag=new CompoundTag().putString("name",bamboo?"minecraft:bamboo":"minecraft:pointed_dripstone").putCompound("states",props);
                var network=new NetworkBlockState(0,block.getFullId(),tag);
                var position=new BlockVector3(x,y,z);var state=new NavroBlockState(block,network,position,0);
                var shape=(bamboo?PlantCollision.bamboo(position,state):PlantCollision.pointedDripstone(position,state)).get(0);
                out.writeBoolean(bamboo);out.writeInt(x);out.writeInt(y);out.writeInt(z);out.writeInt(block.getFullId());
                byte[] bytes=NBTIO.write(tag,ByteOrder.BIG_ENDIAN,false);out.writeInt(bytes.length);out.write(bytes);
                box(out,shape);box(out,shape.offset(x,y,z));
            }
        }
    }
}
