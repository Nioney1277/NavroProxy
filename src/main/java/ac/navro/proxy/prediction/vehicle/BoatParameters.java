package ac.navro.proxy.prediction.vehicle;

import ac.navro.proxy.compensated.cache.entity.EntityCache;
import com.google.gson.JsonParser;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;

import java.util.HashSet;
import java.util.Set;


public record BoatParameters(boolean buoyant, boolean gravity, float base, Set<String> liquids) {
    public static BoatParameters read(EntityCache actor) {
        var metadata = actor.metadata();
        boolean buoyant = !metadata.containsKey(EntityDataTypes.IS_BUOYANT) || Boolean.TRUE.equals(metadata.get(EntityDataTypes.IS_BUOYANT));
        boolean gravity = actor.flagOr(EntityFlag.HAS_GRAVITY, true);
        if (!metadata.containsKey(EntityDataTypes.BUOYANCY_DATA))
            return new BoatParameters(buoyant, gravity, 1, Set.of("minecraft:water", "minecraft:flowing_water"));
        try {
            var json = JsonParser.parseString(metadata.get(EntityDataTypes.BUOYANCY_DATA)).getAsJsonObject();
            float base = json.has("base_buoyancy") ? json.get("base_buoyancy").getAsFloat() : 1;
            gravity = !json.has("apply_gravity") || json.get("apply_gravity").getAsBoolean();
            if (!Float.isFinite(base)) return null;
            var liquids = new HashSet<String>();
            if (json.has("liquid_blocks")) for (var value : json.getAsJsonArray("liquid_blocks")) liquids.add(value.getAsString());

            if (!Set.of("minecraft:water", "minecraft:flowing_water", "minecraft:lava", "minecraft:flowing_lava").containsAll(liquids)) return null;
            return new BoatParameters(buoyant, gravity, base, Set.copyOf(liquids));
        } catch (RuntimeException invalidComponent) { return null; }
    }

    public static boolean prepare(org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap metadata) {
        String value = metadata.get(EntityDataTypes.BUOYANCY_DATA);
        if (value == null) return false;
        try {
            var component = JsonParser.parseString(value).getAsJsonObject();
            component.addProperty("movement_type", "none");
            component.addProperty("simulate_waves", false);
            String rewritten = component.toString();
            metadata.put(EntityDataTypes.BUOYANCY_DATA, rewritten);
            return !value.equals(rewritten);
        } catch (RuntimeException invalidComponent) { return false; }
    }
    public static boolean boatPacket(ac.navro.proxy.SessionState session,
                                     org.cloudburstmc.protocol.bedrock.packet.BedrockPacket packet) {
        long id;
        if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket spawn) id = spawn.getRuntimeEntityId();
        else if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket update) id = update.getRuntimeEntityId();
        else return false;
        var entity = session.entity(id);
        return entity != null && entity.boat();
    }
    public boolean accepts(String identifier) { return buoyant && liquids.contains(identifier); }
}
