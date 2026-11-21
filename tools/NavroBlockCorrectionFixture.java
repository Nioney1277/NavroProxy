import ac.navro.anticheat.util.block.BlockUtil;
import cn.nukkit.*;
import cn.nukkit.block.*;
import cn.nukkit.level.Level;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.network.protocol.*;
import sun.misc.Unsafe;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroBlockCorrectionFixture {
    private static final List<UpdateBlockPacket> sent=new ArrayList<>();
    public static final class NativeLevel extends Level {
        Block[] states;int[] networkIds;
        public NativeLevel(){super(null,"","",null);}
        @Override public Block getBlock(int x,int y,int z,int layer){return states[layer];}
        @Override public int getBlockRuntimeId(GameVersion version,int x,int y,int z,int layer){return networkIds[layer];}
    }
    public static final class Session extends Player {
        NativeLevel level;int protocol;
        public Session(){super(null,0L,null);}
        @Override public Level getLevel(){return level;}
        @Override public GameVersion getGameVersion(){return GameVersion.byProtocol(protocol,false);}
        @Override public boolean dataPacket(DataPacket data){
            UpdateBlockPacket update=(UpdateBlockPacket)data;update.protocol=protocol;update.gameVersion=getGameVersion();update.encode();sent.add(update);return true;
        }
    }
    public static void main(String[] args)throws Exception {
        var field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);var unsafe=(Unsafe)field.get(null);
        int[] protocols={729,776,786,800,819,860,898,924,944,975,1001,2168,2169,2192,2193,2225};
        int[] coordinates={0,1,-1,8,63,64,-64,-65,319,320,Integer.MIN_VALUE,Integer.MAX_VALUE};
        int[] ids={0,1,127,128,16383,16384,Integer.MAX_VALUE,Integer.MIN_VALUE,-1,-1579512345};
        var random=new Random(0x4E43525041434BL);
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))) {
            out.writeInt(0x4E435231);out.writeInt(protocols.length*256);
            for(int protocol:protocols)for(int i=0;i<256;i++) {
                var session=(Session)unsafe.allocateInstance(Session.class);var level=(NativeLevel)unsafe.allocateInstance(NativeLevel.class);
                int x=coordinates[random.nextInt(coordinates.length)],y=coordinates[random.nextInt(coordinates.length)],z=coordinates[random.nextInt(coordinates.length)];
                var position=new BlockVector3(x,y,z);boolean single=(i&1)==0;
                boolean hasPlayer=i%17!=0,hasPosition=i%19!=0,hasLevel=i%23!=0;
                boolean layerZero=i%7!=0,layerOne=i%11!=0;
                int zero=ids[random.nextInt(ids.length)],one=i%3==0?random.nextInt():ids[random.nextInt(ids.length)];
                level.networkIds=new int[]{zero,one};level.states=new Block[]{layerZero?new BlockStone():null,layerOne?new BlockWater():null};
                session.level=hasLevel?level:null;session.protocol=protocol;
                out.writeInt(protocol);out.writeInt(x);out.writeInt(y);out.writeInt(z);
                out.writeBoolean(single);out.writeBoolean(hasPlayer);out.writeBoolean(hasPosition);out.writeBoolean(hasLevel);
                out.writeBoolean(layerZero);out.writeBoolean(layerOne);out.writeInt(zero);out.writeInt(one);
                sent.clear();
                if(single)BlockUtil.restoreCorrectBlock(hasPlayer?session:null,hasPosition?position:null,layerZero?new BlockBedrock():null);
                else BlockUtil.restoreCorrectBlock(hasPlayer?session:null,hasPosition?position:null);
                out.writeInt(sent.size());for(var packet:sent) {
                    out.writeInt(packet.blockRuntimeId);out.writeInt(packet.dataLayer);out.writeInt(packet.flags);
                    byte[] bytes=packet.getBuffer();out.writeInt(bytes.length);out.write(bytes);
                }
            }
        }
        System.out.println("Native correction reference: 4096 cases, 16 protocols, both overloads, null gates, full int coordinates and IDs; raw packet bytes.");
    }
}
