#!/usr/bin/env bash
# Rebuild the app and restart the always-on service. Use this after any code change.
set -euo pipefail
export PATH="/opt/homebrew/bin:$PATH"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

bash "$DIR/build.sh"

PLIST="$HOME/Library/LaunchAgents/com.predatorfx.ytdlp-web.plist"
launchctl unload "$PLIST" 2>/dev/null || true
launchctl load -w "$PLIST"

IFACE="$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')"
IP="$(ipconfig getifaddr "$IFACE" 2>/dev/null || echo localhost)"
echo ""
echo "✓ Rebuilt and restarted. Live for the team at:  http://$IP:8080"
