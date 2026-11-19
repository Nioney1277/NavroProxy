package ac.navro.proxy.prediction.fluid;

import ac.navro.anticheat.util.MathUtil;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.anticheat.data.MovementAabbSnapshot;
import ac.navro.proxy.data.FluidState;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;


public final class ProtocolFluidStateTracker {
    private float swimAmount;
    private boolean wasSwimming;
    private boolean wasCrawling;
    private boolean swimming;
    private boolean wasInWater;
    private boolean waterTravel;
    private boolean headInWater;
    private boolean breathingInAir;
    private boolean breathingInLiquid;
    private LiquidPhysics.Result liquids = LiquidPhysics.Result.EMPTY;
    private EntityDimensions inputDimensions;
    private Box inputBox = Box.EMPTY;
    private MovementAabbSnapshot inputAabb;
    private boolean inputHorizontalPose;

    public void beginInput(CompensatedWorld world, MovementAabbSnapshot inputAabb,
                           Box liveBox, EntityDimensions liveDimensions, Vec3 feet,
                           EntityDimensions dimensions, boolean horizontalPose, boolean flying,
                           boolean riding, boolean swimmingBefore, boolean swimmingNow,
                           boolean crawlingBefore, PlayerAuthInputData[] inputActions, Vec3 view) {
        this.inputDimensions = dimensions;
        this.inputAabb = copy(inputAabb);
        this.inputHorizontalPose = horizontalPose;
        wasSwimming = swimmingBefore;
        wasCrawling = crawlingBefore;
        swimAmount = ProtocolFluidPrediction.nextSwimAmount(swimAmount, wasSwimming || wasCrawling);
        Box inputBox = inputAabb != null && inputAabb.box().isValid()
                ? inputAabb.at(feet)
                : liveBox.isValid() && dimensions.width() == liveDimensions.width()
                && dimensions.height() == liveDimensions.height() ? liveBox : dimensions.getBoxAt(feet);
        this.inputBox = inputBox.clone();
        liquids = LiquidPhysics.sample(world, inputBox, flying, world.dimension());
        wasInWater = liquids.selectedType() == FluidState.Type.WATER;
        float headY = feet.y + (horizontalPose ? 0.4F : dimensions.eyeHeight());
        int headX=(int)Math.floor(feet.x), headBlockY=(int)Math.floor(headY), headZ=(int)Math.floor(feet.z);
        FluidState headFluid = world.fluidAt(headX,headBlockY,headZ,world.dimension());
        float headSurface = headFluid.type()==FluidState.Type.WATER
                ? headBlockY+1.0F-((headFluid.effectiveFlowDecay()+1.0F)/9.0F-1.0F/9.0F)
                : headBlockY;
        headInWater = headFluid.type()==FluidState.Type.WATER && headY < headSurface;
        breathingInAir = world.isAirAt(headX,headBlockY,headZ,world.dimension());
        breathingInLiquid = headFluid.type()!=FluidState.Type.EMPTY;
        boolean start = contains(inputActions, PlayerAuthInputData.START_SWIMMING);
        boolean stop = contains(inputActions, PlayerAuthInputData.STOP_SWIMMING);
        swimming = resolveSwimming(swimmingNow, wasSwimming, headInWater, flying, riding,
                breathingInAir, liquids.water().touching(), wasInWater, start, stop, view);
        waterTravel=!flying&&wasInWater;
    }


    public void sampleTravelLiquids(CompensatedWorld world, Box actor, boolean flying) {
        liquids = LiquidPhysics.sample(world, actor, flying, world.dimension());
        waterTravel = !flying && wasInWater;
    }

    private static boolean resolveSwimming(boolean swimming, boolean wasSwimming, boolean headInWater,
                                            boolean flying, boolean riding, boolean breathingInAir,
                                            boolean touchingWater, boolean wasInWater, boolean start,
                                            boolean stop, Vec3 view) {
        if (start && !wasSwimming) {
            boolean accepted = headInWater && !flying && !riding
                    && ((!breathingInAir && touchingWater) || view != null && view.y < 0.15F);
            return accepted && swimming;
        }
        if (!swimming) return false;
        if (!stop && wasInWater && breathingInAir && view != null && view.y > 0) {
            float horizontalSquared = view.x * view.x + view.z * view.z;
            float angle = (float) Math.acos(MathUtil.clamp(horizontalSquared, 0, 1))
                    * 57.295776F;
            stop = angle > 45.0F;
        }
        return !stop && !riding;
    }

