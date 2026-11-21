from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
original = root.parent / 'Navro'
dest = root / 'build/native-rotation-pipeline'
dest.mkdir(parents=True, exist_ok=True)
modules = Path.home() / '.gradle/caches/modules-2/files-2.1'
lombok = next((modules / 'org.projectlombok/lombok/1.18.46').rglob('*.jar'))
junit = [next((modules / name).rglob('*.jar')) for name in
         ['org.junit.jupiter/junit-jupiter-api/5.10.0', 'org.opentest4j/opentest4j',
          'org.junit.platform/junit-platform-commons/1.10.0', 'org.apiguardian/apiguardian-api']]
classpath = ':'.join(map(str, [original / 'build/classes/java/main', original / 'Nukkit-MOT-SNAPSHOT.jar', lombok, *junit]))
with (dest / 'compile.log').open('w') as log:
    subprocess.run(['javac', '-J-XX:ActiveProcessorCount=2', '-cp', classpath, '-processorpath', str(lombok), '-d', str(dest),
                    str(original / 'src/main/java/ac/navro/anticheat/check/impl/place/RotationPlace.java'),
                    str(original / 'src/main/java/ac/navro/anticheat/util/reach/CameraCandidates.java'),
                    str(original / 'src/test/java/ac/navro/anticheat/check/impl/place/RotationPlaceTest.java'),
                    'tools/NavroRotationPipelineFixture.java'],cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True)
with (dest / 'native.log').open('w') as log:
    subprocess.run(['java', '-XX:ActiveProcessorCount=2', '-Xmx256m', '-cp', str(dest) + ':' + classpath,
                    'ac.navro.anticheat.check.impl.place.NavroRotationPipelineFixture', 'src/test/resources/navro-rotation-pipeline.jsonl.gz'],
                   cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True)
print((dest / 'native.log').read_text().strip())
