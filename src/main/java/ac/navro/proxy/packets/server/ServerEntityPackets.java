package ac.navro.proxy.packets.server;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.compensated.cache.entity.EntityCache;
import ac.navro.proxy.state.WirePlayerState;
import ac.navro.proxy.state.WireVec3;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.util.EntityMetadataUtil;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.packet.*;

import java.util.IdentityHashMap;
import java.util.Map;



public final class ServerEntityPackets {
    private final WirePlayerState player;
    private final CompensatedWorld world;
    private final Map<Long, EntityCache> entities;
    private final Map<Long, Long> uniqueIdToRuntimeId;
    private final ac.navro.proxy.data.entity.NetworkEntityRegistry registry = new ac.navro.proxy.data.entity.NetworkEntityRegistry();
    private final Map<BedrockPacket, Runnable> queuedCallbacks = new IdentityHashMap<>();

    public ServerEntityPackets(WirePlayerState player, CompensatedWorld world, Map<Long, EntityCache> entities,
                              Map<Long, Long> uniqueIdToRuntimeId) {
        this.player = player;
        this.world = world;
        this.entities = entities;
        this.uniqueIdToRuntimeId = uniqueIdToRuntimeId;
    }

    public void onPacket(BedrockPacket packet) {
        if (packet instanceof AvailableEntityIdentifiersPacket identifiers) {
            registry.replace(identifiers.getIdentifiers());
        }
        if (packet instanceof AddEntityPacket add) {
            EntityCache entity = addToCache(add.getRuntimeEntityId(), add.getUniqueEntityId(), add.getEntityType(),
                    add.getIdentifier(), add.getMetadata(), false);
            if (entity == null) return;
            Vec3 position = new Vec3(add.getPosition()).add(0, -entity.getYOffset(), 0);
            entity.setServerPosition(position);
            entity.init();
            entity.interpolate(position, false);
            entity.metadata(add.getMetadata());
            entity.motion(WireVec3.of(add.getMotion()));
            if (add.getRotation() != null) entity.yaw(add.getRotation().getY());
            entity.properties(add.getProperties());
            entity.attributes(add.getAttributes());
        }
        if (packet instanceof AddPlayerPacket add) {
            EntityCache entity = addToCache(add.getRuntimeEntityId(), add.getUniqueEntityId(), 63,
                    "minecraft:player", add.getMetadata(), true);
            if (entity == null) return;
            Vec3 position = new Vec3(add.getPosition());
            entity.setServerPosition(position);
            entity.init();
            entity.interpolate(position, false);
            entity.metadata(add.getMetadata());
            entity.motion(WireVec3.of(add.getMotion()));
            entity.properties(add.getProperties());
        }
        if (packet instanceof MoveEntityDeltaPacket move) {
            EntityCache entity = entities.get(move.getRuntimeEntityId());
            if (entity == null) return;
            boolean hasX = move.getFlags().contains(MoveEntityDeltaPacket.Flag.HAS_X);
            boolean hasY = move.getFlags().contains(MoveEntityDeltaPacket.Flag.HAS_Y);
            boolean hasZ = move.getFlags().contains(MoveEntityDeltaPacket.Flag.HAS_Z);
            if (move.getFlags().contains(MoveEntityDeltaPacket.Flag.HAS_YAW)) {
                float yaw = move.getYaw();
                boolean controlledAtSend = locallyControlled(entity);
                boolean force = move.getFlags().contains(MoveEntityDeltaPacket.Flag.TELEPORTING)
                        || move.getFlags().contains(MoveEntityDeltaPacket.Flag.FORCE_MOVE_LOCAL_ENTITY);
                player.queueAcknowledgment(packet, () -> { if (entities.get(entity.runtimeId()) == entity
                        && (!controlledAtSend && !locallyControlled(entity) || force)) entity.yaw(yaw); });
            }
            if (!hasX && !hasY && !hasZ) return;
            float x = hasX ? move.getX() : entity.getServerPosition().x;
            float y = hasY ? move.getY() - entity.getYOffset() : entity.getServerPosition().y;
            float z = hasZ ? move.getZ() : entity.getServerPosition().z;
            boolean force = move.getFlags().contains(MoveEntityDeltaPacket.Flag.TELEPORTING)
                    || move.getFlags().contains(MoveEntityDeltaPacket.Flag.FORCE_MOVE_LOCAL_ENTITY);
            if (force) player.queueAcknowledgment(packet, () -> { if (entities.get(entity.runtimeId()) == entity) entity.forcePosition(); });
            queuePositionUpdate(packet, entity, new Vec3(x, y, z), !force, false, force);
            entity.lastWireTick(move.getTicks());
        }
        if (packet instanceof MoveEntityAbsolutePacket move) {
            EntityCache entity = entities.get(move.getRuntimeEntityId());
            if (entity != null) {
                if (move.getRotation() != null) {
                    float yaw = move.getRotation().getY(); boolean force = move.isTeleported() || move.isForceMove();
                    boolean controlledAtSend = locallyControlled(entity);
                    player.queueAcknowledgment(packet, () -> { if (entities.get(entity.runtimeId()) == entity
                            && (!controlledAtSend && !locallyControlled(entity) || force)) entity.yaw(yaw); });
                }
                if (move.isTeleported() || move.isForceMove())
                    player.queueAcknowledgment(packet, () -> { if (entities.get(entity.runtimeId()) == entity) entity.forcePosition(); });
                queuePositionUpdate(packet, entity, new Vec3(move.getPosition()), false, true, move.isTeleported() || move.isForceMove());
            }
        }
        if (packet instanceof MovePlayerPacket move && move.getRuntimeEntityId() != player.runtimeId()
                && move.getMode() != MovePlayerPacket.Mode.HEAD_ROTATION) {
            EntityCache entity = entities.get(move.getRuntimeEntityId());
            if (entity != null && entity.getYOffset() == 0F) {
                queuePositionUpdate(packet, entity, new Vec3(move.getPosition()), move.getMode() == MovePlayerPacket.Mode.NORMAL);
                entity.lastWireTick(move.getTick());
            }
        }
        if (packet instanceof RemoveEntityPacket remove) {
            player.queueAcknowledgment(packet, () -> {


                Long key = uniqueIdToRuntimeId.remove(remove.getUniqueEntityId());
                if (key != null) {
                    if (player.vehicleData != null && player.vehicleData.vehicleRuntimeId == key) player.vehicleData = null;
                    entities.remove(key);
                }
            });
        }
    }

