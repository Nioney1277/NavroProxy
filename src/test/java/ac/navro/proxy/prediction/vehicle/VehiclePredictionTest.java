package ac.navro.proxy.prediction.vehicle;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SessionState;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.data.entity.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class VehiclePredictionTest {
    private static final java.util.concurrent.atomic.AtomicLong MARKER=new java.util.concurrent.atomic.AtomicLong(10000);
    static SessionState session(boolean horse) {
        var s = AuthInputPacketsTest.session();
        var spawn = new AddEntityPacket(); spawn.setRuntimeEntityId(42); spawn.setUniqueEntityId(420);
        spawn.setIdentifier(horse ? "minecraft:horse" : "minecraft:boat"); spawn.setEntityType(horse ? 23 : 90);
        spawn.setPosition(Vector3f.from(8, horse ? 64 : 64.375F, 8)); spawn.setMotion(Vector3f.ZERO);
        spawn.setRotation(Vector2f.from(0, 90));
        spawn.getMetadata().setFlag(EntityFlag.HAS_GRAVITY, true);
        spawn.getMetadata().setFlag(EntityFlag.SADDLED, horse);
        if (!horse) {
            spawn.getMetadata().put(EntityDataTypes.IS_BUOYANT, true);
            spawn.getMetadata().put(EntityDataTypes.BUOYANCY_DATA, "{\"base_buoyancy\":1.0,\"liquid_blocks\":[\"minecraft:water\",\"minecraft:flowing_water\"]}");
        }
        spawn.getAttributes().add(new AttributeData("minecraft:movement", 0, 10, .225F));
        spawn.getAttributes().add(new AttributeData("minecraft:horse.jump_strength", 0, 10, .7F));
        s.observeBackend(spawn); ack(s);
        link(s, 70, EntityLinkData.Type.RIDER); ack(s);
        return s;
    }
    static void ack(SessionState s) {
        var pending = s.player().getLatencyUtil().sentQueue().peekLast();
        if (pending != null) s.player().getLatencyUtil().onResponse(pending.id());
    }
    static void link(SessionState s, long rider, EntityLinkData.Type type) {
        s.player().getLatencyUtil().queue(MARKER.incrementAndGet(), true);
        var p = new SetEntityLinkPacket(); p.setEntityLink(new EntityLinkData(420, rider, type, false, false, 0)); s.observeBackend(p);
    }
    static PlayerAuthInputPacket input(SessionState s, long tick, float x, float y, float z) {
        var p = AuthInputPacketsTest.input(tick, x, y, z, Vec3.ZERO);
        p.setPosition(Vector3f.from(x, y + s.playerYOffset(), z)); p.setPredictedVehicle(42);
        p.setVehicleRotation(Vector2f.from(0, 90)); p.setRotation(Vector3f.from(0, 90, 0));
        p.getInputData().add(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE); return p;
    }
    @Test void uniqueIdsMountOnlyAfterAckAndPassengerCannotDrive() {
        var s = session(false); assertEquals(42, s.player().vehicleData.vehicleRuntimeId); assertTrue(s.player().vehicleData.driver);
        link(s, 70, EntityLinkData.Type.PASSENGER); assertTrue(s.player().vehicleData.driver); ack(s); assertFalse(s.player().vehicleData.driver);
        s.observeClient(input(s, 10, 80, 64, 8)); assertNull(s.authInputPackets().lastResult());
        assertEquals("unsupported_or_passenger", s.authInputPackets().vehicles().status());
    }
    @Test void driverPositionAndRewrittenDeltaComeFromPhysicsNotSpoofedInput() {
        var s = session(false); s.setSmoothSetback(true); var corrections = new ArrayList<CorrectPlayerMovePredictionPacket>();
        s.setSoftCorrectionTransport(p -> { corrections.add(p); return true; });
        var p = input(s, 10, 800, 64, 8); s.observeClient(p);
        assertEquals("offset", s.authInputPackets().vehicles().status()); assertFalse(s.authInputPackets().lastResult().accepted());
        assertEquals(8, s.player().position().x); assertEquals(8, p.getPosition().getX()); assertEquals(-.0392F, p.getDelta().getY());
        assertEquals(1, corrections.size()); assertEquals(PredictionType.VEHICLE, corrections.getFirst().getPredictionType());
        assertEquals(90, corrections.getFirst().getVehicleRotation().getY());
    }
    @Test void missingFlagOrWrongIdCannotDisableTheVehicleModel() {
        for (int option = 0; option < 3; option++) {
            var s = session(false); var p = input(s, 10, 800, 64, 8);
            if (option == 0) p.getInputData().clear(); else if (option == 1) p.setPredictedVehicle(99);
            else p.setVehicleRotation(Vector2f.from(Float.NaN, 0));
            s.observeClient(p); assertEquals("invalid_vehicle_input", s.authInputPackets().vehicles().status()); assertEquals(8, s.player().position().x);
        }
    }
    @Test void boatFloatsAndPaddlesInWaterWithNoSelfCollision() {
        var s = session(false); int water = block("minecraft:water");
        for (int x = 6; x <= 10; x++) for (int z = 6; z <= 10; z++) s.world().updateBlock(x, 64, z, 1, water);
        var p = input(s, 10, 8, 64, 8 + .071775F); p.setMotion(Vector2f.from(0, 1));
        s.entity(42).metadata().setFlag(EntityFlag.COLLIDABLE, true);
        s.observeClient(p);
        assertEquals(8 + .071775F, s.player().position().z, 1e-6); assertEquals(.02256F, s.player().velocity().y, 1e-7);
        assertTrue(s.authInputPackets().lastResult().accepted());
    }
    @Test void zeroServerVerticalVelocityPreservesGroundAndContinuedPaddling() {
        var s = session(false);
        s.observeClient(input(s, 10, 8, 64, 8));
        s.observeClient(input(s, 11, 8, 64, 8));
        for (int tick = 12; tick <= 15; tick++) {
            if (tick > 12) {
                var motion = new SetEntityMotionPacket(); motion.setRuntimeEntityId(42);
                motion.setMotion(Vector3f.ZERO);
                s.player().getLatencyUtil().queue(100 + tick, true); s.observeBackend(motion); ack(s);
            }
            var p = input(s, tick, 8, 64, 8); p.setMotion(Vector2f.from(0, 1)); s.observeClient(p);
            assertTrue(s.player().onGround(), "ground lost at " + tick);
            assertTrue(s.player().velocity().z > 0, "paddle thrust lost at " + tick);
            assertEquals(-.0392F, s.player().velocity().y);
        }
    }
    @Test void upwardServerMotionStillLeavesGroundAndZeroMotionCannotGroundAnAirborneBoat() {
        var s = session(false); s.observeClient(input(s,10,8,64,8));
        var motion = new SetEntityMotionPacket(); motion.setRuntimeEntityId(42);
        motion.setMotion(Vector3f.from(0,.3F,0)); s.observeBackend(motion); ack(s);
        s.observeClient(input(s,11,8,64.3F,8)); assertFalse(s.player().onGround());
        motion = new SetEntityMotionPacket(); motion.setRuntimeEntityId(42); motion.setMotion(Vector3f.ZERO);
        s.observeBackend(motion); ack(s); s.observeClient(input(s,12,8,64.3F,8));
        assertFalse(s.player().onGround());
    }
    @Test void boatInAirDoesNotGainPaddleThrust() {
        var s = session(false);
        for (int x = 6; x <= 10; x++) for (int z = 6; z <= 10; z++) s.world().updateBlock(x, 63, z, 0, s.world().airNetworkId());
        var p = input(s, 10, 8, 64, 8); p.setMotion(Vector2f.from(0, 1)); s.observeClient(p);
        assertEquals(8, s.player().position().x); assertEquals(8, s.player().position().z);
    }
    @Test void horseUsesServerSpeedAndJumpChargeAndDoesNotRunHumanChecks() {
        var s = session(true);
        s.observeClient(input(s, 10, 8, 64, 8));s.observeClient(input(s, 11, 8, 64, 8));
        var alerts = new ArrayList<String>(); s.player().setAlertSink(alerts::add);
        var jump = new RiderJumpPacket(); jump.setJumpStrength(100); s.observeClient(jump);
        var p = input(s, 12, 8 - (.225F + .4F), 64.7F, 8); p.setMotion(Vector2f.from(0, 1)); s.observeClient(p);
        assertEquals(64.7F, s.player().position().y); assertEquals((.7F - .08F) * .98F, p.getDelta().getY());
        assertTrue(s.authInputPackets().lastResult().rawOffset() < .001F); assertTrue(alerts.isEmpty(), alerts.toString());
        var second = input(s, 13, 8, 64, 8); s.observeClient(second);
        assertTrue(s.player().position().y > 65); assertEquals(-1, s.player().vehicleData.jumpCharge);
    }
    @Test void motionAndMetadataApplyAtClosingAckAndMergePartialUpdates() {
        var s = session(true); var motion = new SetEntityMotionPacket(); motion.setRuntimeEntityId(42); motion.setMotion(Vector3f.from(.4F, .3F, 0));
        s.player().getLatencyUtil().queue(100, true); s.observeBackend(motion); assertEquals(0, s.entity(42).motion().x());
        var data = new SetEntityDataPacket(); data.setRuntimeEntityId(42); data.getMetadata().put(EntityDataTypes.WIDTH, 1.7F); s.observeBackend(data);
        s.player().getLatencyUtil().onResponse(100); assertEquals(.4F, s.entity(42).motion().x()); assertTrue(s.entity(42).flag(EntityFlag.SADDLED));
        var p = input(s, 10, 8.4F, 64.3F, 8); s.observeClient(p); assertTrue(s.authInputPackets().lastResult().accepted());
    }
    @Test void removeUsesUniqueToRuntimeMappingAndDismountClearsPrediction() {
        var s = session(false); s.observeClient(input(s, 10, 8, 64, 8));
        var remove = new RemoveEntityPacket(); remove.setUniqueEntityId(420); s.player().getLatencyUtil().queue(MARKER.incrementAndGet(), true); s.observeBackend(remove); assertNotNull(s.player().vehicleData); ack(s);
        assertNull(s.player().vehicleData); assertNull(s.entity(42));
    }
    @Test void serverVehicleCorrectionWaitsForAckAndDoesNotEnterHumanRewindQueue() {
        var s = session(false); s.observeClient(input(s, 10, 8, 64, 8));
        var p = new CorrectPlayerMovePredictionPacket(); p.setPredictionType(PredictionType.VEHICLE); p.setPosition(Vector3f.from(9, 64.375F, 8));
        p.setDelta(Vector3f.from(.2F, .1F, 0)); p.setVehicleRotation(Vector2f.from(0, 45)); p.setTick(s.player().simulationFrame());
        s.player().getLatencyUtil().queue(100, true); s.observeBackend(p); assertEquals(8, s.player().position().x); assertFalse(s.teleports().hasPendingBackendRewind());
        s.player().getLatencyUtil().onResponse(100); assertEquals(9, s.player().position().x); assertEquals(.2F, s.player().velocity().x); assertEquals(45, s.authInputPackets().vehicles().yaw());
    }
    @Test void unknownTerrainDoesNotAcceptVehiclePositionOrProduceOffsetPunishment() {
        var s = session(false); s.world().put(0, 0, CompensatedWorld.CachedChunk.partialNetwork(-64, 320, 24, s.world().airNetworkId()));
        s.observeClient(input(s, 10, 800, 64, 8)); assertEquals("waiting_for_terrain", s.authInputPackets().vehicles().status()); assertEquals(8, s.player().position().x);
        assertFalse(s.authInputPackets().lastResult().accepted());
    }
    @Test void delayedServerCorrectionReplaysTheControlsAfterItsSourceFrame() {
        var s = session(true);
        var first = input(s, 10, 8, 64, 8); s.observeClient(first); long source = s.player().simulationFrame();
        var second = input(s, 11, 8, 64, 8); second.setMotion(Vector2f.from(0, 1)); s.observeClient(second);
        var p = new CorrectPlayerMovePredictionPacket(); p.setPredictionType(PredictionType.VEHICLE);
        p.setPosition(Vector3f.from(9, 64 + s.playerYOffset(), 8)); p.setDelta(Vector3f.from(0, -.0784F, 0));
        p.setOnGround(true); p.setVehicleRotation(Vector2f.from(0, 90)); p.setTick(source); s.observeBackend(p);
        assertEquals(9F - .225F, s.player().position().x, 1e-6);
        assertEquals("backend_correction", s.authInputPackets().vehicles().status());
    }
    @Test void collisionStopsAJumpAtTheCeilingAndStopsTheMountAtAWall() {
        var s = session(true); int stone = block("minecraft:stone");
        s.observeClient(input(s, 10, 8, 64, 8));s.observeClient(input(s, 11, 8, 64, 8));
        for (int x = 6; x <= 10; x++) for (int z = 6; z <= 10; z++) s.world().updateBlock(x, 66, z, 0, stone);
        var jump = new RiderJumpPacket(); jump.setJumpStrength(100); s.observeClient(jump);
        s.observeClient(input(s, 12, 8, 64.7F, 8));
        assertEquals(64.4F, s.player().position().y, 1e-5); assertTrue(s.player().verticalCollision());
        assertEquals(-.0784F, s.player().velocity().y);
        s = session(false); for (int y = 64; y <= 65; y++) for (int z = 6; z <= 10; z++) s.world().updateBlock(9, y, z, 0, stone);
        var motion = new SetEntityMotionPacket(); motion.setRuntimeEntityId(42); motion.setMotion(Vector3f.from(2, 0, 0)); s.observeBackend(motion);
        s.observeClient(input(s, 10, 10, 64, 8)); assertTrue(s.player().horizontalCollision()); assertEquals(0, s.player().velocity().x);
        assertTrue(s.player().position().x <= 9 - 1.375F / 2 + 1e-5);
    }
    @Test void ordinaryServerEchoCannotRewindTheLocalVehicleButForcedMoveCan() {
        var s = session(true); s.observeClient(input(s, 10, 8, 64, 8));
        var move = new MoveEntityAbsolutePacket(); move.setRuntimeEntityId(42); move.setPosition(Vector3f.from(10, 64, 8)); move.setRotation(Vector3f.from(0, 90, 0));
        s.observeBackend(move); s.onBackendPacketQueued(move); s.observeClient(input(s, 11, 8, 64, 8)); assertEquals(8, s.player().position().x);
        move = new MoveEntityAbsolutePacket(); move.setRuntimeEntityId(42); move.setPosition(Vector3f.from(11, 64, 8)); move.setRotation(Vector3f.from(0, 90, 0)); move.setTeleported(true);
        s.observeBackend(move); s.onBackendPacketQueued(move); s.observeClient(input(s, 12, 11, 64, 8)); assertEquals(11, s.player().position().x);
    }
    @Test void protocolGateAndUnsaddledHorsesRetainPassengerForwarding() {
        var s = session(false); s.player().setProtocolVersion(943); s.observeClient(input(s, 10, 8, 64, 8));
        assertEquals("unsupported_or_passenger", s.authInputPackets().vehicles().status()); assertNull(s.authInputPackets().lastResult());
        s = session(true); var data = new SetEntityDataPacket(); data.setRuntimeEntityId(42); data.getMetadata().setFlag(EntityFlag.SADDLED, false); s.observeBackend(data);
        s.observeClient(input(s, 10, 8, 64, 8)); assertNull(s.authInputPackets().lastResult());
    }
    @Test void partialBoatMetadataKeepsBuoyancyAndRemoteDriverRevokesLocalControl() {
        var s = session(false); var data = new SetEntityDataPacket(); data.setRuntimeEntityId(42); data.getMetadata().put(EntityDataTypes.NAME, "boat"); s.observeBackend(data);
        s.observeClient(input(s, 10, 8, 64, 8)); assertTrue(s.authInputPackets().lastResult().accepted());
        link(s, 9999, EntityLinkData.Type.RIDER); ack(s); assertFalse(s.player().vehicleData.driver);
    }
    @Test void dimensionChangeClearsTheMountAtTheSameAcknowledgedBoundary() {
        var s = session(false); s.observeClient(input(s, 10, 8, 64, 8));
        var change = new ChangeDimensionPacket(); change.setDimension(1); change.setPosition(Vector3f.from(8, 64, 8));
        s.player().getLatencyUtil().queue(100, true); s.observeBackend(change); assertNotNull(s.player().vehicleData);
        s.player().getLatencyUtil().onResponse(100); assertNull(s.player().vehicleData); assertEquals("reset", s.authInputPackets().vehicles().status());
    }
    @Test void acknowledgedRiderSeatOverridesTheFallbackHorseHeight() {
        var s = session(true); float original = s.playerYOffset();
        var metadata = new SetEntityDataPacket(); metadata.setRuntimeEntityId(7); metadata.getMetadata().put(EntityDataTypes.SEAT_OFFSET, Vector3f.from(0, .9F, 0));
        s.player().getLatencyUtil().queue(100,true); s.observeBackend(metadata); assertEquals(original,s.playerYOffset());
        s.player().getLatencyUtil().onResponse(100); assertEquals(.9F + 1.62001F,s.playerYOffset());
    }
    @Test void correctionUsesTheSeatHeightRecordedAtItsSourceFrame() {
        var s = session(true); s.observeClient(input(s,10,8,64,8));
        long source = s.player().simulationFrame(); float sourceOffset = s.playerYOffset();
        var metadata = new SetEntityDataPacket(); metadata.setRuntimeEntityId(7);
        metadata.getMetadata().put(EntityDataTypes.SEAT_OFFSET,Vector3f.from(0,1.1F,0)); s.observeBackend(metadata);
        var correction = new CorrectPlayerMovePredictionPacket(); correction.setPredictionType(PredictionType.VEHICLE);
        correction.setPosition(Vector3f.from(8,64+sourceOffset,8)); correction.setDelta(Vector3f.from(0,-.0784F,0));
        correction.setOnGround(true); correction.setVehicleRotation(Vector2f.from(0,90)); correction.setTick(source); s.observeBackend(correction);
        assertEquals(64,s.player().position().y);
    }
    @Test void touchJoystickActuallyTurnsTheBoatInsteadOfGeneratingSetbacks() {
        for (var layout : new InputInteractionModel[]{InputInteractionModel.TOUCH,InputInteractionModel.CROSSHAIR}) {
            var s = session(false); int water = block("minecraft:water");
            for(int x=6;x<=10;x++)for(int z=6;z<=10;z++)s.world().updateBlock(x,64,z,1,water);
            var p = input(s,10,8.000971F,64,8.032284F); p.setInputMode(InputMode.TOUCH); p.setInputInteractionModel(layout);
            p.setMotion(Vector2f.from(1,0)); p.setAnalogMoveVector(Vector2f.ZERO); p.setVehicleRotation(Vector2f.from(0,88.2774F));
            var corrections = new ArrayList<CorrectPlayerMovePredictionPacket>(); s.setSoftCorrectionTransport(c->{corrections.add(c);return true;});
            s.observeClient(p);
            assertEquals(88.2774F,s.authInputPackets().vehicles().yaw(),1e-5);
            assertTrue(s.player().position().z>8.03F); assertTrue(s.authInputPackets().lastResult().accepted()); assertTrue(corrections.isEmpty());
        }
    }
    @Test void boatUpFlagAndKeyboardWithZeroAnalogVectorStillProvideThrust() {
        for(boolean up:new boolean[]{false,true}) {
            var s=session(false); int water=block("minecraft:water");
            for(int x=6;x<=10;x++)for(int z=6;z<=10;z++)s.world().updateBlock(x,64,z,1,water);
            var p=input(s,10,8,64,8.071775F);p.setAnalogMoveVector(Vector2f.ZERO);
            p.setMotion(up?Vector2f.ZERO:Vector2f.from(0,1)); if(up)p.getInputData().add(PlayerAuthInputData.UP);
            s.observeClient(p);assertTrue(s.authInputPackets().lastResult().accepted());assertEquals(8.071775F,s.player().position().z,1e-6);
        }
    }
    @Test void yawDifferenceDoesNotAddAnIndependentMovementThreshold() {
        var s=session(false);var p=input(s,10,8,64,8);p.setVehicleRotation(Vector2f.from(0,130));s.observeClient(p);
        assertTrue(s.authInputPackets().lastResult().accepted());
    }
    @Test void vehiclePositionUsesNavrosConfiguredThresholdWithoutAHardcodedFloor() {
        var strict=session(false);strict.observeClient(input(strict,10,8.0005F,64,8));assertFalse(strict.authInputPackets().lastResult().accepted());
        var configured=session(false);configured.authInputPackets().context().maxOffset=.001F;
        configured.observeClient(input(configured,10,8.0005F,64,8));assertTrue(configured.authInputPackets().lastResult().accepted());
    }
    @Test void buoyancyComponentOwnsGravityAndWaveMode() {
        var s=session(false);var metadata=new SetEntityDataPacket();metadata.setRuntimeEntityId(42);
        metadata.getMetadata().put(EntityDataTypes.BUOYANCY_DATA,"{\"apply_gravity\":false,\"simulate_waves\":true,\"liquid_blocks\":[\"minecraft:water\"]}");
        s.observeBackend(metadata);
        var component=com.google.gson.JsonParser.parseString(s.entity(42).metadata().get(EntityDataTypes.BUOYANCY_DATA)).getAsJsonObject();
        assertFalse(component.get("simulate_waves").getAsBoolean());assertEquals("none",component.get("movement_type").getAsString());
        s.observeClient(input(s,10,8,64,8));assertEquals(0,s.player().velocity().y);
    }
    @Test void acknowledgedBoatDimensionsAreUsedForCeilingCollision() {
        var s=session(false);int stone=block("minecraft:stone");
        for(int x=6;x<=10;x++)for(int z=6;z<=10;z++)s.world().updateBlock(x,65,z,0,stone);
        var metadata=new SetEntityDataPacket();metadata.setRuntimeEntityId(42);metadata.getMetadata().put(EntityDataTypes.HEIGHT,.455F);metadata.getMetadata().put(EntityDataTypes.WIDTH,1.4F);s.observeBackend(metadata);
        var motion=new SetEntityMotionPacket();motion.setRuntimeEntityId(42);motion.setMotion(Vector3f.from(0,.6F,0));s.observeBackend(motion);
        s.observeClient(input(s,10,8,64.545F,8));assertEquals(64.545F,s.player().position().y,1e-5);assertTrue(s.player().verticalCollision());
    }
    @Test void buoyancyGravityOverridesTheGenericFlagAndLetsAGroundedBoatRow() {
        var s=session(false);var metadata=new SetEntityDataPacket();metadata.setRuntimeEntityId(42);
        metadata.getMetadata().setFlag(EntityFlag.HAS_GRAVITY,false);
        metadata.getMetadata().put(EntityDataTypes.BUOYANCY_DATA,"{\"apply_gravity\":true,\"base_buoyancy\":1,\"liquid_blocks\":[\"minecraft:water\"]}");
        s.observeBackend(metadata);s.observeClient(input(s,10,8,64,8));assertEquals(-.0392F,s.player().velocity().y);
        s.observeClient(input(s,11,8,64,8));assertTrue(s.player().onGround());
        var row=input(s,12,8,64,8.04785F);row.setInputMode(InputMode.TOUCH);row.setInputInteractionModel(InputInteractionModel.CROSSHAIR);
        row.setMotion(Vector2f.from(0,1));s.observeClient(row);
        assertEquals(8.04785F,s.player().position().z,1e-6);assertTrue(s.authInputPackets().lastResult().accepted());
    }
    static int block(String name) { return NetworkBlockStateRegistry.forProtocol(2193, false).states().values().stream()
            .filter(b -> b.is(name) && b.blockData() == 0).findFirst().orElseThrow().networkId(); }
}
