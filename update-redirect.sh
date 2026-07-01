#!/usr/bin/env bash
# Point the public redirect page (docs/index.html) at the CURRENT tunnel URL and push,
# so Netlify / GitHub Pages redeploys. Run this after a reboot changes the tunnel URL.
set -euo pipefail
export PATH="/opt/homebrew/bin:$PATH"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

URL=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' /tmp/ytdlp-tunnel.log 2>/dev/null | tail -1)
if [ -z "$URL" ]; then
  echo "No tunnel URL found — is the tunnel running?  (bash tunnel-url.sh)"
  exit 1
fi

FILE="$DIR/docs/index.html"
perl -i -pe "s|https://[a-z0-9-]+\\.trycloudflare\\.com|$URL|g" "$FILE"
echo "Redirect target set to: $URL"

# The 5amServer volume spawns AppleDouble files inside .git; clean them so git is quiet.
find "$DIR/.git" -name '._*' -delete 2>/dev/null || true

git -C "$DIR" add "$FILE"
if git -C "$DIR" diff --cached --quiet; then
  echo "Already up to date — nothing to push."
  exit 0
fi
git -C "$DIR" commit -m "Update EasyDownload redirect to current tunnel URL" >/dev/null
git -C "$DIR" push
echo "Pushed — Netlify / GitHub Pages will redeploy in ~1 minute."
