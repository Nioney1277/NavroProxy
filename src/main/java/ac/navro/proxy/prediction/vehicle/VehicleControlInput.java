package ac.navro.proxy.prediction.vehicle;

import ac.navro.anticheat.util.math.Vec3;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.InputInteractionModel;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;


public final class VehicleControlInput {
    private VehicleControlInput() { }
    public static boolean valid(PlayerAuthInputPacket packet) {
        var motion = packet.getMotion();
        return motion != null && Float.isFinite(motion.getX()) && Float.isFinite(motion.getY())
                && Math.abs(motion.getX()) <= 1 && Math.abs(motion.getY()) <= 1;
    }
    public static boolean analogPaddles(PlayerAuthInputPacket packet) {
        return packet.getInputMode() != InputMode.TOUCH
                || packet.getInputInteractionModel() != InputInteractionModel.CLASSIC;
    }
    public static Vec3 decode(PlayerAuthInputPacket packet, boolean boat) {
        if (!valid(packet)) return Vec3.ZERO.clone();
        if (boat && !analogPaddles(packet)) return new Vec3(
                packet.getInputData().contains(PlayerAuthInputData.PADDLE_LEFT) ? 1 : 0, 0,
                packet.getInputData().contains(PlayerAuthInputData.PADDLE_RIGHT) ? 1 : 0);
        float x = packet.getMotion().getX(), z = packet.getMotion().getY();
        float length = Math.max(1, (float) Math.sqrt(x*x + z*z));
        return new Vec3(x/length, 0, boat && packet.getInputData().contains(PlayerAuthInputData.UP) ? 1 : z/length);
    }
}
