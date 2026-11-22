package ac.navro.proxy.data.inventory;

import ac.navro.proxy.util.ItemUtil;
import ac.navro.proxy.util.NbtNumber;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPInputStream;


public final class NativeItemDecoder {
    public enum Format { INVENTORY, TRANSACTION, SLOT, DESCRIPTOR }
    private record Binding(int factory,int fixedDamage){}
    private record Protocol(boolean inventoryDescriptor,boolean transactionDescriptor,boolean slotDescriptor,
                            Map<String,Binding> bindings,Map<Integer,Integer> forward,
                            Map<Integer,String> reverse,Map<String,Integer> named){
        boolean descriptor(Format format){return switch(format){
            case INVENTORY,DESCRIPTOR->inventoryDescriptor;case TRANSACTION->transactionDescriptor;case SLOT->slotDescriptor;};}
    }
    private record Factory(int base,Integer number,String name,boolean statefulDefault,int[] nbtEnds,NbtMap[] nbtTags,int mode,int constant,int[] damage,int[] exceptions,
                           int[] namespaceEnds,String[] namespaces,byte[] extra){
        NbtMap defaultTag(int raw){if(raw<0||raw>65535)throw new IllegalArgumentException("Native constructor damage is not exported: "+raw);int i=Arrays.binarySearch(nbtEnds,raw);return nbtTags[i>=0?i:-i-1];}
        int damage(int raw){return switch(mode){case 0->raw;case 1->constant;case 2->damage[raw];case 3->{int i=Arrays.binarySearch(exceptions,raw);yield i>=0?damage[i]:raw;}default->throw new AssertionError(mode);};}
        String namespace(int raw){int i=Arrays.binarySearch(namespaceEnds,raw);return namespaces[i>=0?i:-i-1];}
    }
    private record Table(int enchantmentCount,Map<Integer,Protocol> protocols,Factory[] factories,Map<Integer,Integer> numeric,Map<String,Integer> named){}
    private record ExtraRow(int[] ends,NativePredictionItems.Traits[] traits){}
    private static final Map<Integer,ExtraRow> EXTRA_CACHE=new LinkedHashMap<>(16,.75F,true){
        @Override protected boolean removeEldestEntry(Map.Entry<Integer,ExtraRow> entry){return size()>4;}
    };
    private static final NativePredictionItems.Traits AIR=new NativePredictionItems.Traits(0,true,false,0,false,"","");
    private static final NativePredictionItems.Traits SERVER_ITEM =
            new NativePredictionItems.Traits(-1, false, false, 0, false, "", "");
    private static final NativeItemPlacementProperties.Properties SERVER_PLACEMENT =
            new NativeItemPlacementProperties.Properties(false, false, 0, 0, 0);
    private static final Table TABLE=load();
    private NativeItemDecoder(){}


    private record Definition(int protocol,int factory,int rawDamage,String identifier,int runtimeId,
                              ItemDefinition wire) implements ItemDefinition {
        public String getIdentifier(){return identifier;}
        public int getRuntimeId(){return runtimeId;}
        public boolean isComponentBased(){return wire!=null&&wire.isComponentBased();}
        public NbtMap getComponentData(){return wire==null?null:wire.getComponentData();}
    }
    public static void initialize(){if(TABLE.factories.length==0)throw new IllegalStateException("Empty native item decoder");}

