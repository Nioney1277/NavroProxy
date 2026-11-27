package ac.navro.proxy.prediction.vehicle;


final class HorseControl {
    boolean jumping, standing, sliding;
    float jump, animation;
    long standingRevision = Long.MIN_VALUE;

    HorseControl copy() {
        var result = new HorseControl();
        result.jumping=jumping; result.standing=standing; result.sliding=sliding;
        result.jump=jump; result.animation=animation; result.standingRevision=standingRevision;
        return result;
    }
    void metadata(long revision, boolean value) {
        if (revision != standingRevision) { standing = value; standingRevision = revision; }
    }
    boolean suppressed(boolean ground, int release) {
        return ground && standing && !jumping && !sliding && release < 0;
    }
    void request(int charge) {
        if (charge >= 0) { jump=VehiclePhysics.jumpScale(charge); standing=true; sliding=true; }
    }
    boolean canJump(boolean ground) { return ground && !jumping && jump > 0; }
    void launched() { jumping=true; jump=0; }
    void finish(boolean wasGrounded, boolean grounded) {
        if (!wasGrounded && grounded) { jumping=false; jump=0; standing=false; }
        sliding &= standing;
        animation = standing ? Math.min(1, animation+(1-animation)*.4F+.05F)
                : Math.max(0, animation+(.8F*animation*animation*animation-animation)*.6F-.05F);
    }
}
