<?php

declare(strict_types=1);

namespace ac\navro\bridge;

use pocketmine\network\mcpe\auth\ProxyBackendIdentityVerifier;
use pocketmine\network\mcpe\convert\TypeConverter;
use pocketmine\network\mcpe\protocol\ProtocolInfo;
use pocketmine\network\mcpe\raklib\RakLibInterface;
use pocketmine\Server;

final class PrivateRakNet{
	public static function start(NavroBridge $plugin, array $settings) : RakLibInterface{
		$server = $plugin->getServer();
		$bind = $settings["bind-address"] ?? "127.0.0.1";
		$port = $settings["port"] ?? 19133;
		if(!is_string($bind) || $bind === ""){
			throw new \InvalidArgumentException("Private RakNet bind-address must be a non-empty IP address");
		}
		if(!is_int($port) || $port < 1 || $port > 65535 || $port === $server->getPort()){
			throw new \InvalidArgumentException("Private RakNet port must be 1-65535 and differ from the public RakNet port");
		}
		$keyFile = $settings["proxy-public-key-file"] ?? "navro-backend.pub.pem";
		if(!is_string($keyFile) || $keyFile === "" || str_starts_with($keyFile, "/") || str_contains($keyFile, "..")){
			throw new \InvalidArgumentException("Private RakNet proxy-public-key-file must be a file in the plugin data folder");
		}
		$keyPath = $plugin->getDataFolder() . $keyFile;
		$keyPem = @file_get_contents($keyPath);
		if($keyPem === false){
			throw new \RuntimeException("Could not read proxy public key " . $keyPath);
		}
		$authenticator = new ProxyBackendIdentityVerifier($keyPem);
		$converter = TypeConverter::getInstance(ProtocolInfo::CURRENT_PROTOCOL);
		$broadcaster = $server->getPacketBroadcaster(ProtocolInfo::CURRENT_PROTOCOL);
		$interface = new RakLibInterface(
			$server,
			$bind,
			$port,
			false,
			$broadcaster,
			$server->getEntityEventBroadcaster($broadcaster, $converter),
			$converter,
			$authenticator
		);
		if(!$server->getNetwork()->registerInterface($interface)){
			$interface->shutdown();
			throw new \RuntimeException("Private RakNet interface registration was cancelled");
		}
		return $interface;
	}

	public static function stop(Server $server, RakLibInterface $interface) : void{
		$network = $server->getNetwork();
		if(in_array($interface, $network->getInterfaces(), true)){
			$network->unregisterInterface($interface);
		}
	}
}
