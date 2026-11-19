package ac.navro.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtContext;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class LoginIdentityTest {
    @Test void legacyVerifiedIdentityKeepsItsOriginalNameAndUuid(){
        UUID id=UUID.fromString("00000000-0000-0000-0000-000000000007");
        var result=new ChainValidationResult(true,Map.of("identityPublicKey","fixture", "extraData",Map.of("displayName","LegacyPlayer","identity",id.toString(),"XUID","123")));
        assertEquals(new LoginIdentity(id,"LegacyPlayer"),LoginIdentity.fromVerifiedChain(result));
    }
    @Test void modernVerifiedIdentityUsesSdkNormalizedClaimsWithoutRawExtraData(){
        var claims=new JwtClaims();claims.setClaim("cpk","fixture");claims.setClaim("xname","OakenApple11053");claims.setClaim("xid","123456789");claims.setClaim("mid","fixture-minecraft-id");
        var result=new ChainValidationResult(true,new JwtContext(claims,List.of()));assertFalse(result.rawIdentityClaims().containsKey("extraData"));
        var identity=LoginIdentity.fromVerifiedChain(result);assertEquals("OakenApple11053",identity.displayName());assertEquals(UUID.nameUUIDFromBytes("pocket-auth-1-xuid:123456789".getBytes(StandardCharsets.UTF_8)),identity.uuid());
    }
    @Test void unsignedOrMissingDisplayNameCannotRegisterAnAlertIdentity(){
        assertThrows(IllegalArgumentException.class,()->LoginIdentity.fromVerifiedChain(new ChainValidationResult(false,Map.of())));
        var claims=new JwtClaims();claims.setClaim("cpk","fixture");claims.setClaim("xid","123");var result=new ChainValidationResult(true,new JwtContext(claims,List.of()));
        assertThrows(NullPointerException.class,()->LoginIdentity.fromVerifiedChain(result));
    }
}
