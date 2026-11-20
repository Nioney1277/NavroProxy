package ac.navro.proxy.teleport;

import ac.navro.proxy.ack.types.MovementCorrectionAck;
import ac.navro.proxy.ack.types.TeleportAcceptAck;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.teleport.data.TeleportData;
import ac.navro.proxy.teleport.data.CorrectionResetData;
import ac.navro.proxy.teleport.data.RewindData;
import ac.navro.proxy.teleport.data.RewindHistory;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket;
import java.util.function.Function;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public final class TeleportUtil {
    private static final int MIN_CHUNK_AGE_FOR_BLOCK_SYNC = 200;
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
    private MovementCorrectionAck pendingCorrection;
    private boolean correctionCooldown;
    private Vec3 interpolationTarget;
    private int interpolationTicks;
    public TeleportUtil(Context player){this.player=player;}
    public Vec3 getLastKnowValid(){return lastKnowValid;}
    public void setLastKnowValid(Vec3 value){lastKnowValid=value;}
    public Queue<TeleportData> getQueuedTeleports(){return queuedTeleports;}
    public boolean isTeleporting(){return !queuedTeleports.isEmpty() || interpolationTicks > 0;}
    public boolean hasPendingCorrection() { return pendingCorrection != null; }
    public boolean isCorrectionCooldown() { return correctionCooldown; }
    public boolean canAcceptClientMovement() {
        return !isTeleporting() && !hasPendingCorrection() && !isCorrectionCooldown();
    }
    public MovementCorrectionAck addPendingCorrection(long tick) {
        if (hasPendingCorrection()) throw new IllegalStateException("Movement correction already pending");
        pendingCorrection = new MovementCorrectionAck(tick);
        startCorrectionCooldown();
        return pendingCorrection;
    }
    public void startCorrectionCooldown() { correctionCooldown = true; }
    public boolean removePendingCorrection(MovementCorrectionAck acknowledgment) {
        if (pendingCorrection != acknowledgment) return false;
        pendingCorrection = null;
        return true;
    }
    public void finishCorrectionCooldown() {
        if (!hasPendingCorrection()) correctionCooldown = false;
    }
    public void resetCorrection() {
        pendingCorrection = null;
        correctionCooldown = false;
    }
    public boolean isHardTeleporting(){return queuedTeleports.stream().anyMatch(CorrectionResetData.class::isInstance);}
    public boolean hasPendingBackendRewind(){return queuedTeleports.stream().anyMatch(RewindData.class::isInstance);}
    public void supersedeHardCorrection(TeleportData backendMove) {
        if (!backendMove.isAccepted() || !queuedTeleports.contains(backendMove)) return;
        for (var queued : queuedTeleports) {
            if (queued == backendMove) return;
            if (queued instanceof CorrectionResetData reset && queuedTeleports.remove(reset)) {
                if (backendMove instanceof RewindData rewind) {
                    player.movementEvent("hard_correction_superseded", "sourceTick", reset.sourceTick(),
                            "backendRewindTick", rewind.tick());
                } else {
                    player.movementEvent("hard_correction_superseded", "sourceTick", reset.sourceTick(),
                            "backendTeleportTarget", backendMove.getPosition().toVector3f());
                }
            }
        }
    }
    public Vec3 pendingTeleportPosition() {
        Vec3 target = null;
        for (var data : queuedTeleports) {
            if (!(data instanceof CorrectionResetData) && !(data instanceof RewindData) && !data.isAccepted())
                target = data.getPosition();
        }
        return target;
    }
    public void startInterpolation(Vec3 target) {
        interpolationTarget = target.clone();
        interpolationTicks = 3;
    }
    public void clearInterpolation() {
        interpolationTarget = null;
        interpolationTicks = 0;
    }
    public Vec3 stepInterpolation(Vec3 origin) {
        if (interpolationTicks == 0) return null;
        if (interpolationTicks == 1) {
            Vec3 target = interpolationTarget;
            clearInterpolation();
            return target;
        }
        float steps = interpolationTicks--;
        return origin.add(interpolationTarget.subtract(origin).multiply(1F / steps));
    }
    private final java.util.NavigableMap<Long, ac.navro.proxy.data.input.TickData> authInputHistory =
            new java.util.concurrent.ConcurrentSkipListMap<>();
    public java.util.NavigableMap<Long, ac.navro.proxy.data.input.TickData> getAuthInputHistory(){return authInputHistory;}
    public void pollAuthInputHistory(int capacity){while(authInputHistory.size()>capacity)authInputHistory.pollFirstEntry();}
    public void teleport(final Vec3 vec3){
        if(this.isTeleporting())return;
        var packet = positionPacket(vec3, false);
        player.movementEvent("anticheatTeleportRequested","wireTarget",vec3.toVector3f(),"mode",packet.getMode().ordinal());
        player.swimStage("CORRECTION","wireTarget",new float[]{vec3.x,vec3.y,vec3.z},"mode",packet.getMode().ordinal(),"onGround",false);
        player.itemUseStage("CORRECTION","wireTarget",new float[]{vec3.x,vec3.y,vec3.z},"mode",packet.getMode().ordinal(),"onGround",false);
        player.sendPacket(packet);
    }
    public void correct(RewindHistory frame, boolean vehicle,
                        Function<CorrectPlayerMovePredictionPacket, Boolean> send) {
        if (isTeleporting() || hasPendingCorrection()) return;
        var packet = frame.createPacket(vehicle);
        if (!vehicle) packet.setPosition(frame.position().up(0.001F).toVector3f());
        send.apply(packet);
    }
    public void syncBlocks(CompensatedWorld world, Vec3 feet, long tick, Consumer<Vector3i> sync) {
        int minX = (int) Math.floor(feet.x - .05F), maxX = (int) Math.ceil(feet.x + .05F);
        int minY = (int) Math.floor(feet.y - .05F), maxY = (int) Math.ceil(feet.y + .05F);
        int minZ = (int) Math.floor(feet.z - .05F), maxZ = (int) Math.ceil(feet.z + .05F);
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                var chunk = world.getChunk(chunkX, chunkZ);
                if (chunk == null || chunk.loadedTick() < 0 || tick - chunk.loadedTick() < MIN_CHUNK_AGE_FOR_BLOCK_SYNC)
                    return;
            }
        }
        for (int blockX = minX; blockX <= maxX; blockX++) {
            for (int blockY = minY; blockY <= maxY; blockY++) {
                for (int blockZ = minZ; blockZ <= maxZ; blockZ++) sync.accept(Vector3i.from(blockX, blockY, blockZ));
            }
        }
    }
    public MovePlayerPacket hardCorrectionPacket(CorrectionResetData reset) {
        return positionPacket(reset.getPosition(), reset.isOnGround());
    }
    private MovePlayerPacket positionPacket(Vec3 position, boolean onGround){
        final MovePlayerPacket packet=new MovePlayerPacket();
        packet.setRuntimeEntityId(player.runtimeEntityId());
        packet.setPosition(position.toVector3f());
        Vec3 rotation=player.rotation();
        packet.setRotation(Vector3f.from(rotation.x,rotation.y,rotation.z));
        packet.setOnGround(onGround);packet.setMode(MovePlayerPacket.Mode.TELEPORT);
        packet.setTeleportationCause(MovePlayerPacket.TeleportationCause.BEHAVIOR);
        return packet;
    }
    public void queue(final TeleportData data,final Consumer<Runnable> sendAcknowledgment){
        queuedTeleports.add(data);
        if(player!=null)player.movementEvent("teleportQueued","id",Integer.toHexString(System.identityHashCode(data)),
                "type",data.getClass().getSimpleName(),"target",data.getPosition().toVector3f(),"mode",data.getMode(),"ground",data.isOnGround());
        var acknowledgment = new TeleportAcceptAck(data);
        sendAcknowledgment.accept(()->{
            if (!queuedTeleports.contains(acknowledgment.data())) return;
            acknowledgment.data().accept();
            if(player!=null)player.movementEvent("teleportLatencyAccepted","id",Integer.toHexString(System.identityHashCode(data)));
        });
    }

    public void drainReadyTeleports(org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket input,Consumer<TeleportData> process){
        drainReadyTeleports(input, true, process);
    }
    public void drainReadyTeleports(org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket input,boolean onFoot,Consumer<TeleportData> process){
        TeleportData data;
        while((data=queuedTeleports.peek())!=null && (data instanceof CorrectionResetData reset ? reset.confirmedBy(input)
                : data.isAccepted() && (data instanceof RewindData rewind ? rewind.tick() < input.getTick() : onFoot))){
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
