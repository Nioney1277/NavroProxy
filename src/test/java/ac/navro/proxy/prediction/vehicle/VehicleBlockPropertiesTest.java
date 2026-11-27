package ac.navro.proxy.prediction.vehicle;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VehicleBlockPropertiesTest {
    private static SessionState world(int protocol) {
        var session=new SessionState();session.setProtocolVersion(protocol);
        var start=new StartGamePacket();start.setPlayerPosition(Vector3f.from(8,64,8));session.observeBackend(start);
        session.world().put(0,0,CompensatedWorld.CachedChunk.network(-64,320,24,session.world().airNetworkId()));
        return session;
    }

    private static int block(int protocol,String name) {
        return NetworkBlockStateRegistry.forProtocol(protocol,false).states().values().stream()
                .filter(s->s.is(name)).findFirst().orElseThrow().networkId();
    }

    @Test void carpetConsultsHoneyBelowWhenFeetAreAboveTheIntegerBoundary() {
        var session=world(2225);var env=new VehicleEnvironment(session);
        session.world().updateBlock(8,63,8,0,block(2225,"minecraft:honey_block"));
        session.world().updateBlock(8,64,8,0,block(2225,"minecraft:white_carpet"));
        var feet=new Vec3(8.5F,64.0625F,8.5F);
        assertTrue(env.preventsJump(feet,true));
        assertFalse(env.preventsJump(feet,false));
        session.world().updateBlock(8,64,8,0,session.world().airNetworkId());
        assertFalse(env.preventsJump(feet,true));
        assertTrue(env.preventsJump(new Vec3(8.5F,64,8.5F),true));
        session.world().updateBlock(8,64,8,0,block(2225,"minecraft:stone"));
        assertFalse(env.preventsJump(feet,true));
    }

    @Test void honeyAtFeetAndVehicleAirFrictionKeepTheirOwnSemantics() {
        var session=world(2225);var env=new VehicleEnvironment(session);
        session.world().updateBlock(8,64,8,0,block(2225,"minecraft:honey_block"));
        assertTrue(env.preventsJump(new Vec3(8.5F,64.9375F,8.5F),true));
        var registry=NetworkBlockStateRegistry.forProtocol(2225,false);
        assertEquals(.9F,env.friction(registry.resolve(registry.airNetworkId())));
        assertEquals(.989F,env.friction(registry.resolve(block(2225,"minecraft:blue_ice"))));
    }

    @Test void earlierProtocolDoesNotSilentlyUseTheNewCatalog() {
        var session=world(2193);var env=new VehicleEnvironment(session);
        session.world().updateBlock(8,63,8,0,block(2193,"minecraft:honey_block"));
        session.world().updateBlock(8,64,8,0,block(2193,"minecraft:white_carpet"));
        assertFalse(env.preventsJump(new Vec3(8.5F,64.0625F,8.5F),true));
        assertTrue(env.preventsJump(new Vec3(8.5F,64,8.5F),true));
    }
}
