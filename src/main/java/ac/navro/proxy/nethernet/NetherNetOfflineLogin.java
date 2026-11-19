package ac.navro.proxy.nethernet;

import ac.navro.proxy.protocol.LoginIdentity;
import org.cloudburstmc.protocol.bedrock.data.auth.*;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.json.JsonUtil;
import java.security.KeyPair;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;





public final class NetherNetOfflineLogin {
    private NetherNetOfflineLogin(){ }
    public static LoginPacket encode(int protocol,LoginIdentity verified,Map<String,Object> claims,
                                     byte[] verifiedClientData,KeyPair backendKey)throws Exception{
        String cpk=Base64.getEncoder().encodeToString(backendKey.getPublic().getEncoded());
        long now=Instant.now().getEpochSecond();
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("nbf",now-21600);payload.put("exp",now+21600);payload.put("iat",now);
        payload.put("aud","api://auth-minecraft-services/multiplayer");
        payload.put("cpk",cpk);payload.put("xname",verified.displayName());
        payload.put("leguuid",verified.uuid().toString());



        Object xuid=claims.get("xid");
        if(!(xuid instanceof String)&&claims.get("extraData") instanceof Map<?,?> extra)xuid=extra.get("XUID");
        payload.put("xid",xuid instanceof String value?value:"");payload.put("ipt","");
        payload.put("mid",claims.get("mid") instanceof String value?value:"");
        payload.put("tid",claims.get("tid") instanceof String value?value:"");
        var login=new LoginPacket();login.setProtocolVersion(protocol);
        login.setAuthPayload(new TokenPayload(sign(JsonUtil.toJson(payload),backendKey,cpk),AuthType.SELF_SIGNED));
        login.setClientJwt(sign(new String(verifiedClientData,StandardCharsets.UTF_8),backendKey,cpk));
        return login;
    }
    private static String sign(String payload,KeyPair key,String cpk)throws Exception{
        var jws=new JsonWebSignature();jws.setAlgorithmHeaderValue("ES384");jws.setHeader("x5u",cpk);
        jws.setPayload(payload);jws.setKey(key.getPrivate());return jws.getCompactSerialization();
    }
}
