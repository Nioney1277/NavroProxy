package ac.navro.proxy.nethernet;

import com.google.gson.Gson;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry;
import ac.navro.proxy.data.block.NetworkBlockStateRegistry.RuntimePalette;
import java.nio.file.Path;
import java.util.HashMap;
import java.io.*;
import java.net.*;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;


public final class NetherNetInspectServer {
    private static final int DETECT_BRIDGE_MAGIC = 0x4e564234;
    private static final int GROUPED_BRIDGE_MAGIC = 0x4e564235;
    private static final int STOCK_BACKEND_MAGIC = 0x4e564236;
    private static final int MAX_GROUP_COUNT = 64;
    private static final int MAX_GROUP_BYTES = 2 * 1024 * 1024;

    public static void main(String[] args)throws Exception{
        if(System.getProperty("navro.worldCollisionLog")==null)
            System.setProperty("navro.worldCollisionLog","logs/world-collision-nethernet.jsonl");
        int port=args.length==0?19138:Integer.parseInt(args[0]);
        var backendType = ac.navro.proxy.protocol.BackendType.NAVRO;
        boolean smoothSetback = false;
        boolean stockBackend = false;
        boolean operatorCommandTable = false;
        boolean backendStateSync = false;
        var palettes = new HashMap<Integer, RuntimePalette>();
        for (int i = 1; i < args.length; i++) {
            switch(args[i]) {
                case "--backend-type" -> {
                    if(++i >= args.length) throw new IllegalArgumentException("Missing backend type");
                    backendType = ac.navro.proxy.protocol.BackendType.parse(args[i]);
                }
                case "--smooth-setback" -> smoothSetback = true;
                case "--stock-geyser-backend" -> stockBackend = true;
                case "--operator-command-table" -> operatorCommandTable = true;
                case "--backend-state-sync" -> backendStateSync = true;
                case "--block-palette" -> {
                    if (++i >= args.length) throw new IllegalArgumentException("Missing block palette path");
                    var palette = NetworkBlockStateRegistry.readRuntimePalette(Path.of(args[i]));
                    if (palettes.putIfAbsent(palette.protocol(), palette) != null)
                        throw new IllegalArgumentException("Duplicate block palette for protocol " + palette.protocol());
                    System.out.println("NETHERNET_PALETTE_LOADED protocol=" + palette.protocol() + " states=" + palette.size());
                }
                default -> throw new IllegalArgumentException("Unknown adapter option: " + args[i]);
            }
        }
        ac.navro.proxy.SupportedCodecs.forProtocol(2225);
        ac.navro.proxy.data.inventory.NativePredictionItems.initialize();
        NetherNetAlerts.configure(smoothSetback);
        ac.navro.proxy.command.ConsoleCommands.start(NetherNetAlerts.commands(),NetherNetAlerts.ALERTS);
        final var selectedBackend = backendType;
        final boolean selectedSmoothSetback = smoothSetback;
        final boolean allowStockBackend = stockBackend;
        final boolean restrictCommandTable = operatorCommandTable;
        final boolean allowBackendStateSync = backendStateSync;
        var selectedPalettes = Map.copyOf(palettes);
        var pool=new ThreadPoolExecutor(0,32,30,TimeUnit.SECONDS,new SynchronousQueue<>(),
                new ThreadPoolExecutor.AbortPolicy());
        try(var server=new ServerSocket()){
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(),port));
            System.out.println("NETHERNET_ADAPTER_READY loopback="+server.getLocalSocketAddress()+" mode=per_connection backendType="+backendType);
            while(true){var socket=server.accept();try{pool.execute(()->handle(socket, selectedBackend, selectedSmoothSetback, allowStockBackend, selectedPalettes, restrictCommandTable, allowBackendStateSync));}
                catch(RejectedExecutionException e){socket.close();}}
        }finally{pool.shutdownNow();}
    }
    private static void handle(Socket socket, ac.navro.proxy.protocol.BackendType backendType, boolean smoothSetback, boolean allowStockBackend, Map<Integer, RuntimePalette> palettes, boolean restrictCommandTable, boolean allowBackendStateSync){
        var gson=new Gson();
        try(socket;var in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            var out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))){
            socket.setSoTimeout(30000);
            int magic=in.readInt();
            boolean stockBackend=magic==STOCK_BACKEND_MAGIC;
            if(stockBackend&&!allowStockBackend)throw new IOException("Stock Geyser backend mode is disabled");
            boolean grouped=stockBackend||magic==GROUPED_BRIDGE_MAGIC;
            boolean detectBridge=grouped||magic==DETECT_BRIDGE_MAGIC;
            boolean duplex=magic==0x4e564233||detectBridge;
            if(magic!=0x4e564231&&magic!=0x4e564232&&!duplex)throw new IOException("Invalid NetherNet IPC handshake");
            if(detectBridge)socket.setTcpNoDelay(true);
            int n=in.readInt();if(n<0||n>512)throw new IOException("Invalid transport key size");
            if(detectBridge&&n==0)throw new IOException("Missing verified NetherNet transport key");
            var key=n==0?null:KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(in.readNBytes(n)));
            KeyPair backendKey=null;
            if(stockBackend||magic==0x4e564232||magic==0x4e564233){
                int publicSize=in.readInt();if(publicSize<1||publicSize>512)throw new IOException("Invalid backend public key size");
                var publicKey=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(in.readNBytes(publicSize)));
                int privateSize=in.readInt();if(privateSize<1||privateSize>1024)throw new IOException("Invalid backend private key size");
                byte[] privateBytes=in.readNBytes(privateSize);
                var privateKey=KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
                java.util.Arrays.fill(privateBytes,(byte)0);
                backendKey=new KeyPair(publicKey,privateKey);
            }
            InetAddress peerAddress=null;
            if(stockBackend){
                int addressLength=in.readUnsignedByte();
                if(addressLength!=4&&addressLength!=16)throw new IOException("Invalid frontend address size");
                peerAddress=InetAddress.getByAddress(in.readNBytes(addressLength));
                if(peerAddress.isAnyLocalAddress()||peerAddress.isMulticastAddress())
                    throw new IOException("Invalid frontend address");
            }
            boolean detect=detectBridge||magic==0x4e564233&&in.readBoolean();
            try(var session=new NetherNetBatchSession(key,backendKey,detect,backendType,smoothSetback,!detectBridge || stockBackend && allowBackendStateSync,peerAddress)){
                session.setRuntimePalettes(palettes);
                session.setOperatorCommandTable(restrictCommandTable);
                if(detectBridge){out.writeInt(magic);out.flush();}
                Set<String> observed=new HashSet<>();
                boolean verbose=!grouped||Boolean.getBoolean("navro.nethernet.trace-batches");
                long groups=0, requests=0;
                int largestGroup=0;
                try {
                    for(int lead;(lead=in.read())>=0;){
                        List<BridgeBatch> group=grouped?readBridgeGroup(in,lead)
                                :List.of(readBatch(in,lead,duplex,NetherNetBatchSession.MAX_BATCH));
                        groups++;
                        requests+=group.size();
                        largestGroup=Math.max(largestGroup,group.size());
                        for(BridgeBatch batch:group){
                            int direction=batch.direction();
                            var result=direction==2?session.tick():session.process(direction==1,batch.payload());
                            writeReply(out,result,duplex);
                            if(direction!=2&&(verbose||firstSeen(observed,direction,result.packets()))){
                                System.out.println(gson.toJson(Map.of("event","nethernet_batch","backend",direction==1,
                                        "packets",result.packets(),"authenticated",result.authenticated(),"mode",session.mode(),
                                        "clientPackets",result.clientbound().size(),"backendPackets",result.backendbound().size())));
                            }
                            if(direction==1&&result.packets().contains("DisconnectPacket")){
                                System.out.println(gson.toJson(Map.of("event","nethernet_backend_disconnect",
                                        "reason",session.backendDisconnect())));
                            }
                        }
                        out.flush();
                    }
                }finally{
                    System.out.println(gson.toJson(Map.of("event","nethernet_session_close","counts",session.counts(),
                            "ipcVersion",(magic&255)-'0',"groups",groups,"requests",requests,"largestGroup",largestGroup)));
                }
            }
        }catch(Exception|LinkageError e){

            var trace=new java.util.ArrayList<String>();
            for(Throwable cause=e;cause!=null&&trace.size()<24;cause=cause.getCause()){
                trace.add(cause.getClass().getSimpleName());
                for(var frame:cause.getStackTrace()){
                    if(trace.size()>=24)break;
                    trace.add(frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber());
                }
            }
            System.out.println(gson.toJson(Map.of("event","nethernet_session_error","type",e.getClass().getSimpleName(),
                    "detail",e instanceof IOException?String.valueOf(e.getMessage()):"Native packet decode or identity validation failed","trace",trace)));
        }
    }

    static List<BridgeBatch> readBridgeGroup(DataInputStream in,int count)throws IOException{
        if(count<1||count>MAX_GROUP_COUNT)throw new IOException("Invalid NetherNet bridge group count");
        int remaining=count==1?NetherNetBatchSession.MAX_BATCH:MAX_GROUP_BYTES;
        var group=new ArrayList<BridgeBatch>(count);
        for(int index=0;index<count;index++){
            var batch=readBatch(in,in.readUnsignedByte(),true,remaining);
            remaining-=batch.payload().length;
            group.add(batch);
        }
        return group;
    }

    private static BridgeBatch readBatch(DataInputStream in,int direction,boolean duplex,int remaining)throws IOException{
        if(direction>2||direction==2&&!duplex)throw new IOException("Invalid batch direction");
        int size=in.readInt();
        if(size<0||(size==0)!=(direction==2)||size>remaining)throw new IOException("Invalid IPC batch size");
        byte[] batch=in.readNBytes(size);
        if(batch.length!=size)throw new EOFException("Incomplete IPC batch");
        return new BridgeBatch(direction,batch);
    }

    private static void writeReply(DataOutputStream out,NetherNetBatchSession.Result result,boolean duplex)throws IOException{
        out.writeInt(result.forwarded()==null?-1:result.forwarded().length);
        if(result.forwarded()!=null)out.write(result.forwarded());
        writeOutputs(out,result.clientbound());
        if(duplex)writeOutputs(out,result.backendbound());
    }

    private static void writeOutputs(DataOutputStream out,List<byte[]> outputs)throws IOException{
        out.writeInt(outputs.size());
        for(byte[] batch:outputs){out.writeInt(batch.length);out.write(batch);}
    }

    private static boolean firstSeen(Set<String> observed,int direction,List<String> packets){
        boolean first=false;
        for(String packet:packets)first|=observed.add(direction+packet);
        return first;
    }

    record BridgeBatch(int direction,byte[] payload) { }
}
