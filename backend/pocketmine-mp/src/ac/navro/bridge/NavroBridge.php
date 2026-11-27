<?php

declare(strict_types=1);

namespace ac\navro\bridge;

use pocketmine\command\Command;
use pocketmine\command\CommandSender;
use pocketmine\network\mcpe\NetworkSession;
use pocketmine\network\mcpe\nethernet\NetherNetInterface;
use pocketmine\network\mcpe\raklib\RakLibInterface;
use pocketmine\player\Player;
use pocketmine\plugin\PluginBase;
use pocketmine\utils\Config;
use pocketmine\utils\TextFormat;

final class NavroBridge extends PluginBase{

    private array $trustedProxies;
    private bool $consoleAlerts;
    private bool $staffChatAlerts;
    private Config $mutedPlayers;
    private ?NativeStateSync $stateSync = null;
    private ?FireworkBridge $fireworks = null;
    private ?NetherNetInterface $privateNetherNet = null;
    private ?RakLibInterface $privateRakNet = null;

    protected function onEnable() : void{
        $this->saveDefaultConfig();
        $config = $this->getConfig();
        $trusted = $config->get("trusted-proxy-addresses", ["127.0.0.1", "::1"]);
        $this->trustedProxies = is_array($trusted) ? array_values(array_filter($trusted, "is_string")) : [];
        $this->consoleAlerts = $config->get("console-alerts", true) === true;
        $this->staffChatAlerts = $config->get("staff-chat-alerts", true) === true;
        $this->mutedPlayers = new Config($this->getDataFolder() . "muted-alerts.json", Config::JSON);
        $stateSync = new NativeStateSync($this);
        $this->stateSync = $stateSync;
        $stateSync->register();
        if(FireworkBridge::isSupported()){
            $this->fireworks = new FireworkBridge($stateSync);
            $this->fireworks->register($this);
        }
        $netherNet = $config->get("private-nethernet", []);
        if(is_array($netherNet) && ($netherNet["enabled"] ?? false) === true){
            try{
                $this->privateNetherNet = PrivateNetherNet::start($this, $netherNet);
                $this->getLogger()->info("Private NetherNet listening on " . ($netherNet['signaling-bind-address'] ?? '127.0.0.1') . ':' . ($netherNet['signaling-port'] ?? 19608));
            }catch(\Exception $error){
                $this->getLogger()->error("Private NetherNet could not start: " . $error->getMessage());
            }
        }
        $rakNet = $config->get("private-raknet", []);
        if(is_array($rakNet) && ($rakNet["enabled"] ?? false) === true){
            try{
                $this->privateRakNet = PrivateRakNet::start($this, $rakNet);
                $this->getLogger()->info("Private RakNet listening on " . ($rakNet["bind-address"] ?? "127.0.0.1") . ":" . ($rakNet["port"] ?? 19133));
            }catch(\Throwable $error){
                $this->getLogger()->error("Private RakNet could not start: " . $error->getMessage());
                throw $error;
            }
        }
    }

    protected function onDisable() : void{
        if($this->privateRakNet !== null){
            PrivateRakNet::stop($this->getServer(), $this->privateRakNet);
            $this->privateRakNet = null;
        }
        if($this->privateNetherNet !== null){
            PrivateNetherNet::stop($this->getServer(), $this->privateNetherNet);
            $this->privateNetherNet = null;
        }
        $this->fireworks?->clear();
        $this->fireworks = null;
    }

    public function onCommand(CommandSender $sender, Command $command, string $label, array $args) : bool{
        if($command->getName() !== "navro"){
            return false;
        }
        if(!$sender instanceof Player){
            $sender->sendMessage("Run /navro in game through NavroProxy.");
            return true;
        }
        if($this->stateSync === null || !$this->stateSync->bridgeCommands($sender->getNetworkSession())){
            $sender->sendMessage("This connection uses proxy commands or has no active Navro command bridge.");
            return true;
        }
        $action = strtolower($args[0] ?? "");
        if(in_array($action, ["debug", "debug2", "reload", "proxyping"], true)){
            if(!$sender->hasPermission($action === "reload" ? "navrobridge.reload" : ($action === "proxyping" ? "navrobridge.proxyping" : "navrobridge.debug"))){
                $sender->sendMessage("You do not have permission to use this command.");
                return true;
            }
            if(count($args) > 2 || ($action === "debug" && count($args) !== 2) || ($action === "reload" && count($args) !== 1)){
                $sender->sendMessage("/navro alert | reload | debug <player> | debug2 [player] | proxyping [player]");
                return true;
            }
            $target = $args[1] ?? "";
            if(strlen($target) > 256 || preg_match('/[\\x00-\\x1f\\x7f]/', $target) === 1 ||
                !$this->stateSync->command($sender->getNetworkSession(), $action, $target)){
                $sender->sendMessage("Could not send command to NavroProxy.");
            }
            return true;
        }
        if($action !== "alert" || count($args) !== 1){
            $sender->sendMessage("/navro alert | reload | debug <player> | debug2 [player] | proxyping [player]");
            return true;
        }
        if(!$sender->hasPermission("navrobridge.alerts")){
            $sender->sendMessage("You do not have permission to receive Navro chat alerts.");
            return true;
        }
        if(!$this->staffChatAlerts){
            $sender->sendMessage("Navro chat alerts are disabled in the server configuration.");
            return true;
        }

        $uuid = $sender->getUniqueId()->toString();
        $enabled = $this->mutedPlayers->exists($uuid);
        if($enabled){
            $this->mutedPlayers->remove($uuid);
        }else{
            $this->mutedPlayers->set($uuid, true);
        }
        $this->mutedPlayers->save();
        $sender->sendMessage($enabled ? "Navro chat alerts enabled." : "Navro chat alerts disabled.");
        return true;
    }

    public function trusts(NetworkSession $session) : bool{
        return in_array($session->getIp(), $this->trustedProxies, true);
    }

    public function displayAlert(string $message) : void{
        $message = preg_replace('/\p{Cc}/u', ' ', $message);
        if($message === null || trim($message) === ""){
            return;
        }
        if($this->consoleAlerts){
            $this->getLogger()->info(TextFormat::clean($message));
        }
        if($this->staffChatAlerts){
            foreach($this->getServer()->getOnlinePlayers() as $player){
                if($this->stateSync?->bridgeCommands($player->getNetworkSession()) === true && $player->hasPermission("navrobridge.alerts") &&
                    !$this->mutedPlayers->exists($player->getUniqueId()->toString())){
                    $player->sendMessage($message);
                }
            }
        }
    }
}
