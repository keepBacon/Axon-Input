#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo '[1/4] Secret scan'
python3 tools/oss_secret_scan.py

echo '[2/4] JSON syntax'
python3 - <<'PY'
from pathlib import Path
import json
root=Path('.')
for p in root.rglob('*.json'):
    if any(part in {'.git','out','build','.gradle','node_modules'} for part in p.parts):
        continue
    json.loads(p.read_text(encoding='utf-8'))
print('JSON syntax: OK')
PY

echo '[3/4] Shell syntax'
for f in build-termux.sh build-native.sh setup-termux.sh init-signing-key.sh; do
  [ ! -f "$f" ] || bash -n "$f"
done

echo '[4/4] Sensitive file names'
if find . -type f \( -name '*.jks' -o -name '*.keystore' -o -name '*.p12' -o -name '*.pfx' -o -name '*.pem' -o -name '*.key' \) -print -quit | grep -q .; then
  echo 'Credential-like file found.' >&2
  exit 1
fi

echo 'Pre-publish checks: OK'
