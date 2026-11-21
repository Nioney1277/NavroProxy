from pathlib import Path
import hashlib
import json
import subprocess

root = Path(__file__).resolve().parent.parent
original = root.parent / 'Navro'
destination = root / 'build/native-prediction-sync'
destination.mkdir(parents=True, exist_ok=True)
native_jar = original / 'Nukkit-MOT-SNAPSHOT.jar'
sources = [original / 'src/main/java/ac/navro/anticheat' / name for name in (
    'packets/input/ClientMovementPredictionSyncPacket.java',
    'prediction/PredictionSyncState.java',
)]
sources.append(root / 'tools/NavroPredictionSyncFixture.java')
reference = root / 'src/test/resources/navro-prediction-sync.jsonl.gz'
subprocess.run(['javac', '-J-XX:ActiveProcessorCount=2', '-proc:none', '-cp', str(native_jar),
                '-d', str(destination), *map(str, sources)], cwd=root, check=True)
subprocess.run(['java', '-XX:ActiveProcessorCount=2', '-Xmx256m', '-cp',
                str(destination) + ':' + str(native_jar), 'NavroPredictionSyncFixture', str(reference)],
               cwd=root, check=True)
files = sources + [native_jar, Path(__file__).resolve(), reference]
manifest = {
    'scope': 'Original packet decoder and receive/consume/clear transitions; no movement or transport simulation',
    'files': {str(path.relative_to(root.parent)): hashlib.sha256(path.read_bytes()).hexdigest() for path in files},
}
(root / 'tools/prediction-sync-reference-sha256.json').write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
