package ac.navro.proxy.data;

import ac.navro.proxy.util.ItemUtil;
import com.google.gson.*;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import java.util.*;


public class ItemUseTestModel implements ItemUseTracker.Context<ItemUseTestModel.Item> {
    public static final class Item {
        public final int index;public final JsonObject profile;public int count;
        public Item(int index,JsonObject profile,int count){this.index=index;this.profile=profile;this.count=count;}
    }
    public final List<Item> inputs=new ArrayList<>();public final Item air;public final Set<Integer> flags=new HashSet<>();public final List<String> debug=new ArrayList<>();public long tick;public int since;public boolean serverUsing;
    public ItemUseTestModel(JsonArray profiles,JsonObject airProfile){air=new Item(-2,airProfile,0);for(int i=0;i<profiles.size();i++)inputs.add(new Item(i,profiles.get(i).getAsJsonObject(),1));}
    public Item item(int index){return index==-1?null:index==-2?air:inputs.get(index);}
    public Item airItem(){return air;}public String identifier(Item item){return item==null||item.profile.get("name").isJsonNull()?null:item.profile.get("name").getAsString();}
    ItemData definition(Item item){return item==null?null:ItemData.builder().definition(new SimpleItemDefinition(identifier(item),item.profile.get("runtime").getAsInt(),false)).count(item.count).build();}
    public boolean sameDefinition(Item p,Item c){return ItemUtil.sameDefinition(definition(p),definition(c));}
    public boolean isNull(Item item){return item.count<=0||item.profile.get("emptyPositive").getAsBoolean();}
    public int nativeItemId(Item item){return item.profile.get("id").getAsInt();}public boolean canRelease(Item item){return item.profile.get("canRelease").getAsBoolean();}public int useDuration(Item item){return item.profile.get("duration").getAsInt();}public boolean hasUseDurationComponent(Item item){return item!=null&&item.profile.get("component").getAsBoolean();}
    public Object foodByRelative(Item item){Objects.requireNonNull(item);String error=item.profile.get("foodError").getAsString();if(!error.isEmpty())throw switch(error){case "NullPointerException"->new NullPointerException();case "IllegalArgumentException"->new IllegalArgumentException();case "IllegalStateException"->new IllegalStateException();case "UnsupportedOperationException"->new UnsupportedOperationException();default->throw new AssertionError("Unbound native food exception "+error);};return item.profile.get("food").getAsBoolean()?Boolean.TRUE:null;}
    public Item cloneItem(Item item){return new Item(item.index,item.profile,item.count);}public long playerTick(){return tick;}public boolean serverUsingItem(){return serverUsing;}public boolean hasFlag(int flag){return flags.contains(flag);}public void setFlag(int flag,boolean value){if(value)flags.add(flag);else flags.remove(flag);}public Set<Integer> cloneFlags(){return new HashSet<>(flags);}public int sinceTridentUse(){return since;}public void sinceTridentUse(int value){since=value;}public void stage(String name,Object...args){debug.add(name+Arrays.toString(args));}public void event(String name,Object...args){debug.add(name+Arrays.toString(args));}
    public Map<String,Object> view(Item item){var m=new LinkedHashMap<String,Object>();if(item==null){m.put("kind","null");return m;}if(item==air){m.put("kind","air");return m;}m.put("kind","item");m.put("index",item.index);m.put("count",item.count);m.put("input",item==inputs.get(item.index));return m;}
    public Map<String,Object> snapshot(ItemUseTracker.Snapshot<Item> s){if(s==null)return null;var m=new LinkedHashMap<String,Object>();m.put("item",view(s.usedItem()));m.put("javaItemId",s.javaItemId());m.put("dirty",s.dirtyUsing()==null?-1:s.dirtyUsing().ordinal());m.put("duration",s.useDuration());m.put("latch",s.useButtonLatched());m.put("pendingRelease",s.buttonReleasePending());m.put("await",s.awaitingMetadataStartConfirmation());m.put("using",s.predictionUsingItem());m.put("action",s.predictionAction());m.put("inputTick",s.predictionInputTick());m.put("factor",Float.floatToRawIntBits(s.predictionItemSlowdownFactor()));m.put("since",s.sinceTridentUse());return m;}
    public Map<String,Object> state(ItemUseTracker<Item> tracker,List<ItemUseTracker.Snapshot<Item>> saved)throws Exception{var m=new LinkedHashMap<String,Object>();m.put("used",view(tracker.getUsedItem()));String[] fields={"javaItemId","useDuration","useButtonLatched","buttonReleasePending","awaitingMetadataStartConfirmation","predictionUsingItem","predictionAction","predictionInputTick","pendingMetadataFalsePackets","lastMetadataTrueSentPlayerTick","lastMetadataFalseAckPlayerTick","lastAcknowledgedMetadataUsing"};for(String name:fields){var f=ItemUseTracker.class.getDeclaredField(name);f.setAccessible(true);m.put(name,f.get(tracker));}m.put("dirty",tracker.getDirtyUsing()==null?-1:tracker.getDirtyUsing().ordinal());m.put("factor",Float.floatToRawIntBits(tracker.getPredictionItemSlowdownFactor()));m.put("slowdown",tracker.hasPredictionItemSlowdown());m.put("spear",tracker.isUsingSpear());m.put("since",since);m.put("flags",new TreeSet<>(cloneFlags()));m.put("snapshot",snapshot(tracker.snapshot()));List<Object> slots=new ArrayList<>();for(var s:saved){if(s==null)slots.add(null);else{var v=snapshot(s);v.put("liveAlias",s.usedItem()==tracker.getUsedItem());slots.add(v);}}m.put("saved",slots);m.put("counts",inputs.stream().map(item->item.count).toList());return m;}
}
