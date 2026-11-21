import ac.navro.anticheat.Navro;
import ac.navro.anticheat.config.Config;
import ac.navro.anticheat.player.*;
import ac.navro.anticheat.player.data.PlayerData;
import ac.navro.anticheat.compensated.world.CompensatedWorldImpl;
import ac.navro.anticheat.collision.sampling.NearbyBlockSampler;
import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.anticheat.prediction.PredictionSyncState;
import ac.navro.anticheat.teleport.TeleportUtil;
import ac.navro.anticheat.util.PostSendAcknowledgments;
import ac.navro.anticheat.packets.server.ServerDataPackets;
import cn.nukkit.Player;
import cn.nukkit.event.server.DataPacketSendEvent;
import cn.nukkit.network.protocol.*;
import cn.nukkit.network.protocol.types.GameType;
import sun.misc.Unsafe;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroGameTypeFixture {
    private static Unsafe unsafe;
    private static void set(Object value,Class<?> type,String name,Object data)throws Exception {
        var f=type.getDeclaredField(name);f.setAccessible(true);f.set(value,data);
    }
    private static DataPacket packet(int type,int mode,long entity)throws Exception {
        if(type==0){var p=(StartGamePacket)unsafe.allocateInstance(StartGamePacket.class);p.playerGamemode=mode<0?0:mode;p.entityRuntimeId=7;p.entityUniqueId=99;return p;}
        if(type==1){var p=new SetPlayerGameTypePacket();p.gamemode=mode;return p;}
        var p=new UpdatePlayerGameTypePacket();p.entityId=entity;p.gameType=mode<0?null:GameType.from(mode);return p;
    }
    private static void mutate(DataPacket data,int mode) {
        if(data instanceof StartGamePacket p)p.playerGamemode=mode<0?0:mode;
        if(data instanceof SetPlayerGameTypePacket p)p.gamemode=mode;
        if(data instanceof UpdatePlayerGameTypePacket p)p.gameType=mode<0?null:GameType.from(mode);
    }
    public static void main(String[] args)throws Exception {
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);unsafe=(Unsafe)f.get(null);
        var config=new Config();set(config,Config.class,"movementSampling",false);Navro.setConfig(config);
        var listener=new ServerDataPackets();var random=new Random(0x4E47414D455459L);
        try(var out=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(args[0])))) {
            out.writeInt(0x4E475431);int sessions=128;out.writeInt(sessions);
            for(int run=0;run<sessions;run++) {
                var player=(NavroPlayer)unsafe.allocateInstance(NavroPlayer.class);var session=(Player)unsafe.allocateInstance(Player.class);
                var manager=new NavroPlayerManager();manager.put(session,player);set(Navro.getInstance(),Navro.class,"playerManager",manager);
                set(player,NavroPlayer.class,"session",session);set(player,NavroPlayer.class,"predictionSync",new PredictionSyncState());
                set(player,NavroPlayer.class,"compensatedWorld",new CompensatedWorldImpl(null));set(player,NavroPlayer.class,"teleportUtil",new TeleportUtil(player));
                set(player,NavroPlayer.class,"movementSampler",new NearbyBlockSampler(player,NearbyBlockSampler.Kind.MOVEMENT));
                player.dimensions=EntityDimensions.changing(0.6F,1.8F);player.runtimeEntityId=7;player.uniqueEntityId=99;player.gameType=GameType.DEFAULT;
                var scheduled=new ArrayList<Runnable>();var acknowledgments=new ArrayList<Runnable>();
                var post=new PostSendAcknowledgments(scheduled::add,acknowledgments::add);set(player,NavroPlayer.class,"entityPositionAcks",post);
                int groups=6;out.writeInt(groups);
                for(int group=0;group<groups;group++) {
                    var packets=new ArrayList<DataPacket>();var events=new ArrayList<DataPacketSendEvent>();var modesAfter=new ArrayList<String>();var futures=new ArrayList<Integer>();var mutations=new ArrayList<Boolean>();
                    out.writeInt(3);
                    for(int j=0;j<3;j++) {
                        int type=(j+group)%3;
                        int mode=type==1?(run+group)%11-2:random.nextInt(8)-1;
                        if(type==1&&run%11==0)mode=Integer.MAX_VALUE;
                        long entity=switch((run+j)%4){case 0->7;case 1->99;case 2->8;default->Long.MAX_VALUE;};
                        int future=type==1?random.nextInt(11)-2:random.nextInt(8)-1;
                        boolean mutate=(run+group+j)%3==0;boolean cancelled=(run+group+j)%17==0;
                        var data=packet(type,mode,entity);var event=new DataPacketSendEvent(session,data);event.setCancelled(cancelled);
                        listener.onPacket(event);
                        out.writeInt(type);out.writeInt(mode);out.writeLong(entity);out.writeBoolean(mutate);out.writeInt(future);out.writeBoolean(cancelled);
                        out.writeInt(event.getPostSendActions().size());out.writeUTF(String.valueOf(player.gameType));
                        packets.add(data);events.add(event);futures.add(future);mutations.add(mutate);
                    }
                    for(int j=0;j<3;j++)if(mutations.get(j))mutate(packets.get(j),futures.get(j));
                    for(var event:events) {
                        for(var action:event.getPostSendActions())action.run();
                        post.enqueue(()->modesAfter.add(String.valueOf(player.gameType)));
                    }
                    for(var task:List.copyOf(scheduled))task.run();scheduled.clear();
                    out.writeUTF(String.valueOf(player.gameType));out.writeInt(acknowledgments.size());
                    for(var task:List.copyOf(acknowledgments))task.run();acknowledgments.clear();
                    out.writeInt(modesAfter.size());for(var mode:modesAfter)out.writeUTF(mode);
                    out.writeUTF(String.valueOf(player.gameType));
                }
                post.close();
            }
        }
        System.out.println("Original game-type reference: 128 sessions, 2304 packets, post-send barriers, entity/null filters, mutable ACK fields and invalid ordinals.");
    }
}
