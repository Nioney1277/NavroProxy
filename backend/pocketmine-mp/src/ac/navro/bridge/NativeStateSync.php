<?php

declare(strict_types=1);

namespace ac\navro\bridge;

use pocketmine\event\EventPriority;
use pocketmine\event\inventory\InventoryTransactionEvent;
use pocketmine\event\player\PlayerJoinEvent;
use pocketmine\event\server\DataPacketDecodeEvent;
use pocketmine\event\server\DataPacketReceiveEvent;
use pocketmine\event\server\DataPacketSendEvent;
use pocketmine\inventory\Inventory;
use pocketmine\inventory\transaction\action\SlotChangeAction;
use pocketmine\nbt\BigEndianNbtSerializer;
use pocketmine\nbt\tag\CompoundTag;
use pocketmine\nbt\tag\ListTag;
use pocketmine\nbt\TreeRoot;
use pocketmine\network\mcpe\NetworkSession;
use pocketmine\network\mcpe\protocol\ProtocolInfo;
use pocketmine\network\mcpe\protocol\ScriptMessagePacket;
use pocketmine\network\mcpe\protocol\StartGamePacket;
use pocketmine\network\mcpe\protocol\UpdateBlockPacket;
use pocketmine\network\mcpe\protocol\UpdateSubChunkBlocksPacket;
use pocketmine\scheduler\ClosureTask;
use pocketmine\Server;
use WeakMap;

final class NativeStateSync{
    private const CHANNEL = "navro:backend_sync_v1";
    private const MAX_ALERT_BYTES = 4096;
    private const MAX_PALETTE_BYTES = 512 * 1024;
    private const PALETTE_PART_BYTES = 30_000;

    /** @var WeakMap<NetworkSession, BackendPlayerSync> */
    private WeakMap $sessions;
    /** @var WeakMap<object, array<int, array{states: array, message: string}>> */
    private WeakMap $palettes;

    public function __construct(private readonly NavroBridge $plugin){
        $this->sessions = new WeakMap();
        $this->palettes = new WeakMap();
    }

    public function register() : void{
        $plugin = $this->plugin;
        $server = $plugin->getServer();
        $manager = $server->getPluginManager();
        $manager->registerEvent(DataPacketDecodeEvent::class, $this->allowBridgePacket(...), EventPriority::HIGH, $plugin, true);
        $manager->registerEvent(DataPacketReceiveEvent::class, $this->receiveBridgePacket(...), EventPriority::HIGHEST, $plugin);
        $manager->registerEvent(InventoryTransactionEvent::class, $this->rememberSlots(...), EventPriority::MONITOR, $plugin, true);
        $manager->registerEvent(PlayerJoinEvent::class, $this->syncCommandsAfterJoin(...), EventPriority::MONITOR, $plugin);
        $manager->registerEvent(DataPacketSendEvent::class, $this->beforeWorldPacket(...), EventPriority::MONITOR, $plugin);
        $plugin->getScheduler()->scheduleRepeatingTask(new ClosureTask(function() use ($server) : void{
            $this->tick($server);
        }), 1);
    }

    private function syncCommandsAfterJoin(PlayerJoinEvent $event) : void{
        $session = $event->getPlayer()->getNetworkSession();
        if(!$this->plugin->trusts($session)){
            return;
        }
        $this->plugin->getScheduler()->scheduleDelayedTask(new ClosureTask(function() use ($session) : void{
            if($session->isConnected()){
                $session->syncAvailableCommands();
            }
        }), 1);
    }

    private function allowBridgePacket(DataPacketDecodeEvent $event) : void{
        // PM5 can discard unhandled script packets before DataPacketReceiveEvent.
        if($event->getPacketId() === ScriptMessagePacket::NETWORK_ID && $event->getOrigin()->getPlayer() !== null &&
            $this->plugin->trusts($event->getOrigin())){
            $buffer = $event->getPacketBuffer();
            if(strlen($buffer) <= self::MAX_ALERT_BYTES + 128 && str_contains($buffer, self::CHANNEL)){
                $event->uncancel();
            }
        }
    }

    private function receiveBridgePacket(DataPacketReceiveEvent $event) : void{
        $packet = $event->getPacket();
        if(!$packet instanceof ScriptMessagePacket || $packet->getMessageId() !== self::CHANNEL){
            return;
        }
        $event->cancel();
        $session = $event->getOrigin();
        $player = $session->getPlayer();
        if($player === null || !$this->plugin->trusts($session)){
            return;
        }
        $message = $packet->getValue();
        $state = $this->sessions[$session] ?? null;
        if($state !== null && str_starts_with($message, "alert|{$state->nonce}|")){
            $alert = substr($message, strlen("alert|{$state->nonce}|"));
            if(strlen($alert) <= self::MAX_ALERT_BYTES){
                $this->plugin->displayAlert($alert);
            }
            return;
        }
        if(preg_match('/\Ahello\|([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\z/', $message, $match) !== 1){
            return;
        }
        $state = new BackendPlayerSync($match[1]);
        $this->sessions[$session] = $state;
        $session->sendDataPacket(ScriptMessagePacket::create(self::CHANNEL, "pm_ready|{$state->nonce}"));
        $this->sendTick($session, $state, $player->getServer()->getTick());
    }

