package ac.navro.proxy.permission;

import ac.navro.proxy.state.WirePlayerState;
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission;
import org.cloudburstmc.protocol.bedrock.packet.*;


public final class ProtocolPermissions {
    private volatile boolean operator;
    public boolean isOperator(){return operator;}
    public void observe(WirePlayerState player,BedrockPacket packet){
        if(packet instanceof StartGamePacket){operator=false;return;}
        if(packet instanceof UpdateAbilitiesPacket p&&p.getUniqueEntityId()==player.uniqueId()){

            operator=p.getPlayerPermission()==PlayerPermission.OPERATOR
                    ||p.getCommandPermission()!=null&&p.getCommandPermission().ordinal()==1;
        }else if(packet instanceof AdventureSettingsPacket p&&p.getUniqueEntityId()==player.uniqueId()){
            operator=p.getPlayerPermission()==PlayerPermission.OPERATOR
                    ||p.getCommandPermission()!=null&&p.getCommandPermission().ordinal()==1;
        }
    }
}
