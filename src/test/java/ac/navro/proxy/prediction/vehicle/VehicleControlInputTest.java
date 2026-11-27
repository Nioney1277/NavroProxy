package ac.navro.proxy.prediction.vehicle;

import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VehicleControlInputTest {
    private PlayerAuthInputPacket input(InputMode mode, InputInteractionModel model) {
        var p = new PlayerAuthInputPacket(); p.setInputMode(mode); p.setInputInteractionModel(model);
        p.setMotion(Vector2f.ZERO); p.setAnalogMoveVector(Vector2f.from(1,1)); return p;
    }
    @Test void bothTouchJoystickLayoutsUseMotionWithoutPaddleButtons() {
        for (var layout : new InputInteractionModel[]{InputInteractionModel.TOUCH, InputInteractionModel.CROSSHAIR}) {
            var p = input(InputMode.TOUCH,layout); p.setMotion(Vector2f.from(.5F,0));
            assertTrue(VehicleControlInput.analogPaddles(p));
            var control = VehicleControlInput.decode(p,true); assertEquals(.5F,control.x); assertEquals(0,control.z);
        }
    }
    @Test void onlyClassicTouchUsesIndependentPaddleButtons() {
        var p = input(InputMode.TOUCH,InputInteractionModel.CLASSIC); p.setMotion(Vector2f.from(0,1));
        p.getInputData().add(PlayerAuthInputData.PADDLE_LEFT);
        assertFalse(VehicleControlInput.analogPaddles(p));
        var control = VehicleControlInput.decode(p,true); assertEquals(1,control.x); assertEquals(0,control.z);
        p.getInputData().add(PlayerAuthInputData.PADDLE_RIGHT); assertEquals(1,VehicleControlInput.decode(p,true).z);
        p.setInputMode(InputMode.GAMEPAD); assertTrue(VehicleControlInput.analogPaddles(p));
    }
    @Test void keyboardMotionIsNotReplacedByTheWalkingAnalogVector() {
        var p = input(InputMode.MOUSE,InputInteractionModel.CROSSHAIR); p.setMotion(Vector2f.from(0,1)); p.setAnalogMoveVector(Vector2f.ZERO);
        assertEquals(1,VehicleControlInput.decode(p,true).z);
        p.setMotion(Vector2f.ZERO); assertEquals(0,VehicleControlInput.decode(p,true).z);
    }
    @Test void upAppliesAfterDiagonalNormalizationOnlyForBoats() {
        var p = input(InputMode.TOUCH,InputInteractionModel.CROSSHAIR); p.setMotion(Vector2f.from(1,1));
        p.getInputData().add(PlayerAuthInputData.UP);
        assertEquals(.70710677F,VehicleControlInput.decode(p,true).x); assertEquals(1,VehicleControlInput.decode(p,true).z);
        assertEquals(.70710677F,VehicleControlInput.decode(p,false).z);
    }
    @Test void invalidControlsAreRejectedAndTinyMotionResolvesToZero() {
        var p = input(InputMode.MOUSE,InputInteractionModel.CROSSHAIR);
        for (var motion : new Vector2f[]{Vector2f.from(Float.NaN,0),Vector2f.from(0,Float.POSITIVE_INFINITY),Vector2f.from(1.01F,0)}) {
            p.setMotion(motion); assertFalse(VehicleControlInput.valid(p));
        }
        p.setMotion(Vector2f.from(1e-7F,-1e-7F)); assertTrue(VehicleControlInput.valid(p));
        assertEquals(2.0E-14F,VehicleControlInput.decode(p,true).lengthSquared());
    }
}
