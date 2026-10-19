#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
from pathlib import Path
from xml.etree import ElementTree
root=Path('app/src/main')
manifest=(root/'AndroidManifest.xml').read_text()
ElementTree.fromstring(manifest)
android_ns = '{http://schemas.android.com/apk/res/android}'
permissions = [e.attrib.get(android_ns + 'name', '') for e in ElementTree.fromstring(manifest).findall('uses-permission')]
for prohibited in ('android.permission.INTERNET','android.permission.READ_SMS','android.permission.READ_CONTACTS',
                   'android.permission.QUERY_ALL_PACKAGES','android.permission.READ_EXTERNAL_STORAGE'):
    assert prohibited not in permissions, f'Disallowed permission: {prohibited}'
assert 'android:allowBackup="false"' in manifest
assert 'android:exported="false"' in manifest
assert 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE' in manifest
print('PASS: Manifest parsing, privacy permission policy, no backup and listener declarations')
PY
bash scripts/test-core.sh
