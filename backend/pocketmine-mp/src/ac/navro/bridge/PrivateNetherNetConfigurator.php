<?php
declare(strict_types=1);
namespace ac\navro\bridge;

use pocketmine\network\mcpe\nethernet\NetherNetServerConfigurator;
use pocketmine\nethernet\identity\AssertionIdentityVerifier;
use pocketmine\nethernet\server\ServerConfiguration;

final class PrivateNetherNetConfigurator extends NetherNetServerConfigurator{
    public function __construct(private string $proxyPublicKey, private string $advertisedAddress, private int $limit){}
    public function configure(ServerConfiguration $defaults) : ServerConfiguration{
        return new ServerConfiguration(
            identityProvider: $defaults->identityProvider,
            logger: $defaults->logger,
            identityVerifier: new AssertionIdentityVerifier(false, new ProxyNetherNetTokenVerifier($this->proxyPublicKey)),
            transportFactory: $defaults->transportFactory,
            budget: $defaults->budget,
            gatheringTimeout: $defaults->gatheringTimeout,
            channelTimeout: $defaults->channelTimeout,
            maxRemoteCandidates: $defaults->maxRemoteCandidates,
            maxNegotiations: min(32, $this->limit),
            maxNegotiationsPerAddress: min(8, $this->limit),
            advertisedAddresses: [$this->advertisedAddress]
        );
    }
}
