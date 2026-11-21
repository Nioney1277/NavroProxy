<?php
declare(strict_types=1);
require $argv[1] . '/vendor/autoload.php';
use pocketmine\network\mcpe\protocol\UpdateAbilitiesPacket;
use pocketmine\network\mcpe\protocol\types\AbilitiesData;
use pmmp\encoding\ByteBufferWriter;
$out=fopen($argv[2], 'wb');
for($player=0;$player<4;$player++){
 for($command=0;$command<6;$command++){
  $packet=UpdateAbilitiesPacket::create(new AbilitiesData($command,$player,9002,[]));
  $buffer=new ByteBufferWriter();$packet->encode($buffer,2193);
  fwrite($out,json_encode(['engine'=>'pmmp','player'=>$player,'command'=>$command,'entity'=>9002,'bytes'=>base64_encode($buffer->getData())],JSON_THROW_ON_ERROR)."\n");
 }
}
fclose($out);
