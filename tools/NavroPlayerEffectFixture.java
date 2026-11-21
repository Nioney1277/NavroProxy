import ac.navro.anticheat.Navro;
import ac.navro.anticheat.collision.CollisionPhysicsState;
import ac.navro.anticheat.compensated.world.CompensatedWorldImpl;
import ac.navro.anticheat.data.ItemUseTracker;
import ac.navro.anticheat.data.vanilla.AttributeInstance;
import ac.navro.anticheat.packets.player.PlayerEffectPackets;
import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.player.NavroPlayerManager;
import ac.navro.anticheat.player.data.PlayerData;
import ac.navro.anticheat.player.data.tracker.FlagTracker;
import ac.navro.anticheat.util.EntityBatchEncoder;
import ac.navro.anticheat.util.LatencyUtil;
import cn.nukkit.event.server.DataPacketSendEvent;
import cn.nukkit.network.CompressionProvider;
import cn.nukkit.network.protocol.MobEffectPacket;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.potion.Effect;
import cn.nukkit.utils.BinaryStream;
import com.google.gson.Gson;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;





public final class NavroPlayerEffectFixture {
    static void set(Object o, Class<?> type, String name, Object value) throws Exception {
        var f = type.getDeclaredField(name); f.setAccessible(true); f.set(o, value);
    }
    static Map<String,Object> state(NavroPlayer p, LatencyUtil latency) {
        var effects = new TreeMap<Integer,List<Integer>>();
        p.getActiveEffects().forEach((id,effect) -> effects.put(id, List.of(effect.getAmplifier(), effect.getDuration())));
        return Map.of("effects", effects, "speedBits", Float.floatToRawIntBits(p.attributes.get("minecraft:movement").getValue()),
                "modifiers", p.attributes.get("minecraft:movement").getModifiers().size(), "pending", latency.sentQueue().size());
    }
    public static void main(String[] args) throws Exception {
        Effect.init(); cn.nukkit.entity.Attribute.init();
        var gson = new Gson();
        var field = Effect.class.getDeclaredField("effects"); field.setAccessible(true);
        int registrySize = ((Effect[]) field.get(null)).length;
        var ids = new ArrayList<Integer>();
        for (int id=0; id<registrySize; id++) if (Effect.getEffect(id)!=null) ids.add(id);
        Files.writeString(Path.of(args[0]), gson.toJson(ids)+"\n");

        var f=Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); var unsafe=(Unsafe)f.get(null);
        var manager=new NavroPlayerManager(); set(Navro.getInstance(), Navro.class, "playerManager", manager);
        var handler=new PlayerEffectPackets(); var random=new Random(0x4546464543544143L);
        int[] durations={Integer.MIN_VALUE,-1,0,1,2,20,Integer.MAX_VALUE-1,Integer.MAX_VALUE};
        int[] effectIds={-1,0,1,2,8,24,27,28,29,30,31,32,33,34,35,36,37,40,255,256,Integer.MAX_VALUE};
        int sessions=8, steps=192, rows=0;
        try(var out=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(args[1])),StandardCharsets.UTF_8))) {
            out.write(gson.toJson(Map.of("magic","NPE1","sessions",sessions,"steps",steps,"nativeIds",ids))); out.newLine();
            for(int sn=0; sn<sessions; sn++) {
                var p=(NavroPlayer)unsafe.allocateInstance(NavroPlayer.class); p.runtimeEntityId=7;
                set(p,PlayerData.class,"activeEffects",new HashMap<>());
                set(p,PlayerData.class,"attributes",new HashMap<String,AttributeInstance>());
                p.attributes.put("minecraft:movement",new AttributeInstance(.1F));
                set(p,PlayerData.class,"flagTracker",new FlagTracker()); p.setSprinting(true);
                set(p,PlayerData.class,"collisionPhysics",new CollisionPhysicsState());
                set(p,NavroPlayer.class,"compensatedWorld",new CompensatedWorldImpl(p));
                set(p,NavroPlayer.class,"itemUseTracker",new ItemUseTracker(p));
                manager.put(null,p);
                var latency=new LatencyUtil(null); var sequence=new AtomicLong(1000);
                try(var encoder=new EntityBatchEncoder(CompressionProvider.NONE,ProtocolInfo.CURRENT_PROTOCOL,sequence::incrementAndGet,ignored->{},latency)) {
                    p.entityBatchEncoder=encoder;
                    for(int step=0; step<steps; step++) {
                        int op=step<ids.size()?0:random.nextInt(3); var row=new LinkedHashMap<String,Object>(); row.put("op",op);
                        if(op==0) {
                            int effect=step<ids.size()?ids.get(step):effectIds[random.nextInt(effectIds.length)];
                            int event=step<ids.size()?1:random.nextInt(4), amp=random.nextInt(5)-1, duration=durations[random.nextInt(durations.length)];
                            long entity=step<ids.size()||random.nextInt(5)!=0?7:99;
                            var packet=new MobEffectPacket();packet.protocol=ProtocolInfo.CURRENT_PROTOCOL;packet.eid=entity;
                            packet.eventId=event;packet.effectId=effect;packet.amplifier=amp;packet.duration=duration;
                            handler.onPacket(new DataPacketSendEvent(null,packet));
                            var raw=new BinaryStream();
                            for(var encoded:encoder.prepare(packet)){byte[] bytes=encoded.getBuffer();raw.putUnsignedVarInt(bytes.length);raw.put(bytes);}
                            encoder.compress(raw,0);
                            row.put("entity",entity);row.put("event",event);row.put("effect",effect);row.put("amplifier",amp);row.put("duration",duration);row.put("marker",sequence.get());

                            packet.eid=99;packet.effectId=999;packet.amplifier=99;packet.duration=999;packet.eventId=3;
                        } else if(op==1) {
                            long marker=latency.sentQueue().isEmpty()||random.nextInt(4)==0?999999:latency.sentQueue().stream().toList().get(random.nextInt(latency.sentQueue().size())).id();
                            row.put("marker",marker);row.put("accepted",latency.onResponse(marker));
                        } else p.tick();
                        row.put("state",state(p,latency));out.write(gson.toJson(row));out.newLine();rows++;
                    }
                }
            }
        }
        System.out.println("Native effect IDs: "+ids+"; original packet/ACK/tick transitions: "+rows);
    }
}
