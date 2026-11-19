package ac.navro.proxy.prediction.fluid;

import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.anticheat.data.MovementAabbSnapshot;


public final class FluidPoseState {
    public boolean wasSwimming, wasCrawling;
    public boolean horizontalPoseApplied;
    public EntityDimensions dimensionsBeforeHorizontalPose;
    public EntityDimensions inputDimensions;
    public MovementAabbSnapshot inputAabb;
    public boolean inputHorizontalPoseApplied;
    public EntityDimensions inputStandingDimensions;
    public boolean inputNonCrawlingHorizontal;
    public boolean inputActive;
    public EntityDimensions pendingPoseDimensions;
    public EntityDimensions readyPoseDimensions, inputReadyPoseDimensions;
    public boolean readyPoseBeforeMovement, inputReadyPoseBeforeMovement;

    public void captureInputState(boolean swimming, boolean crawling) {
        wasSwimming = swimming;
        wasCrawling = crawling;
    }
}
