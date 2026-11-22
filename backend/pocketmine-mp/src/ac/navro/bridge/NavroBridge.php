<?php

declare(strict_types=1);

namespace ac\navro\bridge;

use pocketmine\command\Command;
use pocketmine\command\CommandSender;
use pocketmine\network\mcpe\NetworkSession;
use pocketmine\network\mcpe\nethernet\NetherNetInterface;
use pocketmine\player\Player;
use pocketmine\plugin\PluginBase;
use pocketmine\utils\Config;
use pocketmine\utils\TextFormat;

final class NavroBridge extends PluginBase{

    private array $trustedProxies;
    private bool $consoleAlerts;
    private bool $staffChatAlerts;
    private Config $mutedPlayers;
    private ?FireworkBridge $fireworks = null;
    private ?NetherNetInterface $privateNetherNet = null;

    protected function onEnable() : void{
        $this->saveDefaultConfig();
        $config = $this->getConfig();
        $trusted = $config->get("trusted-proxy-addresses", ["127.0.0.1", "::1"]);
        $this->trustedProxies = is_array($trusted) ? array_values(array_filter($trusted, "is_string")) : [];
        $this->consoleAlerts = $config->get("console-alerts", true) === true;
        $this->staffChatAlerts = $config->get("staff-chat-alerts", true) === true;
        $this->mutedPlayers = new Config($this->getDataFolder() . "muted-alerts.json", Config::JSON);
        $stateSync = new NativeStateSync($this);
        $stateSync->register();
        if(FireworkBridge::isSupported()){
            $this->fireworks = new FireworkBridge($stateSync);
            $this->fireworks->register($this);
        }
        $netherNet = $config->get("private-nethernet", []);
        if(is_array($netherNet) && ($netherNet["enabled"] ?? false) === true){
            try{
                $this->privateNetherNet = PrivateNetherNet::start($this, $netherNet);
                $this->getLogger()->info("Private NetherNet listening on " . $this->privateNetherNet->getAddress());
            }catch(\Exception $error){
                $this->getLogger()->error("Private NetherNet could not start: " . $error->getMessage());
            }
        }
    }

    protected function onDisable() : void{
        if($this->privateNetherNet !== null){
            PrivateNetherNet::stop($this->getServer(), $this->privateNetherNet);
            $this->privateNetherNet = null;
        }
        $this->fireworks?->clear();
        $this->fireworks = null;
    }

    public function onCommand(CommandSender $sender, Command $command, string $label, array $args) : bool{
        if($command->getName() !== "navroalerts" || $args !== []){
            return false;
        }
        if(!$sender instanceof Player){
            $sender->sendMessage("Run this command in game to toggle your chat alerts.");
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
                if($player->hasPermission("navrobridge.alerts") &&
                    !$this->mutedPlayers->exists($player->getUniqueId()->toString())){
                    $player->sendMessage($message);
                }
            }
        }
    }
}
