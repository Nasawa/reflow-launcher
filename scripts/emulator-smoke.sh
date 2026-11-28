#!/usr/bin/env bash
# Install the debug APK, seed a launcher.json that decodes a card (so Card / TOKEN run),
# cold-start the launcher 3 times, and fail on a crash or ANR.
# App-only mode has no .configlauncher/status.json; a successful decode is "ok" when
# MainActivity starts, the process stays up, and logcat has no FATAL EXCEPTION / ANR /
# PatternSyntaxException. An unescaped TOKEN brace crashes in Card.<clinit> on API 29
# before the first start returns, which this script would report as "crash or ANR".
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/pkg.env"
mkdir -p smoke
# Older adb/devices (API < 24) do not propagate exit codes, so check the output text.
adb install -r app/build/outputs/apk/debug/*.apk | tee smoke/install.txt
grep -q "^Success" smoke/install.txt || { echo "install failed"; exit 1; }

# Seed after install (run-as needs the package). Same path as longpress-smoke.sh / screen-matrix.sh.
# appOnly skips first-run so ConfigEngine loads files/config/source/launcher.json.
# The card's picture src and text both contain {data:…}, so Card.TOKEN is compiled and used.
cat > smoke/launcher.json <<'J'
{"schemaVersion":1,"revision":1,"grid":{"columns":4,"rows":6},"pages":[{"id":"home","items":[
 {"id":"s","type":"app","package":"com.android.settings","label":"Settings","x":0,"y":0}]}],
 "elements":[{"id":"w-smoke","type":"card","requires":6,"page":"home","x":0,"y":1,"w":2,"h":2,
  "title":"Smoke","author":"agent",
  "layers":[{"kind":"picture","src":"sticker-{data:state}.webp","default":"sticker-idle.webp","scrim":"none"},{"kind":"content"}],
  "layouts":{"2x2":[{"type":"text","text":"{data:monitors.summary}"}]}}]}
J
cat > smoke/settings.xml <<'J'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><boolean name="appOnly" value="true" /></map>
J
for f in launcher.json settings.xml; do adb push "smoke/$f" "/data/local/tmp/$f" >/dev/null; done
adb shell "run-as $PKG sh -c 'mkdir -p files/config/source shared_prefs && cp /data/local/tmp/launcher.json files/config/source/launcher.json && cp /data/local/tmp/settings.xml shared_prefs/settings.xml'"

adb logcat -c || true   # API 26 images: "failed to clear the main log"
: > smoke/am-start.txt   # fresh per run; the loop appends
for i in 1 2 3; do
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/$NS.MainActivity" | tee -a smoke/am-start.txt
  sleep 5
done
adb logcat -d > smoke/logcat.txt
grep -E 'TotalTime|WaitTime' smoke/am-start.txt || true
# Pull the seeded file back; if clinit crashed we may never have decoded it, but the crash
# check below is the ICU signal. Confirm the seed is still the file the phone has open.
adb shell "run-as $PKG cat files/config/source/launcher.json" > smoke/seeded-on-device.json || true
if grep -E "FATAL EXCEPTION|ANR in $PKG|PatternSyntaxException" smoke/logcat.txt; then echo "crash or ANR"; exit 1; fi
grep -q 'TotalTime' smoke/am-start.txt || { echo "MainActivity did not start"; exit 1; }
adb shell "ps -A 2>/dev/null || ps" | grep -q "$PKG" || { echo "process not running"; exit 1; }
# The on-device file must still be the card seed (a {data:…} token), so this start decoded Card.
grep -q '{data:monitors.summary}' smoke/seeded-on-device.json || { echo "seeded card config missing on device"; exit 1; }
echo "smoke OK"
