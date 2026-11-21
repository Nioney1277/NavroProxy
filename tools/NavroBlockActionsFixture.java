import ac.navro.anticheat.util.block.BlockActions;
import ac.navro.anticheat.packets.input.NavroAuthInputPacket;
import cn.nukkit.math.BlockVector3;
import cn.nukkit.network.protocol.PlayerAuthInputPacket;
import cn.nukkit.network.protocol.types.AuthInputAction;
import cn.nukkit.network.protocol.types.PlayerActionType;
import cn.nukkit.network.protocol.types.PlayerBlockActionData;
import java.io.*;
import java.util.*;


public final class NavroBlockActionsFixture {
    private static void action(DataOutputStream out,PlayerBlockActionData action) throws Exception {
        out.writeBoolean(action!=null);if(action==null)return;
        out.writeInt(action.getAction()==null?-1:action.getAction().ordinal());
        var p=action.getPosition();out.writeBoolean(p!=null);
        if(p!=null){out.writeInt(p.x);out.writeInt(p.y);out.writeInt(p.z);}
        out.writeInt(action.getFacing());
    }
    private static void actions(DataOutputStream out,List<PlayerBlockActionData> actions) throws Exception {
        out.writeInt(actions.size());for(var a:actions)action(out,a);
    }
    private static PlayerBlockActionData action(PlayerActionType type,int x,int face) {
        return new PlayerBlockActionData(type,new BlockVector3(x,64,-17),face);
    }
    public static void main(String[] args) throws Exception {
        var random=new Random(0x4E4156524F4143L);
        var types=new PlayerActionType[]{PlayerActionType.START_DESTROY_BLOCK,PlayerActionType.ABORT_DESTROY_BLOCK,
                PlayerActionType.PREDICT_DESTROY_BLOCK,PlayerActionType.CONTINUE_DESTROY_BLOCK,
                PlayerActionType.CRACK_BLOCK,PlayerActionType.STOP_DESTROY_BLOCK,PlayerActionType.DROP_ITEM};
        try(var out=new DataOutputStream(new FileOutputStream(args[0]))) {
            out.writeInt(0x4E424131);int count=480;out.writeInt(count);
            for(int i=0;i<count;i++) {
                var map=new LinkedHashMap<PlayerActionType,PlayerBlockActionData>();
                var shuffled=new ArrayList<>(List.of(types));Collections.shuffle(shuffled,random);
                for(var type:shuffled)if(random.nextBoolean())map.put(type,action(type,random.nextInt(3),i%8-1));
                if(i%3==0){map.put(PlayerActionType.START_DESTROY_BLOCK,action(PlayerActionType.START_DESTROY_BLOCK,1,1));map.put(PlayerActionType.PREDICT_DESTROY_BLOCK,action(PlayerActionType.PREDICT_DESTROY_BLOCK,1,1));}
                if(i%5==0)map.put(PlayerActionType.STOP_DESTROY_BLOCK,null);
                if(i%7==0)map.put(PlayerActionType.CRACK_BLOCK,action(PlayerActionType.START_DESTROY_BLOCK,2,4));
                if(i%11==0)map.put(null,new PlayerBlockActionData(null,null,-1));
                out.writeInt(map.size());for(var e:map.entrySet()){out.writeInt(e.getKey()==null?-1:e.getKey().ordinal());action(out,e.getValue());}
                var packet=new PlayerAuthInputPacket();packet.setBlockActionData(map);
                actions(out,BlockActions.read(packet));
            }
            int ordered=240;out.writeInt(ordered);
            for(int i=0;i<ordered;i++) {
                var packet=new NavroAuthInputPacket();boolean flag=(i&1)==0;
                if(flag)packet.getInputData().add(AuthInputAction.PERFORM_BLOCK_ACTIONS);
                var input=new ArrayList<PlayerBlockActionData>();
                if(i%8!=0)for(int j=0;j<2+i%8;j++)input.add(action(types[j%types.length],j/2,j%6));
                packet.setBlockActions(input);
                out.writeBoolean(flag);actions(out,input);actions(out,BlockActions.read(packet));
                var filtered=new ArrayList<PlayerBlockActionData>();
                for(int j=0;j<input.size();j++)if((j+i)%3!=0)filtered.add(input.get(j));
                actions(out,filtered);actions(out,BlockActions.write(packet,filtered));
                out.writeBoolean(packet.getInputData().contains(AuthInputAction.PERFORM_BLOCK_ACTIONS));
                actions(out,BlockActions.read(packet));
                if(!filtered.isEmpty())filtered.get(0).setFacing(99);
                actions(out,BlockActions.read(packet));
            }
        }
        System.out.println("Original BlockActions reference: 480 map fallbacks and 240 ordered/filter sequences.");
    }
}
