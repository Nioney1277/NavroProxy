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
    private final Map<BedrockPacket, Runnable> queuedCallbacks = new IdentityHashMap<>();

    public ServerEntityPackets(WirePlayerState player, CompensatedWorld world, Map<Long, EntityCache> entities,
                              Map<Long, Long> uniqueIdToRuntimeId) {
        this.player = player;
        this.world = world;
        this.entities = entities;
        this.uniqueIdToRuntimeId = uniqueIdToRuntimeId;
    }

    public void onPacket(BedrockPacket packet) {
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
            if (!hasX && !hasY && !hasZ) return;
            float x = hasX ? move.getX() : entity.getServerPosition().x;
            float y = hasY ? move.getY() : entity.getServerPosition().y;
            float z = hasZ ? move.getZ() : entity.getServerPosition().z;
            queuePositionUpdate(packet, entity, new Vec3(x, y, z), true);
            entity.lastWireTick(move.getTicks());
        }
        if (packet instanceof MoveEntityAbsolutePacket move) {
            EntityCache entity = entities.get(move.getRuntimeEntityId());
            if (entity != null) queuePositionUpdate(packet, entity, new Vec3(move.getPosition()), false);
        }
        if (packet instanceof RemoveEntityPacket remove) {
            player.queueAcknowledgment(packet, () -> {


                if (player.vehicleData != null && player.vehicleData.vehicleRuntimeId == remove.getUniqueEntityId())
                    player.vehicleData = null;
                Long key = uniqueIdToRuntimeId.remove(remove.getUniqueEntityId());
                if (key != null) entities.remove(key);
            });
        }
    }

    private EntityCache addToCache(long runtimeId, long uniqueId, int type, String identifier,
                                    EntityDataMap metadata, boolean playerEntity) {
        if (runtimeId == player.runtimeId()) return null;
        if (playerEntity) identifier = "minecraft:player";

        if (identifier == null || identifier.isBlank()) return null;
        Float width = EntityMetadataUtil.getFloat(metadata, EntityDataTypes.WIDTH);
        Float height = EntityMetadataUtil.getFloat(metadata, EntityDataTypes.HEIGHT);
        boolean boat = "minecraft:boat".equalsIgnoreCase(identifier) || "minecraft:chest_boat".equalsIgnoreCase(identifier);
        if (!playerEntity && !boat && (width == null || height == null)) return null;
        float definitionWidth = playerEntity ? 0.6F : boat ? 1.4F : width;
        float definitionHeight = playerEntity ? 1.8F : boat ? 0.455F : height;
        float definitionOffset = playerEntity ? 1.62001F : boat ? 0.375F : 0F;
        EntityCache entity = new EntityCache(player, runtimeId, uniqueId, type, identifier, definitionWidth,
                definitionHeight, definitionOffset, playerEntity || boat, world.dimension());
        entities.put(runtimeId, entity);
        uniqueIdToRuntimeId.put(uniqueId, runtimeId);
        return entity;
    }

    private void queuePositionUpdate(BedrockPacket packet, EntityCache entity, Vec3 raw, boolean lerp) {
        Vec3 position = raw.add(0, -entity.getYOffset(), 0);
        float distance = entity.getServerPosition().squaredDistanceTo(position);
        if (distance < 1.0E-15F) return;
        long[] sequence = {0};
        player.queueAcknowledgment(packet, () -> {
            if (entities.get(entity.runtimeId()) != entity) return;
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
