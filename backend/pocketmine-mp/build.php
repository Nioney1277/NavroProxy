<?php

declare(strict_types=1);

$output = __DIR__ . "/build";
if(!is_dir($output) && !mkdir($output, 0777, true)){
    throw new RuntimeException("Could not create $output");
}

$path = "$output/NavroBackendBridge.phar";
$phar = new Phar($path);
$phar->startBuffering();
$phar->setStub("<?php __HALT_COMPILER();");
$phar->setSignatureAlgorithm(Phar::SHA256);
$phar->addFile(__DIR__ . "/plugin.yml", "plugin.yml");
$phar->buildFromDirectory(__DIR__, '~[\\/](?:resources|src)[\\/]~');
$phar->stopBuffering();
echo $path . PHP_EOL;
