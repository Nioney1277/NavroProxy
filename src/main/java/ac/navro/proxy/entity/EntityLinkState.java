package ac.navro.proxy.entity;

import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;


public record EntityLinkState(long from, long to, EntityLinkData.Type type,
                             boolean immediate, boolean riderInitiated,
                             float vehicleAngularVelocity) {
    public static EntityLinkState from(EntityLinkData value) {
        return new EntityLinkState(value.getFrom(), value.getTo(), value.getType(),
                value.isImmediate(), value.isRiderInitiated(), value.getVehicleAngularVelocity());
    }
}
