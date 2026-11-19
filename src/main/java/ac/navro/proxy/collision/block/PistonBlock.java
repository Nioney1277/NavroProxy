package ac.navro.proxy.collision.block;

import ac.navro.proxy.data.block.NetworkBlockState;
import ac.navro.anticheat.util.math.Box;

import java.util.List;






public final class PistonBlock {
    public enum Type {
        Normal(0),
        Sticky(1);

        private final int value;

        Type(final int value) {
            this.value = value;
        }

        public int value() {
            return this.value;
        }
    }

    private final Type mType;

    private PistonBlock(final Type type) {
        this.mType = type == null ? Type.Normal : type;
    }

    public static PistonBlock from(final NetworkBlockState state) {
        final boolean sticky = state != null && state.is("minecraft:sticky_piston");
        return new PistonBlock(sticky ? Type.Sticky : Type.Normal);
    }

    public Type type() {
        return this.mType;
    }

    public List<Box> getCollisionShape() {
        return List.of(new Box(0, 0, 0, 1, 1, 1));
    }
}
