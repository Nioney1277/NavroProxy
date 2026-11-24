package ac.navro.proxy.packets.server;

import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.GameType;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class GameTypeReferenceTest {
    private static BedrockPacket packet(int type,int mode,long entity) {
        if(type==0){var p=new StartGamePacket();if(mode>=0)p.setPlayerGameType(GameType.from(mode));p.setRuntimeEntityId(7);p.setUniqueEntityId(99);p.setPlayerPosition(Vector3f.ZERO);return p;}
        if(type==1){var p=new SetPlayerGameTypePacket();p.setGamemode(mode);return p;}
        var p=new UpdatePlayerGameTypePacket();p.setEntityId(entity);p.setGameType(mode<0?null:GameType.from(mode));return p;
    }
    private static void mutate(BedrockPacket data,int mode) {
        if(data instanceof StartGamePacket p)p.setPlayerGameType(mode<0?null:GameType.from(mode));
        if(data instanceof SetPlayerGameTypePacket p)p.setGamemode(mode);
        if(data instanceof UpdatePlayerGameTypePacket p)p.setGameType(mode<0?null:GameType.from(mode));
    }
    private static int tasks(WirePlayerState player)throws Exception {
        var last=player.getLatencyUtil().sentQueue().getLast();var f=last.getClass().getDeclaredField("tasks");f.setAccessible(true);
        return ((List<?>)f.get(last)).size();
    }
    @Test void allOriginalGameTypeAcknowledgmentSequencesRetainOrderingAndMutationSemantics()throws Exception {
        try(var in=new DataInputStream(new GZIPInputStream(getClass().getResourceAsStream("/navro-game-type.bin.gz")))) {
            assertEquals(0x4E475431,in.readInt());int count=in.readInt();assertEquals(128,count);
            for(int run=0;run<count;run++) {
                var player=new WirePlayerState();var identity=new StartGamePacket();identity.setRuntimeEntityId(7);identity.setUniqueEntityId(99);player.apply(identity);
                for(int group=0,groups=in.readInt();group<groups;group++) {
                    String label="session="+run+" group="+group;long marker=1L+group;player.getLatencyUtil().queue(marker,true);
                    int packets=in.readInt();var data=new ArrayList<BedrockPacket>();var future=new ArrayList<Integer>();var mutations=new ArrayList<Boolean>();var after=new ArrayList<String>();
                    for(int i=0;i<packets;i++) {
                        int type=in.readInt(),mode=in.readInt();long entity=in.readLong();boolean change=in.readBoolean();int next=in.readInt();boolean cancelled=in.readBoolean();
                        var packet=packet(type,mode,entity);int before=tasks(player);
                        if(!cancelled) {
                            ServerDataPackets.onPacket(player,packet);
                            if(packet instanceof StartGamePacket start)player.apply(start);
                        }
                        assertEquals(in.readInt(),tasks(player)-before,label+" packet-bound task count");
                        assertEquals(in.readUTF(),String.valueOf(player.gameType()),label+" before acceptance");
                        player.getLatencyUtil().queue(()->after.add(String.valueOf(player.gameType())));
                        data.add(packet);future.add(next);mutations.add(change);
                    }
                    for(int i=0;i<packets;i++)if(mutations.get(i))mutate(data.get(i),future.get(i));
                    assertEquals(in.readUTF(),String.valueOf(player.gameType()),label+" before ACK");assertEquals(1,in.readInt());
                    assertTrue(player.getLatencyUtil().onResponse(marker));int size=in.readInt();var expected=new ArrayList<String>();for(int i=0;i<size;i++)expected.add(in.readUTF());
                    assertEquals(expected,after,label+" each acknowledged task");assertEquals(in.readUTF(),String.valueOf(player.gameType()),label+" final");
                }
            }
            assertEquals(-1,in.read());
        }
    }
}
