package ac.navro.proxy.teleport.data;

import ac.navro.anticheat.util.math.Vec3;



public class TeleportData {
    private final Vec3 position;
    private final boolean onGround;
    private final int mode;
    private boolean accepted;

    public TeleportData(final Vec3 position) {
        this(position, false);
    }

    public TeleportData(final Vec3 position, final boolean onGround) {
        this(position, onGround, 2);
    }

    public TeleportData(final Vec3 position, final boolean onGround, final int mode) {
        this.position = position.clone();
        this.onGround = onGround;
        this.mode = mode;
    }

    public Vec3 getPosition() { return position; }
    public boolean isOnGround() { return onGround; }
    public int getMode() { return mode; }
    public boolean isAccepted() { return accepted; }
    public boolean isHard() { return true; }

    public void accept() {
        this.accepted = true;
    }


    public boolean matchesConfirmation(final Vec3 clientNetworkPosition, final boolean handled) {
        if (mode == 1) {
            final double dx = (double) clientNetworkPosition.x - position.x;
            final double dy = (double) clientNetworkPosition.y - position.y;
            final double dz = (double) clientNetworkPosition.z - position.z;
            return Double.isFinite(dx) && Double.isFinite(dy) && Double.isFinite(dz)
                    && dx * dx + dy * dy + dz * dz <= 0.1D;
        }
        final float distance = position.distanceTo(clientNetworkPosition);
        return handled && Float.isFinite(distance) && distance <= 1.0E-3F;
    }
}
