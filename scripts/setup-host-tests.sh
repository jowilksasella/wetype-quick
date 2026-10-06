#!/usr/bin/env bash
# Optional framework-backed tests. Requires Apache Maven on PATH.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v mvn >/dev/null || { echo 'Install Apache Maven from its official distribution first.' >&2;exit 1; }
mkdir -p .local/host-tests/dependencies .local/host-tests/aar-jars
mvn -B -f tests/pom.xml -Dmaven.repo.local="$PWD/.local/m2" org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies -DoutputDirectory="$PWD/.local/host-tests/dependencies"
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile
root=Path('.local/host-tests')
for p in sorted((root/'dependencies').glob('*.aar')):
 with ZipFile(p) as z:
  if 'classes.jar' in z.namelist():(root/'aar-jars'/(p.stem+'.jar')).write_bytes(z.read('classes.jar'))
PY
