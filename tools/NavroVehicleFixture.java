import ac.navro.anticheat.Navro;
import ac.navro.anticheat.compensated.world.CompensatedWorldImpl;
import ac.navro.anticheat.packets.other.VehiclePackets;
import ac.navro.anticheat.packets.server.ServerEntityPackets;
import ac.navro.anticheat.player.*;
import ac.navro.anticheat.teleport.TeleportUtil;
import ac.navro.anticheat.teleport.data.TeleportData;
import ac.navro.anticheat.util.*;
import ac.navro.anticheat.util.math.Vec3;
import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.server.*;
import cn.nukkit.network.CompressionProvider;
import cn.nukkit.network.protocol.*;
import cn.nukkit.utils.BinaryStream;
import com.google.gson.*;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;




public final class NavroVehicleFixture {
    static final long[] UNIQUE={7,70,42,43,44,420,430,440,450,-5,999};
    static void set(Object o,Class<?> type,String name,Object value)throws Exception{
        var f=type.getDeclaredField(name);f.setAccessible(true);f.set(o,value);
    }
    static Map<String,Object> state(NavroPlayer p,LatencyUtil latency){
        var out=new LinkedHashMap<String,Object>();
        out.put("vehicle",p.vehicleData==null?null:p.vehicleData.vehicleRuntimeId);
        out.put("control",p.vehicleData!=null&&p.vehicleData.canWeControlThisVehicle);
        out.put("offsetBits",Float.floatToRawIntBits(p.getYOffset()));
        out.put("teleports",p.getTeleportUtil().getQueuedTeleports().size());
        var flags=new TreeMap<Long,Boolean>();p.compensatedWorld.getEntities().forEach((id,e)->flags.put(id,e.isInVehicle()));out.put("riders",flags);
        var index=new TreeMap<Long,Long>();for(long id:UNIQUE){Long runtime=p.compensatedWorld.getRuntimeIdByUniqueId(id);if(runtime!=null)index.put(id,runtime);}out.put("index",index);
        out.put("pending",latency.sentQueue().size());return out;
    }
    static void spawn(ServerEntityPackets listener,Player viewer,long runtime,long unique,boolean boat){
        var p=new AddEntityPacket();p.entityRuntimeId=runtime;p.entityUniqueId=unique;p.type=boat?90:12;
        p.id=boat?"minecraft:boat":"minecraft:pig";p.metadata.putFloat(Entity.DATA_BOUNDING_BOX_WIDTH,1F).putFloat(Entity.DATA_BOUNDING_BOX_HEIGHT,1F);
        listener.onPacket(new DataPacketSendEvent(viewer,p));
    }
    static void send(EntityBatchEncoder encoder,DataPacket packet)throws Exception{
        packet.protocol=ProtocolInfo.CURRENT_PROTOCOL;var raw=new BinaryStream();
        for(var p:encoder.prepare(packet)){byte[] bytes=p.getBuffer();raw.putUnsignedVarInt(bytes.length);raw.put(bytes);}encoder.compress(raw,0);
    }
    static List<Object> wireCases(){
        var rows=new ArrayList<Object>();
        for(int protocol:new int[]{729,898,2168,2193})for(int i=0;i<8;i++){
            var p=new SetEntityLinkPacket();p.protocol=protocol;
            p.vehicleUniqueId=new long[]{420,-5,Long.MIN_VALUE,Long.MAX_VALUE}[i%4];p.riderUniqueId=i%2==0?7:70;
            p.type=(byte)(i%3);p.immediate=(byte)(i%2);p.riderInitiated=i%3==0;p.vehicleAngularVelocity=i%2==0?-0F:1.25F;p.tryEncode();
            rows.add(Map.of("protocol",protocol,"vehicle",p.vehicleUniqueId,"rider",p.riderUniqueId,"type",p.type,
                    "immediate",p.immediate!=0,"initiated",p.riderInitiated,"angularBits",Float.floatToRawIntBits(p.vehicleAngularVelocity),
                    "bytes",Base64.getEncoder().encodeToString(p.getBuffer())));
        }return rows;
    }
    public static void main(String[] args)throws Exception{
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var unsafe=(Unsafe)f.get(null);
        var manager=new NavroPlayerManager();set(Navro.getInstance(),Navro.class,"playerManager",manager);
        var vehicles=new VehiclePackets();var entities=new ServerEntityPackets();var gson=new GsonBuilder().serializeNulls().create();
        var random=new Random(0x56454849434c4553L);int sessions=8,steps=128,rows=0;
        try(var out=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(args[0])),StandardCharsets.UTF_8))){
            out.write(gson.toJson(Map.of("magic","NVL1","sessions",sessions,"steps",steps,"uniqueIds",UNIQUE,"wire",wireCases())));out.newLine();
            for(int sn=0;sn<sessions;sn++){
                var p=(NavroPlayer)unsafe.allocateInstance(NavroPlayer.class);p.runtimeEntityId=7;p.uniqueEntityId=70;
                var viewer=(Player)unsafe.allocateInstance(Player.class);set(p,NavroPlayer.class,"session",viewer);manager.put(viewer,p);
                set(p,NavroPlayer.class,"compensatedWorld",new CompensatedWorldImpl(p));set(p,NavroPlayer.class,"teleportUtil",new TeleportUtil(p));
                spawn(entities,viewer,42,420,true);spawn(entities,viewer,43,430,false);spawn(entities,viewer,44,440,false);
                var latency=new LatencyUtil(null);var sequence=new AtomicLong(1000);SetEntityLinkPacket lastLink=null;
                try(var encoder=new EntityBatchEncoder(CompressionProvider.NONE,ProtocolInfo.CURRENT_PROTOCOL,sequence::incrementAndGet,ignored->{},latency)){
                    p.entityBatchEncoder=encoder;
                    for(int step=0;step<steps;step++){
                        int op=step<4?new int[]{5,1,2,3}[step]:random.nextInt(7);if(op==6&&lastLink==null)op=5;
                        var row=new LinkedHashMap<String,Object>();row.put("op",op);
                        long vehicle=UNIQUE[random.nextInt(UNIQUE.length)],rider=UNIQUE[random.nextInt(UNIQUE.length)];int type=random.nextInt(3);
                        if(step==1){vehicle=420;rider=7;type=1;}
                        if(op==0){
                            long runtime=42+random.nextInt(4),unique=UNIQUE[random.nextInt(UNIQUE.length)];boolean boat=random.nextBoolean();
                            spawn(entities,viewer,runtime,unique,boat);row.put("runtime",runtime);row.put("unique",unique);row.put("boat",boat);
                        }else if(op==1){
                            var packet=new SetEntityLinkPacket();packet.vehicleUniqueId=vehicle;packet.riderUniqueId=rider;packet.type=(byte)type;
                            vehicles.onPacketSend(new DataPacketSendEvent(viewer,packet));send(encoder,packet);lastLink=packet;
                            row.put("vehicle",vehicle);row.put("rider",rider);row.put("type",type);row.put("marker",sequence.get());
                        }else if(op==2){
                            long marker=latency.sentQueue().isEmpty()||random.nextInt(5)==0?999999:latency.sentQueue().stream().toList().get(random.nextInt(latency.sentQueue().size())).id();
                            if(step==2)marker=sequence.get();row.put("marker",marker);row.put("accepted",latency.onResponse(marker));
                        }else if(op==3){
                            var packet=new InteractPacket();packet.target=random.nextBoolean()?7:70;packet.action=random.nextBoolean()?3:4;
                            vehicles.onPacketReceive(new DataPacketReceiveEvent(viewer,packet));row.put("target",packet.target);row.put("action",packet.action);
                        }else if(op==4){
                            long unique=UNIQUE[random.nextInt(UNIQUE.length)];var packet=new RemoveEntityPacket();packet.eid=unique;
                            entities.onPacket(new DataPacketSendEvent(viewer,packet));send(encoder,packet);row.put("unique",unique);row.put("marker",sequence.get());
                        }else if(op==5)p.getTeleportUtil().getQueuedTeleports().add(new TeleportData(Vec3.ZERO));
                        else {lastLink.vehicleUniqueId=vehicle;lastLink.riderUniqueId=rider;lastLink.type=(byte)type;row.put("vehicle",vehicle);row.put("rider",rider);row.put("type",type);}
                        row.put("state",state(p,latency));out.write(gson.toJson(row));out.newLine();rows++;
                    }
                }
            }
        }System.out.println("Original vehicle/link/entity-index transitions: "+rows+"; original link wire cases: 32");
    }
}
