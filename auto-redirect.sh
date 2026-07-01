#!/usr/bin/env bash
# Keeps the public redirect (docs/index.html) pointed at the current Cloudflare tunnel URL
# and pushes so Netlify redeploys — so after a reboot the team's link needs no manual step.
#
# Runs against a LOCAL git clone (passed as $1) on the internal disk, because macOS blocks
# launchd from touching the project on the /Volumes mount. Push uses the osxkeychain creds.
export PATH="/opt/homebrew/bin:/usr/bin:/bin"
DIR="${1:?pass the local clone path}"
LOG="/tmp/ytdlp-tunnel.log"
REDIRECT="$DIR/docs/index.html"

echo "$(date '+%F %T') auto-redirect watcher started (repo: $DIR)"
while true; do
  TUN=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOG" 2>/dev/null | tail -1)
  CUR=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$REDIRECT" 2>/dev/null | tail -1)
  if [ -n "$TUN" ] && [ "$TUN" != "$CUR" ]; then
    echo "$(date '+%F %T') tunnel changed: $CUR -> $TUN"
    git -C "$DIR" pull --rebase --quiet >/dev/null 2>&1
    perl -i -pe "s|https://[a-z0-9-]+\\.trycloudflare\\.com|$TUN|g" "$REDIRECT"
    git -C "$DIR" add docs/index.html
    git -C "$DIR" commit --quiet -m "Auto: point redirect at current tunnel URL ($TUN)"
    OUT=$(git -C "$DIR" push 2>&1); RC=$?
    echo "$OUT" | tail -2
    if [ "$RC" -eq 0 ]; then
      echo "$(date '+%F %T') pushed — Netlify will redeploy"
    else
      echo "$(date '+%F %T') push FAILED (rc=$RC) — will retry next cycle"
    fi
  fi
  sleep 30
done
