#!/usr/bin/env bash
# Print the current public URL for the team to use (from the running Cloudflare tunnel).
URL=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' /tmp/ytdlp-tunnel.log 2>/dev/null | tail -1)
if [ -z "$URL" ]; then
  echo "No tunnel URL found. Is the tunnel running?  Check /tmp/ytdlp-tunnel.log"
  exit 1
fi
echo "Share this with the team (password: see application.properties):"
echo "  $URL"
