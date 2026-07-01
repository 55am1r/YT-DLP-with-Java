#!/usr/bin/env bash
# Keeps the public redirect page (docs/index.html) pointed at the Cloudflare tunnel's
# CURRENT URL and pushes so Netlify redeploys. Runs forever as a LaunchAgent, so after a
# reboot (which gives the tunnel a new URL) the team's link keeps working with no manual
# step. Self-healing: if a push fails it retries on the next cycle.
export PATH="/opt/homebrew/bin:/usr/bin:/bin"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG="/tmp/ytdlp-tunnel.log"
REDIRECT="$DIR/docs/index.html"

echo "$(date '+%F %T') auto-redirect watcher started ($DIR)"
while true; do
  TUN=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOG" 2>/dev/null | tail -1)
  if [ -n "$TUN" ]; then
    # 1) make sure the page points at the current tunnel URL
    perl -i -pe "s|https://[a-z0-9-]+\\.trycloudflare\\.com|$TUN|g" "$REDIRECT" 2>/dev/null
    find "$DIR/.git" -name '._*' -delete 2>/dev/null
    git -C "$DIR" add docs/index.html 2>/dev/null
    # 2) commit only if the page actually changed
    if ! git -C "$DIR" diff --cached --quiet 2>/dev/null; then
      git -C "$DIR" commit -q -m "Auto: point redirect at current tunnel URL ($TUN)" 2>/dev/null
      echo "$(date '+%F %T') redirect updated -> $TUN"
    fi
    # 3) push if we're ahead of the remote (flushes any commit that failed to push before)
    if [ -n "$(git -C "$DIR" rev-list @{u}.. 2>/dev/null)" ]; then
      if git -C "$DIR" push >/dev/null 2>&1; then
        echo "$(date '+%F %T') pushed ($TUN) — Netlify will redeploy"
      else
        echo "$(date '+%F %T') push failed — will retry next cycle"
      fi
    fi
  fi
  sleep 60
done
