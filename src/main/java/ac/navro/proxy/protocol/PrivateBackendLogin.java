package ac.navro.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.data.auth.*;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.json.JsonUtil;
import org.jose4j.jwt.JwtClaims;
import java.security.KeyPair;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class PrivateBackendLogin {
    private static final String PROXY_ISSUER="navro-proxy";
    private static final String RAKNET_AUDIENCE="urn:navro:raknet-backend";
    private PrivateBackendLogin(){ }

    public static PrivateKey loadProxySigningKey(Path path)throws Exception{
        String pem=Files.readString(path).strip();
        String begin="-----BEGIN PRIVATE KEY-----",end="-----END PRIVATE KEY-----";
        if(!pem.startsWith(begin)||!pem.endsWith(end))throw new java.security.GeneralSecurityException("Expected an unencrypted PKCS#8 RSA private key");
        byte[] der=Base64.getDecoder().decode(pem.substring(begin.length(),pem.length()-end.length()).replaceAll("\\s",""));
        PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        if(!(key instanceof RSAPrivateKey rsa)||rsa.getModulus().bitLength()<2048)
            throw new java.security.GeneralSecurityException("Backend proxy identity requires an RSA key of at least 2048 bits");
        return key;
    }
    public static LoginPacket encode(int protocol,LoginIdentity verified,Map<String,Object> claims,
                                     byte[] verifiedClientData,KeyPair backendKey)throws Exception{
        return encode(protocol,verified,claims,verifiedClientData,backendKey,null);
    }
    public static LoginPacket encode(int protocol,LoginIdentity verified,Map<String,Object> claims,
                                     byte[] verifiedClientData,KeyPair backendKey,PrivateKey proxySigningKey)throws Exception{
        String cpk=Base64.getEncoder().encodeToString(backendKey.getPublic().getEncoded());
        long now=Instant.now().getEpochSecond();
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("nbf",now-21600);payload.put("exp",now+21600);payload.put("iat",now);
        payload.put("aud","api://auth-minecraft-services/multiplayer");
        payload.put("cpk",cpk);payload.put("xname",verified.displayName());
        payload.put("leguuid",verified.uuid().toString());
        // This value was verified at the public frontend. Preserve it for the
        // private backend's account/OP lookup; the new token still explicitly
        // remains SELF_SIGNED, never presented as a Microsoft signature.
        Object xuid=claims.get("xid");
        if(!(xuid instanceof String)&&claims.get("extraData") instanceof Map<?,?> extra)xuid=extra.get("XUID");
        payload.put("xid",xuid instanceof String value?value:"");payload.put("ipt","");
        payload.put("mid",claims.get("mid") instanceof String value?value:"");
        payload.put("tid",claims.get("tid") instanceof String value?value:"");
        if(proxySigningKey!=null){
            String forwardedXuid=payload.get("xid") instanceof String value?value:"";
            payload.put("proxyAuth",signProxyAssertion(verified,cpk,forwardedXuid,proxySigningKey,now));
        }
        if (protocol < 944) {
            payload.put("identityPublicKey", cpk);
            payload.put("certificateAuthority", true);
            payload.put("extraData", Map.of("identity", verified.uuid().toString(),
                    "displayName", verified.displayName(), "XUID", payload.get("xid")));
            var login = new LoginPacket(); login.setProtocolVersion(protocol);
            login.setAuthPayload(new CertificateChainPayload(
                    List.of(sign(JsonUtil.toJson(payload), backendKey, cpk)), AuthType.SELF_SIGNED));
            login.setClientJwt(sign(new String(verifiedClientData, StandardCharsets.UTF_8), backendKey, cpk));
            return login;
        }
        return packet(protocol,sign(JsonUtil.toJson(payload),backendKey,cpk),verifiedClientData,backendKey);
    }

    private static String signProxyAssertion(LoginIdentity identity,String cpk,String xuid,
                                             PrivateKey signingKey,long now)throws Exception{
        JwtClaims claims=new JwtClaims();
        claims.setIssuer(PROXY_ISSUER);claims.setSubject(PROXY_ISSUER);claims.setAudience(RAKNET_AUDIENCE);
        claims.setIssuedAt(org.jose4j.jwt.NumericDate.fromSeconds(now));
        claims.setExpirationTime(org.jose4j.jwt.NumericDate.fromSeconds(now+60));
        claims.setJwtId(java.util.UUID.randomUUID().toString());
        claims.setStringClaim("cpk",cpk);claims.setStringClaim("leguuid",identity.uuid().toString());
        claims.setStringClaim("xname",identity.displayName());claims.setStringClaim("xid",xuid);
        var signature=new JsonWebSignature();signature.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        signature.setKeyIdHeaderValue(PROXY_ISSUER);signature.setKey(signingKey);signature.setPayload(claims.toJson());
        return signature.getCompactSerialization();
    }
    static LoginPacket packet(int protocol,String token,byte[] verifiedClientData,KeyPair backendKey)throws Exception{
        String cpk=Base64.getEncoder().encodeToString(backendKey.getPublic().getEncoded());
        var login=new LoginPacket();login.setProtocolVersion(protocol);
        login.setAuthPayload(new TokenPayload(token,AuthType.SELF_SIGNED));
        login.setClientJwt(sign(new String(verifiedClientData,StandardCharsets.UTF_8),backendKey,cpk));
        return login;
    }
    private static String sign(String payload,KeyPair key,String cpk)throws Exception{
        var jws=new JsonWebSignature();jws.setAlgorithmHeaderValue("ES384");jws.setHeader("x5u",cpk);
        jws.setPayload(payload);jws.setKey(key.getPrivate());return jws.getCompactSerialization();
    }
}
