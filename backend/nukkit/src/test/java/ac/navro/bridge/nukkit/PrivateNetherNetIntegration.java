package ac.navro.bridge.nukkit;

import cn.nukkit.Server;
import cn.nukkit.network.Network;
import cn.nukkit.network.NetherNetInterface;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.network.encryption.EncryptionUtils;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.netty.util.nethernet.*;
import org.cloudburstmc.netty.util.nethernet.Identity;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import tel.schich.libdatachannel.LibDataChannelArchDetect;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;

import static ac.navro.bridge.nukkit.NativeStateSyncIntegration.*;

public final class PrivateNetherNetIntegration {
    static String sign(JwtClaims claims,PrivateKey key,String algorithm) throws Exception {
        var signature=new JsonWebSignature();signature.setAlgorithmHeaderValue(algorithm);
        signature.setKeyIdHeaderValue(ProxyTokenTrust.ISSUER);signature.setKey(key);signature.setPayload(claims.toJson());
        return signature.getCompactSerialization();
    }
    static JwtClaims tokenClaims(PublicKey key) {
        var claims=new JwtClaims();claims.setIssuer(ProxyTokenTrust.ISSUER);claims.setSubject(ProxyTokenTrust.ISSUER);
        claims.setAudience(ProxyTokenTrust.AUDIENCE);claims.setIssuedAtToNow();claims.setExpirationTimeMinutesInTheFuture(1);
        claims.setStringClaim("cpk",Base64.getEncoder().encodeToString(key.getEncoded()));return claims;
    }
    static Identity identity(String token) {return new Identity(new Identity.Idp("navro-proxy",""),new Identity.Assertion(token,""));}
    static void rejects(Checked task,String message) throws Exception {
        try {task.run();}catch(Exception expected){return;}throw new AssertionError(message);
    }
    interface Checked {void run() throws Exception;}
    public static void main(String[] args) throws Exception {
        var folder=Files.createTempDirectory("navro-private-nethernet-");
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var rsa=generator.generateKeyPair();
        var session=EncryptionUtils.createKeyPair();
        var pem=folder.resolve("navro-backend.pub.pem");
        Files.writeString(pem,"-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder().encodeToString(rsa.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n");
        var trust=new ProxyTokenTrust(pem);var valid=tokenClaims(session.getPublic());
        check(trust.claims(identity(sign(valid,rsa.getPrivate(),"RS256"))).getIssuer().equals("navro-proxy"),"Valid proxy token");
        rejects(()->trust.claims(identity(sign(valid,generator.generateKeyPair().getPrivate(),"RS256"))),"Wrong RSA key accepted");
        var expired=tokenClaims(session.getPublic());expired.setExpirationTime(NumericDate.fromSeconds(System.currentTimeMillis()/1000-30));
        rejects(()->trust.claims(identity(sign(expired,rsa.getPrivate(),"RS256"))),"Expired token accepted");
        var audience=tokenClaims(session.getPublic());audience.setAudience("other-service");
        rejects(()->trust.claims(identity(sign(audience,rsa.getPrivate(),"RS256"))),"Wrong audience accepted");
        rejects(()->trust.claims(identity(sign(valid,session.getPrivate(),"ES384"))),"Wrong algorithm accepted");
        var loginClaims=new JwtClaims();loginClaims.setAudience("api://auth-minecraft-services/multiplayer");
        loginClaims.setIssuedAtToNow();loginClaims.setExpirationTimeMinutesInTheFuture(2);
        loginClaims.setStringClaim("cpk",Base64.getEncoder().encodeToString(session.getPublic().getEncoded()));
        loginClaims.setStringClaim("leguuid",UUID.randomUUID().toString());
        loginClaims.setStringClaim("xname","ProxyPlayer");loginClaims.setStringClaim("xid","123456789");
        var clientClaims=new JwtClaims();clientClaims.setStringClaim("GameVersion","1.26.50");
        String gameToken=sign(loginClaims,session.getPrivate(),"ES384");
        String clientToken=sign(clientClaims,session.getPrivate(),"ES384");
        var payload=new cn.nukkit.utils.BinaryStream();
        byte[] auth=("{\"AuthenticationType\":2,\"Token\":\""+gameToken+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        payload.putLInt(auth.length);payload.put(auth);
        byte[] client=clientToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);payload.putLInt(client.length);payload.put(client);
        var channel=new EmbeddedChannel();
        try {
            var peer=new PlayerInfo("","","",new InetSocketAddress("127.0.0.1",19132),null,valid);
            channel.attr(org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel.PLAYER_INFO).set(peer);
            TransportIdentityBinding.install(channel,TransportIdentityBinding.forPlayer(peer));
            var forwarded=PrivateNetherNetPlayer.verifyForwardedLogin(payload.getBuffer(),channel);
            check(forwarded.getStringClaimValue("leguuid").equals(loginClaims.getStringClaimValue("leguuid")),"Verified game UUID preserved");
            for(String authJson:List.of("{\"chain\":[\""+gameToken+"\"]}",
                    new com.google.gson.Gson().toJson(Map.of("AuthenticationType",2,"Token","", "Certificate","{\"chain\":[\""+gameToken+"\"]}")))) {
                var certificatePayload=new cn.nukkit.utils.BinaryStream();
                byte[] encodedAuth=authJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                certificatePayload.putLInt(encodedAuth.length);certificatePayload.put(encodedAuth);
                certificatePayload.putLInt(client.length);certificatePayload.put(client);
                var legacy=PrivateNetherNetPlayer.verifyForwardedLogin(certificatePayload.getBuffer(),channel);
                check(legacy.getStringClaimValue("leguuid").equals(loginClaims.getStringClaimValue("leguuid")),"Legacy certificate identity preserved");
            }
            var other=tokenClaims(EncryptionUtils.createKeyPair().getPublic());
            channel.attr(org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel.PLAYER_INFO).set(new PlayerInfo("","","",null,null,other));
            rejects(()->PrivateNetherNetPlayer.verifyForwardedLogin(payload.getBuffer(),channel),"Mismatched session key accepted");
        } finally {channel.finishAndReleaseAll();}
        var unbound=new EmbeddedChannel();
        try {
            rejects(()->PrivateNetherNetPlayer.verifyForwardedLogin(payload.getBuffer(),unbound),"Missing RSA transport binding accepted");
        } finally {unbound.finishAndReleaseAll();}
        var player=(PrivateNetherNetPlayer)U.allocateInstance(PrivateNetherNetPlayer.class);
        UUID account=UUID.randomUUID();
        set(player,PrivateNetherNetPlayer.class,"forwardedUuid",account);
        set(player,PrivateNetherNetPlayer.class,"forwardedXuid","123456789");
        var original=(cn.nukkit.utils.LoginChainData)java.lang.reflect.Proxy.newProxyInstance(
                cn.nukkit.utils.LoginChainData.class.getClassLoader(),new Class<?>[]{cn.nukkit.utils.LoginChainData.class},
                (proxy,method,values)->method.getReturnType()==boolean.class?false:null);
        set(player,cn.nukkit.Player.class,"loginChainData",original);
        player.applyForwardedIdentity();player.applyForwardedIdentity();
        check(player.getUniqueId().equals(account),"Forwarded UUID replaces offline-name UUID");
        check(player.getLoginChainData().getClientUUID("NormalizedName").equals(account),"Normalized name keeps forwarded UUID");
        check(player.getLoginChainData().getXUID().equals("123456789"),"Forwarded XUID retained");
        check(!player.getLoginChainData().isXboxAuthed(),"Proxy auth is not presented as a Microsoft signature");
        var settings=PrivateNetherNetSettings.parse(Map.of(),folder);
        check(settings.signalingPort()==19608&&settings.iceBegin()==20000&&settings.iceEnd()==20127,"PMMP defaults");
        rejects(()->PrivateNetherNetSettings.parse(Map.of("ice-port-range",List.of(21000,20000)),folder),"Reversed ICE range accepted");
        rejects(()->PrivateNetherNetSettings.parse(Map.of("signaling-port",0),folder),"Zero port accepted");
        rejects(()->PrivateNetherNetSettings.parse(Map.of("advertised-address","0.0.0.0"),folder),"Wildcard advertised address accepted");
        System.out.println("PRIVATE_NETHERNET_AUTH_PASSED valid RSA, wrong key, expiry, audience, algorithm and settings");
        if (args.length==0 || !args[0].equals("--network")) return;
        LibDataChannelArchDetect.initialize();
        var server=(Server)U.allocateInstance(Server.class);set(null,Server.class,"instance",server);
        server.motd="Navro private integration";server.setMaxPlayers(20);
        set(server,Server.class,"playerList",new HashMap<>());
        var network=(Network)U.allocateInstance(Network.class);
        set(network,Network.class,"interfaces",new CopyOnWriteArraySet<>());set(network,Network.class,"advancedInterfaces",new HashSet<>());
        set(server,Server.class,"network",network);
        var plugin=new NavroFireworkBridge();set(plugin,PluginBase.class,"server",server);
        var nativeInterface=(NetherNetInterface)U.allocateInstance(NetherNetInterface.class);
        int port;
        try(var probe=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=probe.getLocalPort();}
        var liveSettings=PrivateNetherNetSettings.parse(Map.of("signaling-port",port),folder);
        var listener=new PrivateNetherNet(plugin,nativeInterface,liveSettings);
        try {
            var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/join")).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(response.statusCode()==200&&response.body().contains(server.motd),"Real HTTP status endpoint");
            var buffer=io.netty.buffer.Unpooled.buffer().writeByte(1);
            listener.sendRawPacket(new InetSocketAddress("127.0.0.1",9),buffer);
            check(buffer.refCnt()==1,"Shared network buffer must not be released");buffer.release();
        } finally {listener.shutdown();}
        try(var probe=new ServerSocket(port,1,InetAddress.getByName("127.0.0.1"))) { }
        System.out.println("PRIVATE_NETHERNET_LISTENER_PASSED real signaling, status, buffer ownership and shutdown");
    }
}