    private static boolean contains(PlayerAuthInputData[] actions, PlayerAuthInputData value) {
        if(actions==null)return false;
        for(PlayerAuthInputData action:actions)if(action==value)return true;
        return false;
    }

    public Frame snapshot() {
        return new Frame(swimAmount,wasSwimming,wasCrawling,wasInWater,waterTravel,headInWater,
                breathingInAir,breathingInLiquid,copy(liquids),inputDimensions,
                inputBox == null ? Box.EMPTY : inputBox.clone(),inputHorizontalPose,swimming,copy(inputAabb));
    }

    public void restore(Frame frame) {
        if(frame==null){reset();return;}
        swimAmount=frame.swimAmount();wasSwimming=frame.wasSwimming();wasCrawling=frame.wasCrawling();
        wasInWater=frame.wasInWater();waterTravel=frame.waterTravel();headInWater=frame.headInWater();
        breathingInAir=frame.breathingInAir();breathingInLiquid=frame.breathingInLiquid();
        liquids=copy(frame.liquids());
        inputDimensions=frame.inputDimensions();
        inputBox=frame.inputBox()==null?Box.EMPTY:frame.inputBox().clone();
        inputHorizontalPose=frame.inputHorizontalPose();
        swimming=frame.swimming();
        inputAabb=copy(frame.inputAabb());
    }

    public void reset() {
        swimAmount=0;wasSwimming=wasCrawling=swimming=wasInWater=waterTravel=headInWater=false;
        breathingInAir=breathingInLiquid=false;liquids=LiquidPhysics.Result.EMPTY;
        inputDimensions=null;inputBox=Box.EMPTY;inputAabb=null;inputHorizontalPose=false;
    }

    public void resetMovement() {
        reset();
    }

    private static LiquidPhysics.Result copy(LiquidPhysics.Result value) {
        if(value==null)return LiquidPhysics.Result.EMPTY;
        return new LiquidPhysics.Result(copy(value.water()),copy(value.lava()),value.selectedType(),
                value.selectedPush()==null?Vec3.ZERO:value.selectedPush().clone());
    }

    private static MovementAabbSnapshot copy(MovementAabbSnapshot value) {
        return value == null ? null : new MovementAabbSnapshot(value.dimensions(), value.position(),
                value.box(), value.resizeRevision());
    }

    private static LiquidPhysics.Sample copy(LiquidPhysics.Sample value) {
        if(value==null)return LiquidPhysics.Sample.EMPTY;
        return new LiquidPhysics.Sample(value.touching(),value.surfaceHeight(),value.downwardFlow(),
                value.accumulatedFlow()==null?Vec3.ZERO:value.accumulatedFlow().clone(),
                value.appliedPush()==null?Vec3.ZERO:value.appliedPush().clone(),
                value.queryBox()==null?Box.EMPTY:value.queryBox().clone());
    }

    public record Frame(float swimAmount,boolean wasSwimming,boolean wasCrawling,boolean wasInWater,
                        boolean waterTravel,boolean headInWater,boolean breathingInAir,
                        boolean breathingInLiquid,LiquidPhysics.Result liquids,
                        EntityDimensions inputDimensions,Box inputBox,boolean inputHorizontalPose,
                        boolean swimming,MovementAabbSnapshot inputAabb) {
        public Frame {
            inputBox=inputBox==null?Box.EMPTY:inputBox.clone();
            liquids=copy(liquids);
            inputAabb=copy(inputAabb);
        }
        @Override public Box inputBox(){return inputBox==null?Box.EMPTY:inputBox.clone();}
        @Override public LiquidPhysics.Result liquids(){return copy(liquids);}
        @Override public MovementAabbSnapshot inputAabb(){return copy(inputAabb);}
    }

    public float swimAmount(){return swimAmount;} public boolean wasSwimming(){return wasSwimming;}
    public boolean swimming(){return swimming;}
    public boolean wasCrawling(){return wasCrawling;} public boolean wasInWater(){return wasInWater;}
    public boolean waterTravel(){return waterTravel;} public boolean headInWater(){return headInWater;}
    public boolean breathingInAir(){return breathingInAir;} public boolean breathingInLiquid(){return breathingInLiquid;}
    public LiquidPhysics.Result liquids(){return liquids;}
}
