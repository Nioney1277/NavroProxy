<?php

declare(strict_types=1);

namespace ac\navro\bridge;

use pocketmine\network\mcpe\convert\TypeConverter;
use pocketmine\network\mcpe\nethernet\NetherNetConfig;
use pocketmine\network\mcpe\nethernet\NetherNetInterface;
use pocketmine\network\mcpe\protocol\ProtocolInfo;
use pocketmine\Server;

final class PrivateNetherNet{

    private const LOCAL_ADDRESS = "127.0.0.1";
    private const KEY_ID = "navro-proxy";
    private const AUDIENCE = "urn:navro:nethernet-backend";

    public static function start(NavroBridge $plugin, array $settings) : NetherNetInterface{
        if(!class_exists(NetherNetConfig::class) || !class_exists(NetherNetInterface::class)){
            throw new \RuntimeException("Private NetherNet requires the PocketMine-MP-NG NetherNet interface");
        }

        $server = $plugin->getServer();
        $port = $settings["signaling-port"] ?? 19608;
        if(!is_int($port) || $port < 1 || $port > 65535){
            throw new \InvalidArgumentException("Private NetherNet signaling port must be between 1 and 65535");
        }
        $maxConnections = $settings["max-connections"] ?? 128;
        if(!is_int($maxConnections) || $maxConnections < 1 || $maxConnections > 1024){
            throw new \InvalidArgumentException("Private NetherNet max-connections must be between 1 and 1024");
        }
        $config = NetherNetConfig::fromArray([
            "signaling-port" => $port,
            "bind-address" => self::LOCAL_ADDRESS,
            "ice-bind-address" => self::LOCAL_ADDRESS,
            "ice-port-range" => $settings["ice-port-range"] ?? [20000, 20127],
            "max-connections" => $maxConnections,
            "max-per-ip" => $maxConnections,
            "max-pending-negotiations" => min(32, $maxConnections),
            "trusted-key-files" => [self::KEY_ID => $settings["proxy-public-key-file"] ?? "navro-backend.pub.pem"],
            "issuer" => self::KEY_ID,
            "audience" => self::AUDIENCE,
        ], $plugin->getDataFolder(), $server->getPort(), self::LOCAL_ADDRESS, $server->getMotd());
        if($config === null){
            throw new \RuntimeException("Private NetherNet configuration did not start");
        }
        $advertised = $config->getAdvertisedAddress();
        if($advertised !== null && $advertised !== self::LOCAL_ADDRESS){
            throw new \RuntimeException("Private NetherNet requires loopback ICE, including AMBER_NETHERNET_PUBLIC_IP");
        }

        $converter = TypeConverter::getInstance();
        $broadcaster = $server->getPacketBroadcaster(ProtocolInfo::CURRENT_PROTOCOL);
        $interface = new NetherNetInterface(
            $server,
            $config,
            $broadcaster,
            $server->getEntityEventBroadcaster($broadcaster, $converter),
            $converter,
            identityHandler: $config->createIdentityHandler(),
        );
        if(!$server->getNetwork()->registerInterface($interface)){
            $interface->shutdown();
            throw new \RuntimeException("Private NetherNet interface registration was cancelled");
        }
        return $interface;
    }

    public static function stop(Server $server, NetherNetInterface $interface) : void{
        $network = $server->getNetwork();
        if(in_array($interface, $network->getInterfaces(), true)){
            $network->unregisterInterface($interface);
        }
    }
}
