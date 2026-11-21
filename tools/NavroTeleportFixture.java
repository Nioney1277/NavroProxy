import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.player.data.PlayerData;
import ac.navro.anticheat.teleport.TeleportUtil;
import ac.navro.anticheat.teleport.data.TeleportData;
import ac.navro.anticheat.util.math.Vec3;
import cn.nukkit.math.Vector3f;
import cn.nukkit.network.protocol.*;
import com.google.gson.*;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public class NavroTeleportFixture {
    static class Capturing extends TeleportUtil {
        MovePlayerPacket sent;
        Capturing(NavroPlayer p){super(p);}
        @Override protected boolean sendPacket(DataPacket packet){sent=(MovePlayerPacket)packet;return true;}
    }
    static List<Integer> vector(Vec3 v){return List.of(Float.floatToRawIntBits(v.x),Float.floatToRawIntBits(v.y),Float.floatToRawIntBits(v.z));}
    public static void main(String[] args)throws Exception{
        var field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);var unsafe=(Unsafe)field.get(null);
        var drain=Class.forName("ac.navro.anticheat.packets.input.teleport.TeleportHandler").getDeclaredMethod("drainAcceptedTeleports",Queue.class,java.util.function.Consumer.class);drain.setAccessible(true);
        Gson gson=new GsonBuilder().serializeNulls().create();Random r=new Random(0x54454c45504f5254L);int cases=4096;
        try(var out=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(args[0])),StandardCharsets.UTF_8))){
            out.write(gson.toJson(Map.of("magic","NTP1","cases",cases)));out.newLine();
            for(int n=0;n<cases;n++){
                Vec3 target=new Vec3((r.nextFloat()-.5F)*1000000F,(r.nextFloat()-.5F)*1024F,(r.nextFloat()-.5F)*1000000F);
                Vec3 rotation=new Vec3((r.nextFloat()-.5F)*180F,(r.nextFloat()-.5F)*720F,(r.nextFloat()-.5F)*720F);
                boolean pending=n%5==0,nil=n%37==0;int mode=n%4,protocol=new int[]{924,2193,2225}[n%3];long eid=r.nextLong();
                var p=(NavroPlayer)unsafe.allocateInstance(NavroPlayer.class);p.runtimeEntityId=eid;p.rotation=new Vector3f(rotation.x,rotation.y,rotation.z);var util=new Capturing(p);
                if(pending)util.getQueuedTeleports().add(new TeleportData(target));
                String error="";try{util.teleport(nil?null:target);}catch(Exception e){error=e.getClass().getSimpleName();}
                var row=new LinkedHashMap<String,Object>();row.put("target",vector(target));row.put("rotation",vector(rotation));row.put("entity",eid);row.put("pending",pending);row.put("nil",nil);row.put("mode",mode);row.put("protocol",protocol);row.put("error",error);
                if(util.sent!=null){util.sent.protocol=protocol;util.sent.encode();row.put("bytes",Base64.getEncoder().encodeToString(util.sent.getBuffer()));}else row.put("bytes",null);
                var data=new TeleportData(target,n%2==0,mode);target.x+=1;row.put("copied",vector(data.getPosition()));
                Vec3 incoming=data.getPosition().add(new Vec3(n%7==0?0.001F:0,n%11==0?0.25F:0,n%13==0?Float.NaN:0));boolean handled=n%3==0;row.put("incoming",vector(incoming));row.put("handled",handled);row.put("match",data.matchesConfirmation(incoming,handled));
                Queue<TeleportData> queue=new java.util.concurrent.ConcurrentLinkedQueue<>();List<Boolean> acks=new ArrayList<>();List<TeleportData> entries=new ArrayList<>();
                for(int k=0;k<8;k++){var t=new TeleportData(new Vec3(k,k+1,k+2));boolean ack=r.nextBoolean();if(ack)t.accept();queue.add(t);entries.add(t);acks.add(ack);}
                var processed=new ArrayList<Integer>();drain.invoke(null,queue,(java.util.function.Consumer<TeleportData>)t->processed.add(entries.indexOf(t)));row.put("acks",acks);row.put("drained",processed);row.put("remaining",queue.size());
                out.write(gson.toJson(row));out.newLine();
            }
        }
        if(args.length>1)try(var out=new BufferedWriter(new FileWriter(args[1]))){
            for(int player=0;player<4;player++)for(int command=0;command<5;command++){
                var packet=new UpdateAbilitiesPacket();packet.protocol=2193;packet.setEntityId(9002);
                packet.setPlayerPermission(UpdateAbilitiesPacket.PlayerPermission.values()[player]);
                packet.setCommandPermission(UpdateAbilitiesPacket.CommandPermission.values()[command]);packet.encode();
                out.write(gson.toJson(Map.of("engine","nukkit","player",player,"command",command,"entity",9002,
                        "bytes",Base64.getEncoder().encodeToString(packet.getBuffer()))));out.newLine();
            }
        }
        System.out.println("Original ordinary teleport: "+cases+" packet/byte, confirmation, copy and ordered-drain cases.");
    }
}