    public static ItemData constructNumeric(int protocol,int id,int rawDamage,int count) {
        Protocol version=Objects.requireNonNull(TABLE.protocols.get(protocol),"Native constructor protocol");
        int factory=numeric(id);Factory constructor=TABLE.factories[factory];
        if(constructor.statefulDefault)throw new IllegalArgumentException("Stateful constructor requires authoritative NBT");
        if(rawDamage<0||rawDamage>65535)throw new IllegalArgumentException("Native constructor damage is not exported: "+rawDamage);
        var traits=traits(factory,rawDamage);int damage=constructor.damage(rawDamage);String namespace=constructor.namespace(rawDamage);int runtime;
        if(namespace!=null)runtime=version.named.getOrDefault(namespace,-1);
        else{int full=((short)traits.nativeId()<<16)|((damage&0x7fff)<<1);
            runtime=version.forward.getOrDefault(full,version.forward.getOrDefault((short)traits.nativeId()<<16,Integer.MIN_VALUE));
            namespace=traits.nativeId()==0?"minecraft:air":version.reverse.get(runtime);}
        return ItemData.builder().definition(new Definition(protocol,factory,rawDamage,namespace,runtime,null))
                .damage(damage).count(count).tag(ItemUtil.copyTag(constructor.defaultTag(rawDamage))).build();
    }
    public static ItemData decode(int protocol,ItemData wire){return decode(protocol,wire,Format.INVENTORY);}
    public static ItemData decode(int protocol,ItemData wire,Format format){
        if(wire==null)return null;
        if(wire.getDefinition() instanceof Definition decoded){
            if(decoded.protocol()!=protocol)throw new IllegalArgumentException("Decoded item belongs to another protocol");
            return wire;
        }
        Protocol version=TABLE.protocols.get(protocol);
        if(version==null)throw new IllegalArgumentException("Native item decoding is not exported for protocol "+protocol);
        boolean descriptor=version.descriptor(format);
        if(wire.getDefinition()==null || (!descriptor&&wire.getDefinition().getRuntimeId()==0))return canonicalAir(protocol);
        Binding binding=wire.getDefinition().getRuntimeId()==0?new Binding(numeric(0),-1):version.bindings.get(wire.getDefinition().getIdentifier());
        if (binding == null && isCustomDefinition(wire.getDefinition())) return wire;
        if(binding==null)throw new IllegalArgumentException("Native item definition is not exported: "+wire.getDefinition().getIdentifier());
        int factory=binding.factory(),rawDamage=binding.fixedDamage()>=0?binding.fixedDamage():wire.getDamage();
        NbtMap tag=ItemUtil.copyTag(wire.getTag());
        if(tag!=null&&!tag.isEmpty()){
            var tags=tag.toBuilder();
            if(tag.containsKey("Damage")){rawDamage=NbtNumber.getInt(tag,"Damage");tags.remove("Damage");}
            if(tag.containsKey("__DamageConflict__")){tags.put("Damage",tag.get("__DamageConflict__"));tags.remove("__DamageConflict__");}
            tag=tags.build();
            if(!descriptor&&tag.get("ench") instanceof org.cloudburstmc.nbt.NbtList<?> ench&&ench.size()>TABLE.enchantmentCount*1.5)
                throw new RuntimeException("Too many enchantment: "+ench.size());
        }
        Factory constructor=TABLE.factories[factory];
        boolean restore=tag!=null&&tag.containsKey("mv_origin_id")&&tag.containsKey("mv_origin_meta")
                &&(descriptor||Objects.equals(constructor.number,248));
        if(restore){
            int id=NbtNumber.getInt(tag,"mv_origin_id");rawDamage=NbtNumber.getInt(tag,"mv_origin_meta");
            factory=id==255&&tag.containsKey("mv_origin_namespace")?named(string(tag,"mv_origin_namespace")):numeric(id);
            constructor=TABLE.factories[factory];
            if(tag.containsKey("mv_origin_nbt"))tag=(NbtMap)tag.get("mv_origin_nbt");
            else {if(constructor.statefulDefault)throw new IllegalArgumentException("Stateful native map constructor requires origin NBT");tag=constructor.defaultTag(rawDamage);}
        }else{
            if(constructor.statefulDefault&&constructor.number!=null&&(tag==null||tag.isEmpty()))
                throw new IllegalArgumentException("Stateful native map constructor requires decoded NBT");
            if(constructor.number!=null&&(tag==null||tag.isEmpty()))tag=constructor.defaultTag(rawDamage);
            if(!descriptor&&constructor.number==null&&wire.getDamage()>32767)throw new NumberFormatException("Value out of range. Value:\""+wire.getDamage()+"\" Radix:10");
            var tags=tag==null?NbtMap.builder():tag.toBuilder();
            if(wire.getCanBreak()!=null&&wire.getCanBreak().length>0)tags.putList("CanDestroy",NbtType.STRING,wire.getCanBreak());
            if(wire.getCanPlace()!=null&&wire.getCanPlace().length>0)tags.putList("CanPlaceOn",NbtType.STRING,wire.getCanPlace());
            tag=tags.build();
        }
        if(rawDamage<0||rawDamage>65535)throw new IllegalArgumentException("Native constructor damage is not exported: "+rawDamage);
        var traits=traits(factory,rawDamage);int damage=constructor.damage(rawDamage);
        String namespace=constructor.namespace(rawDamage);int runtime;
        if(namespace!=null)runtime=version.named.getOrDefault(namespace,-1);
        else{
            int full=((short)traits.nativeId()<<16)|((damage&0x7fff)<<1);
            runtime=version.forward.getOrDefault(full,version.forward.getOrDefault((short)traits.nativeId()<<16,Integer.MIN_VALUE));
            namespace=traits.nativeId()==0?"minecraft:air":version.reverse.get(runtime);
        }
        int netId=descriptor?(wire.getNetId()>0?wire.getNetId():0):wire.getNetId();
        ItemData result=wire.toBuilder().definition(new Definition(protocol,factory,rawDamage,namespace,runtime,restore?null:wire.getDefinition()))
                .damage(damage).count(wire.getCount()).tag(tag==null||tag.isEmpty()?null:ItemUtil.copyTag(tag))
                .canPlace(new String[0]).canBreak(new String[0]).netId(netId).usingNetId(netId>0).build();



        boolean namedTagSetter=restore || (wire.getCanPlace()!=null&&wire.getCanPlace().length>0)
                || (wire.getCanBreak()!=null&&wire.getCanBreak().length>0);
        if(namedTagSetter&&ItemUtil.isBundle(result)&&result.getTag()!=null&&result.getTag().containsKey("bundle_id"))
            NbtNumber.getInt(result.getTag(),"bundle_id");
        return result;
    }
    private static ItemData canonicalAir(int protocol){return ItemData.AIR.toBuilder()
            .definition(new Definition(protocol,numeric(0),0,"minecraft:air",TABLE.protocols.get(protocol).forward.getOrDefault(0,Integer.MIN_VALUE),null)).build();}
    private static int numeric(int id){Integer value=TABLE.numeric.get(id);if(value==null)throw new IllegalArgumentException("Native numeric constructor is not exported: "+id);return value;}
    private static int named(String name){
        String normalized=name.trim().replace(' ','_').toLowerCase(Locale.ROOT);
        if(normalized.indexOf(':')<0)normalized="minecraft:"+normalized;
        Integer value=TABLE.named.get(normalized);
        if(value==null)throw new IllegalArgumentException("Native named constructor is not exported: "+name);
        return value;
    }
    private static String string(NbtMap tag,String key){Object value=tag.get(key);return value==null?"":value instanceof Number?String.valueOf(value):(String)value;}

