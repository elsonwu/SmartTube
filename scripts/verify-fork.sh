#!/usr/bin/env bash
#
# Build the fork and run the sanity checks that matter after an upstream sync.
#
#   ./scripts/verify-fork.sh
#
# Checks that:
#   1. The build succeeds.
#   2. The APK has the expected package name, label and ABIs.
#   3. The upstream update manifest is still disabled.
#   4. The parental-limit strings are present in all three languages.
#   5. The lock Activity is registered in the manifest.

set -uo pipefail

cd "$(dirname "$0")/.."

APK="smarttubetv/build/outputs/apk/ststable/release/SmallTube_parental-limit_stable_32.68_universal.apk"
FAILURES=0

pass() { echo "  [✓] $1"; }
fail() { echo "  [✗] $1"; FAILURES=$((FAILURES + 1)); }

echo "══════════════════════════════════════════════════════════════"
echo "  1. build"
echo "══════════════════════════════════════════════════════════════"
if ! ./gradlew :smarttubetv:assembleStstableRelease --no-daemon 2>&1 | tail -3; then
    echo "  [✗] build FAILED"
    exit 1
fi

if [ ! -f "$APK" ]; then
    echo "  [✗] APK not found at $APK"
    exit 1
fi
echo "  [✓] $APK"
ls -lh "$APK" | awk '{print "      " $5}'

AAPT="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/build-tools/34.0.0/aapt"
[ -x "$AAPT" ] || AAPT="aapt"

echo
echo "══════════════════════════════════════════════════════════════"
echo "  2. package identity"
echo "══════════════════════════════════════════════════════════════"
BADGING=$("$AAPT" dump badging "$APK" 2>/dev/null)

echo "$BADGING" | grep -q "package: name='org.smalltube.stable'" \
    && pass "applicationId = org.smalltube.stable (side-by-side with upstream)" \
    || fail "applicationId is NOT org.smalltube.stable"

echo "$BADGING" | grep -q "application-label:'SmallTube'" \
    && pass "app label = SmallTube" \
    || fail "app label is not SmallTube"

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    echo "$BADGING" | grep -q "'$abi'" \
        && pass "ABI present: $abi" \
        || fail "ABI missing: $abi   (upstream only ships ARM in 'universal' - this bit us before)"
done

echo
echo "══════════════════════════════════════════════════════════════"
echo "  3. upstream update manifest must stay disabled"
echo "══════════════════════════════════════════════════════════════"
if grep -q "<item>" common/src/ststable/res/values/update_urls.xml; then
    fail "update_urls.xml is not empty - the app would download the ORIGINAL SmartTube"
else
    pass "update_urls.xml is empty (no upstream APK download)"
fi

for url in "smarttube_stable2.json" "smarttube_beta2.json"; do
    if strings "$APK" 2>/dev/null | grep -q "$url"; then
        fail "'$url' still present in the APK"
    else
        pass "'$url' absent from the APK"
    fi
done

echo
echo "══════════════════════════════════════════════════════════════"
echo "  4. parental-limit strings in all three languages"
echo "══════════════════════════════════════════════════════════════"
python3 - <<'PY'
import re, sys
def keys(p):
    try:
        s = open(p, encoding='utf-8').read()
    except OSError:
        return set()
    return set(re.findall(r'name="(parental_limit_[a-z_0-9]+)"', s))
en = keys('common/src/main/res/values/strings.xml')
zh = keys('common/src/main/res/values-zh/strings.xml')
tw = keys('common/src/main/res/values-zh-rTW/strings.xml')
bad = 0
if not en:
    print("  [✗] no parental_limit strings in the English file")
    bad = 1
else:
    print(f"  [✓] en: {len(en)} strings")
for name, s in (('zh', zh), ('zh-rTW', tw)):
    missing = sorted(en - s)
    if missing:
        print(f"  [✗] {name} missing {len(missing)}: {missing[:5]}")
        bad = 1
    else:
        print(f"  [✓] {name}: {len(s)} strings (complete)")
sys.exit(bad)
PY
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

echo
echo "══════════════════════════════════════════════════════════════"
echo "  5. manifest registration"
echo "══════════════════════════════════════════════════════════════"
grep -q "ParentalLimitActivity" smarttubetv/src/main/AndroidManifest.xml \
    && pass "ParentalLimitActivity is registered" \
    || fail "ParentalLimitActivity is NOT registered"

# Scope the noHistory check to the ParentalLimitActivity block only - other
# activities in the manifest legitimately use noHistory.
LOCK_BLOCK=$(python3 - <<'PY'
import re
x = open('smarttubetv/src/main/AndroidManifest.xml', encoding='utf-8').read()
m = re.search(r'<activity[^>]*ParentalLimitActivity[^>]*/?>', x)
print(m.group(0) if m else '')
PY
)
if [ -z "$LOCK_BLOCK" ]; then
    fail "could not find the ParentalLimitActivity <activity> block"
elif echo "$LOCK_BLOCK" | grep -q 'android:noHistory="true"'; then
    fail "noHistory is set on ParentalLimitActivity - it destroys the lock screen when the parent menu opens"
else
    pass "noHistory is not set on the lock screen"
fi

echo
echo "══════════════════════════════════════════════════════════════"
if [ "$FAILURES" -eq 0 ]; then
    echo "  ALL CHECKS PASSED"
    echo "══════════════════════════════════════════════════════════════"
    echo "  Now test on the TV - FORK_MAINTENANCE.md §7"
    exit 0
else
    echo "  $FAILURES CHECK(S) FAILED"
    echo "══════════════════════════════════════════════════════════════"
    exit 1
fi
