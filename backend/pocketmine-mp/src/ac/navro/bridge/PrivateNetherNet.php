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
        if(!class_exists(NetherNetConfig::class) && class_exists(\pocketmine\network\mcpe\nethernet\NetherNetServerConfigurator::class)){
            return self::startCurrent($plugin, $settings);
        }
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
            "bind-address" => $settings["signaling-bind-address"] ?? self::LOCAL_ADDRESS,
            "ice-bind-address" => $settings["ice-bind-address"] ?? self::LOCAL_ADDRESS,
            "ice-port-range" => $settings["ice-port-range"] ?? [20000, 20127],
            "max-connections" => $maxConnections,
            "max-per-ip" => $maxConnections,
            "max-pending-negotiations" => min(32, $maxConnections),
            "trusted-key-files" => [self::KEY_ID => $settings["proxy-public-key-file"] ?? "navro-backend.pub.pem"],
            "issuer" => self::KEY_ID,
            "audience" => self::AUDIENCE,
        ], $plugin->getDataFolder(), $server->getPort(), $settings["advertised-address"] ?? self::LOCAL_ADDRESS, $server->getMotd());
        if($config === null){
            throw new \RuntimeException("Private NetherNet configuration did not start");
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

    private static function startCurrent(NavroBridge $plugin, array $settings) : NetherNetInterface{
        $server = $plugin->getServer();
        $port = $settings['signaling-port'] ?? 19608;
        $limit = $settings['max-connections'] ?? 128;
        if(!is_int($port) || $port < 1 || $port > 65535 || !is_int($limit) || $limit < 1 || $limit > 1024){
            throw new \InvalidArgumentException('Invalid private NetherNet port or connection limit');
        }
        $bind = $settings['signaling-bind-address'] ?? self::LOCAL_ADDRESS;
        $advertised = $settings['advertised-address'] ?? self::LOCAL_ADDRESS;
        $ice = \pocketmine\network\mcpe\nethernet\NetherNetIceConfiguration::parse(
            [], $settings['ice-port-range'] ?? [20000, 20127], true,
            $settings['ice-bind-address'] ?? self::LOCAL_ADDRESS, [$advertised]);
        $keyName = $settings['proxy-public-key-file'] ?? 'navro-backend.pub.pem';
        if(!is_string($keyName) || str_starts_with($keyName, '/') || str_contains($keyName, '..')){
            throw new \InvalidArgumentException('Proxy public key must stay in the plugin data folder');
        }
        $key = @file_get_contents($plugin->getDataFolder() . $keyName);
        if($key === false) throw new \RuntimeException('Could not read private NetherNet proxy public key');
        new ProxyNetherNetTokenVerifier($key);
        $identityFile = $plugin->getDataFolder() . 'private-nethernet-identity.pem';
        if(!is_file($identityFile)){
            $identity = openssl_pkey_new(['private_key_type' => OPENSSL_KEYTYPE_EC, 'curve_name' => 'secp384r1']);
            if($identity === false || !openssl_pkey_export($identity, $pem) || file_put_contents($identityFile, $pem, LOCK_EX) === false){
                throw new \RuntimeException('Could not generate private NetherNet server identity');
            }
            @chmod($identityFile, 0600);
        }
        $pem = file_get_contents($identityFile);
        if($pem === false) throw new \RuntimeException('Could not read private NetherNet server identity');
        $factories = new \pmmp\thread\ThreadSafeArray();
        $factories[] = new \pocketmine\network\mcpe\nethernet\NetherNetBuiltinSignalingFactory(
            $bind, $port, null, null, null, null, null, 'X-Forwarded-For', self::LOCAL_ADDRESS, 0, random_int(1, PHP_INT_MAX));
        $converter = TypeConverter::getInstance();
        $broadcaster = $server->getPacketBroadcaster(ProtocolInfo::CURRENT_PROTOCOL);
        $interface = new NetherNetInterface($server, $pem, self::KEY_ID, $ice, 1400, $factories,
            new PrivateNetherNetConfigurator($key, $advertised, $limit), $broadcaster,
            $server->getEntityEventBroadcaster($broadcaster, $converter), $converter);
        if(!$server->getNetwork()->registerInterface($interface)){
            $interface->shutdown();
            throw new \RuntimeException('Private NetherNet interface registration was cancelled');
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
