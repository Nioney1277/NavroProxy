package ac.navro.proxy.util;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class EntityMetadataUtilReferenceTest {
    static boolean b(JsonObject o,String k){return o.get(k).getAsBoolean();}static int i(JsonObject o,String k){return o.get(k).getAsInt();}
    static Set<Integer> set(JsonElement e){if(e.isJsonNull())return null;Set<Integer> values=new HashSet<>();for(var v:e.getAsJsonArray())values.add(v.isJsonNull()?null:v.getAsInt());return values;}
    @Test void originalTypedMetadataMatchesFloatFlagWordsPartialMergeExceptionsAndGetterOrder()throws Exception {
        var gson=new GsonBuilder().serializeNulls().create();int errors=0;
        try(var in=new BufferedReader(new InputStreamReader(new GZIPInputStream(getClass().getResourceAsStream("/navro-metadata-util.jsonl.gz")),StandardCharsets.UTF_8))){var h=JsonParser.parseString(in.readLine()).getAsJsonObject();assertEquals("NMU1",h.get("magic").getAsString());assertEquals(4096,i(h,"cases"));
            for(int n=0;n<4096;n++){
                var r=JsonParser.parseString(in.readLine()).getAsJsonObject();Map<Integer,Object> map=new HashMap<>();if(b(r,"base")){if(b(r,"baseWord"))map.put(0,r.get("rawBase").getAsLong());else map.put(0,1.25F);}if(b(r,"ext")){if(b(r,"extWord"))map.put(92,r.get("rawExt").getAsLong());else map.put(92,1.25F);}if(b(r,"hasFloat")){switch(i(r,"floatKind")){case 0->map.put(53,Float.intBitsToFloat(i(r,"floatBits")));case 1->map.put(53,42);case 2->map.put(53,null);default->throw new AssertionError();}}
                var calls=new ArrayList<String>();EntityMetadataUtil.Metadata metadata=b(r,"nil")?null:new EntityMetadataUtil.Metadata(){public boolean exists(int key){calls.add("exists:"+key);return map.containsKey(key);}public float getFloat(int key){calls.add("float:"+key);return (Float)map.getOrDefault(key,0F);}public long getLong(int key){calls.add("long:"+key);return (Long)map.getOrDefault(key,0L);}};Set<Integer> current=set(r.get("current")),update=set(r.get("update"));
                for(String method:new String[]{"float","copy","has","merge"}){calls.clear();Object result=null;String error="";try{result=switch(method){case "float"->{Float value=EntityMetadataUtil.getFloat(metadata,i(r,"key"));yield value==null?null:Float.floatToRawIntBits(value);}case "copy"->{var value=EntityMetadataUtil.copyFlags(metadata);yield value==null?null:new TreeSet<>(value);}case "has"->EntityMetadataUtil.hasFlag(metadata,i(r,"flag"));default->new TreeSet<>(EntityMetadataUtil.mergeFlags(current,update,b(r,"b"),b(r,"c")));};}catch(RuntimeException e){error=e.getClass().getSimpleName();}String label="native metadata case="+n+" method="+method;assertEquals(r.get(method+"Error").getAsString(),error,label);assertEquals(r.get(method+"Result"),gson.toJsonTree(result),label);assertEquals(r.get(method+"Calls"),gson.toJsonTree(calls),label);if(!error.isEmpty())errors++;}
            }
            assertNull(in.readLine());
        }
        assertTrue(errors>100);
    }
}
