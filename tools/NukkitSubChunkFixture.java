import cn.nukkit.GameVersion;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.network.protocol.SubChunkPacket;
import cn.nukkit.network.protocol.types.SubChunkRequestResult;
import cn.nukkit.utils.BinaryStream;
import java.io.*;
import java.util.zip.GZIPOutputStream;


public final class NukkitSubChunkFixture {
    public static void main(String[] args)throws Exception{
        int[] protocols={729,776,975,1001,2168,2193,2225};int count=0;
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))){
            out.writeInt(0x4e534331);out.writeInt(protocols.length*8);
            for(int protocol:protocols)for(boolean cached:new boolean[]{false,true})for(boolean air:new boolean[]{false,true})for(int format:new int[]{8,9}){
                var packet=new SubChunkPacket();packet.protocol=protocol;packet.gameVersion=GameVersion.byProtocol(protocol,false);packet.dimension=0;packet.cacheEnabled=cached;packet.centerPosition=new BlockVector3(3,-1,-7);
                var data=new SubChunkPacket.SubChunkData();data.offset=new BlockVector3(-1,-3,2);data.result=air?SubChunkRequestResult.SUCCESS_ALL_AIR:SubChunkRequestResult.SUCCESS;
                var payload=new BinaryStream();payload.putByte((byte)format);payload.putByte((byte)2);if(format==9)payload.putByte((byte)-4);
                payload.putByte((byte)1);payload.putVarInt(200);payload.putByte((byte)1);payload.putVarInt(300);
                data.data=air?new byte[0]:payload.getBuffer();data.blobId=12345;data.hasBlobId=cached;packet.subChunks.add(data);packet.encode();byte[] bytes=packet.getBuffer();
                out.writeInt(protocol);out.writeBoolean(air);out.writeInt(bytes.length);out.write(bytes);count++;
            }
        }
        System.out.println("Frozen native SubChunk encodings="+count);
    }
}
