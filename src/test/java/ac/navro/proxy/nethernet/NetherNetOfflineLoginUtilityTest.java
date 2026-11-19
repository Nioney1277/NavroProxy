package ac.navro.proxy.nethernet;

import ac.navro.proxy.protocol.LoginIdentity;
import org.cloudburstmc.protocol.bedrock.data.auth.*;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.json.JsonUtil;
import org.junit.jupiter.api.Test;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;





class NetherNetOfflineLoginUtilityTest {
    static KeyPair keys()throws Exception{
        var generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp384r1"));return generator.generateKeyPair();
    }
    @Test void offlineTokenAndClientDataShareUpstreamKeyAndNeverClaimAMicrosoftSignature()throws Exception{
        var key=keys();var originalKey=keys();
        String data="{\"DeviceOS\":1,\"SkinId\":\"fixture\",\"ClientRandomId\":123}";
        var who=new LoginIdentity(UUID.fromString("11111111-2222-3333-8444-555555555555"),"BridgeTest");
        var result=NetherNetOfflineLogin.encode(2225,who,Map.of("xid","1234567890123456","mid","recorded-mid"),data.getBytes(StandardCharsets.UTF_8),key);
        assertEquals(AuthType.SELF_SIGNED,result.getAuthPayload().getAuthType());
        var payload=(TokenPayload)result.getAuthPayload();var token=new JsonWebSignature();token.setCompactSerialization(payload.getToken());token.setKey(key.getPublic());
        assertEquals("ES384",token.getAlgorithmHeaderValue());assertTrue(token.verifySignature());
        var claims=JsonUtil.parseJson(token.getPayload());
        assertEquals(Base64.getEncoder().encodeToString(key.getPublic().getEncoded()),claims.get("cpk"));
        assertEquals(who.uuid().toString(),claims.get("leguuid"));assertEquals("BridgeTest",claims.get("xname"));
        assertEquals("1234567890123456",claims.get("xid"));assertEquals("api://auth-minecraft-services/multiplayer",claims.get("aud"));
        var skin=new JsonWebSignature();skin.setCompactSerialization(result.getClientJwt());skin.setKey(key.getPublic());
        assertTrue(skin.verifySignature());assertEquals(data,skin.getPayload());
        skin.setKey(originalKey.getPublic());assertFalse(skin.verifySignature(),"Frontend private key must not be used for the upstream login");
    }
}
