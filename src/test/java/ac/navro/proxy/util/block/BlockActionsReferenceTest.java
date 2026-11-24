package ac.navro.proxy.util.block;

import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;
import org.cloudburstmc.math.vector.Vector3i;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockActionsReferenceTest {
    private static PlayerActionType type(int ordinal) {return ordinal<0?null:PlayerActionType.values()[ordinal];}
    private static PlayerBlockActionData action(DataInputStream in) throws Exception {
        if(!in.readBoolean())return null;
        var result=new PlayerBlockActionData();result.setAction(type(in.readInt()));
        if(in.readBoolean())result.setBlockPosition(Vector3i.from(in.readInt(),in.readInt(),in.readInt()));
        result.setFace(in.readInt());return result;
    }
    private static List<PlayerBlockActionData> actions(DataInputStream in) throws Exception {
        int count=in.readInt();var result=new ArrayList<PlayerBlockActionData>();
        for(int i=0;i<count;i++)result.add(action(in));return result;
    }
    @Test void mapFallbackAndOrderedFilterWriteMatchOriginalIncludingDuplicateTypesAndOwnedCopies() throws Exception {
        try(var in=new DataInputStream(getClass().getResourceAsStream("/navro-block-actions.bin"))) {
            assertEquals(0x4E424131,in.readInt());int maps=in.readInt();assertEquals(480,maps);
            for(int i=0;i<maps;i++) {
                int size=in.readInt();var map=new LinkedHashMap<PlayerActionType,PlayerBlockActionData>();
                for(int j=0;j<size;j++)map.put(type(in.readInt()),action(in));
                var before=new LinkedHashMap<>(map);
                assertEquals(actions(in),BlockActions.read(map),"map="+i);assertEquals(before,map);
            }
            int ordered=in.readInt();assertEquals(240,ordered);
            for(int i=0;i<ordered;i++) {
                var packet=new PlayerAuthInputPacket();if(in.readBoolean())packet.getInputData().add(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS);
                var input=actions(in);packet.getPlayerActions().addAll(input);
                var expected=actions(in);var observed=BlockActions.read(packet);
                assertEquals(expected,observed,"input="+i);
                if(!observed.isEmpty()) {observed.get(0).setFace(999);assertEquals(expected,packet.getPlayerActions());}
                var filtered=actions(in);var expectedReturned=actions(in);
                assertSame(filtered,BlockActions.write(packet,filtered));assertEquals(expectedReturned,filtered);
                assertEquals(in.readBoolean(),packet.getInputData().contains(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS));
                assertEquals(actions(in),BlockActions.read(packet),"written="+i);
                if(!filtered.isEmpty())filtered.get(0).setFace(99);
                assertEquals(actions(in),BlockActions.read(packet),"owned="+i);
            }
            assertEquals(-1,in.read());
        }
    }
    @Test void malformedDecodedEntryReachesTheValidatorButNullWritesStillRejectWithoutChangingThePacket() {
        var packet=new PlayerAuthInputPacket();var valid=new PlayerBlockActionData();
        valid.setAction(PlayerActionType.START_BREAK);valid.setBlockPosition(Vector3i.ZERO);valid.setFace(1);
        packet.getPlayerActions().add(valid);packet.getPlayerActions().add(null);
        var read=BlockActions.read(packet);assertEquals(2,read.size());assertNull(read.get(1));assertNotSame(valid,read.get(0));
        assertThrows(NullPointerException.class,()->BlockActions.write(packet,Arrays.asList(valid,null)));
        assertSame(valid,packet.getPlayerActions().get(0));assertNull(packet.getPlayerActions().get(1));
    }

}
