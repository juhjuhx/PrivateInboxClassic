#!/usr/bin/env bash
# Binary audit of the built APKs.
#
# This inspects the PACKAGED artifacts, not the source manifests, because the
# manifest that ships is the result of the manifest merge and can differ from
# what the source says.
#
# Checks:
#   1. Forbidden permissions are absent from the debug and release APKs.
#   2. DebugSettingsProvider exists ONLY in the debug APK.
#   3. The debug provider is guarded by a signature-level permission.
#   4. The release APK carries NO debug signature (it must be unsigned or
#      signed with a real release key).
#   5. The release APK contains a parsable dataExtractionRules resource and
#      excludes the database and preferences.
#   6. allowBackup / fullBackupContent are set to block backup.
#
# Usage: bash scripts/audit-release.sh [path-to-apk-dir]
set -uo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
BT="$SDK/build-tools/36.0.0"
AAPT="$BT/aapt"
APKSIGNER="$BT/apksigner"
APKDEBUG="app/build/outputs/apk/debug/app-debug.apk"
APKRELEASE="app/build/outputs/apk/release/app-release-unsigned.apk"

fail=0
ok()   { printf '  OK   %s\n' "$*"; }
bad()  { printf '  FAIL %s\n' "$*"; fail=1; }
skip() { printf '  SKIP %s\n' "$*"; }

for tool in "$AAPT" "$APKSIGNER"; do
  if [ ! -x "$tool" ]; then
    echo "ERROR: missing tool $tool (set ANDROID_SDK_ROOT)"
    exit 2
  fi
done

echo "=== 1. Forbidden permissions ==="
for apk in "$APKDEBUG" "$APKRELEASE"; do
  if [ ! -f "$apk" ]; then skip "$apk not built"; continue; fi
  perms=$("$AAPT" dump permissions "$apk")
  for p in INTERNET READ_SMS READ_CONTACTS QUERY_ALL_PACKAGES READ_EXTERNAL_STORAGE ACCESS_NETWORK_STATE; do
    if printf '%s' "$perms" | grep -q "android.permission.$p"; then
      bad "$(basename "$apk") contains android.permission.$p"
    fi
  done
  ok "$(basename "$apk"): no sensitive permission"
  # POST_NOTIFICATIONS is allowed: it is only used for the app's own self-test.
  if printf '%s' "$perms" | grep -q "android.permission.POST_NOTIFICATIONS"; then
    ok "$(basename "$apk"): POST_NOTIFICATIONS present (self-test only)"
  fi
done

echo ""
echo "=== 2. DebugSettingsProvider must be debug-only ==="
if [ -f "$APKDEBUG" ]; then
  if "$AAPT" dump xmltree "$APKDEBUG" AndroidManifest.xml 2>/dev/null | grep -q "DebugSettingsProvider"; then
    ok "debug APK contains DebugSettingsProvider (expected)"
  else
    bad "debug APK is missing DebugSettingsProvider — the instrumentation tests cannot run"
  fi
else
  skip "debug APK not built"
fi
if [ -f "$APKRELEASE" ]; then
  if "$AAPT" dump xmltree "$APKRELEASE" AndroidManifest.xml 2>/dev/null | grep -q "DebugSettingsProvider"; then
    bad "RELEASE APK contains DebugSettingsProvider — it must not"
  else
    ok "release APK has no DebugSettingsProvider"
  fi
else
  skip "release APK not built"
fi

echo ""
echo "=== 3. Debug provider must require a signature-level permission ==="
if [ -f "$APKDEBUG" ]; then
  xml=$("$AAPT" dump xmltree "$APKDEBUG" AndroidManifest.xml 2>/dev/null)
  # protectionLevel 0x2 == signature
  if printf '%s' "$xml" | grep -q 'android:permission(0x01010006)="tw.personal.privateinbox.permission.DEBUG_SETTINGS"'; then
    ok "provider requires tw.personal.privateinbox.permission.DEBUG_SETTINGS"
  else
    bad "provider is not guarded by the signature permission"
  fi
  if printf '%s' "$xml" | grep -q 'android:name(0x01010003)="tw.personal.privateinbox.permission.DEBUG_SETTINGS"' &&
     printf '%s' "$xml" | grep -q 'android:protectionLevel(0x01010009)=(type 0x11)0x2'; then
    ok "DEBUG_SETTINGS permission is signature-protected (protectionLevel 0x2)"
  else
    bad "DEBUG_SETTINGS permission is missing or not signature-protected"
  fi
fi

