package ac.navro.proxy.compensated.cache.entity;

import ac.navro.anticheat.data.EntityDimensions;
import ac.navro.anticheat.util.math.Box;
import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.compensated.cache.entity.state.CachedEntityState;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.state.WireVec3;
import ac.navro.proxy.util.EntityMetadataUtil;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityProperties;

import java.util.EnumMap;
import java.util.List;


public final class EntityCache {
    private final WirePlayerState player;
    private final long runtimeId, uniqueId;
    private final int entityType, dimension;
    private final String identifier;
    private final float definitionWidth, definitionHeight, definitionOffset;
    private EntityDimensions dimensions;
    private Vec3 serverPosition = Vec3.ZERO;
    private boolean inVehicle;
    private EntityDataMap metadata = new EntityDataMap();
    private final EnumMap<EntityFlag, Boolean> collisionFlags = new EnumMap<>(EntityFlag.class);
    private boolean hasCollisionFlags;
    private EntityProperties properties = new EntityProperties();
    private List<AttributeData> attributes = List.of();
    private long lastWireTick, queuedPositionUpdateSequence, appliedPositionUpdateSequence;
    private CachedEntityState current;
    public boolean affectedByOffset;

    public EntityCache(WirePlayerState player, long runtimeId, long uniqueId, int entityType,
                        String identifier, float definitionWidth, float definitionHeight,
                        float definitionOffset, boolean affectedByOffset, int dimension) {
        this.player = player;
        this.runtimeId = runtimeId;
        this.uniqueId = uniqueId;
        this.entityType = entityType;
        this.identifier = identifier;
        this.definitionWidth = definitionWidth;
        this.definitionHeight = definitionHeight;
        this.definitionOffset = definitionOffset;
        this.affectedByOffset = affectedByOffset;
        this.dimension = dimension;
        dimensions = EntityDimensions.fixed(definitionWidth, definitionHeight);
    }

    public void metadata(EntityDataMap update) {
        if (update == null) return;
        if (update.getFlags() != null) {
            collisionFlags.putAll(update.getFlags());
            hasCollisionFlags = true;
        }
        metadata = new EntityDataMap();
        update.forEach((type, value) -> metadata.put(type, value instanceof EnumMap<?, ?> flags ? flags.clone() : value));
        Float width = EntityMetadataUtil.getFloat(update, EntityDataTypes.WIDTH);
        Float height = EntityMetadataUtil.getFloat(update, EntityDataTypes.HEIGHT);
        Float scale = EntityMetadataUtil.getFloat(update, EntityDataTypes.SCALE);
        if (width != null) dimensions = EntityDimensions.fixed(width, definitionHeight);
        if (height != null) dimensions = EntityDimensions.fixed(definitionWidth, height);
        if (boat()) dimensions = EntityDimensions.fixed(definitionWidth, definitionHeight);
        if (scale != null) dimensions = dimensions.hardScaled(scale);
    }

    public Boolean collidableFlag() { return hasCollisionFlags ? Boolean.TRUE.equals(collisionFlags.get(EntityFlag.COLLIDABLE)) : null; }
    public boolean stackable() { return Boolean.TRUE.equals(collisionFlags.get(EntityFlag.STACKABLE)); }
    public boolean fallingBlock() { return "minecraft:falling_block".equalsIgnoreCase(identifier); }
    public boolean happyGhast() { return "minecraft:happy_ghast".equalsIgnoreCase(identifier); }
    public boolean isPlayerEntity() { return "minecraft:player".equalsIgnoreCase(identifier); }
    public boolean boat() {
        return entityType == 90 || entityType == 218 || "minecraft:boat".equalsIgnoreCase(identifier)
                || "minecraft:chest_boat".equalsIgnoreCase(identifier);
    }
    public boolean isBoat() { return boat(); }
    public boolean isMinecart() { return entityType == 84 || entityType == 98 || entityType == 96 || entityType == 97 || entityType == 100; }
    public float getYOffset() { return affectedByOffset ? definitionOffset : 0; }
    public EntityDimensions getDimensions() { return dimensions; }
    public CachedEntityState getCurrent() { return current; }
    public Vec3 getServerPosition() { return serverPosition; }
    public void setServerPosition(Vec3 value) { serverPosition = value; }
    public void init() { current = new CachedEntityState(player, this); }
    public void interpolate(Vec3 position, boolean lerp) {
        if (!lerp) current.setTeleportPos(position);
        else if (current.getInterpolator() != null) current.getInterpolator().refreshPositionAndAngles(position);
    }
    public long nextPositionUpdateSequence() { return ++queuedPositionUpdateSequence; }
    public void interpolate(Vec3 position, boolean lerp, long sequence) {
        interpolate(position, lerp);
        appliedPositionUpdateSequence = sequence;
    }
    public long queuedPositionUpdateSequence() { return queuedPositionUpdateSequence; }
    public long appliedPositionUpdateSequence() { return appliedPositionUpdateSequence; }
    public long runtimeId() { return runtimeId; }
    public long uniqueId() { return uniqueId; }
    public int entityType() { return entityType; }
    public int dimension() { return dimension; }
    public String identifier() { return identifier; }
    public WireVec3 position() { return current == null ? null : wire(current.getPos()); }
    public WireVec3 previousPosition() { return current == null ? null : wire(current.getPrevPos()); }
    public Box collisionBox() { return current == null ? null : current.getBoundingBox(); }
    public Box previousCollisionBox() { return current == null ? null : current.getBoundingBox(0); }
    private static WireVec3 wire(Vec3 value) { return new WireVec3(value.x, value.y, value.z); }
    public EntityDataMap metadata() { return metadata; }
    public EntityProperties properties() { return properties; }
    public void properties(EntityProperties value) { if (value != null) properties = value; }
    public List<AttributeData> attributes() { return attributes; }
    public void attributes(List<AttributeData> value) { attributes = value == null ? List.of() : List.copyOf(value); }
    public WireVec3 motion() { return motion; }
    private WireVec3 motion;
    public void motion(WireVec3 value) { motion = value; }
    public long lastWireTick() { return lastWireTick; }
    public void lastWireTick(long value) { lastWireTick = value; }
    public boolean inVehicle() { return inVehicle; }
    public void inVehicle(boolean value) { inVehicle = value; }
}
