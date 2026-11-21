from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
original = root.parent / 'Navro'
dest = root / 'build/native-placement-bridge'
dest.mkdir(parents=True, exist_ok=True)
runtime = list((root / 'build/install/navro-proxy/lib').glob('*.jar'))
classpath = ':'.join(map(str, [original / 'build/classes/java/main', original / 'Nukkit-MOT-SNAPSHOT.jar', *runtime]))
with (dest / 'fixture-compile.log').open('w') as log:
    subprocess.run(['javac', '-J-XX:ActiveProcessorCount=2', '-proc:none', '-cp', classpath, '-d', str(dest),
                    str(original / 'src/main/java/ac/navro/anticheat/util/block/BlockReachUtil.java'),
                    'tools/NukkitPredictionItemsExporter.java', 'tools/NukkitItemDecoderExporter.java',
                    'tools/NukkitItemDecoderFixture.java', 'tools/NavroPlacementBridgeFixture.java'],
                   cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
with (dest / 'fixture.log').open('w') as log:
    subprocess.run(['java', '-XX:ActiveProcessorCount=2', '-Xmx384m', '-cp', str(dest) + ':' + classpath,
                    'NavroPlacementBridgeFixture', 'src/test/resources/native-placement-bridge.bin.gz',
                    'src/test/resources/navro-block-reach.bin.gz', 'src/test/resources/native-placement-wire.bin.gz'],
                   cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
print((dest / 'fixture.log').read_text().strip())
