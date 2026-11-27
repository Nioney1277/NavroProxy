package ac.navro.proxy.check.impl.timer;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.listener.PacketListener;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import ac.navro.proxy.util.LatencyUtil;
import com.google.gson.*;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;






class RespawnMovementLifecycleTest {
    private static JsonObject recording() throws Exception {
        try (var in = new InputStreamReader(RespawnMovementLifecycleTest.class.getResourceAsStream(
                "/respawn-phase-input-20261115.json"), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(in).getAsJsonObject();
        }
    }

    private static Vector3f vec(JsonArray a) {
        return Vector3f.from(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }

    private static PlayerAuthInputPacket input(JsonObject row) {
        var p = new PlayerAuthInputPacket();
        p.setTick(row.get("tick").getAsLong());
        p.setPosition(vec(row.getAsJsonArray("position")));
        p.setDelta(vec(row.getAsJsonArray("delta")));
        p.setRotation(vec(row.getAsJsonArray("rotation")));
        p.setMotion(Vector2f.ZERO);
        p.setRawMoveVector(Vector2f.ZERO);
        p.setInputMode(InputMode.MOUSE);
        for (var action : row.getAsJsonArray("actions"))
            p.getInputData().add(PlayerAuthInputData.valueOf(action.getAsString()));
        return p;
    }

    private static void queued(LiveDetectionTest.Fixture f, long ack, BedrockPacket p) {
        f.state().player().getLatencyUtil().sentQueue().add(new LatencyUtil.Latency(
                ack, f.clock().get() / 1_000_000L, f.clock().get(), true, new ArrayList<>()));
        PacketListener.onPacketSent(f.state().player(), p);
        f.state().observeBackend(p);
    }

    private static void ack(LiveDetectionTest.Fixture f, long marker) {
        assertTrue(f.state().player().getLatencyUtil().onResponse(marker));
    }

    private static UpdateAttributesPacket health(float value) {
        var p = new UpdateAttributesPacket();
        p.setRuntimeEntityId(7);
        p.getAttributes().add(new AttributeData("minecraft:health", 0, 20, value, 20));
        return p;
    }

    private static LiveDetectionTest.Fixture fixture(JsonObject facts) throws Exception {
        var f = LiveDetectionTest.Fixture.create(true);
        var s = f.state();
        s.world().clearChunks();
        var terrain = facts.getAsJsonObject("terrain");
        int grass = terrain.get("grassNetworkId").getAsInt();
        int dirt = terrain.get("dirtNetworkId").getAsInt();
        assertEquals("minecraft:grass_block", s.world().networkBlockState(grass).identifier());
        assertEquals("minecraft:dirt", s.world().networkBlockState(dirt).identifier());
        for (int cx = 7; cx <= 8; cx++) for (int cz = 8; cz <= 9; cz++) {
            s.world().put(cx, cz, CompensatedWorld.CachedChunk.network(-64, 320, 24, s.world().airNetworkId()));
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                s.world().updateBlock((cx << 4) + x, 3, (cz << 4) + z, 0, grass);
                s.world().updateBlock((cx << 4) + x, 2, (cz << 4) + z, 0, dirt);
            }
        }
        s.player().rebasePosition(new Vec3(vec(facts.getAsJsonArray("initialFeet"))), false);
        s.player().setVelocity(new Vec3(vec(facts.getAsJsonArray("initialVelocity"))));
        s.player().setCollisionResult(false, true, true);
        s.player().sinceLoadingScreen = 20;
        s.player().commitInputTick(2415);
        AuthInputPacketsTest.transport(s);
        return f;
    }

    @Test void recordedRespawnFootEchoIsHeldUntilHealthAndTeleportRestoreMovement() throws Exception {
        var facts = recording();
        var f = fixture(facts);
        var s = f.state();
        var rows = facts.getAsJsonArray("inputs");
        f.clock().addAndGet(50_000_000L);
        assertTrue(s.receiveClient(input(rows.get(0).getAsJsonObject()), 8500));
        assertTrue(f.alerts().isEmpty(), f.alerts().toString());
        var beforeDeath = s.authInputPackets().lastResult();
        assertNotNull(beforeDeath);

        queued(f, 91, health(0));
        ack(f, 91);
        var searching = new RespawnPacket();
        searching.setState(RespawnPacket.State.SERVER_SEARCHING);
        searching.setPosition(Vector3f.from(122.977295F, 4, 143.06895F));
        queued(f, 92, searching);
        ack(f, 92);
        var request = new RespawnPacket();
        request.setState(RespawnPacket.State.CLIENT_READY);
        assertTrue(s.receiveClient(request, 8500));
        var ready = new RespawnPacket();
        ready.setState(RespawnPacket.State.SERVER_READY);
        ready.setPosition(searching.getPosition());
        queued(f, 93, ready);
        ack(f, 93);
        assertFalse(s.player().acknowledgedDead, "Keep the existing respawn-check handshake semantics");


        var echo = input(rows.get(1).getAsJsonObject());
        var original = echo.getPosition();
        f.clock().addAndGet(1_650_000_000L);
        boolean forward = s.receiveClient(echo, 8500);
        assertTrue(f.alerts().isEmpty(), "Respawn foot echo must not run Phase: " + f.alerts());
        assertFalse(forward, "A dead actor's movement must not reach backend movement processing");
        assertEquals(original, echo.getPosition(), "Do not rewrite a respawn echo to the old movement anchor");
        assertSame(beforeDeath, s.authInputPackets().lastResult());
        assertEquals(0, s.player().pendingCorrectionRequests());
        assertFalse(s.teleports().getAuthInputHistory().containsKey(2449L));


        var move = new MovePlayerPacket();
        move.setRuntimeEntityId(7);
        move.setMode(MovePlayerPacket.Mode.TELEPORT);
        move.setPosition(Vector3f.from(128.5F, 5.62001F, 128.5F));
        move.setRotation(Vector3f.ZERO);
        move.setOnGround(true);
        queued(f, 94, move);
        queued(f, 95, health(20));
        f.clock().addAndGet(50_000_000L);
        var pending = input(rows.get(1).getAsJsonObject());
        pending.setTick(2450);
        assertFalse(s.receiveClient(pending, 8500));
        assertTrue(f.alerts().isEmpty(), f.alerts().toString());
        ack(f, 95);
        f.clock().addAndGet(50_000_000L);
        var confirmed = AuthInputPacketsTest.input(2451, 128.5F, 4, 128.5F, new Vec3(0, -.0784F, 0));
        confirmed.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
        assertTrue(s.receiveClient(confirmed, 8500),
                () -> "alerts=" + f.alerts() + ", anomalies=" + s.anomalies()
                        + ", queuedTeleports=" + s.teleports().getQueuedTeleports().size());
        assertFalse(s.teleports().isTeleporting());
        assertEquals(4, s.player().position().y);
        f.clock().addAndGet(50_000_000L);
        assertTrue(s.receiveClient(AuthInputPacketsTest.input(2452, 128.5F, 4, 128.5F,
                new Vec3(0, -.0784F, 0)), 8500));
        assertEquals(2420, s.authInputPackets().lastResult().tick());
        assertEquals(2452, s.player().simulationFrame());
        assertTrue(f.alerts().isEmpty(), f.alerts().toString());


        f.clock().addAndGet(50_000_000L);
        var invalid = AuthInputPacketsTest.input(2453, 128.5F, 4, 128.5F, new Vec3(0, -.0784F, 0));
        invalid.setPosition(Vector3f.from(128.5F, 4.00001F, 128.5F));
        assertFalse(s.receiveClient(invalid, 8500));
        assertTrue(f.alerts().stream().anyMatch(a -> a.contains("Phase")), f.alerts().toString());
        assertTrue(s.player().pendingCorrectionRequests() > 0);
    }
}
