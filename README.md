# EZ-Tube

A self-hosted web application for downloading audio and video from YouTube, built for
a production team that needs source-quality media on demand.

One machine runs the server. Everyone else opens a link in their browser, picks what
they want, and the finished file downloads to **their own** computer. No per-seat
licences, no upload limits, no third-party site covered in adverts, and nothing leaves
hardware you control.

| | |
|---|---|
| **Frontend** | React 19 + Vite |
| **Backend** | Java 21 + Spring Boot 4 |
| **Media engine** | `yt-dlp` + `ffmpeg` / `ffprobe` |
| **Runs on** | macOS (Apple Silicon or Intel), self-hosted |
| **Cost** | Free — your own hardware, no cloud bill |

---

## Why it exists

Off-the-shelf download sites cap resolution, inject adverts, re-encode silently, and
break whenever YouTube changes something. This build removes those constraints:

- **True source quality.** Video and audio streams are merged **losslessly** by
  default, so a 4K60 source stays 4K60 — no generational quality loss.
- **It keeps working.** An outdated `yt-dlp` is the single most common cause of
  downloads failing without explanation. The server checks its own version and
  updates itself before that becomes your problem.
- **It scales to a team.** A bounded worker pool means thirty people clicking
  *Download* at once queues neatly instead of overwhelming the machine.
- **It respects the machine.** Finished files are swept automatically, duplicate
  requests are caught before they cost bandwidth, and heavy work is capped.

---

## Features

### Media selection

- **Any YouTube link** — single video, playlist, Shorts, or YouTube Music.
- **Real format detection.** Quality options come from the formats that specific
  video actually publishes, not a fixed list, so nothing is offered that can't be
  delivered. Labels use broadcast shorthand — `2160p · UHD`, `1440p · QHD`,
  `1080p · FHD`.
- **Audio extraction** — MP3, M4A, OPUS or WAV, with metadata and embedded cover art
  where the container supports it.
- **Per-link tabs.** Several links can be analysed at once, each with its own
  settings and its own download list.
- **Refresh control.** A per-item refresh re-reads one link's available formats from
  the server without reloading the page or disturbing other work in progress.

### Auto / Advanced format modes

Every format choice — globally and for each playlist item — follows the same shape:

```
Video ─┬─ Auto      → MP4, original quality         → resolution → download
       └─ Advanced  → container → compression model → resolution → download
```

- **Auto** is MP4 with no re-encoding: fastest, and nothing is lost.
- **Advanced** exposes the container (MP4 / MKV / WEBM) and the compression model.

### Compression models

Offered options are filtered against what the host's `ffmpeg` actually reports at
startup, and against what the chosen container can legally hold — H.265 is never
offered inside WEBM, because WEBM cannot store it.

| Model | Measured size | Notes |
|---|---|---|
| **Original** | full size | No re-encode. Best quality, fastest. |
| **H.265 / HEVC** | 65–90% | *Recommended.* Encoded on the Mac's media engine, so it stays quick. |
| **H.264 / AVC** | 80–90% | Maximum compatibility — older phones, TVs, editing suites. |
| **AV1** | 40–55% | *Best savings.* Software-encoded, so noticeably slower. |
| **VP9** | ~100% | For players that require VP9; does not shrink YouTube sources. |

Every figure above was **measured on real downloads**, not taken from codec
marketing. This matters: YouTube already ships efficient AV1/VP9, so a quality-based
encoder setting produces files *larger* than the original — one measurement returned
441 MB from a 101 MB source. Rate control therefore targets a share of the source
bitrate, which is what makes the advertised saving actually happen. VP9 is labelled
honestly rather than implying a saving it cannot deliver.

On Apple Silicon, H.265 and H.264 run on the hardware media engine, so a 4K clip
finishes in minutes rather than hours and the CPU stays free for other jobs.

### Playlists

- **Per-item control.** Tick individual videos; each carries its own type
  (video/audio), quality, container and compression. One playlist can produce some
  MP4s and some MP3s in a single pass.
- **Honest zip gating.** "Download all as one `.zip`" is offered **only** when every
  item genuinely offers the same resolutions, and then only at those shared
  resolutions. Otherwise the reason is shown and the list switches to per-item
  selection — a single quality setting across items that don't share it would
  quietly hand people different files.

### Downloads and job control

- **Live progress** with transfer speed, ETA, elapsed time and final file size.
- **Pause, resume and cancel** on any running job.
- **Trimming** via a YouTube-style range slider, cut at keyframes. If the pasted link
  carries a timestamp (`?t=90`), the slider opens pre-set to it; trimming is opt-in
  and revertible.
