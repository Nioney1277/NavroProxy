package ac.navro.proxy.state;

import ac.navro.anticheat.data.*;
import ac.navro.anticheat.util.math.*;
import ac.navro.proxy.data.*;
import ac.navro.proxy.prediction.fluid.*;
import ac.navro.proxy.player.data.tracker.FlagTracker;
import com.google.gson.*;
import java.util.*;


class MovementMetadataTestModel extends ItemUseTestModel implements MovementMetadata.Context<ItemUseTestModel.Item> {
    final FlagTracker<Item> trackedFlags=new FlagTracker<>();
    final ItemUseTracker<Item> tracker=new ItemUseTracker<>(this);
    final FluidPoseState pose=new FluidPoseState();
    final WirePlayerState geometry=new WirePlayerState();
    final List<String> events=new ArrayList<>();
    Item chest;boolean serverSprinting,clientSprinting,clientSwimming,live=true;
    String failAt;
    MovementMetadataTestModel(JsonObject h,int session){super(h.getAsJsonArray("profiles"),h.getAsJsonObject("air"));tick=session;geometry.setResolvedPosition(new Vec3(session*123.456F,session-16.25F,-session*123.456F),Box.EMPTY,false);}
    void note(String event){events.add(event);if(event.equals(failAt))throw new IllegalStateException(event);}
    @Override public boolean hasFlag(int f){return trackedFlags.has(f);}
    @Override public void setFlag(int f,boolean value){trackedFlags.set(f,value);}
    @Override public Set<Integer> cloneFlags(){return trackedFlags.cloneFlags();}
    public FlagTracker<Item> getFlagTracker(){return trackedFlags;}
    public ItemUseTracker<Item> getItemUseTracker(){return tracker;}
    public FluidPoseState fluidPrediction(){return pose;}
    public EntityDimensions dimensions(){return geometry.dimensions();}
    public Box boundingBox(){return geometry.boundingBox();}
    public MovementAabbSnapshot snapshotAabb(){note("snapshotAabb");return geometry.snapshotAabb();}
    public void restoreAabb(MovementAabbSnapshot saved,Vec3 target){note("restoreAabb");geometry.restoreAabb(saved,target);}
    public void applyDimensions(EntityDimensions d){note("applyDimensions");geometry.applyDimensions(d);}
    public void setServerSprinting(boolean value){note("serverSprinting");serverSprinting=value;}
    public void setClientSwimming(boolean value){note("clientSwimming");clientSwimming=value;trackedFlags.set(57,value);}
    public Item compensatedChest(){note("compensatedChest");return chest;}
    public boolean isLivePlayer(){return live;}
    public void movementSamplerPoseDimensions(EntityDimensions before,Box box,boolean horizontal){note("movementSampler");}
    public void trapdoorSamplerPoseDimensions(EntityDimensions before,Box box,boolean horizontal){note("trapdoorSampler");}
    public void trace(String name,Object...values){note(name);}
    @Override public void stage(String name,Object...values){note(name);super.stage(name,values);}
    static int bits(float value){return Float.floatToRawIntBits(value);}
    static Object dims(EntityDimensions d){return d==null?null:List.of(bits(d.width()),bits(d.height()),bits(d.eyeHeight()),d.fixed());}
    static Object vec(Vec3 v){return List.of(bits(v.x),bits(v.y),bits(v.z));}
    static Object box(Box b){return List.of(bits(b.minX),bits(b.minY),bits(b.minZ),bits(b.maxX),bits(b.maxY),bits(b.maxZ));}
    static Object snapshot(MovementAabbSnapshot s){return s==null?null:Map.of("dimensions",dims(s.dimensions()),"position",vec(s.position()),"box",box(s.box()),"revision",s.resizeRevision());}
    Map<String,Object> poseState()throws Exception{
        var m=new LinkedHashMap<String,Object>();m.put("dimensions",dims(dimensions()));m.put("position",vec(geometry.position()));m.put("box",box(boundingBox()));m.put("revision",geometry.aabbResizeRevision());m.put("serverSprinting",serverSprinting);m.put("clientSprinting",clientSprinting);m.put("clientSwimming",clientSwimming);
        for(String n:new String[]{"wasSwimming","wasCrawling","horizontalPoseApplied","inputHorizontalPoseApplied","inputNonCrawlingHorizontal","inputActive","readyPoseBeforeMovement","inputReadyPoseBeforeMovement"})m.put(n,FluidPoseState.class.getField(n).get(pose));
        for(String n:new String[]{"dimensionsBeforeHorizontalPose","inputDimensions","inputStandingDimensions","pendingPoseDimensions","readyPoseDimensions","inputReadyPoseDimensions"})m.put(n,dims((EntityDimensions)FluidPoseState.class.getField(n).get(pose)));
        m.put("inputAabb",snapshot(pose.inputAabb));return m;
    }
}