    private boolean locallyControlled(EntityCache entity) {
        return player.vehicleData != null && player.vehicleData.driver && player.vehicleData.vehicleRuntimeId == entity.runtimeId();
    }

    private EntityCache addToCache(long runtimeId, long uniqueId, int type, String identifier,
                                    EntityDataMap metadata, boolean playerEntity) {
        if (runtimeId == player.runtimeId()) return null;
        if (playerEntity) identifier = "minecraft:player";

        if (identifier == null || identifier.isBlank()) return null;
        type = registry.type(identifier, type);
        Float width = EntityMetadataUtil.getFloat(metadata, EntityDataTypes.WIDTH);
        Float height = EntityMetadataUtil.getFloat(metadata, EntityDataTypes.HEIGHT);
        boolean boat = "minecraft:boat".equalsIgnoreCase(identifier) || "minecraft:chest_boat".equalsIgnoreCase(identifier);
        boolean horse = EntityCache.equine(identifier);
        if (!playerEntity && !boat && !horse && (width == null || height == null)) return null;
        float definitionWidth = playerEntity ? 0.6F : boat ? 1.4F : width != null ? width : 1.3964844F;
        float definitionHeight = playerEntity ? 1.8F : boat ? 0.455F : height != null ? height : 1.6F;
        float definitionOffset = playerEntity ? 1.62001F : boat ? 0.375F : 0F;
        EntityCache entity = new EntityCache(player, runtimeId, uniqueId, type, identifier, definitionWidth,
                definitionHeight, definitionOffset, playerEntity || boat, world.dimension());
        entities.put(runtimeId, entity);
        uniqueIdToRuntimeId.put(uniqueId, runtimeId);
        return entity;
    }

    private void queuePositionUpdate(BedrockPacket packet, EntityCache entity, Vec3 raw, boolean lerp) {
        queuePositionUpdate(packet, entity, raw, lerp, true);
    }

    private void queuePositionUpdate(BedrockPacket packet, EntityCache entity, Vec3 raw, boolean lerp, boolean applyYOffset) {
        queuePositionUpdate(packet, entity, raw, lerp, applyYOffset, false);
    }

    private void queuePositionUpdate(BedrockPacket packet, EntityCache entity, Vec3 raw, boolean lerp,
                                     boolean applyYOffset, boolean forced) {
        boolean controlledAtSend = locallyControlled(entity);
        Vec3 position = applyYOffset ? raw.add(0, -entity.getYOffset(), 0) : raw;
        float distance = entity.getServerPosition().squaredDistanceTo(position);
        if (distance < 1.0E-15F) return;
        long[] sequence = {0};
        player.queueAcknowledgment(packet, () -> {
            if (entities.get(entity.runtimeId()) != entity) return;
            if (!forced && (controlledAtSend || locallyControlled(entity))) return;
            entity.interpolate(position, lerp && distance < 4096, sequence[0]);
        });
        queuedCallbacks.put(packet, () -> {
            if (entities.get(entity.runtimeId()) != entity) return;
            entity.setServerPosition(position);
            sequence[0] = entity.nextPositionUpdateSequence();
        });
    }

    public void afterSend(BedrockPacket packet) {
        Runnable callback = queuedCallbacks.remove(packet);
        if (callback != null) callback.run();
    }
}
