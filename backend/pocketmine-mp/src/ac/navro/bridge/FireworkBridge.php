<?php

declare(strict_types=1);

namespace ac\navro\bridge;

use pocketmine\entity\object\ElytraFireworkRocket;
use pocketmine\event\EventPriority;
use pocketmine\event\server\DataPacketSendEvent;
use pocketmine\network\mcpe\NetworkSession;
use pocketmine\network\mcpe\protocol\AddActorPacket;
use pocketmine\network\mcpe\protocol\MovementEffectPacket;
use pocketmine\network\mcpe\protocol\ProtocolInfo;
use pocketmine\network\mcpe\protocol\SetActorMotionPacket;
use pocketmine\network\mcpe\protocol\types\MovementEffectType;
use pocketmine\network\mcpe\protocol\types\entity\EntityIds;
use pocketmine\player\Player;
use pocketmine\scheduler\ClosureTask;
use WeakMap;

final class FireworkBridge{
    private const MIN_PROTOCOL = 748;
    private const BOOST_DURATION = 1_000_000;


    private WeakMap $active;

    private array $spawns = [];

    public function __construct(private readonly NativeStateSync $stateSync){
        $this->active = new WeakMap();
    }

    public static function isSupported() : bool{
        return class_exists(ElytraFireworkRocket::class) && class_exists(MovementEffectPacket::class)
            && defined(MovementEffectType::class . "::GLIDE_BOOST");
    }

    public function register(NavroBridge $plugin) : void{
        $manager = $plugin->getServer()->getPluginManager();
        $manager->registerEvent(DataPacketSendEvent::class, $this->filterRocketMotion(...), EventPriority::HIGHEST, $plugin);
        $manager->registerEvent(DataPacketSendEvent::class, $this->observeSpawns(...), EventPriority::MONITOR, $plugin);
        $plugin->getScheduler()->scheduleRepeatingTask(new ClosureTask($this->tick(...)), 1);
    }

    private function observeSpawns(DataPacketSendEvent $event) : void{
        foreach($event->getPackets() as $packet){
            if(!$packet instanceof AddActorPacket || $packet->type !== EntityIds::FIREWORKS_ROCKET){
                continue;
            }
            foreach($event->getTargets() as $session){
                $player = $session->getPlayer();
                if($player === null || !$this->stateSync->hasSession($session)){
                    continue;
                }
                $protocol = method_exists($session, "getProtocolId") ? $session->getProtocolId() : ProtocolInfo::CURRENT_PROTOCOL;
                if($protocol < self::MIN_PROTOCOL){
                    continue;
                }
                $rocket = $player->getWorld()->getEntity($packet->actorRuntimeId);
                if($rocket instanceof ElytraFireworkRocket && $rocket->getOwningEntityId() === $player->getId()){
                    $this->spawns[] = [$event, $packet, $session, $rocket];
                }
            }
        }
    }

    private function tick() : void{
        $spawns = $this->spawns;
        $this->spawns = [];
        foreach($spawns as [$event, $packet, $session, $rocket]){
            $player = $session->getPlayer();
            if(!$session->isConnected() || $player === null || $event->isCancelled()
                || !in_array($packet, $event->getPackets(), true) || !$this->isActiveRocket($rocket, $player)){
                continue;
            }
            $rockets = $this->active[$session] ?? [];
            if($rockets === [] && !$this->sendEffect($session, $player, self::BOOST_DURATION)){
                continue;
            }
            $rockets[$rocket->getId()] = true;
            $this->active[$session] = $rockets;
        }

        foreach($this->active as $session => $rockets){
            $player = $session->getPlayer();
            if(!$session->isConnected() || $player === null){
                unset($this->active[$session]);
                continue;
            }
            foreach($rockets as $id => $_){
                $rocket = $player->getWorld()->getEntity($id);
                if(!$rocket instanceof ElytraFireworkRocket || !$this->isActiveRocket($rocket, $player)){
                    unset($rockets[$id]);
                }
            }
            if($rockets === []){
                if($this->sendEffect($session, $player, 0)){
                    unset($this->active[$session]);
                }
            }else{
                $this->active[$session] = $rockets;
            }
        }
    }

    private function isActiveRocket(ElytraFireworkRocket $rocket, Player $player) : bool{
        return !$rocket->isClosed() && !$rocket->isFlaggedForDespawn() && $player->isAlive() && $player->isGliding()
            && $rocket->getWorld() === $player->getWorld() && $rocket->getOwningEntityId() === $player->getId();
    }

    private function filterRocketMotion(DataPacketSendEvent $event) : void{
        $targets = $event->getTargets();
        if(count($targets) !== 1){
            return;
        }
        $session = reset($targets);
        $player = $session->getPlayer();
        $rockets = $this->active[$session] ?? [];
        if($rockets === [] || $player === null){
            return;
        }
        $packets = $event->getPackets();
        $filtered = array_values(array_filter($packets, function($packet) use ($player, $rockets) : bool{
            if(!$packet instanceof SetActorMotionPacket || $packet->actorRuntimeId !== $player->getId()){
                return true;
            }
            $rocket = $this->directRocketCaller($player);
            return $rocket === null || !isset($rockets[$rocket->getId()]);
        }));
        if($filtered === []){
            $event->cancel();
        }elseif(count($filtered) !== count($packets)){
            $event->setPackets($filtered);
        }
    }

    private function directRocketCaller(Player $player) : ?ElytraFireworkRocket{
        $frames = debug_backtrace(DEBUG_BACKTRACE_PROVIDE_OBJECT | DEBUG_BACKTRACE_IGNORE_ARGS);
        foreach($frames as $index => $frame){
            if(($frame["object"] ?? null) === $player && $frame["function"] === "setMotion"){
                $caller = $frames[$index + 1] ?? [];
                $rocket = $caller["object"] ?? null;
                return $rocket instanceof ElytraFireworkRocket && ($caller["function"] ?? "") === "entityBaseTick" ? $rocket : null;
            }
        }
        return null;
    }

    private function sendEffect(NetworkSession $session, Player $player, int $duration) : bool{
        return $session->sendDataPacket(MovementEffectPacket::create($player->getId(), MovementEffectType::GLIDE_BOOST, $duration, 0));
    }

    public function clear() : void{
        foreach($this->active as $session => $_){
            $player = $session->getPlayer();
            if($session->isConnected() && $player !== null){
                $this->sendEffect($session, $player, 0);
            }
        }
        $this->active = new WeakMap();
        $this->spawns = [];
    }
}