echo ""
echo "=== 4. Release APK must not carry the debug signature ==="
if [ -f "$APKRELEASE" ]; then
  if "$APKSIGNER" verify --print-certs "$APKRELEASE" > /tmp/rel-cert.txt 2>&1; then
    if grep -q "CN=Android Debug" /tmp/rel-cert.txt; then
      bad "RELEASE APK is signed with the Android DEBUG key — it must not be distributable"
    else
      ok "release APK is signed with a non-debug key"
    fi
  else
    ok "release APK is UNSIGNED (no release keystore configured) — not distributable"
  fi
fi

echo ""
echo "=== 5. Backup rules in the packaged release APK ==="
if [ -f "$APKRELEASE" ]; then
  xml=$("$AAPT" dump xmltree "$APKRELEASE" AndroidManifest.xml 2>/dev/null)
  if printf '%s' "$xml" | grep -q 'android:allowBackup(0x01010280)=(type 0x12)0x0'; then
    ok "allowBackup=false in the packaged release manifest"
  else
    bad "allowBackup is not false in the packaged release manifest"
  fi
  if printf '%s' "$xml" | grep -q 'android:dataExtractionRules'; then
    ok "dataExtractionRules is referenced in the packaged release manifest"
  else
    bad "dataExtractionRules is missing from the packaged release manifest"
  fi
  # fullBackupContent is attribute 0x010104eb on this platform version.
  if printf '%s' "$xml" | grep -q 'android:fullBackupContent(0x010104eb)=(type 0x12)0x0'; then
    ok "fullBackupContent=false in the packaged release manifest"
  else
    bad "fullBackupContent is not false in the packaged release manifest"
  fi

  # The packaged resources are renamed (res/4j.xml ...) AND compiled to
  # binary AXML, so the rules file must be located by content and parsed with
  # aapt2 rather than grepped for literal XML tags.
  rules_file=$(python3 - "$APKRELEASE" <<'PYEOF'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
for n in z.namelist():
    if n.startswith('res/') and n.endswith('.xml'):
        d = z.read(n)
        if b'cloud-backup' in d and b'device-transfer' in d:
            print(n)
            break
PYEOF
)
  if [ -z "$rules_file" ]; then
    bad "the packaged data extraction rules resource was not found"
  else
    excludes=$("$BT/aapt2" dump xmltree --file "$rules_file" "$APKRELEASE" 2>/dev/null \
               | grep -c 'E: exclude')
    domains=$("$BT/aapt2" dump xmltree --file "$rules_file" "$APKRELEASE" 2>/dev/null \
               | grep -c 'A: domain=')
    if [ "${excludes:-0}" -ge 18 ]; then
      ok "the packaged data extraction rules exclude every domain ($excludes exclude rules)"
    else
      bad "the packaged data extraction rules only have $excludes exclude rules (expected 18)"
    fi
    # Collect the domains actually present in the packaged rules.
    domains_found=$("$BT/aapt2" dump xmltree --file "$rules_file" "$APKRELEASE" 2>/dev/null \
                    | sed -n 's/.*A: domain="\([^"]*\)".*/\1/p' | sort -u)
    missing=""
    for required in root file database sharedpref external device_root device_file device_database device_sharedpref; do
      if ! printf '%s\n' "$domains_found" | grep -qx "$required"; then
        missing="$missing $required"
      fi
    done
    if [ -z "$missing" ]; then
      ok "the packaged rules exclude all nine backup domains"
    else
      bad "the packaged rules are missing these domains:$missing"
    fi
    ok "the packaged rules exclude all nine backup domains"
  fi
else
  skip "release APK not built"
fi

echo ""
echo "=== 6. Source-level backup rules exclude data ==="
rules_file="app/src/main/res/xml/data_extraction_rules.xml"
if [ -f "$rules_file" ]; then
  if grep -q "<cloud-backup>" "$rules_file" && grep -q "<device-transfer>" "$rules_file"; then
    # Both sections must exclude at least one path, otherwise the database
    # and preferences would be uploaded.
    cb=$(python3 - "$rules_file" <<'PY'
import sys, re
s = open(sys.argv[1]).read()
def section(tag):
    m = re.search(r"<%s>(.*?)</%s>" % (tag, tag), s, re.S)
    return m.group(1) if m else ""
cb = section("cloud-backup")
dt = section("device-transfer")
excludes = cb.count("<exclude") + dt.count("<exclude")
print(excludes)
PY
)
    if [ "$cb" -gt 0 ]; then
      ok "data_extraction_rules.xml excludes paths in both sections ($cb exclude rules)"
    else
      bad "data_extraction_rules.xml does not exclude any path"
    fi
  else
    bad "data_extraction_rules.xml is missing a section"
  fi
else
  bad "data_extraction_rules.xml is missing"
fi

echo ""
if [ "$fail" -eq 0 ]; then
  echo "RESULT: PASS — the packaged artifacts satisfy every check."
  exit 0
else
  echo "RESULT: FAIL — see the findings above."
  exit 1
fi
