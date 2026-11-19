package ac.navro.proxy.compensated.cache.entity.state;

import ac.navro.proxy.compensated.cache.entity.EntityCache;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.anticheat.util.MathUtil;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.util.reach.PositionInterpolator;

public final class CachedEntityState {
    private final WirePlayerState player;
    private final EntityCache entity;
    private Vec3 prevPos = Vec3.ZERO;
    private Vec3 pos = Vec3.ZERO;
    private PositionInterpolator interpolator;

    public CachedEntityState(WirePlayerState player, EntityCache entity) {
        this.player = player;
        this.entity = entity;

        this.interpolator = new PositionInterpolator(this);
    }

    public WirePlayerState getPlayer() { return player; }
    public EntityCache getEntity() { return entity; }
    public Vec3 getPrevPos() { return prevPos; }
    public void setPrevPos(Vec3 value) { prevPos = value; }
    public Vec3 getPos() { return pos; }
    public void setPos(Vec3 value) { pos = value; }
    public PositionInterpolator getInterpolator() { return interpolator; }
    public void setInterpolator(PositionInterpolator value) { interpolator = value; }

    public void tick() {
        if (this.isInterpolating()) {
            this.interpolator.tick();
        } else {
            this.prevPos = this.pos.clone();
        }
    }

    public boolean isInterpolating() {
        return this.getInterpolator() != null && this.getInterpolator().isInterpolating();
    }

    public Box getBoundingBox() {
        return this.calculateBoundingBox();
    }

    public Box getBoundingBox(float f) {
        if (Math.abs(1 - f) <= 1.0E-3) {
            return this.calculateBoundingBox();
        }

        float x = MathUtil.lerp(f, this.prevPos.x, this.pos.x);
        float y = MathUtil.lerp(f, this.prevPos.y, this.pos.y);
        float z = MathUtil.lerp(f, this.prevPos.z, this.pos.z);
        return this.calculateBoundingBox(new Vec3(x, y, z));
    }

    public Box calculateBoundingBox() {
        return this.entity.getDimensions().getBoxAt(this.pos);
    }

    public Box calculateBoundingBox(Vec3 vec3) {
        return this.entity.getDimensions().getBoxAt(vec3);
    }

    public void setTeleportPos(Vec3 pos) {
        this.prevPos = this.pos = pos;
    }

    @Override
    public CachedEntityState clone() {
        final CachedEntityState state = new CachedEntityState(player, entity);
        state.setPos(this.pos.clone());
        state.setPrevPos(this.prevPos.clone());
        state.setInterpolator(this.interpolator == null ? null : this.interpolator.clone());

        return state;
    }
}
