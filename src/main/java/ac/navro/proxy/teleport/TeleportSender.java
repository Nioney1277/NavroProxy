package ac.navro.proxy.teleport;

import ac.navro.proxy.SessionState;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import java.util.function.BooleanSupplier;


public final class TeleportSender {
    private final BedrockServerSession client;
    private final SessionState state;
    private final BooleanSupplier available;
    public TeleportSender(BedrockServerSession client,SessionState state,BooleanSupplier available){
        this.client=client;this.state=state;this.available=available;
    }
    public void teleportToLastValid(){
        var loop=client.getPeer().getChannel().eventLoop();
        Runnable action=()->{if(ready())state.teleports().teleport(state.teleports().getLastKnowValid());};
        if(loop.inEventLoop())action.run();else loop.execute(action);
    }
    private boolean ready(){return available.getAsBoolean()&&client.isConnected()&&client.getPeer().isConnected()
            &&state.player().getEntityBatchEncoder()!=null;}
    public boolean sendMotion(org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket packet){
        if(!ready())return false;
        client.sendPacket(packet);return true;
    }
    public boolean send(org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket packet) {
        if(!ready())return false;
        var encoder=state.player().getEntityBatchEncoder();
        var acknowledgment=state.onProxyPredictionCorrection(packet);
        try { client.sendPacket(packet);return true; }
        catch(RuntimeException|Error e){
            encoder.discard(packet);
            if(state.teleports().removePendingCorrection(acknowledgment))state.teleports().finishCorrectionCooldown();
            throw e;
        }
    }
    public boolean send(MovePlayerPacket packet){
        if(!ready())return false;
        var encoder=state.player().getEntityBatchEncoder();
        var data=state.onProxyTeleport(packet);
        try { client.sendPacket(packet);return true; }
        catch(RuntimeException|Error e){encoder.discard(packet);if(data!=null)state.teleports().getQueuedTeleports().remove(data);throw e;}
    }
}
