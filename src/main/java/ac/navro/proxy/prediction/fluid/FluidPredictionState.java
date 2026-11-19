package ac.navro.proxy.prediction.fluid;


public final class FluidPredictionState {
  public boolean wasInWater,waterTravel,headInWater,breathingInAir,breathingInLiquid,wasCrawling,inputActive;
  public float swimAmount,swimSpeedMultiplier=1F,waterDrag=0.8F;
  public ac.navro.proxy.prediction.fluid.LiquidPhysics.Result liquids;
  public void load(ac.navro.proxy.prediction.fluid.ProtocolFluidStateTracker f){wasInWater=f.wasInWater();waterTravel=f.waterTravel();headInWater=f.headInWater();breathingInAir=f.breathingInAir();breathingInLiquid=f.breathingInLiquid();wasCrawling=f.wasCrawling();inputActive=true;swimAmount=f.swimAmount();liquids=f.liquids();}
 }
