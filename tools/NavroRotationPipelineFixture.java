package ac.navro.anticheat.check.impl.place;

import ac.navro.anticheat.Navro;
import ac.navro.anticheat.config.Config;
import cn.nukkit.event.server.*;
import cn.nukkit.network.protocol.*;
import cn.nukkit.network.protocol.types.*;
import cn.nukkit.inventory.transaction.data.UseItemData;
import cn.nukkit.math.*;
import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;





public final class NavroRotationPipelineFixture {
    static Object call(Object target,String name,Object...args)throws Exception {
        Method selected=null;
        for(var m:target.getClass().getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){selected=m;break;}
        if(selected==null)throw new NoSuchMethodException(name);selected.setAccessible(true);return selected.invoke(target,args);
    }
    static Object field(Object o,String name)throws Exception {var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static Map<String,Object> snapshot(Object fixture,DataPacketReceiveEvent event)throws Exception {
        Object probe=call(fixture,"check"),session=call(fixture,"session");
        var row=new LinkedHashMap<String,Object>();row.put("flags",field(probe,"flags"));row.put("blockResyncs",field(probe,"blockResyncs"));row.put("slotResyncs",field(probe,"slotResyncs"));
        row.put("applied",((List<?>)field(probe,"applied")).size());row.put("nativePlacements",field(session,"nativePlacements"));row.put("pending",call(fixture,"pendingCount"));row.put("verbose",field(probe,"lastVerbose"));
        row.put("cancelled",event!=null&&event.isCancelled());row.put("embedded",event!=null&&event.getPacket() instanceof PlayerAuthInputPacket input&&input.getItemUseTransaction()!=null);return row;
    }
    public static void main(String[] args)throws Exception {
        Navro.setConfig(new Config());Class<?> fixtureType=Class.forName("ac.navro.anticheat.check.impl.place.RotationPlaceTest$Fixture");
        var create=fixtureType.getDeclaredMethod("create");create.setAccessible(true);var gson=new GsonBuilder().serializeNulls().create();int rows=0;
        try(var out=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(args[0])),StandardCharsets.UTF_8))) {
            out.write(gson.toJson(Map.of("magic","NRP1","sessions",48,"steps",12)));out.newLine();
            for(int sn=0;sn<48;sn++) {
                Object fixture=create.invoke(null),probe=call(fixture,"check");
                for(int step=0;step<12;step++) {
                    var row=new LinkedHashMap<String,Object>();DataPacketReceiveEvent event=null;
                    int op=step<2?0:step==2||step==6?1:step==3||step==7?0:step==4?2:step==5?3:step==8?4:step==9?5:step==10?1:0;
                    row.put("op",op);
                    if(op==0||op==5) {
                        long tick=598+(step<2?step:step-1);if(step==3)tick=600;if(step==7)tick=603;if(step==11)tick=605;
                        float angle=sn%2==0?0:180;boolean touch=sn%4>=2;int camera=sn%3-1;
                        var packet=(PlayerAuthInputPacket)call(fixture,"input",tick,.5F,angle);
                        packet.setInputMode(touch?InputMode.TOUCH:InputMode.MOUSE);packet.setInteractionModel(touch?AuthInteractionModel.CLASSIC:AuthInteractionModel.CROSSHAIR);
                        packet.setCameraOrientation(camera==0?null:new Vector3f(0,0,camera));
                        if(sn%6==0&&step==7)packet.setItemUseTransaction((InventoryTransactionPacket)call(fixture,"transaction"));
                        row.put("tick",tick);row.put("angle",angle);row.put("touch",touch);row.put("camera",camera);row.put("withEmbedded",packet.getItemUseTransaction()!=null);
                        event=(DataPacketReceiveEvent)call(fixture,"begin",packet);if(op==5)event.setCancelled(true);((RotationPlace)probe).finishPacket(event);
                    } else if(op==1) {
                        var packet=(InventoryTransactionPacket)call(fixture,"transaction");var data=(UseItemData)packet.transactionData;
                        data.clientInteractPrediction=sn%5==0?0:1;data.triggerType=sn%3;
                        row.put("prediction",data.clientInteractPrediction);row.put("trigger",data.triggerType);
                        event=(DataPacketReceiveEvent)call(fixture,"begin",packet);((RotationPlace)probe).finishPacket(event);
                    } else if(op==2) {
                        for(Object task:List.copyOf((List<?>)field(probe,"expiries")))((Runnable)task).run();
                    } else if(op==3) {
                        Object world=call(fixture,"world");call(world,"stone",0,65,1);
                    } else if(op==4) {
                        ((RotationPlace)probe).onPacketSend(new DataPacketSendEvent((cn.nukkit.Player)call(fixture,"session"),new CorrectPlayerMovePredictionPacket()));
                    }
                    row.put("state",snapshot(fixture,event));out.write(gson.toJson(row));out.newLine();rows++;
                }
            }
        }System.out.println("Original RotationPlace receive/monitor transitions="+rows);
    }
}
