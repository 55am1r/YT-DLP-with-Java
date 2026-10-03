#!/usr/bin/env bash
# Keeps EZ-Tube reachable on its own, so a network drop never needs someone to reboot the
# Mac. Runs forever as the LaunchAgent com.predatorfx.ytdlp-redirect.
#
# Every cycle it checks three things and fixes what it can:
#
#   1. The app answers on localhost. launchd already restarts it if it EXITS; this also
#      covers the case where it is alive but wedged and answering nothing.
#   2. The tunnel answers from the public side. cloudflared retries a dropped connection
#      itself, backing off 8s -> 16s -> 32s -> ~64s, and usually wins. When it does not,
#      the process stays alive holding a dead tunnel, so launchd's KeepAlive never fires
#      and the team is offline until someone intervenes. A restart re-registers it.
#   3. The public redirect page points at the tunnel URL that is live right now.
#
# THE BUG THIS REPLACES. The old version rewrote docs/index.html and committed BEFORE
# pushing, then decided whether there was work to do by comparing the tunnel URL against
# that same file. So a push that failed — exactly what happens when the URL changes
# because the network just dropped — left the file already matching, the comparison
# already satisfied, and the push never retried, despite a log line promising it would.
# The team's link stayed pointed at a dead tunnel until the Mac was rebooted, which minted
# a new URL at a moment when the network happened to be up. Pushing is now driven by
# whether the branch actually has unpushed commits, so it retries until it lands.
#
# Runs against a LOCAL git clone (passed as $1) on the internal disk, because macOS blocks
# launchd from touching the project on the /Volumes mount. Push uses the osxkeychain creds.
export PATH="/opt/homebrew/bin:/usr/bin:/bin"

DIR="${1:?pass the local clone path}"
LOG="/tmp/ytdlp-tunnel.log"
REDIRECT="$DIR/docs/index.html"
LABEL_WEB="com.predatorfx.ytdlp-web"
LABEL_TUNNEL="com.predatorfx.ytdlp-tunnel"
DOMAIN="gui/$(id -u)"

CYCLE=20            # seconds between checks
TUNNEL_STRIKES=9    # ~3 min unreachable before restarting cloudflared. Its own backoff
                    # peaks near 64s, so 3 min means it is genuinely stuck, not retrying.
APP_STRIKES=15      # ~5 min before restarting the app. Deliberately slow: a restart wipes
                    # every download in progress, so it must not fire on a blip.

say() { echo "$(date '+%F %T') $*"; }

app_ok()      { curl -fsS --max-time 10 -o /dev/null http://127.0.0.1:8080/api/health 2>/dev/null; }
internet_ok() { curl -fsS --max-time 10 -o /dev/null https://www.cloudflare.com/cdn-cgi/trace 2>/dev/null; }
tunnel_url()  { grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOG" 2>/dev/null | tail -1; }
tunnel_ok()   { [ -n "$1" ] && curl -fsS --max-time 20 -o /dev/null "$1/api/health" 2>/dev/null; }
restart()     { say "restarting $1"; launchctl kickstart -k "$DOMAIN/$1" 2>&1 | sed 's/^/    /'; }

app_fails=0
tunnel_fails=0

say "watchdog started (repo: $DIR, every ${CYCLE}s)"

while true; do
  # ---- 1. the app itself -------------------------------------------------------------
  if app_ok; then
    [ "$app_fails" -gt 0 ] && say "app is answering again (after $app_fails failed checks)"
    app_fails=0
  else
    app_fails=$((app_fails + 1))
    say "app health check failed ($app_fails/$APP_STRIKES)"
    if [ "$app_fails" -ge "$APP_STRIKES" ]; then
      restart "$LABEL_WEB"
      app_fails=0
    fi
  fi

  URL="$(tunnel_url)"

  # ---- 2. the tunnel -----------------------------------------------------------------
  # Only judged when the internet is actually reachable. During a real outage there is
  # nothing to fix, and restarting cloudflared would only churn the public URL.
  if internet_ok; then
    if tunnel_ok "$URL"; then
      [ "$tunnel_fails" -gt 0 ] && say "tunnel is answering again (after $tunnel_fails failed checks)"
      tunnel_fails=0
    else
      tunnel_fails=$((tunnel_fails + 1))
      say "tunnel unreachable at ${URL:-<none>} ($tunnel_fails/$TUNNEL_STRIKES)"
      if [ "$tunnel_fails" -ge "$TUNNEL_STRIKES" ]; then
        restart "$LABEL_TUNNEL"   # comes back with a fresh URL, published below
        tunnel_fails=0
        sleep 15                  # give it a moment to register before reading the log
        URL="$(tunnel_url)"
      fi
    fi
  else
    [ "$tunnel_fails" -eq 0 ] || say "no internet — pausing tunnel checks"
    tunnel_fails=0
  fi

  # ---- 3. publish the current URL -----------------------------------------------------
  if [ -n "$URL" ]; then
    # A rebase left half-finished by an earlier failure would block every future push.
    if [ -d "$DIR/.git/rebase-merge" ] || [ -d "$DIR/.git/rebase-apply" ]; then
      say "clearing a stuck rebase in the redirect repo"
      git -C "$DIR" rebase --abort >/dev/null 2>&1
    fi
    # This volume spawns AppleDouble files that confuse git; keep them out of .git.
    find "$DIR/.git" -name '._*' -delete 2>/dev/null

    CUR="$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$REDIRECT" 2>/dev/null | tail -1)"
    if [ "$URL" != "$CUR" ]; then
      say "tunnel URL changed: ${CUR:-<none>} -> $URL"
      git -C "$DIR" pull --rebase --quiet >/dev/null 2>&1
      perl -i -pe "s|https://[a-z0-9-]+\\.trycloudflare\\.com|$URL|g" "$REDIRECT"
      git -C "$DIR" add docs/index.html
      git -C "$DIR" commit --quiet -m "Auto: point redirect at current tunnel URL ($URL)" >/dev/null 2>&1
    fi

    # Retried every cycle until it lands — what the old script could not do.
    AHEAD="$(git -C "$DIR" rev-list --count '@{u}..HEAD' 2>/dev/null || echo 0)"
    if [ "${AHEAD:-0}" -gt 0 ]; then
      git -C "$DIR" pull --rebase --quiet >/dev/null 2>&1
      if git -C "$DIR" push --quiet >/dev/null 2>&1; then
        say "published $URL ($AHEAD commit(s)) — Netlify will redeploy"
      else
        say "push failed, $AHEAD commit(s) still pending — retrying in ${CYCLE}s"
      fi
    fi
  fi

  sleep "$CYCLE"
done
