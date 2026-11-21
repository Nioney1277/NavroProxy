package ac.navro.proxy;

import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.*;
import org.cloudburstmc.protocol.bedrock.codec.*;
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings;
import org.cloudburstmc.protocol.bedrock.data.definitions.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.*;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;


public final class ItemProtocolRegression {
 static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static ByteBuf encode(BedrockCodec codec,BedrockCodecHelper helper,BedrockPacket packet){
  ByteBuf bytes=Unpooled.buffer();codec.tryEncode(helper,bytes,packet);return bytes;
 }
 static void checkVersion(int version){
  var codec=SupportedCodecs.forProtocol(version);
  var defs=new ProtocolDefinitions();
  var backend=codec.createHelper();var client=codec.createHelper();
  defs.configure(backend,true);defs.configure(client,false);
  var component=new ItemComponentPacket();
  for(int id=1;id<=1976;id++)component.getItems().add(new SimpleItemDefinition(
   id==1?"minecraft:stone":id==2?"minecraft:shield":"test:item_"+id,id,ItemVersion.LEGACY,id==1976,
   id==1976?NbtMap.builder().putCompound("components",NbtMap.builder().putInt("test:value",123).build()).build():NbtMap.EMPTY));
  ByteBuf wire=encode(codec,backend,component);
  try{
   boolean oldFailed=false;
   try{codec.tryDecode(codec.createHelper(),wire.duplicate(),codec.getPacketDefinition(ItemComponentPacket.class).getId());}
   catch(RuntimeException expected){oldFailed=true;}
   require(oldFailed,"old 1536-element limit must reproduce");
   var decoded=(ItemComponentPacket)codec.tryDecode(backend,wire.duplicate(),codec.getPacketDefinition(ItemComponentPacket.class).getId());
   require(decoded.getItems().size()==1976,"all 1976 definitions must decode");
   defs.observeBackend(decoded);
   require(client.getItemDefinitions().getDefinition(1)==decoded.getItems().get(0),"both legs must share the actual definition");
   var another=codec.createHelper();new ProtocolDefinitions().configure(another,true);
   require(another.getItemDefinitions().getDefinition(1)==null,"registries must be per session");
   ByteBuf encoded=encode(codec,client,decoded);
   try{require(ByteBufUtil.equals(wire,encoded),"component bytes changed");}finally{encoded.release();}

   var creative=new CreativeContentPacket();
   var stone=ItemData.builder().definition(client.getItemDefinitions().getDefinition(1)).count(64).damage(0)
    .blockDefinition(client.getBlockDefinitions().getDefinition(-123456)).build();
   var shield=ItemData.builder().definition(client.getItemDefinitions().getDefinition(2)).count(1).blockingTicks(987654L)
    .tag(NbtMap.builder().putString("display","§a物品 §r").build()).build();
   creative.getGroups().add(new CreativeItemGroup(CreativeItemCategory.CONSTRUCTION,"itemGroup.name.test",stone));
   creative.getContents().add(new CreativeItemData(shield,1,0));
   for(int n=2;n<=1976;n++)creative.getContents().add(new CreativeItemData(stone,n,0));
   ByteBuf creativeWire=encode(codec,backend,creative);
   try{
    var creativeDecoded=(CreativeContentPacket)codec.tryDecode(backend,creativeWire.duplicate(),codec.getPacketDefinition(CreativeContentPacket.class).getId());
    require(creativeDecoded.getContents().size()==1976,"all creative entries must decode");
    require(creativeDecoded.getContents().get(0).getItem().getBlockingTicks()==987654L,"shield extra bytes lost");
    ByteBuf again=encode(codec,client,creativeDecoded);
    try{require(ByteBufUtil.equals(creativeWire,again),"creative data, runtime IDs, NBT, or color bytes changed");}finally{again.release();}
   }finally{creativeWire.release();}

   var inventory=new InventoryContentPacket();inventory.setContainerId(0);inventory.setContents(List.of(stone,shield));
   ByteBuf inventoryWire=encode(codec,backend,inventory);
   try{
    var decodedInventory=(InventoryContentPacket)codec.tryDecode(client,inventoryWire.duplicate(),codec.getPacketDefinition(InventoryContentPacket.class).getId());
    require(decodedInventory.getContents().get(1).getDefinition().getIdentifier().equals("minecraft:shield"),"inventory mapping lost");
   }finally{inventoryWire.release();}

   var oversized=new ItemComponentPacket();
   for(int n=0;n<=EncodingSettings.CLIENT.maxListSize();n++)oversized.getItems().add(component.getItems().get(0));
   ByteBuf oversizedWire=encode(codec,backend,oversized);
   try{
    boolean rejected=false;
    try{codec.tryDecode(backend,oversizedWire.duplicate(),codec.getPacketDefinition(ItemComponentPacket.class).getId());}
    catch(RuntimeException expected){rejected=true;}
    require(rejected,"bounded server decode limit must remain enforced");
   }finally{oversizedWire.release();}
   System.out.println("PASS protocol="+version+" items=1976 creative=1976 inventory/shield/NBT/colors/session-isolation/bounds");
  }finally{wire.release();}
 }
 static void checkRelay(){
  var sourceChannel=new EmbeddedChannel();var destinationChannel=new EmbeddedChannel();
  var captured=new AtomicReference<BedrockPacketWrapper>();
  var destinationPeer=new BedrockPeer(destinationChannel,(p,id)->new BedrockServerSession(p,id)){
   @Override public void sendPacket(BedrockPacketWrapper packet){captured.set(packet);}
  };
  var sourcePeer=new BedrockPeer(sourceChannel,(p,id)->new BedrockServerSession(p,id));
  var source=new BedrockServerSession(sourcePeer,0);var destination=new BedrockServerSession(destinationPeer,0);
  sourceChannel.pipeline().addLast(BedrockPeer.NAME,new ChannelInboundHandlerAdapter(){
   @Override public void channelRead(ChannelHandlerContext ctx,Object msg){
    try{PacketRelay.forward(source,destination,((BedrockPacketWrapper)msg).getPacket());}
    finally{ReferenceCountUtil.release(msg);}
   }
  });
  PacketRelay.install(sourceChannel);
  byte[] bytes={1,2,3,(byte)0xa7,0,99};
  var packet=new TextPacket();
  var wrapper=BedrockPacketWrapper.create(9,0,0,packet,Unpooled.wrappedBuffer(bytes));
  sourceChannel.writeInbound(wrapper);
  var forwarded=captured.get();
  require(forwarded==wrapper && forwarded.refCnt()==1,"relay reference ownership");
  require(Arrays.equals(ByteBufUtil.getBytes(forwarded.getPacketBuffer()),bytes),"untouched wire bytes changed");
  forwarded.release();require(wrapper.refCnt()==0,"relay reference leak");
  sourceChannel.finishAndReleaseAll();destinationChannel.finishAndReleaseAll();
  System.out.println("PASS untouched packet wire bytes and buffer ownership");
 }
 public static void main(String[] args){
  for(int version:new int[]{924,944,975,1001,2168,2169,2193,2225})checkVersion(version);
  checkRelay();
  if(args.length>0){
   var codec=SupportedCodecs.forProtocol(2168);
   var defs=new ProtocolDefinitions();var helper=codec.createHelper();defs.configure(helper,true);
   byte[] fixture;
   try{fixture=java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[0]));}
   catch(java.io.IOException error){throw new RuntimeException(error);}
   ByteBuf wire=Unpooled.wrappedBuffer(fixture);
   try{
    int header=org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(wire);
    var packet=(ItemComponentPacket)codec.tryDecode(helper,wire,header & 1023);
    require(packet.getItems().size()==1976,"Nukkit fixture item count");
    require(!wire.isReadable(),"Nukkit fixture trailing unread bytes");
    require(packet.getItems().get(1975).getComponentData().getString("label").equals("§a原始颜色 §r"),"Nukkit NBT/color decode");
    ByteBuf encoded=encode(codec,helper,packet);
    try{
     ByteBuf original=Unpooled.wrappedBuffer(fixture);org.cloudburstmc.protocol.common.util.VarInts.readUnsignedInt(original);
     try{require(ByteBufUtil.equals(original,encoded),"Nukkit payload bytes changed");}finally{original.release();}
    }finally{encoded.release();}
    System.out.println("PASS deployed Nukkit 2168 encoder -> proxy decode -> identical payload bytes (1976 entries)");
   }finally{wire.release();}
  }
 }
}