- **Duplicate guard.** A request that would re-download an identical file is caught
  and offered as a choice: reuse the copy already on the server, or download it
  again. This is the main defence against the same 4K video being fetched and stored
  five times by five people.
- **Retry.** A failed download, or one whose file the server lost on a restart,
  offers a one-click retry that re-runs with the exact original settings.
- **Always-visible section.** The Downloads area and its Clear button are shown at all
  times, with a "no downloads yet" placeholder when empty.
- **Automatic cleanup.** Finished files carry a visible countdown and are deleted on
  a TTL, so the disk doesn't silently fill.

### Session persistence

- **Survives refresh and reopening.** Analysed tabs, their settings and the download
  history are saved in the browser and restored on load — a reload never loses your
  place. A phone that closes and reopens the site comes back to the same state, and
  the download it left running continues on the server in the meantime.
- **Refresh safety.** On desktop, refreshing while a download is live prompts a
  confirmation first; on mobile it simply continues.
- **Settings stick per tab.** Switching between tabs — or a full refresh — keeps every
  panel's selections intact, and downloads keep running throughout.
- **Closing a tab** removes that link's files from the server, confirming first if a
  download is running or a finished file was never saved.

### Reliability

- **yt-dlp freshness guard.** The server compares its installed version against the
  latest release and runs `brew upgrade` when behind. This runs in the background and
  never blocks a download.
- **Concurrency cap.** A bounded worker pool with a queue; the limit is configurable.
- **Transfer retries.** Fragment and extractor retries, added after testing showed
  YouTube intermittently dropping fragments under parallel load — failures that
  succeeded on a second attempt.

### Interface

- **Two-column workspace.** On a wide screen the downloads sit to the right of the
  analysed panel and fill the empty space; the panel keeps its size whether downloads
  are present or not. The columns stack on narrower screens.
- **Tabs.** Every analysed link is a tab — shown even for a single link — in one
  strip that always keeps the active tab in view. On desktop, overflow is navigated
  by circular `<` / `>` arrow buttons that appear only when the strip overflows and
  fade the tabs beneath them for a smooth transition. On mobile, the strip scrolls
  natively by touch — no arrows.
- **Responsive across screen sizes.** Below 750px the layout goes full-bleed and
  re-stacks — thumbnails go full width above their controls rather than shrinking
  into stamps — so a phone feels like an app rather than a scaled-down web page.
- **Responsive images.** Thumbnails ship a `srcset`, so a phone fetches a small
  variant instead of downloading a full-size image to draw it a few hundred pixels
  wide — a meaningful saving across a playlist of twenty.
- **Clean, true-shape thumbnails.** The preview frame takes the image's own aspect
  ratio and the image fills it — no black bars, no gradient backdrop, at any aspect
  including square album art.
- **Light and dark themes**, each with its own tuned control surfaces, plus restrained
  entrance and hover motion that respects `prefers-reduced-motion`.
- **Session login.** Cookie-based auth, credentials kept out of version control.

---

## Requirements

| Tool | Purpose |
|------|---------|
| Java 21 | Runs the backend |
| Node + npm | Builds the React UI |
| `yt-dlp` | Media extraction |
| `ffmpeg` (with `ffprobe`) | Merging, compression, inspection |
| Homebrew | Used by the self-update guard |

```bash
brew install yt-dlp ffmpeg node temurin
```

> `yt-dlp` installed via Homebrew must be updated with `brew upgrade` — `yt-dlp -U`
> hangs on a brew-managed install. The freshness guard already accounts for this.

---

## Setup

1. **Create your configuration** (this file is deliberately not in version control):

   ```bash
   cp server/src/main/resources/application.properties.example \
      server/src/main/resources/application.properties
   ```

2. **Edit it** and set, at minimum:
   - `app.auth.username` / `app.auth.password` — your own credentials
   - `ytdlp.work-dir` — an absolute path on a disk with room for temporary files
   - `ytdlp.bin` / `ffmpeg.bin` / `brew.bin` — absolute paths

   Absolute binary paths are required: under `launchd`, Homebrew's `bin` is not on
   `PATH`, and Java's `ProcessBuilder` resolves program names against the JVM's
   inherited `PATH`.

3. **Build and run:**

   ```bash
   bash build.sh     # builds the React UI, then the runnable jar
   bash start.sh     # starts the server and prints the URL to share
   ```

`start.sh` prints both a local address and your machine's LAN address. Share the LAN
one with the team. If macOS blocks incoming connections, allow `java` in
**System Settings → Network → Firewall → Options**.

### Always-on service

