package ac.navro.proxy.util.block;

import ac.navro.proxy.ProtocolDefinitions;
import ac.navro.proxy.SupportedCodecs;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class BlockCorrectionReferenceTest {
    @Test void originalNativeCorrectionsMatchBytesForEverySupportedCodecAndBoundary()throws Exception {
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-block-corrections.bin.gz")))) {
            assertEquals(0x4E435231,in.readInt());int count=in.readInt();assertEquals(4096,count);
            for(int i=0;i<count;i++) {
                int protocol=in.readInt();var position=Vector3i.from(in.readInt(),in.readInt(),in.readInt());
                boolean single=in.readBoolean(),hasPlayer=in.readBoolean(),hasPosition=in.readBoolean(),hasLevel=in.readBoolean();
                boolean zero=in.readBoolean(),one=in.readBoolean();int zeroId=in.readInt(),oneId=in.readInt();
                var definitions=new ProtocolDefinitions();var packets=new ArrayList<UpdateBlockPacket>();
                Consumer<UpdateBlockPacket> send=hasPlayer&&hasLevel?packets::add:null;
                if(single)BlockUtil.restoreCorrectBlock(send,hasPosition?position:null,zero?definitions.blockDefinition(zeroId):null);
                else BlockUtil.restoreCorrectBlock(send,hasPosition?position:null,
                        zero?definitions.blockDefinition(zeroId):null,one?definitions.blockDefinition(oneId):null);
                int expected=in.readInt();assertEquals(expected,packets.size(),"case="+i);
                var codec=SupportedCodecs.forProtocol(protocol);assertNotNull(codec);var helper=codec.createHelper();definitions.configure(helper,false);
                for(var packet:packets) {
                    assertEquals(position,packet.getBlockPosition());assertEquals(in.readInt(),packet.getDefinition().getRuntimeId());
                    assertEquals(in.readInt(),packet.getDataLayer());int flags=in.readInt();assertEquals(11,flags);
                    assertEquals(flags,packet.getFlags().stream().mapToInt(f->1<<f.ordinal()).reduce(0,(a,b)->a|b));
                    byte[] original=in.readNBytes(in.readInt());var encoded=Unpooled.buffer();
                    try {
                        VarInts.writeUnsignedInt(encoded,codec.getPacketDefinition(UpdateBlockPacket.class).getId());codec.tryEncode(helper,encoded,packet);
                        byte[] actual=new byte[encoded.readableBytes()];encoded.getBytes(encoded.readerIndex(),actual);
                        assertArrayEquals(original,actual,"native wire protocol="+protocol+" case="+i);
                        int id=VarInts.readUnsignedInt(encoded);var decoded=(UpdateBlockPacket)codec.tryDecode(helper,encoded,id);
                        assertEquals(packet,decoded);assertSame(definitions.blockDefinition(packet.getDefinition().getRuntimeId()),decoded.getDefinition());
                        assertFalse(encoded.isReadable());
                    } finally {encoded.release();}
                }
                if(packets.size()==2) {
                    assertNotSame(packets.get(0),packets.get(1));packets.get(0).getFlags().clear();
                    assertEquals(3,packets.get(1).getFlags().size());
                }
            }
            assertEquals(-1,in.read());
        }
    }
}
