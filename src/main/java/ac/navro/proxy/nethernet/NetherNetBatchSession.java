package ac.navro.proxy.nethernet;

import ac.navro.proxy.*;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry.RuntimePalette;
import ac.navro.proxy.listener.PacketListener;
import ac.navro.proxy.packets.other.NetworkLatencyPackets;
import ac.navro.proxy.packets.server.ServerChunkPackets;
import ac.navro.proxy.permission.ProtocolPermissions;
import ac.navro.proxy.command.NavroCommand;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.protocol.bedrock.codec.*;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.util.VarInts;
import java.io.*;
import java.net.InetAddress;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.*;


public final class NetherNetBatchSession implements AutoCloseable {
    public static final int MAX_BATCH=16*1024*1024;
    private static final AtomicLong MARKER=new AtomicLong(700_000_000L);
    public record Result(byte[] forwarded,List<byte[]> clientbound,List<byte[]> backendbound,
                         List<String> packets,boolean authenticated) { }
    private final SessionState state;
    private final ac.navro.proxy.bds.BdsCorrectionAcknowledgments correctionAcknowledgments = new ac.navro.proxy.bds.BdsCorrectionAcknowledgments();
    private final ProtocolDefinitions definitions=new ProtocolDefinitions();
    private final ProtocolPermissions permissions=new ProtocolPermissions();
    private final List<Runnable> pending=new ArrayList<>();
    private final List<byte[]> clientQueue=new ArrayList<>(),backendQueue=new ArrayList<>();
    private final java.util.Queue<String> messages=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final PublicKey transportKey;
    private final KeyPair backendKey;
    private final InetAddress forwardedPeer;
    private final boolean detect;
    private BedrockCodec codec;
    private Map<Integer, RuntimePalette> runtimePalettes = Map.of();
    private BedrockCodecHelper clientHelper,backendHelper;
    private boolean compressed,authenticated,closed;
    private boolean operatorCommandTable;
    private boolean commandTableVisible;
    private Set<String> proxyCommandNames = NavroCommand.NAMES;
    private byte[] withheldCommandTable;
    private String backendDisconnect;
    private UUID identity;
    private final Consumer<String> alertRecipient=this::sendMessage;
    private final Map<String,Long> counts=new TreeMap<>();
    public NetherNetBatchSession(PublicKey transportKey){this(transportKey,null,false);}
    public NetherNetBatchSession(PublicKey transportKey,KeyPair backendKey){this(transportKey,backendKey,false);}
    public NetherNetBatchSession(PublicKey transportKey,KeyPair backendKey,boolean detect){
        this(transportKey, backendKey, detect, ac.navro.proxy.protocol.BackendType.NAVRO);
    }
    public NetherNetBatchSession(PublicKey transportKey, KeyPair backendKey, boolean detect,
                                 ac.navro.proxy.protocol.BackendType backendType) {
        this(transportKey, backendKey, detect, backendType, false);
    }
    public NetherNetBatchSession(PublicKey transportKey, KeyPair backendKey, boolean detect,
                                 ac.navro.proxy.protocol.BackendType backendType, boolean smoothSetback) {
        this(transportKey, backendKey, detect, backendType, smoothSetback, true);
    }
    NetherNetBatchSession(PublicKey transportKey, KeyPair backendKey, boolean detect,
                          ac.navro.proxy.protocol.BackendType backendType, boolean smoothSetback,
                          boolean backendStateSync) {
        this(transportKey, backendKey, detect, backendType, smoothSetback, backendStateSync, null);
    }

