<?php
declare(strict_types=1);

namespace ac\navro\bridge;

use pocketmine\network\mcpe\protocol\AvailableCommandsPacket;
use pocketmine\network\mcpe\protocol\serializer\AvailableCommandsPacketAssembler;
use pocketmine\network\mcpe\protocol\serializer\AvailableCommandsPacketDisassembler;
use pocketmine\network\mcpe\protocol\types\command\CommandHardEnum;
use pocketmine\network\mcpe\protocol\types\command\CommandOverload;
use pocketmine\network\mcpe\protocol\types\command\CommandParameter;

final class CommandHints{
    public static function apply(AvailableCommandsPacket $packet) : AvailableCommandsPacket{
        $data = AvailableCommandsPacketDisassembler::disassemble($packet);
        $found = false;
        foreach($data->commandData as $command){
            if($command->name !== "navro" && !str_ends_with($command->name, ":navro")){ continue; }
            $found = true;
            $command->overloads = [new CommandOverload(false, [
                CommandParameter::enum("subcommand", new CommandHardEnum("NavroSubcommand", ["alert", "reload", "debug", "debug2", "proxyping"]), 0, true),
                CommandParameter::standard("player", AvailableCommandsPacket::ARG_TYPE_STRING, 0, true)
            ])];
        }
        return $found ? AvailableCommandsPacketAssembler::assemble($data->commandData,
            array_values($data->unusedHardEnums), array_values($data->unusedSoftEnums)) : $packet;
    }
}
