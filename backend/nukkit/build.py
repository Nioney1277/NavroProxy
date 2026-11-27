from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
arguments = argparse.ArgumentParser(description='Build the optional Nukkit-MOT bridge')
arguments.add_argument('--nukkit-jar', required=True, type=Path, help='Path to the compatible Nukkit-MOT server JAR')
BACKEND = arguments.parse_args().nukkit_jar.resolve()
if not BACKEND.is_file():
    arguments.error('The Nukkit-MOT JAR does not exist: ' + str(BACKEND))
BUILD = ROOT / 'build'
CLASSES = BUILD / 'classes'
CLASSES.mkdir(parents=True, exist_ok=True)
sources = sorted((ROOT / 'src/main/java').rglob('*.java'))
subprocess.run(['javac', '-J-XX:ActiveProcessorCount=2', '--release', '21', '-proc:none', '-cp', str(BACKEND),
                '-d', str(CLASSES), *map(str, sources)], check=True)
jar = BUILD / 'NavroBackendBridge.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
    for base in [CLASSES, ROOT / 'src/main/resources']:
        for path in sorted(base.rglob('*')):
            if path.is_file():
                archive.write(path, path.relative_to(base))
(BUILD / 'NavroFireworkBridge.jar').unlink(missing_ok=True)
summary = {'backendJar': str(BACKEND), 'backendSha256': hashlib.sha256(BACKEND.read_bytes()).hexdigest(),
           'pluginJar': str(jar), 'pluginSha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'compiled': True}
(BUILD / 'validation.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
