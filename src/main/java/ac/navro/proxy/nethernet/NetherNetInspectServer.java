package ac.navro.proxy.nethernet;

import com.google.gson.Gson;
import java.io.*;
import java.net.*;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.KeyPair;
import java.util.Map;
import java.util.concurrent.*;


public final class NetherNetInspectServer {
    public static void main(String[] args)throws Exception{
        if(System.getProperty("navro.worldCollisionLog")==null)
            System.setProperty("navro.worldCollisionLog","logs/world-collision-nethernet.jsonl");
        int port=args.length==0?19138:Integer.parseInt(args[0]);
        var backendType = ac.navro.proxy.protocol.BackendType.NAVRO;
        boolean smoothSetback = false;
        for (int i = 1; i < args.length; i++) {
            switch(args[i]) {
                case "--backend-type" -> {
                    if(++i >= args.length) throw new IllegalArgumentException("Missing backend type");
                    backendType = ac.navro.proxy.protocol.BackendType.parse(args[i]);
                }
                case "--smooth-setback" -> smoothSetback = true;
                default -> throw new IllegalArgumentException("Unknown adapter option: " + args[i]);
            }
        }
        NetherNetAlerts.configure(smoothSetback);
        ac.navro.proxy.command.ConsoleCommands.start(NetherNetAlerts.commands(),NetherNetAlerts.ALERTS);
        final var selectedBackend = backendType;
        final boolean selectedSmoothSetback = smoothSetback;
        var pool=new ThreadPoolExecutor(0,32,30,TimeUnit.SECONDS,new SynchronousQueue<>(),
                new ThreadPoolExecutor.AbortPolicy());
        try(var server=new ServerSocket()){
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(),port));
            System.out.println("NETHERNET_ADAPTER_READY loopback="+server.getLocalSocketAddress()+" mode=per_connection backendType="+backendType);
            while(true){var socket=server.accept();try{pool.execute(()->handle(socket, selectedBackend, selectedSmoothSetback));}
                catch(RejectedExecutionException e){socket.close();}}
        }finally{pool.shutdownNow();}
    }
    private static void handle(Socket socket, ac.navro.proxy.protocol.BackendType backendType, boolean smoothSetback){
        var gson=new Gson();
        try(socket;var in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            var out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))){
            socket.setSoTimeout(30000);
            int magic=in.readInt();
            if(magic!=0x4e564231&&magic!=0x4e564232&&magic!=0x4e564233)throw new IOException("Invalid NetherNet IPC handshake");
            int n=in.readInt();if(n<0||n>512)throw new IOException("Invalid transport key size");
            var key=n==0?null:KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(in.readNBytes(n)));
            KeyPair backendKey=null;
            if(magic==0x4e564232||magic==0x4e564233){
                int publicSize=in.readInt();if(publicSize<1||publicSize>512)throw new IOException("Invalid backend public key size");
                var publicKey=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(in.readNBytes(publicSize)));
                int privateSize=in.readInt();if(privateSize<1||privateSize>1024)throw new IOException("Invalid backend private key size");
                byte[] privateBytes=in.readNBytes(privateSize);
                var privateKey=KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
                java.util.Arrays.fill(privateBytes,(byte)0);
                backendKey=new KeyPair(publicKey,privateKey);
            }
            boolean detect=magic==0x4e564233&&in.readBoolean();
            try(var session=new NetherNetBatchSession(key,backendKey,detect,backendType,smoothSetback)){
                while(true){
                    int direction=in.read();if(direction<0)break;
                    if(direction>2||direction==2&&magic!=0x4e564233)throw new IOException("Invalid batch direction");
                    int size=in.readInt();if(size<0||size==0&&direction!=2||size!=0&&direction==2||size>NetherNetBatchSession.MAX_BATCH)throw new IOException("Invalid IPC batch size");
                    byte[] batch=in.readNBytes(size);if(batch.length!=size)throw new EOFException();
                    var result=direction==2?session.tick():session.process(direction==1,batch);
                    out.writeInt(result.forwarded()==null?-1:result.forwarded().length);
                    if(result.forwarded()!=null)out.write(result.forwarded());
                    out.writeInt(result.clientbound().size());
                    for(byte[] extra:result.clientbound()){out.writeInt(extra.length);out.write(extra);}
                    if(magic==0x4e564233){
                        out.writeInt(result.backendbound().size());
                        for(byte[] extra:result.backendbound()){out.writeInt(extra.length);out.write(extra);}
                    }
                    out.flush();
                    if(direction!=2)System.out.println(gson.toJson(Map.of("event","nethernet_batch","backend",direction==1,
                            "packets",result.packets(),"authenticated",result.authenticated(),"mode",session.mode(),
                            "clientPackets",result.clientbound().size(),"backendPackets",result.backendbound().size())));
                    if(session.backendDisconnect()!=null){
                        System.out.println(gson.toJson(Map.of("event","nethernet_backend_disconnect",
                                "reason",session.backendDisconnect())));
                    }
                }
                System.out.println(gson.toJson(Map.of("event","nethernet_session_close","counts",session.counts())));
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
}
