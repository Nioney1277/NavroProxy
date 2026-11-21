from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
arguments = argparse.ArgumentParser(description='Build and validate the optional Nukkit-MOT bridge')
arguments.add_argument('--nukkit-jar', required=True, type=Path, help='Path to the actual Nukkit-MOT server JAR')
BACKEND = arguments.parse_args().nukkit_jar.resolve()
if not BACKEND.is_file():
    arguments.error('The Nukkit-MOT JAR does not exist: ' + str(BACKEND))
BUILD = ROOT / 'build'
CLASSES = BUILD / 'classes'
TESTS = BUILD / 'test-classes'
CLASSES.mkdir(parents=True, exist_ok=True)
TESTS.mkdir(parents=True, exist_ok=True)

sources = sorted((ROOT / 'src/main/java').rglob('*.java'))
subprocess.run(['javac', '-J-XX:ActiveProcessorCount=2', '--release', '21', '-proc:none', '-cp', str(BACKEND),
                '-d', str(CLASSES), *map(str, sources)], check=True)
test_sources = sorted((ROOT / 'src/test/java').rglob('*.java'))
classpath = os.pathsep.join(map(str, [CLASSES, BACKEND]))
subprocess.run(['javac', '-J-XX:ActiveProcessorCount=2', '--release', '21', '-proc:none', '-cp', classpath,
                '-d', str(TESTS), *map(str, test_sources)], check=True)
result = subprocess.run(['java', '-XX:ActiveProcessorCount=2', '-Xmx256m', '-ea',
                         '-cp', str(TESTS) + os.pathsep + classpath,
                         'ac.navro.bridge.nukkit.FireworkBridgeIntegration'],
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
(BUILD / 'integration.log').write_text(result.stdout)
print(result.stdout)
if result.returncode or 'FIREWORK_BRIDGE_INTEGRATION_PASSED' not in result.stdout:
    raise RuntimeError('Firework integration failed')

native_fixture = BUILD / 'native-transaction-slots.json'
native = subprocess.run(['java', '-XX:ActiveProcessorCount=2', '-Xmx512m', '-ea',
                         '-cp', str(TESTS) + os.pathsep + classpath,
                         'ac.navro.bridge.nukkit.NativeStateSyncIntegration', str(native_fixture)],
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
(BUILD / 'native-state-integration.log').write_text(native.stdout)
print(native.stdout)
if native.returncode or 'NATIVE_STATE_SYNC_INTEGRATION_PASSED' not in native.stdout:
    raise RuntimeError('Native state integration failed')

jar = BUILD / 'NavroBackendBridge.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
    for base in [CLASSES, ROOT / 'src/main/resources']:
        for path in sorted(base.rglob('*')):
            if path.is_file():
                archive.write(path, path.relative_to(base))
(BUILD / 'NavroFireworkBridge.jar').unlink(missing_ok=True)
summary = {'backendJar': str(BACKEND), 'backendSha256': hashlib.sha256(BACKEND.read_bytes()).hexdigest(),
           'pluginJar': str(jar), 'pluginSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
           'realNativeMotionIntegrationPassed': True, 'nativeStateIntegrationPassed': True, 'nativeTransactionFixture': str(native_fixture)}
(BUILD / 'validation.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
