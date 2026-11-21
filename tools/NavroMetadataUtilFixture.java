import ac.navro.anticheat.util.EntityMetadataUtil;
import cn.nukkit.entity.data.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;


public final class NavroMetadataUtilFixture {
    static final class Metadata extends EntityMetadata {
        final EntityMetadata delegate;final List<String> calls=new ArrayList<>();Metadata(EntityMetadata metadata){delegate=metadata;}
        @Override public boolean exists(int id){calls.add("exists:"+id);return delegate.exists(id);}
        @Override public float getFloat(int id){calls.add("float:"+id);return delegate.getFloat(id);}
        @Override public long getLong(int id){calls.add("long:"+id);return delegate.getLong(id);}
    }
    static Set<Integer> values(Random random,int n){if(n%29==0)return null;Set<Integer> s=new HashSet<>();for(int value:new int[]{-1,0,1,4,57,63,64,92,127,128,Integer.MIN_VALUE,Integer.MAX_VALUE})if(random.nextBoolean())s.add(value);if(n%23==0)s.add(null);return s;}
    static Object sorted(Set<Integer> s){return s==null?null:new TreeSet<>(s);}
    public static void main(String[] args)throws Exception{
        Random random=new Random(0x4d455441L);Gson gson=new GsonBuilder().serializeNulls().create();int cases=4096;long[] longs={0,1,-1,Long.MIN_VALUE,Long.MAX_VALUE,0x123456789abcdefL};int[] floats={0,0x80000000,0x3f800000,0x7fc00000,0x7f800000,0xff800000,0x00000001,0x7f7fffff};int[] flagIds={Integer.MIN_VALUE,-1,0,1,4,57,63,64,92,127,128,Integer.MAX_VALUE};
        try(var out=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(args[0])),StandardCharsets.UTF_8))){out.write("{\"magic\":\"NMU1\",\"cases\":"+cases+"}\n");
            for(int n=0;n<cases;n++){
                boolean nil=n%17==0,base=random.nextBoolean(),ext=random.nextBoolean(),hasFloat=random.nextBoolean(),baseWord=random.nextBoolean(),extWord=random.nextBoolean(),b=random.nextBoolean(),c=random.nextBoolean();long rawBase=longs[random.nextInt(longs.length)],rawExt=longs[random.nextInt(longs.length)];int floatKind=n%7==0?1:n%11==0?2:0,floatBits=floats[random.nextInt(floats.length)],key=n%19==0?0:n%31==0?92:53,flag=flagIds[random.nextInt(flagIds.length)];
                var nativeData=new EntityMetadata();if(base)nativeData.put(baseWord?new LongEntityData(0,rawBase):new FloatEntityData(0,1.25F));if(ext)nativeData.put(extWord?new LongEntityData(92,rawExt):new FloatEntityData(92,1.25F));if(hasFloat)nativeData.put(floatKind==0?new FloatEntityData(53,Float.intBitsToFloat(floatBits)):floatKind==1?new IntEntityData(53,42):new StringEntityData(53,null));Metadata metadata=nil?null:new Metadata(nativeData);Set<Integer> current=values(random,n),update=values(random,n+1);
                Map<String,Object> row=new LinkedHashMap<>();row.put("nil",nil);row.put("base",base);row.put("ext",ext);row.put("baseWord",baseWord);row.put("extWord",extWord);row.put("rawBase",rawBase);row.put("rawExt",rawExt);row.put("hasFloat",hasFloat);row.put("floatKind",floatKind);row.put("floatBits",floatBits);row.put("key",key);row.put("flag",flag);row.put("current",current);row.put("update",update);row.put("b",b);row.put("c",c);
                for(String method:new String[]{"float","copy","has","merge"}){if(metadata!=null)metadata.calls.clear();Object result=null;String error="";try{result=switch(method){case "float"->{Float value=EntityMetadataUtil.getFloat(metadata,key);yield value==null?null:Float.floatToRawIntBits(value);}case "copy"->sorted(EntityMetadataUtil.copyFlags(metadata));case "has"->EntityMetadataUtil.hasFlag(metadata,flag);default->sorted(EntityMetadataUtil.mergeFlags(current,update,b,c));};}catch(Exception e){error=e.getClass().getSimpleName();}row.put(method+"Result",result);row.put(method+"Error",error);row.put(method+"Calls",metadata==null?List.of():new ArrayList<>(metadata.calls));}
                out.write(gson.toJson(row));out.newLine();
            }
        }
        System.out.println("Original metadata utility: "+cases+" typed metadata and word-merge cases, nulls, cast failures, getter order and numeric boundaries.");
    }
}
