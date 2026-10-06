#!/usr/bin/env bash
# Robolectric + authored host doubles. No original host bytecode is included.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
TOOLS=$(python3 -c 'from pathlib import Path;import os;print(Path(os.environ.get("WETYPE_TOOLS",".local/tools")).resolve())')
DEPS="$ROOT/.local/host-tests"
CP=$(find -L "$DEPS/dependencies" "$DEPS/aar-jars" -name '*.jar' ! -name 'android-all-9-*' | LC_ALL=C sort | paste -sd: -)
[[ -n "$CP" ]] || { echo 'Run bash scripts/setup-host-tests.sh first.' >&2;exit 1; }
python3 - <<'PY_CLEAN'
from pathlib import Path
import shutil
for name in ['controller-tests','keyboard-picker-tests']:
 p=Path('native-integration')/name/'build/classes'
 if p.exists():shutil.rmtree(p)
 p.mkdir(parents=True)
PY_CLEAN
cd native-integration/controller-tests
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile
with ZipFile('build/test-assets.apk','w') as z:
 for p in Path('../../quick-engine/resources').glob('*.tsv'):z.write(p,'assets/quick/'+p.name)
PY
find ../../quick-engine/src/main/java src/test/java -name '*.java' | LC_ALL=C sort > build/sources.txt
for f in NativeQuickBridge NativeModelPort QuickNativeCandidate QuickLocalActions EditorDeletion InputPolicy;do echo "../src/org/wetype/quick/integration/$f.java" >> build/sources.txt;done
java -jar "$TOOLS/ecj.jar" -source 8 -target 8 -encoding UTF-8 -cp "$CP" -d build/classes @build/sources.txt
java -Xmx2g -Drobolectric.areWindowsMarkedVisible=true -Drobolectric.offline=true -Drobolectric.usePreinstrumentedJars=false -Drobolectric.dependency.dir="$DEPS/dependencies" -cp "build/classes:.:$CP" org.wetype.quick.integration.ControllerRunner "$@"
cd ../keyboard-picker-tests
find src -name '*.java' | LC_ALL=C sort > build/sources.txt
echo ../src/org/wetype/quick/integration/NativeKeyboardPort.java >> build/sources.txt
java -jar "$TOOLS/ecj.jar" -source 8 -target 8 -encoding UTF-8 -cp "$CP" -d build/classes @build/sources.txt
java -Xmx2g -Drobolectric.offline=true -Drobolectric.usePreinstrumentedJars=false -Drobolectric.dependency.dir="$DEPS/dependencies" -cp "build/classes:.:$CP" org.junit.runner.JUnitCore org.wetype.quick.keyboardtest.KeyboardPickerTest
