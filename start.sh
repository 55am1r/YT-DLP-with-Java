#!/usr/bin/env bash
# Start the YT-DLP Studio server and print the URL your team should open.
set -euo pipefail
export PATH="/opt/homebrew/bin:$PATH"

JAR="$HOME/.ytdlp-web/build/libs/ytdlp-web-0.0.1-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
  echo "Jar not found — run:  bash build.sh"
  exit 1
fi

# Find the IP of whichever interface actually carries the LAN/default route
# (on this Mac the LAN IP is not on en0).
IFACE="$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')"
IP="$(ipconfig getifaddr "$IFACE" 2>/dev/null || ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo localhost)"
echo "▼  YT-DLP Studio is starting…"
echo "    On this Mac:      http://localhost:8080"
echo "    For your team:    http://$IP:8080"
echo "    (Keep this window open. Press Ctrl-C to stop.)"
echo ""

# caffeinate keeps the Mac awake while the server runs, so it stays reachable.
exec caffeinate -s java -jar "$JAR"
