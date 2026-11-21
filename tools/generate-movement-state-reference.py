from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
original = root.parent / 'Navro'
dest = root / 'build/native-state-sync'
dest.mkdir(parents=True, exist_ok=True)
lombok = next((Path.home() / '.gradle/caches/modules-2/files-2.1/org.projectlombok/lombok/1.18.46').rglob('*.jar'))
sources = ['player/NavroPlayer.java', 'player/data/PlayerData.java', 'data/ItemUseTracker.java',
           'player/data/tracker/FlagTracker.java', 'util/EntityMetadataUtil.java',
           'prediction/fluid/FluidMovement.java', 'prediction/fluid/FluidPredictionState.java',
           'packets/input/legacy/LegacyAuthInputPackets.java', 'util/InputUtil.java']
classpath = ':'.join(map(str, [original / 'build/classes/java/main', original / 'Nukkit-MOT-SNAPSHOT.jar', lombok]))
with (dest / 'compile.log').open('w') as log:
    subprocess.run(['javac', '-cp', classpath, '-processorpath', str(lombok), '-d', str(dest)] +
                   [str(original / 'src/main/java/ac/navro/anticheat' / name) for name in sources] +
                   ['tools/NavroItemUseTrackerFixture.java', 'tools/NavroMovementMetadataFixture.java',
                    'tools/NavroMovementStateFixture.java'], cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
with (dest / 'native.log').open('w') as log:
    subprocess.run(['java', '-XX:ActiveProcessorCount=2', '-Xmx256m', '-cp', str(dest) + ':' + classpath,
                    'NavroMovementStateFixture', 'src/test/resources/navro-movement-state.jsonl.gz'],
                   cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
print((dest / 'native.log').read_text().splitlines()[-1])
