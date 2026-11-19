package ac.navro.proxy.check.impl.elytra;

import ac.navro.proxy.SessionState;
import ac.navro.proxy.check.api.annotations.CheckInfo;
import ac.navro.proxy.check.api.impl.PacketCheck;
import ac.navro.proxy.data.vanilla.GameType;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.*;


@CheckInfo(name="Elytra",type="A")
public final class ElytraA extends PacketCheck {
    private final SessionState session;
    private PlayerAuthInputPacket rejectedInput;
    public ElytraA(SessionState session){super(session.player());this.session=session;}
    @Override public boolean onPacketReceive(BedrockPacket packet,boolean cancelled){
        if(cancelled||!(packet instanceof PlayerAuthInputPacket input))return cancelled;
        if(input==rejectedInput)return cancelled;
        rejectedInput=null;
        if(player.isExempted()||!session.spawned()||player.inLoadingScreen
                ||player.gameType()==GameType.SPECTATOR
                ||!input.getInputData().contains(PlayerAuthInputData.START_GLIDING)
                ||session.hasElytraEquipped()
                ||session.authInputPackets().context().disabledChecks.contains("Elytra-A"))return cancelled;
        rejectedInput=input;
        input.getInputData().remove(PlayerAuthInputData.START_GLIDING);
        input.getInputData().add(PlayerAuthInputData.STOP_GLIDING);
        player.setPredictionFlag(EntityFlag.GLIDING,false);
        fail("started gliding without an equipped elytra");
        session.resyncGliding();
        return cancelled;
    }
    public boolean onPredictionComplete(PlayerAuthInputPacket input){
        if(input!=rejectedInput)return false;
        rejectedInput=null;
        player.requestRewind(player.tick());
        return true;
    }
}
