package ac.navro.proxy.nethernet;

import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual native batch boundary, independent BDS handshake bytes and explicit
 * ACK invariants. No second model of the movement or ACK state machine.
 */
class NetherNetBatchSessionTest {
    private static PlayerActionPacket action(){
        var p=new PlayerActionPacket();p.setAction(org.cloudburstmc.protocol.bedrock.data.PlayerActionType.JUMP);
        p.setBlockPosition(org.cloudburstmc.math.vector.Vector3i.ZERO);p.setResultPosition(org.cloudburstmc.math.vector.Vector3i.ZERO);return p;
    }
    private static byte[] hex(String s){return HexFormat.of().parseHex(s);}
    private static void negotiate(NetherNetBatchSession s)throws Exception{
        assertEquals(List.of("RequestNetworkSettingsPacket"),s.process(false,hex("06c101000008b1")).packets());
        // Actual direct BDS 1.26.63 probe response, saved in verification logs.
        assertEquals(List.of("NetworkSettingsPacket"),s.process(true,hex("0c8f0101000000000000000000")).packets());
    }
    @Test void remoteEntityRegistryAndMotionUseRealNetherNetFramesAndClosingAcknowledgment() throws Exception {
        try (var session = new NetherNetBatchSession(null)) {
            negotiate(session);
            var start = new StartGamePacket();
            start.setRuntimeEntityId(7);
            start.setPlayerPosition(org.cloudburstmc.math.vector.Vector3f.from(8, 64, 8));
            session.state().observeBackend(start);
            var identifiers = new AvailableEntityIdentifiersPacket();
            identifiers.setIdentifiers(org.cloudburstmc.nbt.NbtMap.builder().putList("idlist", org.cloudburstmc.nbt.NbtType.COMPOUND,
                    List.of(org.cloudburstmc.nbt.NbtMap.builder().putString("id", "minecraft:minecart").putInt("rid", 84).build())).build());
            session.process(true, session.encode(identifiers));
            var actor = new AddEntityPacket();
            actor.setRuntimeEntityId(8);
            actor.setUniqueEntityId(80);
            actor.setIdentifier("minecraft:minecart");
            actor.setPosition(org.cloudburstmc.math.vector.Vector3f.from(1, 64, 1));
            actor.setMotion(org.cloudburstmc.math.vector.Vector3f.ZERO);
            actor.setRotation(org.cloudburstmc.math.vector.Vector2f.ZERO);
            actor.getMetadata().put(EntityDataTypes.WIDTH, .98F);
            actor.getMetadata().put(EntityDataTypes.HEIGHT, .7F);
            session.process(true, session.encode(actor));
            assertTrue(session.state().entity(8).isMinecart());

            var motion = new SetEntityMotionPacket();
            motion.setRuntimeEntityId(8);
            motion.setMotion(org.cloudburstmc.math.vector.Vector3f.from(.5F, .25F, -.5F));
            byte[] encoded = session.encode(motion);
            var forwarded = session.process(true, encoded);
            assertArrayEquals(encoded, forwarded.forwarded());
            assertEquals(0, session.state().entity(8).motion().x());
            long marker = session.state().player().getLatencyUtil().sentQueue().peekLast().id();
            var acknowledgment = new NetworkStackLatencyPacket();
            acknowledgment.setTimestamp(marker * 1_000_000L);
            session.process(false, session.encode(acknowledgment));
            assertEquals(new ac.navro.proxy.state.WireVec3(.5F, .25F, -.5F), session.state().entity(8).motion());
        }
    }
    @Test void actualBdsNetherNetBatchesNegotiateWithoutAnyRaknetFraming()throws Exception{
        try(var s=new NetherNetBatchSession(null)){
            negotiate(s);assertEquals(2225,s.state().player().protocolVersion());
            assertEquals(1,s.counts().get("backend:NetworkSettingsPacket"));
            assertFalse(s.state().detectionEnabled());
            assertThrows(IOException.class,()->s.process(false,hex("fe06c101000008b1")));
        }
    }
    @Test void backendStateWaitsForItsNativeClosingAckAndMixedClientPayloadSurvives()throws Exception{
        try(var s=new NetherNetBatchSession(null)){
            negotiate(s);
            var start=new StartGamePacket();start.setRuntimeEntityId(7);
            start.setPlayerPosition(org.cloudburstmc.math.vector.Vector3f.from(8,64,8));
            s.state().observeBackend(start); // external established-actor fixture
            var data=new SetEntityDataPacket();data.setRuntimeEntityId(7);data.getMetadata().put(EntityDataTypes.HEIGHT,.6F);
            var result=s.process(true,s.encode(data));
            assertEquals(1,result.clientbound().size());
            assertEquals(1.8F,s.state().player().dimensions().height());
            long marker=s.state().player().getLatencyUtil().sentQueue().peekLast().id();
            var ack=new NetworkStackLatencyPacket();ack.setTimestamp(marker*1_000_000L);
            var action=action();
            byte[] a=s.encode(ack),b=s.encode(action);
            var mixed=new ByteArrayOutputStream();mixed.write(a);mixed.write(b,1,b.length-1);
            var response=s.process(false,mixed.toByteArray());
            assertEquals(.6F,s.state().player().dimensions().height());
            assertEquals(List.of("NetworkStackLatencyPacket","PlayerActionPacket"),response.packets());
            assertArrayEquals(b,response.forwarded(),"Consume only proxy marker, preserve the rest of the real batch");
        }
    }
    @Test void compressedNativeBatchIsDecodedByTheRealPacketPipelineAndOriginalBytesPreserved()throws Exception{
        try(var s=new NetherNetBatchSession(null)){
            negotiate(s);var action=action();
            byte[] plain=s.encode(action);var deflate=new Deflater(6,true);deflate.setInput(plain,1,plain.length-1);deflate.finish();
            var out=new ByteArrayOutputStream();out.write(0);byte[] buf=new byte[1024];
            while(!deflate.finished()){int n=deflate.deflate(buf);out.write(buf,0,n);}deflate.end();
            byte[] compressed=out.toByteArray();var result=s.process(false,compressed);
            assertEquals(List.of("PlayerActionPacket"),result.packets());assertArrayEquals(compressed,result.forwarded());
            assertThrows(IOException.class,()->s.process(false,new byte[]{1,0}));
        }
    }
    @Test void missingTransportIdentityCannotBecomeAuthenticatedBySendingALogin()throws Exception{
        try(var s=new NetherNetBatchSession(null)){
            negotiate(s);
            var login=new LoginPacket();login.setProtocolVersion(2225);
            // Drive the same verifier through the decoded packet boundary. A
            // guest payload cannot supply the missing DTLS key binding.
            login.setAuthPayload(new org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload(
                    "synthetic-rejected-guest-token",org.cloudburstmc.protocol.bedrock.data.auth.AuthType.GUEST));
            login.setClientJwt("invalid");
            var e=assertThrows(IOException.class,()->s.process(false,s.encode(login)));
            assertTrue(e.getMessage().contains("authenticated transport identity"));
            assertFalse(s.authenticated());
        }
    }
    @Test void frontendBlobCacheIsDisabledOnTheIndependentBackendConnection()throws Exception{
        try(var s=new NetherNetBatchSession(null,null,true)){
            negotiate(s);
            var request=new ClientCacheStatusPacket();request.setSupported(true);
            var result=s.process(false,s.encode(request));
            assertNull(result.forwarded());
            assertEquals(1,result.backendbound().size());
            var bytes=result.backendbound().get(0);
            assertEquals(255,Byte.toUnsignedInt(bytes[0]));
            var buffer=io.netty.buffer.Unpooled.wrappedBuffer(bytes,1,bytes.length-1);
            try {
                int size=org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(buffer);
                var frame=buffer.readSlice(size);int header=org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(frame);
                var packet=ac.navro.proxy.SupportedCodecs.forProtocol(2225).tryDecode(
                        ac.navro.proxy.SupportedCodecs.forProtocol(2225).createHelper(),frame,header&1023);
                var cache=assertInstanceOf(ClientCacheStatusPacket.class,packet);
                assertFalse(cache.isSupported(),"Backend must send full section data into the proxy's independent world cache");
                io.netty.util.ReferenceCountUtil.safeRelease(packet);
            } finally { buffer.release(); }
        }
    }
    @Test void geyserBridgeDoesNotSendThePrivateBackendHelloOnSpawn() throws Exception {
        var backendType=ac.navro.proxy.protocol.BackendType.NAVRO;
        try(var geyser=new NetherNetBatchSession(null,null,true,backendType,false,false);
            var nativeBackend=new NetherNetBatchSession(null,null,true,backendType,false)) {
            for(var session:List.of(geyser,nativeBackend)) {
                negotiate(session);
                var start=new StartGamePacket();start.setRuntimeEntityId(7);
                start.setPlayerPosition(org.cloudburstmc.math.vector.Vector3f.from(8,64,8));
                session.state().observeBackend(start);
                assertTrue(session.state().detectionEnabled());
                assertEquals(7,session.state().player().runtimeId());
            }
            assertTrue(geyser.tick().backendbound().isEmpty());
            assertEquals(1,nativeBackend.tick().backendbound().size());
        }
    }
    @Test void reservedBackendClockFramesAreConsumedAndCannotBeInjectedByTheClient() throws Exception {
        try(var session=new NetherNetBatchSession(null)) {
            negotiate(session);
            var hello=session.state().backendSync().begin();String nonce=hello.getMessage().substring(6);
            var sample=new ScriptMessagePacket();sample.setChannel(ac.navro.proxy.backend.BackendStateSync.CHANNEL);
            sample.setMessage("tick|"+nonce+"|0|765");
            var rejected=session.process(false,session.encode(sample));
            assertNull(rejected.forwarded());assertTrue(rejected.backendbound().isEmpty());
            assertFalse(session.state().world().hasNativeServerTick());
            var consumed=session.process(true,session.encode(sample));assertNull(consumed.forwarded());
            assertTrue(consumed.clientbound().isEmpty());assertEquals(765,session.state().backendSync().tick());
            assertTrue(session.state().world().hasNativeServerTick());
        }
    }
}
