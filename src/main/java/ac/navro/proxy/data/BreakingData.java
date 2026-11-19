package ac.navro.proxy.data;

import ac.navro.proxy.util.block.BlockActionText;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;


public class BreakingData {
    private PlayerActionType state;
    private Vector3i position;
    private int face;
    private float breakingProcess;

    public BreakingData(PlayerActionType state, Vector3i position, int face) {
        this.state = state; this.position = position; this.face = face;
    }
    public PlayerActionType getState() { return state; }
    public void setState(PlayerActionType state) { this.state = state; }
    public Vector3i getPosition() { return position; }
    public void setPosition(Vector3i position) { this.position = position; }
    public int getFace() { return face; }
    public void setFace(int face) { this.face = face; }
    public float getBreakingProcess() { return breakingProcess; }
    public void setBreakingProcess(float breakingProcess) { this.breakingProcess = breakingProcess; }
    @Override public String toString() {
        return "BreakingData(state=" + BlockActionText.name(state) + ", position=" + BlockActionText.position(position)
                + ", face=" + face + ", breakingProcess=" + breakingProcess + ")";
    }
}
