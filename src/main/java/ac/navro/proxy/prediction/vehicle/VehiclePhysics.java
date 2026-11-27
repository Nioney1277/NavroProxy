package ac.navro.proxy.prediction.vehicle;

import ac.navro.anticheat.util.MathUtil;
import ac.navro.anticheat.util.math.Vec3;


public final class VehiclePhysics {
    private VehiclePhysics() { }


    public static final class Oar {
        private int start = -1, previous = -1;
        private float power;
        public float power() { return power; }
        private void copy(Oar source) { start = source.start; previous = source.previous; power = source.power; }
        public void analog(int step, float input) {
            if (input == 0) { start = -1; power = 0; return; }
            if (step - start > 9) { start = step; power = input * 3; }
            else power = Math.copySign(Math.max(0, Math.abs(input * 3) - .1F), input);
        }
        public void digital(int step, boolean down) {
            if (!down) {
                if (start >= 0) previous = start;
                start = -1;
                power = power > .01F ? power * .5F : 0;
            } else if (start < 0) {
                start = step;
                power = step - previous < 10 ? Math.max(2.5F, power - .05F) : 3;
            } else power = step - start < 10 ? 3 : Math.max(2.5F, power - .05F);
        }
    }

    public static final class Boat {
        public final Oar left = new Oar(), right = new Oar();
        public float angularVelocity;
        public int submergedSteps, steps;
        public Boat copy() {
            var result = new Boat(); result.left.copy(left); result.right.copy(right);
            result.angularVelocity = angularVelocity; result.submergedSteps = submergedSteps; result.steps = steps;
            return result;
        }
        public Vec3 accelerate(Vec3 velocity, float yaw, float side, float forward,
                               boolean analog, boolean leftDown, boolean rightDown,
                               float friction, boolean airborne) {
            if (analog) {
                float thrust = (float) Math.sqrt(side * side + forward * forward);
                if (forward < 0) { thrust *= -.15F; side = -side; }
                left.analog(steps, thrust * (1 - Math.clamp(side, 0, 1)));
                right.analog(steps, thrust * (1 + Math.clamp(side, -1, 0)));
            } else {
                left.digital(steps, leftDown);
                right.digital(steps, rightDown);
            }
            steps++;
            float x = velocity.x * friction, z = velocity.z * friction;
            angularVelocity *= friction;
            if (submergedSteps <= 24) {
                float a = airborne ? 0 : left.power * .01375F;
                float b = airborne ? 0 : right.power * .01375F;
                float turn = 3 * a - 3 * b, push = a + b;
                if (x * x + z * z < .1F * .1F && turn != 0) { push *= friction; turn *= 1.6F; }
                angularVelocity = (angularVelocity + turn * 10) * friction;
                float angle = (90 - (yaw + angularVelocity)) * MathUtil.DEGREE_TO_RAD;
                x = (x + push * (float) Math.sin(angle)) * friction;
                z = (z + push * (float) Math.cos(angle)) * friction;
            }
            if (submergedSteps <= 24) {
                if (left.power == 0) left.start = -1;
                if (right.power == 0) right.start = -1;
            }
            return new Vec3(x, velocity.y, z);
        }
    }

    public static float boatVertical(float velocity, boolean buoyant, boolean gravity,
                                     boolean floating, boolean submerged, float originY, float baseBuoyancy) {
        if (!buoyant) return velocity;
        float y = gravity ? (velocity - .04F) * .98F : velocity;
        if (floating || submerged) {
            float fraction = originY - (float) Math.floor(originY);
            float depth = submerged ? 1 : Math.clamp((baseBuoyancy - fraction) * .9F + .1F, 0, 1);
            if (depth != 0) y = Math.min((depth - .1F) * .15F, y * .7F + .05F);
        }
        return y;
    }

    public static float bubbleColumnY(float velocity, boolean down) {
        return down ? Math.max(velocity - .03F, -.3F) : Math.min(velocity + .06F, .7F);
    }

    private static final class HorseTrig {
        private static final float SCALE = 10430.378F;
        private static final float[] SIN = new float[65536];
        static { for (int i = 0; i < SIN.length; i++) SIN[i] = (float) Math.sin((float) i / SCALE); }
        static float sin(float angle) { return SIN[(int) (angle * SCALE) & 65535]; }
        static float cos(float angle) { return SIN[(int) (angle * SCALE + 16384F) & 65535]; }
    }
    public static Vec3 horseInput(Vec3 input, float speed, float yaw) {
        float x = input.x * .5F, z = input.z <= 0 ? input.z * .25F : input.z;
        float squared = x*x + z*z;
        if (squared < .01F*.01F) return Vec3.ZERO.clone();
        float factor = speed / Math.max(1, (float) Math.sqrt(squared));
        x *= factor; z *= factor;
        float angle = yaw * MathUtil.DEGREE_TO_RAD;
        float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
        return new Vec3(x*cos-z*sin, 0, x*sin+z*cos);
    }
    public static float horseYaw(float yaw, float riderYaw) {
        float delta = MathUtil.wrapDegrees(riderYaw - yaw);
        float fraction = Math.max(.18F, (45 - Math.min(45, Math.abs(delta))) / 90) * .7F;
        return MathUtil.wrapDegrees(yaw + delta * fraction);
    }
    public static float jumpScale(int charge) { return charge >= 90 ? 1 : .4F + .4F * Math.clamp(charge, 0, 89) / 90; }
    public static Vec3 horseJump(Vec3 velocity, float yaw, float strength, int charge,
                                 boolean forward, float blockFactor, int boostLevel) {
        return horseJumpWithScale(velocity,yaw,strength,jumpScale(charge),forward,blockFactor,boostLevel);
    }
    static Vec3 horseJumpWithScale(Vec3 velocity,float yaw,float strength,float scale,boolean forward,float blockFactor,int boostLevel) {
        float angle = yaw * MathUtil.DEGREE_TO_RAD;
        return new Vec3(velocity.x + (forward ? (-.4F * HorseTrig.sin(angle)) * scale : 0),
                (strength * scale) * blockFactor + (boostLevel * .1F) * blockFactor,
                velocity.z + (forward ? (.4F * HorseTrig.cos(angle)) * scale : 0));
    }
}
