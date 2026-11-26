<?php
declare(strict_types=1);
namespace ac\navro\bridge;

use pocketmine\nethernet\identity\IdentityException;
use pocketmine\nethernet\identity\JsonWebToken;
use pocketmine\nethernet\identity\TokenVerifier;

final class ProxyNetherNetTokenVerifier implements TokenVerifier{
    private \OpenSSLAsymmetricKey $key;
    public function __construct(string $pem){
        $key = openssl_pkey_get_public($pem);
        $details = $key === false ? false : openssl_pkey_get_details($key);
        if($details === false || $details['type'] !== OPENSSL_KEYTYPE_RSA || $details['bits'] < 2048){
            throw new \InvalidArgumentException('Expected a proxy RSA public key of at least 2048 bits');
        }
        $this->key = $key;
    }
    public function check(JsonWebToken $token) : void{
        $compact = $token->toString();
        if(strlen($compact) > 16384) throw new IdentityException('Proxy token too large');
        $parts = explode('.', $compact);
        if(count($parts) !== 3) throw new IdentityException('Invalid proxy token');
        $decode = static function(string $value) : string{
            $raw = base64_decode(strtr($value, '-_', '+/') . str_repeat('=', (4 - strlen($value) % 4) % 4), true);
            if($raw === false) throw new IdentityException('Invalid proxy token encoding');
            return $raw;
        };
        try{
            $header = json_decode($decode($parts[0]), true, flags: JSON_THROW_ON_ERROR);
            $claims = json_decode($decode($parts[1]), true, flags: JSON_THROW_ON_ERROR);
        }catch(\JsonException $e){throw new IdentityException('Invalid proxy token JSON', 0, $e);}
        if(($header['alg'] ?? null) !== 'RS256' || ($header['kid'] ?? null) !== 'navro-proxy' ||
            openssl_verify($parts[0] . '.' . $parts[1], $decode($parts[2]), $this->key, OPENSSL_ALGO_SHA256) !== 1){
            throw new IdentityException('Invalid proxy token signature');
        }
        $aud = $claims['aud'] ?? null;
        $iat = $claims['iat'] ?? null; $exp = $claims['exp'] ?? null; $now = time();
        if(($claims['iss'] ?? null) !== 'navro-proxy' || ($claims['sub'] ?? null) !== 'navro-proxy' ||
            !($aud === 'urn:navro:nethernet-backend' || is_array($aud) && in_array('urn:navro:nethernet-backend', $aud, true)) ||
            !is_int($iat) || !is_int($exp) || $iat > $now + 5 || $exp <= $now || $exp <= $iat || $exp - $iat > 120){
            throw new IdentityException('Invalid proxy token claims');
        }
        // The native AssertionIdentityVerifier also verifies the P-384 proof against DTLS.
    }
}
