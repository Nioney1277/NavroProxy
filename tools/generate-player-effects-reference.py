from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
original = root.parent / 'Navro'
dest = root / 'build/native-player-effects'
dest.mkdir(parents=True, exist_ok=True)
lombok = next((Path.home() / '.gradle/caches/modules-2/files-2.1/org.projectlombok/lombok/1.18.46').rglob('*.jar'))
classpath = ':'.join(map(str, [original / 'build/classes/java/main', original / 'Nukkit-MOT-SNAPSHOT.jar', lombok]))
sources = ['packets/player/PlayerEffectPackets.java', 'player/NavroPlayer.java', 'player/data/PlayerData.java',
           'data/vanilla/StatusEffect.java', 'data/ItemUseTracker.java', 'util/EntityBatchEncoder.java', 'util/LatencyUtil.java']
with (dest / 'compile.log').open('w') as log:
    subprocess.run(['javac', '-cp', classpath, '-processorpath', str(lombok), '-d', str(dest)] +
                   [str(original / 'src/main/java/ac/navro/anticheat' / name) for name in sources] +
                   ['tools/NavroPlayerEffectFixture.java'], cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
with (dest / 'native.log').open('w') as log:
    subprocess.run(['java', '-XX:ActiveProcessorCount=2', '-Xmx256m', '-cp', str(dest) + ':' + classpath,
                    'NavroPlayerEffectFixture', 'src/main/resources/navro/status-effects.json',
                    'src/test/resources/navro-player-effects.jsonl.gz'],
                   cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
print((dest / 'native.log').read_text().strip())
