package ac.navro.proxy.nethernet;

import ac.navro.anticheat.util.math.Vec3;
import ac.navro.proxy.SupportedCodecs;
import ac.navro.proxy.compensated.world.base.CompensatedWorld;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.proxy.packets.input.AuthInputPacketsTest;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.*;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;





class NetherNetDetectionTest {
    @Test void nativeVehiclePacketsReachPredictionAndEmitVehicleCorrection() throws Exception {
        try (var s = session()) {
            var spawn = new AddEntityPacket(); spawn.setRuntimeEntityId(42); spawn.setUniqueEntityId(420);
            spawn.setIdentifier("minecraft:boat"); spawn.setPosition(Vector3f.from(8,64.375F,8));
            spawn.setMotion(Vector3f.ZERO); spawn.setRotation(Vector2f.from(0,90));
            s.process(true,s.encode(spawn));
            var link = new SetEntityLinkPacket(); link.setEntityLink(new org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData(
                    420,70,org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData.Type.RIDER,false,false,0));
            s.process(true,s.encode(link)); assertNull(s.state().player().vehicleData); var mounted=ack(s);
            assertEquals(42,s.state().player().vehicleData.vehicleRuntimeId);
            assertTrue(mounted.clientbound().stream().map(bytes->{try{return decode(bytes);}catch(Exception e){throw new RuntimeException(e);}})
                    .noneMatch(MovePlayerPacket.class::isInstance));
            ack(s);
            var confirmation=input(0);confirmation.setPosition(Vector3f.from(8,64.375F,8));confirmation.setPredictedVehicle(42);
            confirmation.setVehicleRotation(Vector2f.from(0,90));confirmation.getInputData().add(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE);
            assertFalse(confirmation.getInputData().contains(PlayerAuthInputData.HANDLE_TELEPORT));
            assertFalse(s.process(false,s.encode(confirmation)).backendbound().isEmpty());
            var input = input(1); input.setPosition(Vector3f.from(800,64.375F,8)); input.setPredictedVehicle(42);
            input.setVehicleRotation(Vector2f.from(0,90)); input.getInputData().add(PlayerAuthInputData.IN_CLIENT_PREDICTED_IN_VEHICLE);
            var result = s.process(false,s.encode(input));
            assertEquals(8,s.state().player().position().x);
            assertEquals("offset",s.state().authInputPackets().vehicles().status());
            assertFalse(result.backendbound().isEmpty(), "Validated vehicle coordinates continue while hard reset is acknowledged");
            var batch=decodeBatch(result.clientbound().getFirst());
            assertEquals(3,batch.size());
            var unlink=assertInstanceOf(SetEntityLinkPacket.class,batch.getFirst()).getEntityLink();
            assertEquals(org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData.Type.REMOVE,unlink.getType());
            var correction=assertInstanceOf(MoveEntityAbsolutePacket.class,batch.get(1));
            assertTrue(correction.isTeleported());assertTrue(correction.isForceMove());assertEquals(42,correction.getRuntimeEntityId());
            var backendPackets=new java.util.ArrayList<BedrockPacket>();
            for(byte[] bytes:result.backendbound())backendPackets.addAll(decodeBatch(bytes));
            var exit=assertInstanceOf(InteractPacket.class,backendPackets.getLast());
            assertEquals(InteractPacket.Action.LEAVE_VEHICLE,exit.getAction());assertEquals(42,exit.getRuntimeEntityId());
            assertTrue(backendPackets.stream().anyMatch(PlayerAuthInputPacket.class::isInstance));
            ack(s);assertNotNull(s.state().player().vehicleData);
            var removed=new SetEntityLinkPacket();removed.setEntityLink(new org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData(
                    420,70,org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData.Type.REMOVE,false,false,0));
            s.process(true,s.encode(removed));var placed=ack(s);
            assertNull(s.state().player().vehicleData);
            var move=placed.clientbound().stream().map(bytes->{try{return decode(bytes);}catch(Exception e){throw new RuntimeException(e);}})
                    .filter(MovePlayerPacket.class::isInstance).map(MovePlayerPacket.class::cast).findFirst().orElseThrow();
            assertEquals(MovePlayerPacket.Mode.TELEPORT,move.getMode());assertEquals(Vector3f.from(8,65.62001F,8),move.getPosition());
            ack(s);var confirmed=input(2);confirmed.setPosition(move.getPosition());confirmed.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
            s.process(false,s.encode(confirmed));
            assertFalse(s.state().teleports().isHardTeleporting());assertFalse(s.state().vehicleHardSetback().pending());
            assertNull(s.state().player().vehicleData);

        }
    }
    @Test void boatComponentNormalizationReachesTheEncodedClientPacket() throws Exception {
        try(var s=session()) {
            var spawn=new AddEntityPacket();spawn.setRuntimeEntityId(42);spawn.setUniqueEntityId(420);spawn.setIdentifier("minecraft:boat");
            spawn.setPosition(Vector3f.from(8,64.375F,8));spawn.setMotion(Vector3f.ZERO);spawn.setRotation(Vector2f.ZERO);
            spawn.getMetadata().put(org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes.BUOYANCY_DATA,"{\"apply_gravity\":true,\"simulate_waves\":true,\"base_buoyancy\":1}");
            var result=s.process(true,s.encode(spawn));var decoded=assertInstanceOf(AddEntityPacket.class,decode(result.forwarded()));
            var component=com.google.gson.JsonParser.parseString(decoded.getMetadata().get(org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes.BUOYANCY_DATA)).getAsJsonObject();
            assertFalse(component.get("simulate_waves").getAsBoolean());assertEquals("none",component.get("movement_type").getAsString());
            var update=new SetEntityDataPacket();update.setRuntimeEntityId(42);update.getMetadata().put(org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes.BUOYANCY_DATA,"{\"simulate_waves\":true}");
            var changed=assertInstanceOf(SetEntityDataPacket.class,decode(s.process(true,s.encode(update)).forwarded()));
            assertTrue(changed.getMetadata().get(org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes.BUOYANCY_DATA).contains("\"simulate_waves\":false"));
        }
    }
    @Test void realBdsCommandsGainNavroWithoutLosingOptionalChainedValues()throws Exception{
        try(var s=session();var recorded=new java.util.zip.GZIPInputStream(
                getClass().getResourceAsStream("/nethernet-bds-2225-commands.batch.gz"))){
            byte[] batch=recorded.readAllBytes();
            var result=s.process(true,batch);
            assertEquals(List.of("AvailableCommandsPacket"),result.packets());
            var original=assertInstanceOf(AvailableCommandsPacket.class,decode(batch));
            var rewritten=assertInstanceOf(AvailableCommandsPacket.class,decode(result.forwarded()));
            assertEquals(original.getCommands(),rewritten.getCommands().stream().filter(c->!c.getName().equals("navro")).toList());
            long absentChainedValues=rewritten.getCommands().stream().flatMap(c->c.getSubcommands().stream())
                    .flatMap(c->c.getValues().stream()).filter(v->v.getSecond()==null).count();
            assertEquals(11,absentChainedValues,"The real BDS capture has eleven absent chained values");
            var navro=rewritten.getCommands().stream().filter(c->c.getName().equals("navro")).toList();
            assertEquals(1,navro.size());
            assertTrue(navro.get(0).getOverloads()[0].getOverloads()[0].isOptional());
            assertTrue(result.backendbound().isEmpty());
        }
    }
    @Test void commandTableWaitsForLocalOperatorAndClearsOnRevocation() throws Exception {
        try (var s = session(); var recorded = new java.util.zip.GZIPInputStream(
                getClass().getResourceAsStream("/nethernet-bds-2225-commands.batch.gz"))) {
            s.setOperatorCommandTable(true);
            byte[] table = recorded.readAllBytes();
            assertNull(s.process(true, table).forwarded());

            var abilities = new UpdateAbilitiesPacket();
            abilities.setUniqueEntityId(71);
            abilities.setCommandPermission(org.cloudburstmc.protocol.bedrock.data.command.CommandPermission.GAME_DIRECTORS);
            abilities.setPlayerPermission(PlayerPermission.OPERATOR);
            assertEquals(1, decodeBatch(s.process(true, s.encode(abilities)).forwarded()).size());
            assertNull(s.process(true, table).forwarded());

            abilities.setUniqueEntityId(s.state().player().uniqueId());
            var promoted = decodeBatch(s.process(true, s.encode(abilities)).forwarded());
            assertInstanceOf(UpdateAbilitiesPacket.class, promoted.getFirst());
            var delivered = assertInstanceOf(AvailableCommandsPacket.class, promoted.get(1));
            assertTrue(delivered.getCommands().stream().anyMatch(command -> command.getName().equals("navro")));
            assertInstanceOf(AvailableCommandsPacket.class, decode(s.process(true, table).forwarded()));

            abilities.setCommandPermission(org.cloudburstmc.protocol.bedrock.data.command.CommandPermission.ANY);
            abilities.setPlayerPermission(PlayerPermission.MEMBER);
            var demoted = decodeBatch(s.process(true, s.encode(abilities)).forwarded());
            assertInstanceOf(UpdateAbilitiesPacket.class, demoted.getFirst());
            assertTrue(assertInstanceOf(AvailableCommandsPacket.class, demoted.get(1)).getCommands().isEmpty());
            assertEquals(1, decodeBatch(s.process(true, s.encode(abilities)).forwarded()).size());
            assertNull(s.process(true, table).forwarded());
        }
    }

