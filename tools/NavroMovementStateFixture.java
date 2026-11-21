import ac.navro.anticheat.Navro;
import ac.navro.anticheat.compensated.CompensatedInventory;
import ac.navro.anticheat.collision.sampling.NearbyBlockSampler;
import ac.navro.anticheat.data.*;
import ac.navro.anticheat.data.vanilla.AttributeInstance;
import ac.navro.anticheat.packets.input.legacy.LegacyAuthInputPackets;
import ac.navro.anticheat.player.NavroPlayer;
import ac.navro.anticheat.player.data.PlayerData;
import ac.navro.anticheat.player.data.tracker.FlagTracker;
import ac.navro.anticheat.prediction.fluid.*;
import ac.navro.anticheat.util.math.*;
import cn.nukkit.*;
import cn.nukkit.block.Block;
import cn.nukkit.item.Item;
import cn.nukkit.item.RuntimeItems;
import cn.nukkit.math.Vector2;
import cn.nukkit.math.Vector2f;
import cn.nukkit.math.Vector3f;
import cn.nukkit.network.protocol.PlayerAuthInputPacket;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.network.protocol.types.AuthInputAction;
import cn.nukkit.network.protocol.types.PlayerAbility;
import com.google.gson.*;
import sun.misc.Unsafe;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroMovementStateFixture {
    static void set(Object o,Class<?> c,String name,Object value)throws Exception { NavroItemUseTrackerFixture.set(o,c,name,value); }
    static Map<String,Object> state(NavroPlayer p)throws Exception {
        var out=NavroMovementMetadataFixture.pose(p);
        out.put("flags",new TreeSet<>(p.getFlagTracker().cloneFlags()));
        out.put("predictionSprinting",p.isPredictionSprinting());out.put("predictionSwimming",p.isPredictionSwimming());
        out.put("flying",p.getFlagTracker().isFlying());out.put("wasFlying",p.getFlagTracker().isWasFlying());
        out.put("speed",Float.floatToRawIntBits(p.attributes.get("minecraft:movement").getValue()));
        return out;
    }
    public static void main(String[] args)throws Exception {
        var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var u=(Unsafe)f.get(null);NavroItemUseTrackerFixture.unsafe=u;
        set(null,Server.class,"instance",u.allocateInstance(Server.class));Navro.setConfig(new ac.navro.anticheat.config.Config());
        Block.init();RuntimeItems.init();Item.init();cn.nukkit.potion.Effect.init();cn.nukkit.entity.Attribute.init();
        var random=new Random(0x535441544541434bL);var gson=new GsonBuilder().serializeNulls().create();int sessions=32,steps=128;
        String[] actions={"START_SPRINTING","STOP_SPRINTING","START_SNEAKING","STOP_SNEAKING","START_SWIMMING","STOP_SWIMMING","START_CRAWLING","STOP_CRAWLING","START_GLIDING","STOP_GLIDING","START_FLYING","STOP_FLYING","SPRINTING"};
        try(var out=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(args[0])),StandardCharsets.UTF_8))) {
            out.write(gson.toJson(Map.of("magic","NMS1","sessions",sessions,"steps",steps)));out.newLine();
            for(int sn=0;sn<sessions;sn++) {
                var p=(NavroPlayer)u.allocateInstance(NavroPlayer.class);var session=(NavroItemUseTrackerFixture.Session)u.allocateInstance(NavroItemUseTrackerFixture.Session.class);session.protocol=ProtocolInfo.CURRENT_PROTOCOL;
                set(p,NavroPlayer.class,"session",session);set(p,PlayerData.class,"flagTracker",new FlagTracker());p.getFlagTracker().set(19,true);
                set(p,PlayerData.class,"fluidPrediction",new FluidPredictionState());set(p,PlayerData.class,"attributes",new HashMap<String,AttributeInstance>());p.attributes.put("minecraft:movement",new AttributeInstance(0.1F));
                set(p,PlayerData.class,"abilities",new HashSet<PlayerAbility>());set(p,NavroPlayer.class,"itemUseTracker",new ItemUseTracker(p));
                set(p,NavroPlayer.class,"itemUseDebug",new ItemUseDebug(p));set(p,NavroPlayer.class,"swimDebug",new SwimDebug(p));
                set(p,NavroPlayer.class,"movementSampler",new NearbyBlockSampler(p,NearbyBlockSampler.Kind.MOVEMENT));set(p,NavroPlayer.class,"trapdoorSampler",new NearbyBlockSampler(p,NearbyBlockSampler.Kind.TRAPDOOR));
                set(p,NavroPlayer.class,"compensatedInventory",new CompensatedInventory(p));p.dimensions=EntityDimensions.changing(0.6F,1.8F).withEyeHeight(1.62F);p.boundingBox=Box.EMPTY;p.position=new Vec3(sn*123.456F,sn-16.25F,-sn*123.456F);

                p.setResolvedPosition(p.position,Box.EMPTY,false);
                for(int step=0;step<steps;step++) {
                    int op=step<10?new int[]{1,0,1,0,2,0,1,3,4,0}[step]:random.nextInt(5);var row=new LinkedHashMap<String,Object>();row.put("op",op);
                    if(op==0) {
                        var packet=new PlayerAuthInputPacket();packet.setTick(step);packet.setMotion(new Vector2(0,1));packet.setRawMoveVector(new Vector2f(0,random.nextBoolean()?1:-1));packet.setPosition(new Vector3f(p.position.x,p.position.y,p.position.z));
                        var requested=new ArrayList<String>();for(String name:actions)if(random.nextInt(5)==0){packet.getInputData().add(AuthInputAction.valueOf(name));requested.add(name);}
                        if(step==1){packet.getInputData().clear();packet.getInputData().add(AuthInputAction.START_SPRINTING);requested=new ArrayList<>(List.of("START_SPRINTING"));packet.setRawMoveVector(new Vector2f(0,1));}
                        if(step==3){packet.getInputData().clear();requested.clear();}
                        row.put("actions",requested);row.put("forward",packet.getRawMoveVector().getY());p.tick=step;
                        LegacyAuthInputPackets.processAuthInput(p,packet,true);row.put("accepted",packet.getInputData().stream().map(Enum::name).sorted().toList());
                    } else if(op==1) {
                        boolean base=random.nextBoolean(),extended=random.nextBoolean();if(!base&&!extended)base=true;
                        var flags=new HashSet<Integer>();for(int bit:new int[]{1,3,19,32,56,57,63,64,71,76,92,109,114,127})if(random.nextBoolean()&&(bit<64?base:extended))flags.add(bit);
                        if(step==0){base=true;extended=false;flags.clear();flags.addAll(Set.of(1,3,57));}
                        if(step==2){base=false;extended=true;flags.clear();flags.add(114);}
                        Float width=random.nextInt(4)==0?0.58F:null,height=random.nextInt(4)==0?new float[]{0.625F,1.49F,1.8F}[random.nextInt(3)]:null,scale=random.nextInt(8)==0?1.001F:null;
                        row.put("base",base);row.put("extended",extended);row.put("flags",flags);row.put("width",width);row.put("height",height);row.put("scale",scale);
                        p.applyClientVisibleMovementMetadata(width,height,scale,flags,base,extended);
                    } else if(op==2) {int count=random.nextInt(2);row.put("count",count);p.compensatedInventory.armorContainer.set(1,Item.get(444,0,count));}
                    else if(op==3) FluidMovement.finishInput(p);
                    else {boolean mayFly=random.nextBoolean();row.put("mayFly",mayFly);p.abilities.clear();if(mayFly)p.abilities.add(PlayerAbility.MAY_FLY);p.getFlagTracker().setFlying(mayFly && p.getFlagTracker().isFlying());}
                    row.put("state",state(p));out.write(gson.toJson(row));out.newLine();
                }
            }
        }
        System.out.println("Original movement-state transitions: "+(sessions*steps));
    }
}