    NetherNetBatchSession(PublicKey transportKey, KeyPair backendKey, boolean detect,
                          ac.navro.proxy.protocol.BackendType backendType, boolean smoothSetback,
                          boolean backendStateSync, InetAddress forwardedPeer) {
        this.forwardedPeer = forwardedPeer;
        this.state = new SessionState(backendType);
        state.setSmoothSetback(smoothSetback);
        this.transportKey=transportKey;this.backendKey=backendKey;this.detect=detect;
        state.setDetectionEnabled(detect);
        if (backendStateSync) state.setBackendSyncTransport(this::queueBackend);
        if (backendType == ac.navro.proxy.protocol.BackendType.BDS)
            state.setBdsCorrectionAcknowledgmentDispatcher(correctionAcknowledgments::defer);
        state.player().setTransportAcknowledgment((packet,task)->pending.add(task));
        if(detect){
            state.player().setFormattedAlertSink(message -> {
                NetherNetAlerts.ALERTS.formattedAlert(message);
                state.forwardBackendAlert(message);
            });
            state.player().setAlertSink(message -> state.player().formattedAlert(NetherNetAlerts.ALERTS.getPrefix() + "§3" + message));
            state.player().setDisconnectSink(this::kick);
            state.player().setCorrectionRequestSink(message->System.out.println("[NetherNet] "+message));
            state.player().setMaxBalanceAdvantageMs(8500);
            state.setTeleportTransport(packet->{
                if(!ready())return false;
                state.onProxyTeleport(packet);queueClient(packet);return true;
            });
            state.setPredictionMotionTransport(packet->{if(!ready())return false;queueClient(packet);return true;});
            state.setSoftCorrectionTransport(packet->{
                if(!ready())return false;
                state.onProxyPredictionCorrection(packet);queueClient(packet);return true;
            });
            state.player().setTimerTeleport(()->state.teleports().teleport(state.teleports().getLastKnowValid()));
            state.setClientStateCorrectionTransport(this::queueClient);
            state.setBlockCorrectionTransport((position,blocks)->ac.navro.proxy.util.block.BlockUtil.restoreCorrectBlock(
                    packet->{state.onProxyBlockCorrection(packet);queueClient(packet);},position,
                    definitions.blockDefinition(blocks.layerZero()),definitions.blockDefinition(blocks.layerOne())));
            state.placementPipeline().setTransports(packet->{
                state.onClientPacketForwarded(packet);queueBackend(packet);return true;
            },this::queueClient);
            state.player().setWorldDiagnostics(new ac.navro.proxy.diagnostics.WorldCollisionDiagnostics(
                    state.player(),state.world(),UUID.randomUUID().toString(),
                    ac.navro.proxy.diagnostics.WorldCollisionDiagnostics.fileSink()));
        }
    }
    private boolean ready(){return !closed&&codec!=null&&state.player().runtimeId()>=0;}
    public SessionState state(){return state;}
    public Map<String,Long> counts(){return Map.copyOf(counts);}
    public boolean authenticated(){return authenticated;}
    public String backendDisconnect(){return backendDisconnect;}
    public String mode(){return detect?"DETECT":"INSPECT";}
    void setRuntimePalettes(Map<Integer, RuntimePalette> palettes) { runtimePalettes = palettes; }
    void setOperatorCommandTable(boolean restrict) { operatorCommandTable = restrict; }
    private void protocol(int version)throws IOException{
        codec=SupportedCodecs.forProtocol(version);
        if(codec==null)throw new IOException("Unsupported NetherNet Bedrock protocol "+version);
        state.setProtocolVersion(version);state.player().setProtocolVersion(version);
        state.setRuntimeBlockPalette(runtimePalettes.get(version));
        clientHelper=codec.createHelper();backendHelper=codec.createHelper();
        definitions.configure(clientHelper,false);definitions.configure(backendHelper,true);
    }
    public Result process(boolean backend,byte[] batch)throws Exception{
        if(closed)throw new IOException("Session closed");
        if(state.player().disconnectRequested())throw new IOException("Player disconnected by Navro punishment");
        byte[] payload=unpack(batch);
        var stream=Unpooled.wrappedBuffer(payload);var kept=Unpooled.buffer();
        var names=new ArrayList<String>();boolean changed=false;
        byte[] forwarded;
        try{
            while(stream.isReadable()){
                if(state.player().disconnectRequested()){changed=true;break;}
                int size=VarInts.readUnsignedInt(stream);
                if(size<=0||size>stream.readableBytes())throw new IOException("Invalid NetherNet packet frame size");
                var frame=stream.readSlice(size);var original=frame.duplicate();boolean keep=true;
                byte[] replacement=null, commandTableUpdate=null;
                int header=VarInts.readUnsignedInt(frame),id=header&1023;
                if((header>>>10)!=0)throw new IOException("NetherNet subclients are not supported yet");
                if(codec==null){
                    if(backend||id!=193||frame.readableBytes()!=4)throw new IOException("Expected RequestNetworkSettings first");
                    protocol(frame.getInt(frame.readerIndex()));
                }
                var packet=codec.tryDecode(backend?backendHelper:clientHelper,frame,id);
                try{
                    if(frame.isReadable())throw new IOException("Trailing bytes in "+packet.getClass().getSimpleName());
                    names.add(packet.getClass().getSimpleName());
                    counts.merge((backend?"backend:":"client:")+packet.getClass().getSimpleName(),1L,Long::sum);
                    if(ac.navro.proxy.backend.BackendStateSync.privatePacket(packet)) {
                        if(backend)state.consumeBackendSync(packet);
                        keep=false;
                    }else if(backend){
                        if (packet instanceof StartGamePacket start && !runtimePalettes.isEmpty()
                                && !start.isBlockNetworkIdsHashed() && (start.getBlockPalette() == null || start.getBlockPalette().isEmpty())
                                && !runtimePalettes.containsKey(state.world().protocolVersion()) && !state.backendSync().hasRuntimePalette())
                            throw new IOException("No runtime block palette for protocol " + state.world().protocolVersion());
                        String violation=state.validateBackend(packet);
                        if(violation!=null)throw new IOException("Invalid backend packet: "+violation);
                        if(packet instanceof DisconnectPacket disconnect)
                            backendDisconnect=String.valueOf(disconnect.getReason())+": "+disconnect.getKickMessage();
                        if(packet instanceof NetworkSettingsPacket settings){
                            if(settings.getCompressionAlgorithm()!=PacketCompressionAlgorithm.ZLIB)
                                throw new IOException("NetherNet adapter requires ZLIB compression");
                            compressed=true;
                        }
                        if(packet instanceof NetworkStackLatencyPacket latency)NetworkLatencyPackets.onPacketSend(state.player(),latency);
                        definitions.observeBackend(packet);
                        PacketListener.onPacketSent(state.player(),packet);
                        state.observeBackend(packet);state.onBackendPacketQueued(packet);
                        commandTableUpdate = observeCommandPermissions(packet);
                        if (packet instanceof AvailableCommandsPacket commands) {
                            if (detect) proxyCommandNames = state.isPocketMineBackend()
                                    ? NavroCommand.unclaimedNames(commands) : NavroCommand.advertise(commands);
                            if (operatorCommandTable && !state.isPocketMineBackend()) {
                                keep = permissions.isOperator();
                                commandTableVisible = keep;
                                if (!keep) withheldCommandTable = encodeFrame(commands);
                            }
                        }
                        if(keep&&(packet instanceof NetworkStackLatencyPacket||detect&&(
                                packet instanceof AvailableCommandsPacket && !state.isPocketMineBackend()
                                ||
                                packet instanceof MovePlayerPacket move&&move.getRuntimeEntityId()==state.player().runtimeId()
                                ||packet instanceof SetEntityMotionPacket motion&&motion.getRuntimeEntityId()==state.player().runtimeId()
                                ||packet instanceof MovementEffectPacket effect&&effect.getEntityRuntimeId()==state.player().runtimeId())))
                            replacement=encodeFrame(packet);
                    }else{
                        if(forwardedPeer!=null&&!authenticated&&!(packet instanceof LoginPacket)
                                &&!(packet instanceof RequestNetworkSettingsPacket))
                            throw new IOException("NetherNet packet received before frontend login");




                        if (packet instanceof ClientCacheStatusPacket cache) {
                            cache.setSupported(false);
                            replacement=encodeFrame(packet);
                        }
                        if(packet instanceof LoginPacket login){var translated=verifyLogin(login);if(translated!=null)replacement=encodeFrame(translated);}
                        boolean ownLatency=packet instanceof NetworkStackLatencyPacket latency&&NetworkLatencyPackets.onPacket(state.player(),latency);
                        if(packet instanceof ServerboundLoadingScreenPacket loading)ServerChunkPackets.finishLoadingScreen(state.player(),loading);
                        if(detect){
                            keep=state.receiveClient(packet,8500)&&!ownLatency;
                            if(keep&&packet instanceof CommandRequestPacket command&&identity!=null){
                                keep=!NavroCommand.handle(command.getCommand(),new NavroCommand.Sender(){
                                    public UUID identity(){return identity;}
                                    public String name(){return state.player().getName();}
                                    public boolean hasPermission(String node){return NetherNetAlerts.PERMISSIONS.hasPermission(identity,node,permissions.isOperator());}
                                    public void sendMessage(String message){NetherNetBatchSession.this.sendMessage(message);}
                                },NetherNetAlerts.ALERTS,NetherNetAlerts.commands(),proxyCommandNames);
                            }
                            if(state.player().disconnectRequested())keep=false;
                            if(keep){
                                state.onClientPacketForwarded(packet);
                                if(packet instanceof PlayerAuthInputPacket||packet instanceof InventoryTransactionPacket)replacement=encodeFrame(packet);
                            }
                        }else{state.observeClient(packet);keep=!ownLatency;}
                    }
                }finally{ReferenceCountUtil.safeRelease(packet);}
                if(replacement!=null)changed=true;
                if(!keep){changed=true;continue;}
                if(detect&&!backend){


                    byte[] bytes=replacement;
                    if(bytes==null){bytes=new byte[size];original.readBytes(bytes);}
                    backendQueue.add(wrapFrame(bytes));changed=true;
                }else{
                    VarInts.writeUnsignedInt(kept,replacement==null?size:replacement.length);
                    if(replacement==null)kept.writeBytes(original);else kept.writeBytes(replacement);
                    if (commandTableUpdate != null) {
                        VarInts.writeUnsignedInt(kept, commandTableUpdate.length);
                        kept.writeBytes(commandTableUpdate);
                        changed = true;
                    }
                }
            }
            if(state.player().disconnectRequested())backendQueue.clear();
            correctionAcknowledgments.commit();
            if(!changed)forwarded=batch;
            else if(!kept.isReadable())forwarded=null;
            else{
                forwarded=new byte[kept.readableBytes()+(compressed?1:0)];
                if(compressed)forwarded[0]=(byte)255;
                kept.readBytes(forwarded,compressed?1:0,kept.readableBytes());
            }
        }finally{stream.release();kept.release();}
        return flush(forwarded,names,false);
    }
    private byte[] observeCommandPermissions(BedrockPacket packet) throws Exception {
        boolean wasOperator = permissions.isOperator();
        permissions.observe(state.player(), packet);
        if (!operatorCommandTable || state.isPocketMineBackend()) return null;
        if (packet instanceof StartGamePacket) {
            withheldCommandTable = null;
            commandTableVisible = false;
            return null;
        }
        boolean isOperator = permissions.isOperator();
        if (isOperator == wasOperator) return null;
        if (isOperator && withheldCommandTable != null) {
            byte[] table = withheldCommandTable;
            withheldCommandTable = null;
            commandTableVisible = true;
            return table;
        }
        if (!isOperator && commandTableVisible) {
            commandTableVisible = false;
            return encodeFrame(new AvailableCommandsPacket());
        }
        return null;
    }


