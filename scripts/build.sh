#!/usr/bin/env bash
# Local, unsigned build. Never installs, signs, uploads, or executes the host APK.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -ne 1 ]]; then echo 'Usage: bash scripts/build.sh /absolute/path/to/original-3.5.4.apk' >&2;exit 2;fi
export WETYPE_ORIGINAL_APK="$1"
export WETYPE_TOOLS="${WETYPE_TOOLS:-$PWD/.local/tools}"
python3 scripts/toolchain.py verify
python3 native-integration/native_build.py preflight
python3 native-integration/native_build.py stage
B=.local/build
python3 - <<'PY_CLEAN'
from pathlib import Path
import shutil
b=Path('.local/build').resolve()
root=Path.cwd().resolve()
if not b.is_relative_to(root) or b==root:raise SystemExit('Unsafe build output')
for name in ('tools','compile/stubs','compile/classes','dex'):
 p=b/name
 if p.exists():shutil.rmtree(p)
 p.mkdir(parents=True)
PY_CLEAN
java -jar "$WETYPE_TOOLS/ecj.jar" -source 8 -target 8 -cp "$WETYPE_TOOLS/apktool.jar" -d "$B/tools" patch/tools/AssembleDex.java
for module in 2 3;do
 java -cp "$WETYPE_TOOLS/apktool.jar:$B/tools" AssembleDex "$B/stage/smali_classes$module" "$B/classes$module.dex"
done
find native-integration/stubs native-integration/keyboard-stubs native-integration/controller-stubs -name '*.java' | LC_ALL=C sort > "$B/compile/stubs.txt"
java -jar "$WETYPE_TOOLS/ecj.jar" -encoding UTF-8 -source 8 -target 8 -cp "$WETYPE_TOOLS/android-all.jar" -d "$B/compile/stubs" @"$B/compile/stubs.txt"
find native-integration/src quick-engine/src/main/java -name '*.java' | LC_ALL=C sort > "$B/compile/sources.txt"
java -jar "$WETYPE_TOOLS/ecj.jar" -encoding UTF-8 -source 8 -target 8 -cp "$WETYPE_TOOLS/android-all.jar:$B/compile/stubs" -d "$B/compile/classes" @"$B/compile/sources.txt"
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile,ZipInfo
b=Path('.local/build')
with ZipFile(b/'compile/native-quick.jar','w') as z:
 for p in sorted((b/'compile/classes').rglob('*.class')):
  info=ZipInfo(p.relative_to(b/'compile/classes').as_posix(),(2026,10,5,0,0,0));info.external_attr=0o100644<<16
  z.writestr(info,p.read_bytes())
# Remove stale generated dex only in this dedicated output directory.
for p in (b/'dex').glob('classes*.dex'):p.unlink()
PY
JAVA_HOME_LOCAL="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.home = //p')"
java -cp "$WETYPE_TOOLS/r8.jar" com.android.tools.r8.D8 --release --min-api 24 --lib "$WETYPE_TOOLS/android-all.jar" --lib "$JAVA_HOME_LOCAL" --output "$B/dex" "$B/compile/native-quick.jar"
python3 native-integration/native_build.py check-sources
python3 native-integration/native_build.py package
python3 native-integration/native_build.py verify-unsigned
echo 'Local unsigned build verified: .local/build/wetype-quick-0.1.0-local-unsigned.apk'
echo 'Structural verification is not a device, privacy, voice, or legal clearance.'
