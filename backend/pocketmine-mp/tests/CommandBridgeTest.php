<?php
declare(strict_types=1);


$runtime = $argv[1] ?? throw new RuntimeException("Pass the PMMP source/runtime directory");
require $runtime . "/vendor/autoload.php";
require __DIR__ . "/../src/ac/navro/bridge/NavroBridge.php";
require __DIR__ . "/../src/ac/navro/bridge/NativeStateSync.php";
require __DIR__ . "/../src/ac/navro/bridge/CommandHints.php";

use ac\navro\bridge\{NavroBridge,NativeStateSync,CommandHints};
use pocketmine\network\mcpe\NetworkSession;
use pocketmine\network\mcpe\protocol\{ClientboundPacket,ScriptMessagePacket,AvailableCommandsPacket};
use pocketmine\network\mcpe\protocol\types\command\{CommandData,CommandOverload,CommandParameter};
use pocketmine\network\mcpe\protocol\serializer\{AvailableCommandsPacketAssembler,AvailableCommandsPacketDisassembler};
use pocketmine\player\Player;
use pocketmine\permission\Permission;
use pocketmine\lang\Translatable;
use pocketmine\command\{Command,CommandSender};
use pocketmine\utils\Config;
use pocketmine\Server;
use pocketmine\event\server\DataPacketReceiveEvent;
use Ramsey\Uuid\{Uuid,UuidInterface};

function check(bool $ok, string $message) : void { if(!$ok){ throw new RuntimeException($message); } }
function setField(object $o, string $name, mixed $v) : void {
    $f = new ReflectionProperty($o,$name); $f->setValue($o,$v);
}
final class TestServer extends Server{ public function __construct(){} public function __destruct(){} }
final class TestSession extends NetworkSession{
    public array $sent=[];
    public int $commandSyncs=0;
    public TestPlayer $owner;
    public function __construct(){}
    public function getPlayer() : ?Player{ return $this->owner; }
    public function getIp() : string{ return "127.0.0.1"; }
    public function sendDataPacket(ClientboundPacket $packet, bool $immediate=false) : bool{ $this->sent[]=$packet; return true; }
    public function syncAvailableCommands() : void{ ++$this->commandSyncs; }
}
final class TestPlayer extends Player{
    public array $messages=[];
    public bool $allowed=true;
    public TestSession $connection;
    public TestServer $testServer;
    public function __construct(){}
    public function __destruct(){}
    public function getNetworkSession() : NetworkSession{ return $this->connection; }
    public function getServer() : Server{ return $this->testServer; }
    public function hasPermission(Permission|string $name) : bool{ return $this->allowed; }
    public function sendMessage(Translatable|string $message) : void{ $this->messages[]=$message; }
    public function getUniqueId() : UuidInterface{ return Uuid::fromString("00000000-0000-0000-0000-000000000123"); }
}
$plugin=(new ReflectionClass(NavroBridge::class))->newInstanceWithoutConstructor();
setField($plugin,"trustedProxies",["127.0.0.1"]);setField($plugin,"staffChatAlerts",true);
$file=sys_get_temp_dir()."/navro-commands-".bin2hex(random_bytes(5)).".json";
setField($plugin,"mutedPlayers",new Config($file,Config::JSON));
$sync=new NativeStateSync($plugin);setField($plugin,"stateSync",$sync);
$player=new TestPlayer();$session=new TestSession();$session->owner=$player;$player->connection=$session;$player->testServer=new TestServer();
$nonce="12345678-1234-1234-1234-123456789012";
$receive=new ReflectionMethod($sync,"receiveBridgePacket");
$send=static function(string $message) use($receive,$sync,$session):void {
    $event=new DataPacketReceiveEvent($session,ScriptMessagePacket::create("navro:backend_sync_v1",$message));
    $receive->invoke($sync,$event);check($event->isCancelled(),"Private message consumed");
};
$send("hello|$nonce");check(count($session->sent)===3,"Readiness, tick and command capability sent");
$send("commands|$nonce|bridge");check($sync->bridgeCommands($session),"Bridge mode negotiated");
$command=new class("navro") extends Command{ public function execute(CommandSender $sender,string $label,array $args):bool{return false;} };
$plugin->onCommand($player,$command,"navro",["alert"]);
check((new Config($file,Config::JSON))->exists($player->getUniqueId()->toString()),"Alert preference persisted");
$plugin->onCommand($player,$command,"navro",["alert"]);
check(!(new Config($file,Config::JSON))->exists($player->getUniqueId()->toString()),"Alert enabled again");
$session->sent=[];$plugin->onCommand($player,$command,"navro",["debug","A Player"]);
check($session->sent[0]->getValue()==="command|$nonce|0|debug|".base64_encode("A Player"),"Debug is forwarded intact");
$before=count($player->messages);$send("command_reply|wrong|debug|b2s=");check(count($player->messages)===$before,"Wrong nonce rejected");
$send("command_reply|$nonce|debug|b2s=");check(end($player->messages)==="ok","Debug reply delivered");
$player->allowed=false;$before=count($player->messages);$send("command_reply|$nonce|debug|b2s=");check(count($player->messages)===$before,"Revoked permission blocks debug output");
$before=count($session->sent);$plugin->onCommand($player,$command,"navro",["debug2"]);check(count($session->sent)===$before,"Denied debug2 not sent");
$player->allowed=true;$send("commands|$nonce|proxy");$plugin->onCommand($player,$command,"navro",["debug2"]);check(count($session->sent)===$before,"Proxy mode not handled by bridge");
$send("commands|$nonce|bridge");$plugin->onCommand($player,$command,"navro",["debug2"]);check(str_contains(end($session->sent)->getValue(),"|debug2|"),"Debug2 accepts self target");
$plugin->onCommand($player,$command,"navro",["proxyping"]);
check(str_contains(end($session->sent)->getValue(),"|proxyping|"),"Proxyping accepts self target");
$send("command_reply|$nonce|proxyping|b2s=");check(end($player->messages)==="ok","Proxyping reply delivered");
$packet=AvailableCommandsPacketAssembler::assemble([new CommandData("navro","Navro",0,0,null,[new CommandOverload(false,[CommandParameter::standard("args",AvailableCommandsPacket::ARG_TYPE_RAWTEXT,0,true)])],[]),new CommandData("help","Help",0,0,null,[],[])],[],[]);
$hints=AvailableCommandsPacketDisassembler::disassemble(CommandHints::apply($packet));
check(count($hints->commandData)===2,"Unrelated commands retained");
check($hints->commandData[0]->overloads[0]->getParameters()[0]->enum->getValues()===["alert","reload","debug","debug2","proxyping"],"Subcommand hints present");
unlink($file);
echo "PMMP_COMMAND_BRIDGE_PASSED\n";
