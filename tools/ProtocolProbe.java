import ac.navro.proxy.SupportedCodecs;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.*;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.PacketSignal;
import java.net.*;
import java.util.concurrent.*;


public final class ProtocolProbe {
 public static void main(String[] args) throws Exception {
  int port=Integer.parseInt(args[0]), protocol=Integer.parseInt(args[1]);
  boolean compressed=args.length>2;
  var codec=SupportedCodecs.forProtocol(protocol);
  System.out.println("PROBE protocol="+protocol+" codec="+codec.getMinecraftVersion());
  var result=new CompletableFuture<String>();
  var group=new NioEventLoopGroup(1);
  Channel channel=null;
  try {
   channel=new Bootstrap().group(group).channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
    .option(RakChannelOption.RAK_PROTOCOL_VERSION,11)
    .option(RakChannelOption.RAK_CONNECT_TIMEOUT,5000L)
    .handler(new BedrockClientInitializer(){
     public void initSession(BedrockClientSession s){
      s.setCodec(codec);
      s.setPacketHandler(new BedrockPacketHandler(){
       public PacketSignal handlePacket(BedrockPacket p){
        System.out.println("RECEIVED "+p);
        if (compressed && p instanceof NetworkSettingsPacket settings) {
         s.setCompression(settings.getCompressionAlgorithm());
         var preLoginPacket=new ClientCacheStatusPacket();
         preLoginPacket.setSupported(false);
         s.sendPacketImmediately(preLoginPacket);
        } else {
         result.complete(p.getClass().getSimpleName());
        }
        return PacketSignal.HANDLED;
       }
       public void onDisconnect(CharSequence reason){result.completeExceptionally(new IllegalStateException("Disconnected: "+reason));}
      });
      RequestNetworkSettingsPacket request=new RequestNetworkSettingsPacket();
      request.setProtocolVersion(protocol);
      s.sendPacketImmediately(request);
     }
    }).connect(new InetSocketAddress("127.0.0.1",port)).sync().channel();
   System.out.println("RESULT "+result.get(10,TimeUnit.SECONDS));
  } finally {
   if(channel!=null)channel.close().sync();
   group.shutdownGracefully(0,1,TimeUnit.SECONDS).sync();
  }
 }
}
