# YT-DLP Studio

A self-hosted web app for downloading audio & video from YouTube, built on top of
the original `SongDownloader.java` yt-dlp logic. You run it on your Mac; your team
opens it in a browser over the LAN and the files download to **their** machines.

- **Frontend:** React (Vite)
- **Backend:** Java 21 + Spring Boot 4, wrapping `yt-dlp` + `ffmpeg`
- **Cost:** free — runs on your own hardware, no cloud, no hosting bills

---

## What it does

- 🎵 **Audio** — extract MP3 / M4A / OPUS / WAV with embedded thumbnail + metadata
- 🎬 **Video** — pick resolution (up to 4K/8K) and container (MP4 / MKV / WEBM),
  merged **losslessly** (no re-encoding, so 4K stays true 4K)
- 📃 **Playlists** — download an entire playlist, delivered as a single `.zip`
- 🔄 **yt-dlp freshness guard** — before any job runs, the server checks whether
  yt-dlp is outdated and **auto-updates it via Homebrew** first. Outdated yt-dlp is
  the #1 cause of downloads silently breaking, so this is built in.
- 📊 **Live progress** — real-time progress bars over Server-Sent Events
- 🧵 **Concurrency cap** — a small worker pool + queue so 30–40 people can't
  overwhelm the Mac with simultaneous 4K downloads

---

## Prerequisites (already installed on this Mac)

| Tool | Notes |
|------|-------|
| Java 21 | Temurin |
| yt-dlp | installed via Homebrew (so updates use `brew upgrade`, **not** `yt-dlp -U`) |
| ffmpeg | for merging/encoding |
| Node + npm | for building the React UI |

If you move this to another Mac: `brew install yt-dlp ffmpeg node temurin`.

---

## Build & run

```bash
bash build.sh     # builds the React UI + the runnable jar
bash start.sh     # starts the server and prints your team's URL
```

`start.sh` prints something like:

```
On this Mac:   http://localhost:8080
For your team: http://192.168.1.10:8080
```

Share the **team** URL. Keep the window open (it uses `caffeinate` to keep the Mac
awake while serving). Press `Ctrl-C` to stop.

### First-time team access (macOS firewall)

If macOS blocks incoming connections, allow `java`:
**System Settings → Network → Firewall → Options → allow incoming for Java** —
or temporarily turn the firewall off on the trusted LAN. Everyone must be on the
same network as the Mac.

---

## Keep it always-on (optional)

Auto-start on login and restart on crash with the included LaunchAgent:

```bash
cp deploy/com.predatorfx.ytdlp-web.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.predatorfx.ytdlp-web.plist   # start
launchctl unload ~/Library/LaunchAgents/com.predatorfx.ytdlp-web.plist # stop
# logs: /tmp/ytdlp-web.log
```

(Run `bash build.sh` at least once first.)

---

## Remote access from any network (Cloudflare tunnel)

Your `192.168.1.10` address only works on the office LAN. To let the team download
from any Wi-Fi, the app is exposed through a **free, unmetered Cloudflare tunnel**,
protected by the team password.

Install the tunnel as an always-on service (once):

    cp deploy/com.predatorfx.ytdlp-tunnel.plist ~/Library/LaunchAgents/
    launchctl load -w ~/Library/LaunchAgents/com.predatorfx.ytdlp-tunnel.plist

Get the current public URL to share with the team:

    bash tunnel-url.sh        # prints https://<something>.trycloudflare.com

Teammates open that URL on any network and log in with the team password.

> **Note:** the free (no-domain) tunnel URL **changes when the tunnel restarts**
> (e.g. after a reboot). Re-run `bash tunnel-url.sh` and re-share it. For a **permanent
> fixed URL** (e.g. `download.chaitusmedia.com`), a domain on a free Cloudflare account
> enables a named tunnel — also free and more robust.

### Change the team login

The site shows a login page; the session stays until the browser is fully closed
(a cookie with no expiry — survives refreshes and server restarts). Change the
credentials in `server/src/main/resources/application.properties`, then `bash restart.sh`:

    app.auth.username=chiatusteam
    app.auth.password=Team1234     # change to something strong before sharing widely

---

## Configuration

Edit `server/src/main/resources/application.properties` (rebuild after changes):

| Key | Default | Meaning |
|-----|---------|---------|
| `server.port` | `8080` | Port the site listens on |
| `ytdlp.work-dir` | `…/YT-DLP-with-Java/.work` | Where temp downloads land (on the 460 GB volume, not the 63 GB system disk) |
| `ytdlp.max-concurrent-jobs` | `3` | Simultaneous heavy downloads; the rest queue |
| `ytdlp.update-check-interval-minutes` | `180` | How often to re-check that yt-dlp is current |
| `ytdlp.file-ttl-minutes` | `120` | How long a finished file stays before cleanup |

---

## How it works (the non-obvious bits)

- **Freshness guard uses Homebrew.** yt-dlp here is a brew formula, so
  `yt-dlp -U` doesn't work (it hangs). The guard reads the installed version,
  compares it to the latest GitHub release, and runs `brew upgrade yt-dlp` only
  when behind — cached so it doesn't hit the network on every request.
- **No re-encoding.** The original CLI re-encoded with `h264_nvenc` (NVIDIA-only,
  absent on Macs). We download best-video + best-audio and merge streams, which is
  faster and preserves original quality. If a player ever struggles with a codec
  (YouTube 4K is VP9/AV1), pick **MKV** — it holds anything without conversion.
- **Robust quality selection.** Uses `bestvideo[height<=N]+bestaudio`, so a chosen
  quality never triggers the "Requested format is not available" retry loop.
- **Build outputs live on the local disk** (`~/.ytdlp-web/build`) because this
  project sits on an SMB/exFAT volume that spawns AppleDouble `._*` files, which
  otherwise break Gradle's cleanup.

---

## Development (hot reload)

Run the backend and the Vite dev server separately:

```bash
# terminal 1 — backend on :8080
cd server && sh gradlew bootRun

# terminal 2 — UI on :5173 with hot reload (proxies /api to :8080)
cd web && npm run dev
```

Open http://localhost:5173.

---

## Project structure

```
YT-DLP-with-Java/
├── build.sh / start.sh            # one-command build & run
├── deploy/…plist                  # optional auto-start LaunchAgent
├── SongDownloader.java            # the original CLI (kept for reference)
├── server/                        # Spring Boot backend
│   └── src/main/java/com/predatorfx/ytdlpweb/
│       ├── service/               # YtDlpService, YtDlpUpdateService, JobService
│       ├── web/                   # REST controller + CORS
│       └── model/                 # Job, DownloadRequest, AnalyzeResult, …
└── web/                           # React frontend (Vite)
    └── src/{App.jsx, api.js, components/}
```

---

## Notes

- **Internal use.** This is an internal team tool. Downloading depends on the
  source site's terms; use it for content you're permitted to download.
- **Security.** There's no login — it assumes a trusted LAN. Don't expose port
  8080 to the public internet. If you later need auth or public access, that's a
  follow-up.
