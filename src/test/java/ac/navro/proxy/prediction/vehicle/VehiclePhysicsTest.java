package ac.navro.proxy.prediction.vehicle;

import ac.navro.anticheat.util.math.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VehiclePhysicsTest {
    @Test void digitalStrokeReleaseAndRestrokeHaveInertia() {
        var oar = new VehiclePhysics.Oar();

        oar.digital(0, true); assertEquals(2.5F, oar.power());
        oar.digital(1, true); assertEquals(3, oar.power());
        for (int i = 2; i <= 25; i++) oar.digital(i, true);
        assertEquals(2.5F, oar.power());
        oar.digital(26, false); assertEquals(1.25F, oar.power());
        oar.digital(27, false); assertEquals(.625F, oar.power());
        oar.digital(28, true); assertEquals(3, oar.power());
    }
    @Test void analogStrokesRefreshEveryTenStepsAndReverseIsWeaker() {
        var oar = new VehiclePhysics.Oar();
        oar.analog(0, 1); assertEquals(2.9F, oar.power());
        oar.analog(9, 1); assertEquals(3, oar.power());
        oar.analog(10, .5F); assertEquals(1.4F, oar.power());
        oar.analog(11, 0); assertEquals(0, oar.power());
        var forward = new VehiclePhysics.Boat(); var reverse = new VehiclePhysics.Boat();
        var f = forward.accelerate(Vec3.ZERO, 90, 0, 1, true, false, false, .9F, false);
        var r = reverse.accelerate(Vec3.ZERO, 90, 0, -1, true, false, false, .9F, false);
        assertEquals(.071775F, f.z, 1e-7); assertTrue(r.z < 0); assertTrue(Math.abs(r.z) < f.z * .2F);
    }
    @Test void waterTurnHasLowSpeedBoostWhileAirCannotBePaddled() {
        var water = new VehiclePhysics.Boat();
        var moved = water.accelerate(Vec3.ZERO, 90, 0, 0, false, true, false, .9F, false);
        assertEquals(1.485F, water.angularVelocity, 1e-6); assertTrue(moved.lengthSquared() > 0);
        var air = new VehiclePhysics.Boat();
        assertEquals(0, air.accelerate(Vec3.ZERO, 0, 1, 1, true, false, false, 1, true).lengthSquared());
        assertEquals(0, air.angularVelocity);
    }
    @Test void prolongedSubmersionDisablesPaddleAcceleration() {
        var boat = new VehiclePhysics.Boat(); boat.submergedSteps = 25;
        var moved = boat.accelerate(new Vec3(1, 0, 0), 0, 0, 1, true, false, false, .9F, false);
        assertEquals(.9F, moved.x); assertEquals(0, moved.z);
    }
    @Test void gravityAndBuoyancyActOnTheNextTicksVelocity() {
        assertEquals(-.0392F, VehiclePhysics.boatVertical(0, true, true, false, false, 64, 1));
        assertEquals(.02256F, VehiclePhysics.boatVertical(0, true, true, true, false, 64.375F, 1), 1e-7);
        assertEquals(.7F, VehiclePhysics.boatVertical(.7F, false, true, true, true, 64, 1));
    }
    @Test void submergedBubbleColumnsChangeVelocityAndClampAtTheirTerminalSpeeds() {
        assertEquals(.06F,VehiclePhysics.bubbleColumnY(0,false));assertEquals(-.03F,VehiclePhysics.bubbleColumnY(0,true));
        assertEquals(.7F,VehiclePhysics.bubbleColumnY(.69F,false));assertEquals(-.3F,VehiclePhysics.bubbleColumnY(-.29F,true));
    }
    @Test void horseSteeringWrapsAndSlowsLargeTurns() {
        assertEquals(11.34F, VehiclePhysics.horseYaw(0, 90), 1e-5);
        assertEquals(179.66888F, VehiclePhysics.horseYaw(179, -179), 1e-5);
    }
    @Test void horseJumpUsesServerStrengthAndChargeWithForwardImpulse() {
        var high = VehiclePhysics.horseJump(Vec3.ZERO, 0, .7F, 100, true, 1, 0);
        assertEquals(.7F, high.y); assertEquals(.4F, high.z);
        var honey = VehiclePhysics.horseJump(Vec3.ZERO, 90, .7F, 0, true, .6F, 2);
        assertEquals((.7F * .4F + .2F) * .6F, honey.y); assertEquals(-.16F, honey.x, 1e-7);
        assertEquals(.4F, VehiclePhysics.jumpScale(-100)); assertEquals(1, VehiclePhysics.jumpScale(1000));
    }
    @Test void horseTravelInputUsesCultDirectionalScaleWithoutThePlayerOnlyPointNineEight() {
        var forward=VehiclePhysics.horseInput(new Vec3(0,0,1),.225F,0);
        assertEquals(.225F,forward.z,1e-7F);
        var backward=VehiclePhysics.horseInput(new Vec3(0,0,-1),.225F,0);
        assertEquals(-.05625F,backward.z,1e-7F);
        var side=VehiclePhysics.horseInput(new Vec3(1,0,0),.225F,0);
        assertEquals(.1125F,side.x,1e-7F);
    }
    @Test void zeroStrengthPaddleClearsItsOldStrokeStartBeforeANewInput() {
        var boat=new VehiclePhysics.Boat();
        boat.left.analog(0,1);assertEquals(2.9F,boat.left.power());
        boat.left.analog(1,0);assertEquals(0,boat.left.power());
        boat.left.analog(2,.001F);assertEquals(0,boat.left.power());
        boat.left.analog(3,1);assertEquals(2.9F,boat.left.power());
    }
    @Test void buoyancyMatchesOnlyTheExplicitLiquidIdentifiers() {
        var parameters=new BoatParameters(true,true,1,java.util.Set.of("minecraft:water"));
        assertTrue(parameters.accepts("minecraft:water"));
        assertFalse(parameters.accepts("minecraft:flowing_water"));
    }
    @Test void horseMountStateTracksJumpChargeRearingAndLanding() {
        var horse=new HorseControl();horse.metadata(1,true);assertTrue(horse.suppressed(true,-1));
        horse.request(0);assertFalse(horse.suppressed(true,0));assertTrue(horse.canJump(true));
        horse.launched();assertTrue(horse.jumping);assertFalse(horse.canJump(true));
        horse.finish(false,false);assertTrue(horse.jumping);
        horse.finish(false,true);assertFalse(horse.jumping);assertFalse(horse.standing);assertEquals(0,horse.jump);
    }
    @Test void touchClassicUsesDigitalPaddlesWhileOtherLayoutsKeepAnalogControl() {
        var classic=new org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket();
        classic.setInputMode(org.cloudburstmc.protocol.bedrock.data.InputMode.TOUCH);
        classic.setInputInteractionModel(org.cloudburstmc.protocol.bedrock.data.InputInteractionModel.CLASSIC);
        assertFalse(VehicleControlInput.analogPaddles(classic));
        classic.setInputInteractionModel(org.cloudburstmc.protocol.bedrock.data.InputInteractionModel.CROSSHAIR);
        assertTrue(VehicleControlInput.analogPaddles(classic));
        classic.setInputMode(org.cloudburstmc.protocol.bedrock.data.InputMode.MOUSE);
        classic.setInputInteractionModel(org.cloudburstmc.protocol.bedrock.data.InputInteractionModel.CLASSIC);
        assertTrue(VehicleControlInput.analogPaddles(classic));
    }
}
