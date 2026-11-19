package ac.navro.proxy.teleport;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.teleport.data.TeleportData;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;


public final class TeleportUtil {
    public interface Context {
        long runtimeEntityId();
        Vec3 rotation();
        boolean sendPacket(MovePlayerPacket packet);
        void movementEvent(String name,Object... values);
        void swimStage(String name,Object... values);
        void itemUseStage(String name,Object... values);
    }
    private final Context player;
    private volatile Vec3 lastKnowValid=Vec3.ZERO;
    private final Queue<TeleportData> queuedTeleports=new ConcurrentLinkedQueue<>();
    public TeleportUtil(Context player){this.player=player;}
    public Vec3 getLastKnowValid(){return lastKnowValid;}
    public void setLastKnowValid(Vec3 value){lastKnowValid=value;}
    public Queue<TeleportData> getQueuedTeleports(){return queuedTeleports;}
    public boolean isTeleporting(){return !queuedTeleports.isEmpty();}

    public boolean isHardTeleporting(){return queuedTeleports.stream().anyMatch(TeleportData::isHard);}
    private final java.util.NavigableMap<Long, ac.navro.proxy.data.input.TickData> authInputHistory =
            new java.util.concurrent.ConcurrentSkipListMap<>();
    public java.util.NavigableMap<Long, ac.navro.proxy.data.input.TickData> getAuthInputHistory(){return authInputHistory;}
    public void pollAuthInputHistory(int capacity){while(authInputHistory.size()>capacity)authInputHistory.pollFirstEntry();}
    public void teleport(final Vec3 vec3){
        if(this.isTeleporting())return;
        sendPosition(vec3,MovePlayerPacket.Mode.TELEPORT,false);
    }
    private void sendPosition(final Vec3 vec3,final MovePlayerPacket.Mode mode,final boolean onGround){
        final MovePlayerPacket packet=new MovePlayerPacket();
        packet.setRuntimeEntityId(player.runtimeEntityId());
        packet.setPosition(Vector3f.from(vec3.x,vec3.y,vec3.z));
        Vec3 rotation=player.rotation();
        packet.setRotation(Vector3f.from(rotation.x,rotation.y,rotation.z));
        packet.setOnGround(onGround);packet.setMode(mode);
        packet.setTeleportationCause(MovePlayerPacket.TeleportationCause.BEHAVIOR);
        player.movementEvent("anticheatTeleportRequested","wireTarget",vec3.toVector3f(),"mode",mode.ordinal());
        player.swimStage("CORRECTION","wireTarget",new float[]{vec3.x,vec3.y,vec3.z},"mode",mode.ordinal(),"onGround",onGround);
        player.itemUseStage("CORRECTION","wireTarget",new float[]{vec3.x,vec3.y,vec3.z},"mode",mode.ordinal(),"onGround",onGround);
        player.sendPacket(packet);
    }
    public void queue(final TeleportData data,final Consumer<Runnable> sendAcknowledgment){
        queuedTeleports.add(data);
        if(player!=null)player.movementEvent("teleportQueued","id",Integer.toHexString(System.identityHashCode(data)),
                "type",data.getClass().getSimpleName(),"target",data.getPosition().toVector3f(),"mode",data.getMode(),"ground",data.isOnGround());
        sendAcknowledgment.accept(()->{
            data.accept();
            if(player!=null)player.movementEvent("teleportLatencyAccepted","id",Integer.toHexString(System.identityHashCode(data)));
        });
    }

    public void drainReadyTeleports(org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket input,Consumer<TeleportData> process){
        TeleportData data;
        while((data=queuedTeleports.peek())!=null && (data instanceof ac.navro.proxy.teleport.data.CorrectionResetData reset ? reset.confirmedBy(input)
                : data.isAccepted() && (!(data instanceof ac.navro.proxy.teleport.data.RewindData rewind)
                    || rewind.tick() < input.getTick()))){
            queuedTeleports.poll();process.accept(data);
        }
    }

    public void drainAcceptedTeleports(Consumer<TeleportData> process){
        TeleportData data;
        while((data=queuedTeleports.peek())!=null&&data.isAccepted()){
            queuedTeleports.poll();process.accept(data);
        }
    }
}
