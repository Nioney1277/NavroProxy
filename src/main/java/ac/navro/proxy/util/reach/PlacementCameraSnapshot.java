package ac.navro.proxy.util.reach;

import ac.navro.anticheat.util.math.Vec3;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.InputInteractionModel;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;


public record PlacementCameraSnapshot(long tick,long previousTick,InputMode inputMode,
        InputInteractionModel interactionModel,ClientPlayMode playMode,Vec3 forward,Vec3 previousForward) { }
