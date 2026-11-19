package ac.navro.proxy.world;

import org.cloudburstmc.nbt.NbtMap;
import ac.navro.anticheat.util.math.Box;

import java.util.List;


public record WireBlock(int networkId, String identifier, NbtMap definition,
                        List<Box> collisionShape) {
    public WireBlock {
        identifier = identifier == null ? "" : identifier;
        collisionShape = collisionShape == null ? null : List.copyOf(collisionShape);
    }

    public boolean collisionKnown() { return collisionShape != null; }
}
