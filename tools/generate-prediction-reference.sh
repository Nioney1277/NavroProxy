#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
mkdir -p build/native-prediction-stages build/native-prediction-items
javac -proc:none -cp '../Navro/build/classes/java/main:../Navro/build/libs/*:../Navro/Nukkit-MOT-SNAPSHOT.jar' -d build/native-prediction-stages tools/NavroPredictionStagesFixture.java
java -Xmx512m -cp 'build/native-prediction-stages:../Navro/build/classes/java/main:../Navro/build/resources/main:../Navro/build/libs/*:../Navro/Nukkit-MOT-SNAPSHOT.jar' NavroPredictionStagesFixture src/test/resources/navro-prediction-stages-reference.bin.gz
javac -proc:none -cp '../Navro/Nukkit-MOT-SNAPSHOT.jar' -d build/native-prediction-items tools/NukkitPredictionItemsExporter.java
java -Xmx512m -cp 'build/native-prediction-items:../Navro/Nukkit-MOT-SNAPSHOT.jar' NukkitPredictionItemsExporter src/main/resources/navro/prediction-items.bin.gz
sh ../Navro/gradlew -p . --offline test installDist --console=plain --no-daemon
