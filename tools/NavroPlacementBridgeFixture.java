import ac.navro.proxy.data.inventory.NativeItemDecoder;
import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.anticheat.data.vanilla.AttributeInstance;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.anticheat.util.block.BlockReachUtil;
import cn.nukkit.*;
import cn.nukkit.block.Block;
import cn.nukkit.item.*;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.types.GameType;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.utils.BinaryStream;
import sun.misc.Unsafe;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;




public final class NavroPlacementBridgeFixture {
    static void set(Object o,Class<?> c,String name,Object value)throws Exception {var f=c.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static Object value(Object o,String name)throws Exception {var m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(o);}
    static void properties(DataOutputStream out,Item item)throws Exception {
        var mapped=item.getBlockUnsafe();var state=mapped==null?null:Block.get(mapped.getId(),0);
        out.writeBoolean(item.canBePlaced());out.writeBoolean(mapped!=null);out.writeInt(item.getBlockId());out.writeInt(state==null?0:state.getId());out.writeInt(state==null?0:state.getFullId());
        out.writeBoolean(BlockReachUtil.isPlaceable(item));
    }
    public static void main(String[] args)throws Exception {
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var u=(Unsafe)f.get(null);
        set(null,Server.class,"instance",u.allocateInstance(Server.class));Block.init();RuntimeItems.init();Item.init();
        f=NativeItemDecoder.class.getDeclaredField("TABLE");f.setAccessible(true);Object[] factories=(Object[])value(f.get(null),"factories");
        int[] damage={0,1,3,8191,32767,32768,65535};int[] protocols={729,2168,2193};int count=0,placeable=0;
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))) {
            out.writeInt(0x4e504231);out.writeInt(factories.length*damage.length*protocols.length);
            for(Object factory:factories)for(int protocol:protocols)for(int raw:damage) {
                Integer number=(Integer)value(factory,"number");String name=(String)value(factory,"name");
                GameVersion version=GameVersion.byProtocol(protocol,false);int format=count%3;
                boolean descriptor=version.getProtocol()>=(format==1?ProtocolInfo.v1_26_30:format==2?ProtocolInfo.v1_26_40:ProtocolInfo.v1_26_20_26);


                var tag=new CompoundTag().putInt("mv_origin_id",number==null?255:number).putInt("mv_origin_meta",raw)
                        .putCompound("mv_origin_nbt",new CompoundTag());
                if(number==null)tag.putString("mv_origin_namespace",name);
                Item shell=Item.get(248);int runtime=shell.getNetworkId(version);String shellName=shell.getNamespaceId(version);
                byte[] wire=NukkitItemDecoderFixture.wire(version,descriptor,runtime,2,0,42,tag);
                var input=new BinaryStream(wire);Item decoded=descriptor?input.getNetworkItemStackDescriptor(version):input.getSlot(version);
                if(input.getOffset()!=wire.length)throw new AssertionError("Unread native wire bytes");
                out.writeInt(protocol);out.writeInt(format);out.writeBoolean(number!=null);if(number!=null)out.writeInt(number);else out.writeUTF(name);
                out.writeInt(raw);out.writeUTF(shellName);out.writeInt(runtime);out.writeInt(decoded.getId());out.writeInt(decoded.getDamage());
                properties(out,decoded);if(BlockReachUtil.isPlaceable(decoded))placeable++;count++;
            }
        }
        System.out.println("Native decoded placement cases="+count+" placeable="+placeable+" constructors="+factories.length);
        String[] names={"minecraft:stone","minecraft:spruce_planks","minecraft:wheat_seeds","minecraft:sugar_cane",
                "minecraft:oak_door","minecraft:torchflower_seeds","minecraft:bucket","minecraft:water_bucket",
                "minecraft:scaffolding","minecraft:snow_layer","minecraft:bow","minecraft:air","minecraft:glow_berries"};
        int ordinary=0;
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[2])))) {
            out.writeInt(0x4e505731);out.writeInt(NukkitItemDecoderExporter.PROTOCOLS.length*3*names.length*3);
            for(int protocol:NukkitItemDecoderExporter.PROTOCOLS)for(int format=0;format<3;format++)for(String name:names)for(int raw:new int[]{0,3,65535}) {
                GameVersion version=GameVersion.byProtocol(protocol,false);
                boolean descriptor=version.getProtocol()>=(format==1?ProtocolInfo.v1_26_30:format==2?ProtocolInfo.v1_26_40:ProtocolInfo.v1_26_20_26);
                Item original=Item.fromString(name);String identifier=original.getId()==0?"minecraft:air":original.getNamespaceId(version);int runtime=original.getId()==0?0:original.getNetworkId(version);
                var tag=new CompoundTag().putString("payload","ordinary");
                byte[] wire=NukkitItemDecoderFixture.wire(version,descriptor,runtime,2,raw,42,tag);
                out.writeInt(protocol);out.writeInt(format);out.writeUTF(identifier);out.writeInt(runtime);out.writeInt(raw);
                try {
                    var input=new BinaryStream(wire);Item decoded=descriptor?input.getNetworkItemStackDescriptor(version):input.getSlot(version);
                    if(input.getOffset()!=wire.length)throw new AssertionError("Unread ordinary wire bytes");
                    out.writeBoolean(true);out.writeInt(decoded.getId());out.writeInt(decoded.getDamage());properties(out,decoded);
                } catch(RuntimeException error) {out.writeBoolean(false);out.writeUTF(error.getClass().getName());}
                ordinary++;
            }
        }
        System.out.println("Ordinary native decoded placement cases="+ordinary);
        var random=new Random(0x504c414345524541L);int reachCases=4096;
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[1])))) {
            out.writeInt(0x4e425231);out.writeInt(reachCases);
            for(int i=0;i<reachCases;i++) {
                var p=(NavroPlayer)u.allocateInstance(NavroPlayer.class);
                float x=random.nextFloat()*200-100,y=random.nextFloat()*200-100,z=random.nextFloat()*200-100;
                p.position=new Vec3(x,y,z);float eye=new float[]{1.62F,1.26F,.42F,1.62005F,1.6202F,0F,Float.NaN}[i%7];
                p.dimensions=EntityDimensions.changing(.6F,1.8F).withEyeHeight(eye);p.gameType=GameType.values()[i%GameType.values().length];
                int mode=i%6;float attribute=new float[]{7F,13F,0F,-1F,Float.NaN,Float.POSITIVE_INFINITY,1.25F}[i%7];
                set(p,ac.navro.anticheat.player.data.PlayerData.class,"attributes",new HashMap<String,AttributeInstance>());
                if(mode==1||mode==3||mode==4||mode==5){var a=new AttributeInstance(0);a.setValue(attribute);p.attributes.put("minecraft:block_interaction_range",a);}
                if(mode>=2){var a=new AttributeInstance(0);a.setValue(2.5F);p.attributes.put("block_interaction_range",a);}
                BlockVector3 target=i%29==0?null:new BlockVector3((int)Math.floor(x)+random.nextInt(35)-17,(int)Math.floor(y)+random.nextInt(35)-17,(int)Math.floor(z)+random.nextInt(35)-17);
                out.writeFloat(x);out.writeFloat(y);out.writeFloat(z);out.writeInt(Float.floatToRawIntBits(eye));out.writeInt(p.gameType.ordinal());out.writeInt(mode);out.writeInt(Float.floatToRawIntBits(attribute));
                out.writeBoolean(target!=null);if(target!=null){out.writeInt(target.x);out.writeInt(target.y);out.writeInt(target.z);}
                out.writeLong(Double.doubleToRawLongBits(BlockReachUtil.maxReach(p)));out.writeLong(Double.doubleToRawLongBits(BlockReachUtil.distanceSquared(p,target)));out.writeBoolean(BlockReachUtil.isFar(p,target));
                if(target!=null){String detail=BlockReachUtil.describe(p,target);out.writeUTF(detail);out.writeUTF(BlockReachUtil.distanceDetail(detail));}
            }
        }
        System.out.println("Original reach rules cases="+reachCases);
    }
}
