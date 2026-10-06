#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/classes
find src -name '*.java' | sort > build/sources.list
if command -v javac >/dev/null 2>&1; then
  javac -source 8 -target 8 -encoding UTF-8 -d build/classes @build/sources.list
else
  ECJ_JAR="${ECJ_JAR:-../tools/ecj.jar}"
  test -f "$ECJ_JAR" || { echo "Set ECJ_JAR or install a JDK with javac." >&2; exit 1; }
  java -jar "$ECJ_JAR" -source 8 -target 8 -encoding UTF-8 -d build/classes @build/sources.list
fi
java -cp build/classes org.wetype.quick.QuickEngineTest
java -cp build/classes org.wetype.quick.CompositionEngineTest
python3 - <<'PY'
from pathlib import Path
import hashlib
import subprocess
import zipfile
before = Path('resources/quick-traditional.tsv').read_bytes()
subprocess.run(['python3', 'scripts/build_dictionary.py'], check=True)
assert Path('resources/quick-traditional.tsv').read_bytes() == before, 'Dictionary is not reproducible'
subprocess.run(['python3', 'scripts/build_phrases.py', '--check'], check=True)
with zipfile.ZipFile('build/quick-engine.jar', 'w', zipfile.ZIP_DEFLATED) as out:
    for p in sorted(Path('build/classes/org/wetype/quick').glob('*.class')):
        if p.name.startswith(('QuickEngineTest', 'CompositionEngineTest', 'CompositionEngineBenchmark')):
            continue
        info = zipfile.ZipInfo(str(p.relative_to('build/classes')), (2026, 10, 5, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        out.writestr(info, p.read_bytes())
print('PASS: reproducible dictionary; built build/quick-engine.jar')
PY
