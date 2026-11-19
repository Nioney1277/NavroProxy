package ac.navro.proxy.packets.input.legacy;

import ac.navro.proxy.prediction.live.PredictionContext;
import ac.navro.proxy.prediction.live.PredictionItemUse;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;


public final class LegacyAuthInputPackets {
    private LegacyAuthInputPackets() { }
    public static void correctInputData(ac.navro.proxy.state.WirePlayerState player, PlayerAuthInputPacket packet) {
        if (player.isMovementExempted()) return;
        var flags = packet.getInputData();
        flags.remove(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.HORIZONTAL_COLLISION);
        flags.remove(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.VERTICAL_COLLISION);
        if (player.horizontalCollision()) flags.add(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.HORIZONTAL_COLLISION);
        if (player.verticalCollision()) flags.add(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.VERTICAL_COLLISION);
        packet.setDelta(player.velocity().toVector3f());
    }
    public static void processAuthInput(PredictionContext player, PredictionItemUse itemUse, PlayerAuthInputPacket input){
        var wire=player.wire;var iterator=input.getInputData().iterator();
        while(iterator.hasNext()){
            var action=iterator.next();switch(action){
                case START_SPRINTING->{boolean forward=wire.input().z>0;wire.setPredictionSprintingAction(forward);if(!forward)iterator.remove();}
                case STOP_SPRINTING->wire.setPredictionSprintingAction(false);
                case START_SNEAKING->wire.setPredictionFlag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.SNEAKING,true);
                case STOP_SNEAKING->wire.setPredictionFlag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.SNEAKING,false);
                case START_SWIMMING->wire.applySwimmingAction(true);
                case STOP_SWIMMING->wire.applySwimmingAction(false);
                case START_FLYING->wire.setFlying(wire.abilities().contains(org.cloudburstmc.protocol.bedrock.data.Ability.MAY_FLY)
                        || wire.abilities().contains(org.cloudburstmc.protocol.bedrock.data.Ability.FLYING));
                case STOP_FLYING->wire.setFlying(false);
                case START_GLIDING->{var chest=player.session.inventory().armorContainer.get(1).getData();boolean allowed=chest!=null&&!itemUse.isNull(chest)&&itemUse.nativeItemId(chest)==444;wire.setPredictionFlag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.GLIDING,allowed);if(!allowed)iterator.remove();}
                case STOP_GLIDING->wire.setPredictionFlag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.GLIDING,false);
                case START_CRAWLING->wire.setPredictionFlag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.CRAWLING,true);
                case STOP_CRAWLING->wire.setPredictionFlag(org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag.CRAWLING,false);
                case STOP_SPIN_ATTACK->{if(player.dirtySpinStop){player.stopRiptide();wire.setVelocity(wire.velocity().multiply(-0.2F));}else iterator.remove();}
                default->{}
            }
        }
        wire.updateInputPose();
        itemUse.beginInput(input);player.dirtySpinStop=false;
    }
}