`deploy/` contains LaunchAgent templates so the server starts at login and restarts if
it exits:

```bash
cp deploy/com.predatorfx.ytdlp-web.plist ~/Library/LaunchAgents/
launchctl load -w ~/Library/LaunchAgents/com.predatorfx.ytdlp-web.plist
```

`restart.sh` rebuilds and reloads the service in one step after a code change.

### Access beyond the LAN

For teammates on other networks, the deployment scripts support a Cloudflare tunnel
that publishes the login-protected app over HTTPS. Free "quick" tunnels mint a new
hostname on every restart; a named tunnel on your own domain gives a stable address
and materially better throughput.

---

## Configuration reference

| Key | Default | Meaning |
|-----|---------|---------|
| `server.port` | `8080` | Port the app listens on |
| `server.address` | `0.0.0.0` | Bind to all interfaces so the LAN can reach it |
| `app.auth.enabled` | `true` | Require login |
| `app.auth.username` / `app.auth.password` | — | Team credentials; keep out of git |
| `ytdlp.work-dir` | — | Where temporary downloads land |
| `ytdlp.max-concurrent-jobs` | `3` | Simultaneous heavy jobs; the rest queue |
| `ytdlp.update-check-interval-minutes` | `180` | How often to re-check yt-dlp |
| `ytdlp.file-ttl-minutes` | `120` | How long a finished file survives |

---

## Development

Run the backend and the Vite dev server separately for hot reload:

```bash
# terminal 1 — backend on :8080
cd server && sh gradlew bootRun

# terminal 2 — UI on :5173, proxies /api to :8080
cd web && npm run dev
```

### Project structure

```
.
├── build.sh / start.sh / restart.sh    # build, run, redeploy
├── deploy/                             # LaunchAgent templates
├── legacy/SongDownloader.java          # the original CLI this grew from
├── server/                             # Spring Boot backend
│   └── src/main/java/com/predatorfx/ytdlpweb/
│       ├── service/                    # YtDlpService, CodecCatalog, JobService,
│       │                               #   YtDlpUpdateService
│       ├── web/                        # REST controllers, auth filter
│       └── model/                      # Job, DownloadRequest, AnalyzeResult, …
└── web/                                # React frontend
    └── src/{App.jsx, api.js, components/}
```

### HTTP API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/analyze` | Probe a URL: metadata, formats, playlist items |
| `GET` | `/api/codecs` | Compression models this host can deliver |
| `GET` | `/api/playlist/formats` | Whether a playlist's items share resolutions |
| `POST` | `/api/jobs` | Queue a download (`409` when it would duplicate one) |
| `GET` | `/api/jobs/{id}` | Job status |
| `GET` | `/api/jobs/{id}/file` | Download the finished file |
| `POST` | `/api/jobs/{id}/pause` · `/resume` · `/cancel` | Job control |
| `GET` | `/api/ytdlp/status` | Version / freshness of yt-dlp |

---

## Implementation notes

Details that are not obvious from the code, recorded because each cost real debugging
time:

- **Lossless by default.** The original CLI re-encoded with `h264_nvenc`, which is
  NVIDIA-only and unavailable on a Mac. Streams are merged instead, which is both
  faster and lossless.
- **WEBM needs constrained stream selection.** WEBM can only hold VP8/VP9/AV1 video
  with Opus/Vorbis audio. A plain "best video + best audio" pick returns AVC1 + M4A,
  and merging that into `.webm` fails outright — every WEBM download failed until the
  selection was restricted for that container specifically.
- **Compression targets bitrate, not quality.** See the compression table above.
- **SVT-AV1 rejects a rate cap** outside CRF mode and refuses to start, so the cap is
  applied only to the VideoToolbox encoders.
- **Thumbnails are matched by aspect.** YouTube serves several per video, and for
  Shorts the default is a padded 4:3 image; the set is filtered to the video's shape
  so vertical content doesn't render letterboxed inside a wide frame.
- **Build output is relocated** to a local disk, because this project can live on a
  network volume where macOS AppleDouble (`._*`) files break Gradle's cleanup.
- **Progress is polled, not streamed.** Server-Sent Events are buffered by some
  reverse proxies, which made progress appear frozen and then jump to complete.

---

## Notes on use

- **Internal tool.** Downloading is governed by the source site's terms — use it for
  content you are permitted to download.
- **Credentials.** `application.properties` is excluded from version control. Never
  commit real credentials; rotate them if they are ever exposed.
- **Exposure.** The app is login-protected, but treat any public tunnel address as
  sensitive and share it only with the team.

---

<sub>Built by PredatorFX · for ChaitusMedia team use</sub>