    private function rememberSlots(InventoryTransactionEvent $event) : void{
        $transaction = $event->getTransaction();
        $session = $transaction->getSource()->getNetworkSession();
        $state = $this->sessions[$session] ?? null;
        if($state === null){
            return;
        }
        foreach($transaction->getActions() as $action){
            if($action instanceof SlotChangeAction){
                $state->slotsDue ??= $transaction->getSource()->getServer()->getTick() + 1;
                $inventory = $action->getInventory();
                $slot = $action->getSlot();
                $state->slots[spl_object_id($inventory) . ":" . $slot] = [$inventory, $slot];
            }
        }
    }

    private function beforeWorldPacket(DataPacketSendEvent $event) : void{
        foreach($event->getPackets() as $packet){
            // StartGame's blockPalette contains custom definitions, not the ordered runtime ID table.
            if($packet instanceof StartGamePacket && !$packet->blockNetworkIdsAreHashes){
                foreach($event->getTargets() as $session){
                    if($this->plugin->trusts($session)){
                        $this->sendRuntimePalette($session);
                    }
                }
            }
            if($packet instanceof UpdateBlockPacket || $packet instanceof UpdateSubChunkBlocksPacket){
                foreach($event->getTargets() as $session){
                    $state = $this->sessions[$session] ?? null;
                    $player = $session->getPlayer();
                    if($state !== null && $player !== null){
                        $this->sendTick($session, $state, $player->getServer()->getTick());
                    }
                }
                return;
            }
        }
    }

    private function sendRuntimePalette(NetworkSession $session) : void{
        $dictionary = $session->getTypeConverter()->getBlockTranslator()->getBlockStateDictionary();
        $protocol = method_exists($session, "getProtocolId") ? $session->getProtocolId() : ProtocolInfo::CURRENT_PROTOCOL;
        $states = $dictionary->getStates();
        $cached = $this->palettes[$dictionary][$protocol] ?? null;
        $message = $cached["message"] ?? "";
        if($cached === null || $cached["states"] !== $states){
            try{
                $ordered = $states;
                ksort($ordered, SORT_NUMERIC);
                if(!array_is_list($ordered) || count($ordered) > 131072){
                    throw new \RuntimeException("Invalid runtime block state table");
                }
                $serialized = array_map(static fn($entry) => $entry->generateStateData()->toNbt(), $ordered);
                $root = CompoundTag::create()->setInt("protocol", $protocol)->setTag("states", new ListTag($serialized));
                $compressed = gzencode((new BigEndianNbtSerializer())->write(new TreeRoot($root)));
                if($compressed === false){
                    throw new \RuntimeException("Could not compress runtime block palette");
                }
                $message = "palette|" . base64_encode($compressed);
                if(strlen($message) > self::MAX_PALETTE_BYTES){
                    throw new \RuntimeException("Runtime block palette exceeds the bridge limit");
                }
            }catch(\Throwable $error){
                $this->plugin->getLogger()->warning("Could not send the runtime block palette: " . $error->getMessage());
                $message = "";
            }
            $entries = $this->palettes[$dictionary] ?? [];
            $entries[$protocol] = ["states" => $states, "message" => $message];
            $this->palettes[$dictionary] = $entries;
        }
        if($message === ""){
            return;
        }
        if(strlen($message) <= self::PALETTE_PART_BYTES){
            $session->sendDataPacket(ScriptMessagePacket::create(self::CHANNEL, $message));
            return;
        }
        $parts = str_split($message, self::PALETTE_PART_BYTES);
        foreach($parts as $index => $part){
            if(!$session->sendDataPacket(ScriptMessagePacket::create(
                self::CHANNEL, "palette_part|" . $index . "|" . count($parts) . "|" . $part
            ))){
                break;
            }
        }
    }

    private function tick(Server $server) : void{
        $tick = $server->getTick();
        foreach($this->sessions as $session => $state){
            if(!$session->isConnected()){
                unset($this->sessions[$session]);
                continue;
            }
            $this->sendTick($session, $state, $tick);
            if($state->slotsDue === null || $state->slotsDue > $tick){
                continue;
            }
            $slots = $state->slots;
            $state->slots = [];
            $state->slotsDue = null;
            $manager = $session->getInvManager();
            if($manager === null){
                continue;
            }
            foreach($slots as [$inventory, $slot]){
                // InventoryManager keeps the native slot mapping and stack network ID.
                if($inventory->slotExists($slot) && $manager->getItemStackInfo($inventory, $slot) !== null){
                    $item = $session->getTypeConverter()->coreItemStackToNet($inventory->getItem($slot));
                    $manager->syncSlot($inventory, $slot, $item);
                }
            }
        }
    }

    private function sendTick(NetworkSession $session, BackendPlayerSync $state, int $tick) : void{
        if($state->lastTick === $tick){
            return;
        }
        $state->lastTick = $tick;
        $session->sendDataPacket(ScriptMessagePacket::create(
            self::CHANNEL,
            "tick|{$state->nonce}|{$state->sequence}|$tick"
        ));
        ++$state->sequence;
    }
}

final class BackendPlayerSync{
    public int $sequence = 0;
    public ?int $lastTick = null;
    public ?int $slotsDue = null;
    /** @var array<string, array{Inventory, int}> */
    public array $slots = [];

    public function __construct(public readonly string $nonce){}
}