    public Result tick()throws Exception{
        if(closed)throw new IOException("Session closed");
        if(state.player().disconnectRequested())throw new IOException("Player disconnected by Navro punishment");
        if(detect&&authenticated&&ready()){
            state.placementTick();
            var oldest=state.player().getLatencyUtil().sentQueue().peekFirst();long now=System.currentTimeMillis();
            if(oldest!=null&&now-oldest.ms()>state.maxLatencyWait()
                    &&now-state.player().getLatencyUtil().prevAcceptedTime>state.maxLatencyWait())
                state.player().requestDisconnect(state.configuredMessage("disconnect-timeout",Map.of()));
        }
        return flush(null,List.of(),detect&&authenticated&&ready()&&state.player().getLatencyUtil().sentQueue().isEmpty());
    }
    private Result flush(byte[] forwarded,List<String> names,boolean keepalive)throws Exception{
        var extra=new ArrayList<>(clientQueue);clientQueue.clear();
        for(String message;(message=messages.poll())!=null;){
            var packet=new TextPacket();packet.setType(TextPacket.Type.RAW);packet.setMessage(message);packet.setNeedsTranslation(false);
            packet.setSourceName("");packet.setXuid("");packet.setPlatformChatId("");extra.add(encode(packet));
        }
        if(!state.player().disconnectRequested()&&codec!=null&&(!pending.isEmpty()||!extra.isEmpty()||keepalive)){
            var tasks=List.copyOf(pending);pending.clear();
            long id=MARKER.updateAndGet(v->v>=999_000_000L?700_000_000L:v+1);
            state.player().getLatencyUtil().queue(id,true,()->tasks.forEach(Runnable::run));
            var marker=new NetworkStackLatencyPacket();marker.setTimestamp(id);marker.setFromServer(true);extra.add(encode(marker));
        }
        var upstream=List.copyOf(backendQueue);backendQueue.clear();
        return new Result(forwarded,List.copyOf(extra),upstream,List.copyOf(names),authenticated);
    }
    private void queueClient(BedrockPacket packet){try{clientQueue.add(encode(packet));}catch(Exception e){throw new IllegalStateException("Cannot encode NetherNet correction",e);}}
    private void queueBackend(BedrockPacket packet){try{backendQueue.add(encode(packet));}catch(Exception e){throw new IllegalStateException("Cannot encode deferred NetherNet transaction",e);}}
    private void sendMessage(String message){
        messages.add(message);
    }
    private void kick(String reason){
        var packet=new DisconnectPacket();
        packet.setReason(org.cloudburstmc.protocol.bedrock.data.DisconnectFailReason.KICKED);
        packet.setMessageSkipped(false);packet.setKickMessage(reason);
        backendQueue.clear();clientQueue.clear();messages.clear();pending.clear();
        queueClient(packet);
    }
    private LoginPacket verifyLogin(LoginPacket login)throws Exception{
        if(authenticated||transportKey==null||login.getAuthPayload()==null||login.getAuthPayload().getAuthType()==AuthType.GUEST)
            throw new IOException("NetherNet login has no authenticated transport identity");
        var chain=EncryptionUtils.validatePayload(login.getAuthPayload());
        if(!chain.signed())throw new IOException("Invalid NetherNet login signature");
        var key=chain.identityClaims().parsedIdentityPublicKey();
        if(!MessageDigest.isEqual(key.getEncoded(),transportKey.getEncoded()))throw new IOException("NetherNet transport public key differs from login key");
        byte[] clientData=EncryptionUtils.verifyClientData(login.getClientJwt(),key);
        if(clientData==null)throw new IOException("Invalid NetherNet client data signature");
        var verified=ac.navro.proxy.protocol.LoginIdentity.fromVerifiedChain(chain);
        identity=verified.uuid();state.player().setName(verified.displayName());authenticated=true;
        state.setExemptionPermission(() -> NetherNetAlerts.PERMISSIONS.hasPermission(identity,"navro.exempt",false));
        try{state.player().setDeviceOs(com.google.gson.JsonParser.parseString(new String(clientData,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().get("DeviceOS").getAsInt());}
        catch(RuntimeException e){state.player().setDeviceOs(-1);}
        if(detect){NetherNetAlerts.register(identity,alertRecipient);NetherNetAlerts.commands().attach(identity,state);}
        if(backendKey==null)return null;
        if(forwardedPeer!=null){
            String xuid=chain.identityClaims().extraData.xuid;
            if(xuid==null||!xuid.matches("[0-9]{1,20}"))throw new IOException("Verified login is missing a valid XUID");
            var data=com.google.gson.JsonParser.parseString(new String(clientData,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            data.addProperty("Waterdog_XUID",xuid);
            data.addProperty("Waterdog_IP",forwardedPeer.getHostAddress());
            clientData=data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return ac.navro.proxy.protocol.PrivateBackendLogin.encode(login.getProtocolVersion(),verified,chain.rawIdentityClaims(),clientData,backendKey);
    }
    private byte[] unpack(byte[] batch)throws IOException,DataFormatException{
        if(batch.length==0||batch.length>MAX_BATCH)throw new IOException("Invalid NetherNet batch length");
        if(!compressed)return batch;
        int type=Byte.toUnsignedInt(batch[0]);
        if(type==255)return Arrays.copyOfRange(batch,1,batch.length);
        if(type!=0)throw new IOException("Unsupported NetherNet compression prefix "+type);
        var inflater=new Inflater(true);inflater.setInput(batch,1,batch.length-1);
        try(var out=new ByteArrayOutputStream()){
            byte[] block=new byte[8192];
            while(!inflater.finished()){
                int n=inflater.inflate(block);if(n==0)throw new IOException("Truncated/invalid NetherNet compressed batch");
                if(out.size()+n>MAX_BATCH)throw new IOException("NetherNet decompressed batch exceeds limit");out.write(block,0,n);
            }
            if(inflater.getRemaining()!=0)throw new IOException("Trailing NetherNet compressed data");return out.toByteArray();
        }finally{inflater.end();}
    }
    public byte[] encode(BedrockPacket packet)throws Exception{return wrapFrame(encodeFrame(packet));}
    private byte[] wrapFrame(byte[] frame){
        var batch=Unpooled.buffer();try{
            if(compressed)batch.writeByte(255);VarInts.writeUnsignedInt(batch,frame.length);batch.writeBytes(frame);
            byte[] result=new byte[batch.readableBytes()];batch.readBytes(result);return result;
        }finally{batch.release();}
    }
    private byte[] encodeFrame(BedrockPacket packet)throws Exception{
        var frame=Unpooled.buffer();try{
            VarInts.writeUnsignedInt(frame,codec.getPacketDefinition(packet.getClass()).getId());codec.tryEncode(backendHelper,frame,packet);
            byte[] result=new byte[frame.readableBytes()];frame.readBytes(result);return result;
        }finally{frame.release();}
    }
    @Override public void close(){
        closed=true;withheldCommandTable=null;state.closePlacement();state.player().getLatencyUtil().close();pending.clear();clientQueue.clear();backendQueue.clear();messages.clear();
        if(identity!=null&&detect){NetherNetAlerts.remove(identity,alertRecipient);NetherNetAlerts.commands().detach(identity,state);}
    }
}