    private static boolean isCustomDefinition(ItemDefinition definition) {
        String identifier = definition.getIdentifier();
        return identifier != null && identifier.indexOf(':') > 0 && !identifier.startsWith("minecraft:");
    }

    public static boolean usesBackendDefinition(int protocol, ItemData item) {
        if (item == null || item.getDefinition() == null || item.getDefinition().getRuntimeId() == 0
                || !isCustomDefinition(item.getDefinition())) return false;
        Protocol version = TABLE.protocols.get(protocol);
        return version != null && !version.bindings.containsKey(item.getDefinition().getIdentifier());
    }

    public static NativePredictionItems.Traits traits(int protocol,ItemData item){
        if(item==null)return AIR;
        ItemData decoded=decode(protocol,item);
        if (!(decoded.getDefinition() instanceof Definition definition)) return SERVER_ITEM;
        return traits(definition.factory,definition.rawDamage);
    }



    public static NativeItemPlacementProperties.Properties placement(int protocol,ItemData item){
        return placement(protocol,item,Format.INVENTORY);
    }
    public static NativeItemPlacementProperties.Properties placement(int protocol,ItemData item,Format format){
        ItemData decoded=Objects.requireNonNull(decode(protocol,item,format),"Native placement item");
        if (!(decoded.getDefinition() instanceof Definition definition)) return SERVER_PLACEMENT;
        Factory constructor=TABLE.factories[definition.factory];
        return constructor.number!=null
                ?NativeItemPlacementProperties.forNumeric(constructor.number,definition.rawDamage)
                :NativeItemPlacementProperties.forIdentifier(constructor.name,definition.rawDamage);
    }
    private static NativePredictionItems.Traits traits(int factory,int damage){
        Factory constructor=TABLE.factories[factory];
        if(constructor.base>=0)return NativePredictionItems.factoryTraits(constructor.base,damage);
        synchronized(EXTRA_CACHE){
            ExtraRow row=EXTRA_CACHE.get(factory);
            if(row==null){
                try(var in=new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(constructor.extra)))){
                    int size=in.readInt();int[] ends=new int[size];var traits=new NativePredictionItems.Traits[size];
                    for(int i=0;i<size;i++){ends[i]=in.readUnsignedShort();traits[i]=NativePredictionItems.readTraits(in);}
                    row=new ExtraRow(ends,traits);EXTRA_CACHE.put(factory,row);
                }catch(IOException e){throw new IllegalStateException("Invalid native constructor traits",e);}
            }
            int index=Arrays.binarySearch(row.ends,damage);return row.traits[index>=0?index:-index-1];
        }
    }
    private static int count(int value,int min,int max)throws IOException{if(value<min||value>max)throw new IOException("Invalid native decoder count: "+value);return value;}
    private static void ranges(int[] ends)throws IOException{
        if(ends.length==0||ends[ends.length-1]!=65535)throw new IOException("Incomplete native metadata range");
        for(int i=1;i<ends.length;i++)if(ends[i]<=ends[i-1])throw new IOException("Unordered native metadata range");
    }
    private static Factory readFactory(DataInputStream in,int index,Map<Integer,Integer> numeric,Map<String,Integer> named,Map<NbtMap,NbtMap> defaults)throws IOException{
                    int base=in.readInt();Integer number=null;String name=null;
                    if(in.readBoolean()){number=in.readInt();if(numeric.putIfAbsent(number,index)!=null)throw new IOException("Duplicate numeric constructor");}else{name=in.readUTF();if(named.putIfAbsent(name,index)!=null)throw new IOException("Duplicate named constructor");}
                    boolean stateful=in.readBoolean();int nbtCount=count(in.readInt(),1,65536);int[] nbtEnds=new int[nbtCount];NbtMap[] nbtTags=new NbtMap[nbtCount];
                    for(int j=0;j<nbtCount;j++){nbtEnds[j]=in.readUnsignedShort();int nbtSize=count(in.readInt(),0,32*1024*1024);
                        if(nbtSize>0){try(var nbt=org.cloudburstmc.nbt.NbtUtils.createReaderLE(new ByteArrayInputStream(in.readNBytes(nbtSize)))){NbtMap parsed=(NbtMap)nbt.readTag();nbtTags[j]=defaults.computeIfAbsent(parsed,key->key);}}}
                    ranges(nbtEnds);
                    int mode=count(in.readUnsignedByte(),0,3),constant=mode==1?in.readInt():0;int[] damage=null;
                    int[] exceptions=null;
                    if(mode==2){damage=new int[65536];for(int j=0;j<damage.length;j++)damage[j]=in.readInt();}
                    if(mode==3){int size=count(in.readInt(),0,65536);exceptions=new int[size];damage=new int[size];for(int j=0;j<size;j++){exceptions[j]=in.readUnsignedShort();damage[j]=in.readInt();}}
                    int size=count(in.readInt(),1,65536);int[] ends=new int[size];String[] namespaces=new String[size];
                    for(int j=0;j<size;j++){ends[j]=in.readUnsignedShort();if(in.readBoolean())namespaces[j]=in.readUTF();}
                    ranges(ends);
                    if(base < -1||base>=NativePredictionItems.factoryCount())throw new IOException("Invalid base constructor");
                    byte[] extra=base<0?in.readNBytes(count(in.readInt(),1,32*1024*1024)):null;
                    return new Factory(base,number,name,stateful,nbtEnds,nbtTags,mode,constant,damage,exceptions,ends,namespaces,extra);
    }
    private static Factory[] supplementaryBindings(Map<Integer,Protocol> versions,Factory[] baseFactories,Map<Integer,Integer> numeric,Map<String,Integer> named,Map<NbtMap,NbtMap> defaults)throws IOException,NoSuchAlgorithmException{
        try(var resource=NativeItemDecoder.class.getResourceAsStream("/navro/item-runtime-bindings.bin.gz")){
            if(resource==null)throw new IOException("Missing supplementary native runtime bindings");
            try(var in=new DataInputStream(new GZIPInputStream(resource))){
                if(in.readInt()!=0x4e494232)throw new IOException("Invalid supplementary native runtime bindings");
                byte[] digest=in.readNBytes(32);
                try(var directory=NativeItemDecoder.class.getResourceAsStream("/navro/item-decoder.bin.gz")){
                    if(directory==null||!Arrays.equals(digest,MessageDigest.getInstance("SHA-256").digest(directory.readAllBytes())))throw new IOException("Native runtime bindings directory digest mismatch");
                }
                int protocols=count(in.readInt(),1,100);if(protocols!=versions.size())throw new IOException("Native runtime binding protocol count mismatch");
                record Pending(Protocol version,String wireName,Integer number,String name,int fixed){}
                var pending=new ArrayList<Pending>();Set<Integer> seen=new HashSet<>();
                for(int p=0;p<protocols;p++){
                    int protocol=in.readInt();Protocol version=versions.get(protocol);
                    if(version==null||!seen.add(protocol))throw new IOException("Invalid native runtime binding protocol");
                    int size=count(in.readInt(),0,100000);
                    for(int i=0;i<size;i++){
                        String wireName=in.readUTF();Integer number=null;String name=null;
                        if(in.readBoolean())number=in.readInt();else name=in.readUTF();
                        pending.add(new Pending(version,wireName,number,name,in.readInt()));
                    }
                }
                var factories=new ArrayList<Factory>(Arrays.asList(baseFactories));int extras=count(in.readInt(),0,100000);
                for(int i=0;i<extras;i++){Factory factory=readFactory(in,factories.size(),numeric,named,defaults);if(factory.base()!=-1)throw new IOException("Invalid supplementary constructor traits");factories.add(factory);}
                for(var binding:pending){
                    Integer factory=binding.number()!=null?numeric.get(binding.number()):named.get(binding.name());
                    if(factory==null)throw new IOException("Unexported supplementary native constructor: "+binding.wireName());
                    if(binding.version().bindings.putIfAbsent(binding.wireName(),new Binding(factory,binding.fixed()))!=null)throw new IOException("Duplicate native wire binding: "+binding.wireName());
                }
                if(in.read()!=-1)throw new IOException("Trailing supplementary native runtime bindings");return factories.toArray(Factory[]::new);
            }
        }
    }
    private static Table load(){
        try(var resource=NativeItemDecoder.class.getResourceAsStream("/navro/item-decoder.bin.gz")){
            if(resource==null)throw new IOException("Missing native item decoder");
            try(var in=new DataInputStream(new GZIPInputStream(resource))){
                if(in.readInt()!=0x4e494434)throw new IOException("Invalid native item decoder");
                int enchantmentCount=in.readInt();
                byte[] digest=in.readNBytes(32);
                try(var base=NativeItemDecoder.class.getResourceAsStream("/navro/prediction-items.bin.gz")){
                    if(base==null||!Arrays.equals(digest,MessageDigest.getInstance("SHA-256").digest(base.readAllBytes())))throw new IOException("Native constructor traits digest mismatch");
                }
                if(in.readInt()!=NativePredictionItems.factoryCount())throw new IOException("Native constructor count mismatch");
                int protocols=in.readInt();var versions=new HashMap<Integer,Protocol>();
                for(int p=0;p<protocols;p++){
                    int protocol=in.readInt();boolean inventory=in.readBoolean(),transaction=in.readBoolean(),slot=in.readBoolean();
                    int size=in.readInt();var bindings=new HashMap<String,Binding>();for(int i=0;i<size;i++)bindings.put(in.readUTF(),new Binding(in.readInt(),in.readInt()));
                    size=in.readInt();var forward=new HashMap<Integer,Integer>();for(int i=0;i<size;i++)forward.put(in.readInt(),in.readInt());
                    size=in.readInt();var reverse=new HashMap<Integer,String>();for(int i=0;i<size;i++)reverse.put(in.readInt(),in.readUTF());
                    size=in.readInt();var named=new HashMap<String,Integer>();for(int i=0;i<size;i++)named.put(in.readUTF(),in.readInt());
                    versions.put(protocol,new Protocol(inventory,transaction,slot,bindings,forward,reverse,named));
                }
                int count=in.readInt();Factory[] factories=new Factory[count];var defaults=new HashMap<NbtMap,NbtMap>();var numeric=new HashMap<Integer,Integer>();var named=new HashMap<String,Integer>();
                for(int i=0;i<count;i++){
                    factories[i]=readFactory(in,i,numeric,named,defaults);
                }
                if(in.read()!=-1)throw new IOException("Trailing native item decoder data");
                Factory[] all=supplementaryBindings(versions,factories,numeric,named,defaults);
                return new Table(enchantmentCount,versions,all,numeric,named);
            }
        }catch(IOException|NoSuchAlgorithmException e){throw new IllegalStateException("Cannot load native item decoder",e);}
    }
}
