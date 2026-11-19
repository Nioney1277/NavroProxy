package ac.navro.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import java.util.Objects;
import java.util.UUID;


public record LoginIdentity(UUID uuid,String displayName) {
    public static LoginIdentity fromVerifiedChain(ChainValidationResult chain){
        if(!chain.signed())throw new IllegalArgumentException("Unsigned Bedrock identity");
        var data=chain.identityClaims().extraData;
        return new LoginIdentity(Objects.requireNonNull(data.identity,"Missing Bedrock identity"),
                Objects.requireNonNull(data.displayName,"Missing Bedrock display name"));
    }
}
