package ac.navro.proxy.packets.input;

import ac.navro.proxy.SessionState;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;


public final class AuthInputClock {
    private AuthInputClock() { }
    public static boolean begin(SessionState session,PlayerAuthInputPacket input) {
        if(input.getTick()<0) {
            session.player().requestDisconnect(session.configuredMessage("disconnect-invalid-tick",
                    java.util.Map.of("tick",input.getTick())));
            session.observeRejectedClient(input,"invalidInputTick","negative client frame="+input.getTick());
            return false;
        }
        session.player().advanceInputClock(input);
        return true;
    }
}