    private static List<BedrockPacket> decodeBatch(byte[] batch) throws Exception {
        assertEquals(255, Byte.toUnsignedInt(batch[0]));
        var bytes = Unpooled.wrappedBuffer(batch, 1, batch.length - 1);
        try {
            var codec = SupportedCodecs.forProtocol(2225);
            var helper = codec.createHelper();
            new ac.navro.proxy.ProtocolDefinitions().configure(helper, true);
            var decoded = new ArrayList<BedrockPacket>();
            while (bytes.isReadable()) {
                var frame = bytes.readSlice(VarInts.readUnsignedInt(bytes));
                int header = VarInts.readUnsignedInt(frame);
                decoded.add(codec.tryDecode(helper, frame, header & 1023));
            }
            return decoded;
        } finally {
            bytes.release();
        }
    }

    @Test void nativeClosingMarkerAcknowledgesAndReplaysBackendPredictionCorrection()throws Exception{
        try(var s=session()){
            for(int tick=0;tick<=10;tick++)assertEquals(1,s.process(false,s.encode(input(tick))).backendbound().size());
            var earlierMotion=new SetEntityMotionPacket();earlierMotion.setRuntimeEntityId(7);
            earlierMotion.setTick(9);earlierMotion.setMotion(Vector3f.from(0,.4F,0));
            s.process(true,s.encode(earlierMotion));
            var correction=new CorrectPlayerMovePredictionPacket();correction.setTick(10);
            correction.setPosition(Vector3f.from(10,65.62001F,8));correction.setDelta(Vector3f.from(0,-.0784F,0));
            correction.setOnGround(true);correction.setPredictionType(PredictionType.PLAYER);
            correction.setVehicleRotation(Vector2f.ZERO);
            var outbound=s.process(true,s.encode(correction));
            assertInstanceOf(CorrectPlayerMovePredictionPacket.class,decode(outbound.forwarded()));
            assertFalse(s.state().teleports().getQueuedTeleports().peek().isAccepted());
            assertInstanceOf(NetworkStackLatencyPacket.class,decode(outbound.clientbound().get(outbound.clientbound().size()-1)));
            s.process(false,s.encode(input(11)));assertEquals(8,s.state().player().position().x);
            ack(s);var live=input(12);live.setPosition(Vector3f.from(10,65.62001F,8));
            var accepted=s.process(false,s.encode(live));
            assertEquals(1,accepted.backendbound().size());assertFalse(s.state().teleports().isTeleporting());
            assertEquals(0,s.state().authInputPackets().lastResult().rawOffset());
            assertEquals(10,s.state().player().position().x);
        }
    }
    private static NetherNetBatchSession session()throws Exception {
        return session(ac.navro.proxy.protocol.BackendType.BDS);
    }
    private static NetherNetBatchSession session(ac.navro.proxy.protocol.BackendType backendType)throws Exception {
        return session(backendType,false);
    }
    private static NetherNetBatchSession session(ac.navro.proxy.protocol.BackendType backendType, boolean smooth)throws Exception{
        var s=new NetherNetBatchSession(null,null,true, backendType,smooth);
        s.process(false,HexFormat.of().parseHex("06c101000008b1"));
        s.process(true,HexFormat.of().parseHex("0c8f0101000000000000000000"));
        var start=new StartGamePacket();start.setRuntimeEntityId(7);start.setUniqueEntityId(70);
        start.setPlayerPosition(Vector3f.from(8,64,8));s.state().observeBackend(start);
        var ready=new RespawnPacket();ready.setState(RespawnPacket.State.SERVER_READY);
        ready.setPosition(Vector3f.from(8,65.62001F,8));s.process(true,s.encode(ready));ack(s);
        var world=s.state().world();
        world.put(0,0,CompensatedWorld.CachedChunk.network(-64,320,24,world.airNetworkId()));
        int stone=NetworkBlockStateRegistry.forProtocol(2225,false).states().values().stream()
                .filter(b->b.is("minecraft:stone")&&b.blockData()==0).findFirst().orElseThrow().networkId();
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)world.updateBlock(x,63,z,0,stone);
        s.state().observeClient(new SetLocalPlayerAsInitializedPacket());
        s.state().player().inLoadingScreen=false;s.state().player().sinceLoadingScreen=0;
        s.state().player().setCollisionResult(false,false,true);
        s.state().player().setExempted(false);
        return s;
    }
    private static PlayerAuthInputPacket input(long tick){
        var p=AuthInputPacketsTest.input(tick,8,64,8,new Vec3(0,-.0784F,0));
        p.setPlayMode(ClientPlayMode.NORMAL);p.setInputInteractionModel(InputInteractionModel.CROSSHAIR);
        p.setAnalogMoveVector(Vector2f.ZERO);p.setCameraOrientation(Vector3f.ZERO);
        p.setInteractRotation(Vector2f.ZERO);return p;
    }
    private static BedrockPacket decode(byte[] batch)throws Exception{
        assertEquals(255,Byte.toUnsignedInt(batch[0]));var bytes=Unpooled.wrappedBuffer(batch,1,batch.length-1);
        try{int size=VarInts.readUnsignedInt(bytes);var frame=bytes.readSlice(size);int header=VarInts.readUnsignedInt(frame);
            var codec=SupportedCodecs.forProtocol(2225);var helper=codec.createHelper();
            new ac.navro.proxy.ProtocolDefinitions().configure(helper,true);
            return codec.tryDecode(helper,frame,header&1023);
        }finally{bytes.release();}
    }
    private static NetherNetBatchSession.Result ack(NetherNetBatchSession s)throws Exception{
        long id=s.state().player().getLatencyUtil().sentQueue().peekLast().id();
        var response=new NetworkStackLatencyPacket();response.setTimestamp(id*1_000_000L);
        return s.process(false,s.encode(response));
    }
    @Test void duplicateTickIsRemovedFromNativeBatchWhileUnrelatedActionStillForwards()throws Exception{
        try(var s=session()){
            var alerts=new ArrayList<String>();s.state().player().setAlertSink(alerts::add);
            var first=s.process(false,s.encode(input(0)));
            assertEquals(1,first.backendbound().size());
            assertInstanceOf(PlayerAuthInputPacket.class,decode(first.backendbound().get(0)));
            var action=new PlayerActionPacket();action.setAction(PlayerActionType.JUMP);
            action.setBlockPosition(Vector3i.ZERO);action.setResultPosition(Vector3i.ZERO);
            byte[] a=s.encode(input(0)),b=s.encode(action);var out=new ByteArrayOutputStream();out.write(a);out.write(b,1,b.length-1);
            var rejected=s.process(false,out.toByteArray());
            assertNull(rejected.forwarded());assertEquals(1,rejected.backendbound().size());
            assertInstanceOf(PlayerActionPacket.class,decode(rejected.backendbound().get(0)));
            assertEquals(1,alerts.size());assertTrue(alerts.get(0).contains("BadPacketsC"));
            assertEquals(0,s.state().player().tick());
        }
    }
    @Test void realCorrectionTransportBlocksInputUntilClosingAckAndTargetConfirmation()throws Exception{
        try(var s=session()){
            var initial=s.process(false,s.encode(input(0)));
            assertEquals(1,initial.backendbound().size());
            s.state().player().requestTeleportToLastValid();
            var outbound=s.tick();
            var move=assertInstanceOf(MovePlayerPacket.class,decode(outbound.clientbound().get(0)));
            assertEquals(MovePlayerPacket.Mode.TELEPORT,move.getMode());
            assertEquals(7,move.getRuntimeEntityId());assertEquals(Vector3f.from(8,65.62001F,8),move.getPosition());
            assertInstanceOf(NetworkStackLatencyPacket.class,decode(outbound.clientbound().get(outbound.clientbound().size()-1)));
            var waiting=s.process(false,s.encode(input(1)));assertTrue(waiting.backendbound().isEmpty());
            assertTrue(s.state().teleports().isTeleporting());
            ack(s);
            var confirmation=input(2);confirmation.setPosition(move.getPosition());confirmation.getInputData().add(PlayerAuthInputData.HANDLE_TELEPORT);
            var accepted=s.process(false,s.encode(confirmation));
            assertFalse(s.state().teleports().isTeleporting());assertEquals(1,accepted.backendbound().size());
            assertEquals(64,s.state().player().position().y);
        }
    }
    @Test void twoLayerBlockCorrectionUsesBackendFactsAndNativeAckTransport()throws Exception{
        try(var s=session()){
            var state=s.state();var pos=Vector3i.from(9,64,9);
            int stone=NetworkBlockStateRegistry.forProtocol(2225,false).states().values().stream()
                    .filter(b->b.is("minecraft:stone")&&b.blockData()==0).findFirst().orElseThrow().networkId();
            var chunk=new LevelChunkPacket();chunk.setChunkX(0);chunk.setChunkZ(0);
            chunk.setDimension(0);chunk.setSubChunksLength(1);
            var data=Unpooled.buffer();data.writeByte(9).writeByte(2).writeByte(4);
            for(int id:new int[]{stone,state.world().airNetworkId()}){
                data.writeByte(1);VarInts.writeInt(data,id);
            }
            chunk.setData(data);
            try{s.process(true,s.encode(chunk));}finally{chunk.release();}
            for(int layer=0;layer<2;layer++){
                int id=layer==0?stone:state.world().airNetworkId();
                var update=new UpdateBlockPacket();update.setBlockPosition(pos);update.setDataLayer(layer);
                update.setDefinition(new org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition(){public int getRuntimeId(){return id;}});
                s.process(true,s.encode(update));
            }
            ack(s);state.restoreCorrectBlock(pos);var outputs=s.tick().clientbound();
            assertEquals(3,outputs.size());
            for(int layer=0;layer<2;layer++){
                var update=assertInstanceOf(UpdateBlockPacket.class,decode(outputs.get(layer)));
                assertEquals(pos,update.getBlockPosition());assertEquals(layer,update.getDataLayer());
                assertEquals(layer==0?stone:state.world().airNetworkId(),update.getDefinition().getRuntimeId());
            }
        }
    }

    @Test void correctionInTheMarkersInputBatchUsesTheConfiguredBackendSemantics() throws Exception {
        for (var type : ac.navro.proxy.protocol.BackendType.values()) try (var s = session(type)) {
            s.process(false, s.encode(input(0)));
            var c = new CorrectPlayerMovePredictionPacket();
            c.setTick(0); c.setPosition(Vector3f.from(10,65.62001F,8));
            c.setDelta(Vector3f.from(0,-.0784F,0)); c.setOnGround(true);
            c.setPredictionType(PredictionType.PLAYER); c.setVehicleRotation(Vector2f.ZERO);
            s.process(true, s.encode(c));
            long id = s.state().player().getLatencyUtil().sentQueue().peekLast().id();
            var response = new NetworkStackLatencyPacket(); response.setTimestamp(id*1_000_000L);
            var live = input(1);
            if (type == ac.navro.proxy.protocol.BackendType.NAVRO) live.setPosition(c.getPosition());
            var result = s.process(false, combined(s, response, live));
            assertEquals(1, result.backendbound().size(), type+" "+s.state().anomalies()+" "+s.state().authInputPackets().lastResult());
            if (type == ac.navro.proxy.protocol.BackendType.NAVRO) {
                assertEquals(10, s.state().player().position().x);
                assertEquals(0, s.state().authInputPackets().lastResult().rawOffset());
                assertFalse(s.state().teleports().isTeleporting());
            } else {
                assertEquals(8, s.state().player().position().x);
                assertTrue(s.state().teleports().getQueuedTeleports().peek().isAccepted());
            }
        }
    }

    @Test void nethernetDoesNotGiveNonBdsServersBdsRespawnCoordinatesOrSprintAttributes() throws Exception {
        for (var type : ac.navro.proxy.protocol.BackendType.values()) try (var s = session(type)) {
            var ready = new RespawnPacket(); ready.setState(RespawnPacket.State.SERVER_READY);
            ready.setPosition(Vector3f.from(8,81.62001F,8));
            s.process(true, s.encode(ready)); ack(s);
            assertEquals(type == ac.navro.proxy.protocol.BackendType.BDS ? 80 : 64,
                    s.state().player().position().y);
            var speed = new UpdateAttributesPacket(); speed.setRuntimeEntityId(7); speed.setTick(0);
            speed.getAttributes().add(new AttributeData("minecraft:movement",0,Float.MAX_VALUE,.2F,.1F));
            s.process(true, s.encode(speed)); ack(s);
            var sprint = input(1); sprint.setMotion(Vector2f.from(0,1));
            sprint.setRawMoveVector(Vector2f.from(0,1));
            sprint.getInputData().add(PlayerAuthInputData.START_SPRINTING);
            s.state().player().apply(sprint, null, null);


            assertEquals(type == ac.navro.proxy.protocol.BackendType.BDS ? .26F : .13F,
                    s.state().player().attributes().get("minecraft:movement").getValue());
        }
    }

    @Test void nativeSmoothSetbackUsesTheRealNethernetSenderWithoutAHandleTeleportGate() throws Exception {
        try (var s = session(ac.navro.proxy.protocol.BackendType.BDS,true)) {
            for(int tick=0;tick<=10;tick++)s.process(false,s.encode(input(tick)));
            var bad=input(11);bad.setPosition(Vector3f.from(80,65.62001F,8));
            var rejected=s.process(false,s.encode(bad));
            assertEquals(2,rejected.clientbound().size());
            var correction=assertInstanceOf(CorrectPlayerMovePredictionPacket.class,decode(rejected.clientbound().get(0)));
            assertEquals(11,correction.getTick());assertEquals(Vector3f.from(8,65.62001F,8),correction.getPosition());
            assertFalse(s.state().teleports().isHardTeleporting());
            ack(s);
            var resumed=s.process(false,s.encode(input(12)));
            assertEquals(1,resumed.backendbound().size());assertFalse(s.state().teleports().isTeleporting());
            assertEquals(0,s.state().authInputPackets().lastResult().rawOffset());
        }
    }

    private static Vector3f capturedVector(String value) {
        var parts=value.substring(1,value.length()-1).split(", ");
        return Vector3f.from(Float.parseFloat(parts[0]),Float.parseFloat(parts[1]),Float.parseFloat(parts[2]));
    }
    private static PlayerAuthInputPacket capturedInput(com.google.gson.JsonObject row) {
        var packet=input(row.get("tick").getAsLong());packet.setPosition(capturedVector(row.get("position").getAsString()));
        packet.setDelta(capturedVector(row.get("delta").getAsString()));packet.setRotation(capturedVector(row.get("rotation").getAsString()));
        packet.getInputData().clear();String flags=row.get("input").getAsString();
        for(String flag:flags.substring(1,flags.length()-1).split(", "))if(!flag.isBlank())packet.getInputData().add(PlayerAuthInputData.valueOf(flag));
        return packet;
    }
    private static byte[] combined(NetherNetBatchSession session,BedrockPacket...packets)throws Exception {
        var out=new ByteArrayOutputStream();boolean first=true;
        for(var packet:packets){byte[] data=session.encode(packet);out.write(data,first?0:1,data.length-(first?0:1));first=false;}
        return out.toByteArray();
    }
    @Test void recordedContinuousInputsBesideCorrectionAckWaitUntilTheNextBatch()throws Exception {
        try(var in=new java.io.InputStreamReader(getClass().getResourceAsStream("/recorded-correction-ack-input-batches.json"))){
            var cases=com.google.gson.JsonParser.parseReader(in).getAsJsonObject().getAsJsonArray("cases");assertEquals(3,cases.size());
            for(var item:cases)try(var session=session()){
                var row=item.getAsJsonObject();long start=row.get("startTick").getAsLong();
                var previous=capturedInput(row.getAsJsonObject("precedingInput"));


                int stone=NetworkBlockStateRegistry.forProtocol(2225,false).states().values().stream()
                        .filter(b->b.is("minecraft:stone")&&b.blockData()==0).findFirst().orElseThrow().networkId();
                session.state().world().put(-1,0,CompensatedWorld.CachedChunk.network(-64,320,24,session.state().world().airNetworkId()));
                for(int x=-16;x<16;x++)for(int z=0;z<16;z++)session.state().world().updateBlock(x,-61,z,0,stone);
                session.state().player().apply(previous,session.state().world(),null,1.62001F);
                session.state().player().sinceLoadingScreen=20;
                var box=row.getAsJsonArray("initialBox");var initial=new ac.navro.anticheat.util.math.Box(box.get(0).getAsFloat(),box.get(1).getAsFloat(),box.get(2).getAsFloat(),box.get(3).getAsFloat(),box.get(4).getAsFloat(),box.get(5).getAsFloat());
                session.state().player().setResolvedPosition(initial.feetCenter(),initial,false);
                session.state().player().setVelocity(new Vec3(previous.getDelta()));
                boolean initialGround = previous.getInputData().contains(PlayerAuthInputData.VERTICAL_COLLISION)
                        && previous.getDelta().getY() < 0;
                session.state().player().setCollisionResult(false,initialGround,initialGround);
                var alerts=new ArrayList<String>();session.state().player().setAlertSink(alerts::add);
                var replayClock = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
                long replayStart = replayClock.get();
                var firstArrival = row.getAsJsonArray("events").asList().stream()
                        .map(e -> e.getAsJsonObject())
                        .filter(e -> "motion_input_raw".equals(e.get("event").getAsString()))
                        .findFirst().orElseThrow();
                var firstTime = java.time.Instant.parse(firstArrival.get("time").getAsString());
                ac.navro.proxy.check.impl.timer.RecordedArrivalClock.install(session.state().player(), replayClock::get);
                Long motionAck=null,correctionAck=null;
                var motion=row.get("motion").isJsonNull()?null:row.getAsJsonObject("motion");
                for(var inputRow:row.getAsJsonArray("inputs")){
                    var current=capturedInput(inputRow.getAsJsonObject());long tick=current.getTick();
                    var arrival = row.getAsJsonArray("events").asList().stream().map(e -> e.getAsJsonObject())
                            .filter(e -> "motion_input_raw".equals(e.get("event").getAsString())
                                    && e.get("tick").getAsLong() == tick).findFirst().orElseThrow();
                    replayClock.set(replayStart + java.time.Duration.between(firstTime,
                            java.time.Instant.parse(arrival.get("time").getAsString())).toNanos());
                    NetherNetBatchSession.Result result;
                    if(correctionAck!=null&&tick==row.get("correctionAckSharesInput").getAsLong()){
                        var ack=new NetworkStackLatencyPacket();ack.setTimestamp(correctionAck*1_000_000L);
                        result=session.process(false,combined(session,ack,current));
                        assertTrue(session.state().teleports().getQueuedTeleports().peek().isAccepted());
                    }else result=session.process(false,session.encode(current));
                    assertEquals(1,result.backendbound().size(),"Recorded input must forward at "+tick+
                            ", alerts="+alerts+", anomalies="+session.state().anomalies()+
                            ", hard="+session.state().teleports().isHardTeleporting()+
                            ", result="+session.state().authInputPackets().lastResult());
                    if(!session.state().teleports().isTeleporting())
                        assertTrue(session.state().authInputPackets().lastResult().rawOffset()<1e-4F,"pre-acceptance residual at "+tick);
                    if(motion!=null&&tick==motion.get("tick").getAsLong()){
                        var packet=new SetEntityMotionPacket();packet.setRuntimeEntityId(7);packet.setTick(motion.get("packetTick").getAsLong());var v=motion.getAsJsonObject("motion");packet.setMotion(Vector3f.from(v.get("x").getAsFloat(),v.get("y").getAsFloat(),v.get("z").getAsFloat()));
                        session.process(true,session.encode(packet));motionAck=session.state().player().getLatencyUtil().sentQueue().peekLast().id();
                        ac.navro.proxy.check.impl.timer.RecordedArrivalClock.sentAt(session.state().player(),
                                replayStart + java.time.Duration.between(firstTime,
                                        java.time.Instant.parse(motion.get("time").getAsString())).toNanos());
                    }
                    if(motionAck!=null&&tick==row.get("motionAckAfterInput").getAsLong()){
                        var ack=new NetworkStackLatencyPacket();ack.setTimestamp(motionAck*1_000_000L);session.process(false,session.encode(ack));motionAck=null;
                    }
                    if(tick==row.get("correctionReceivedAfterInput").getAsLong()){
                        var c=row.getAsJsonObject("correction");var packet=new CorrectPlayerMovePredictionPacket();packet.setTick(c.get("sourceTick").getAsLong());packet.setPosition(capturedVector(c.get("position").getAsString()));packet.setDelta(capturedVector(c.get("delta").getAsString()));packet.setOnGround(c.get("onGround").getAsBoolean());packet.setPredictionType(PredictionType.PLAYER);packet.setVehicleRotation(Vector2f.ZERO);
                        session.process(true,session.encode(packet));correctionAck=session.state().player().getLatencyUtil().sentQueue().peekLast().id();
                        ac.navro.proxy.check.impl.timer.RecordedArrivalClock.sentAt(session.state().player(),
                                replayStart + java.time.Duration.between(firstTime,
                                        java.time.Instant.parse(c.get("time").getAsString())).toNanos());
                    }
                }
                assertTrue(alerts.isEmpty(),alerts.toString());
            }
        }
    }
}
