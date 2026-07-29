# EZ-Tube — Complete Project Overview

A self-hosted YouTube download platform for the ChaitusMedia production team.
This document describes the project in full: what it does, how it is built, every
feature it ships, where files live, how it is operated, and the engineering decisions
behind it.

> For quick setup instructions, see the [README](../README.md). This document is the
> detailed reference.

---

## 1. What this is

One Mac runs the server. Every teammate opens it in a browser, pastes a YouTube link,
chooses the format they want, and the finished file downloads to **their own**
machine. The server does the heavy work — extraction, merging, optional compression,
packaging — and hands back a file.

**The problem it solves.** Public download sites cap resolution, wrap everything in
adverts, silently re-encode (so "4K" arrives visibly degraded), refuse playlists, and
break without warning whenever YouTube changes. For a team that needs genuine
source-quality footage several times a day, none of that is acceptable.

**Who it is for.** A production team of roughly 30–40 people pulling reference
footage, music and 4K video for editing work.

**What it costs.** Nothing beyond hardware already owned. No subscriptions, no
per-seat licences, no cloud bill, no third party handling the team's activity.

---

## 2. Architecture

```
Browser (React 19 + Vite)
        │  HTTPS / LAN
        ▼
Spring Boot 4 · Java 21
        │
        ├── AuthFilter          cookie session, guards every /api route
        ├── DownloadController  REST surface
        ├── JobService          bounded worker pool, job registry, TTL cleanup
        ├── YtDlpService        builds and runs yt-dlp / ffmpeg, parses progress
        ├── CodecCatalog        compression models this host can actually deliver
        └── YtDlpUpdateService  version guard, self-update via Homebrew
        │
        ▼
yt-dlp  ──► ffmpeg / ffprobe ──► .work/<job-id>/<file>
```

The frontend is built by Vite directly into the backend's static resources, so the
whole application is served from a single origin on one port. There is no CORS
configuration to maintain and no second web server to run.

---

## 3. Where downloads are stored

**Location on the server:**

```
/Volumes/5amServer/My Progress/Editing Tools/Sameer/YT-DLP-with-Java/.work
```

Set by `ytdlp.work-dir` in `server/src/main/resources/application.properties`.

### Lifecycle of a file

1. **Job starts.** A directory named after the job id is created inside `.work`,
   e.g. `.work/184c7088/`.
2. **Download.** yt-dlp writes the video and audio streams there, then ffmpeg merges
   them into the target container.
3. **Optional compression.** If a compression model was chosen, ffmpeg re-encodes in
   place inside the same directory.
4. **Optional packaging.** A qualifying playlist is zipped into a single archive in
   that directory.
5. **Delivery.** The browser fetches the file over `/api/jobs/{id}/file`. The file is
   streamed from the server; the copy the user keeps lands in their own Downloads
   folder.
6. **Cleanup.** The job directory is deleted **two hours** after the job finishes
   (`ytdlp.file-ttl-minutes=120`). A sweep runs every five minutes, and the UI shows
   a live countdown on each finished card so nobody is surprised.
7. **Startup wipe.** Every job directory is cleared when the server starts, so a
   crash or restart cannot leave orphaned files behind.

### Practical notes

- Files are **temporary staging**, not an archive. Nothing is kept permanently by
  design — the server is a conduit, not storage.
- Nothing is stored per-user; the directory is keyed by job, not by person.
- **Disk headroom matters.** This volume is currently at 98% capacity with roughly
  84 GB free. A single 4K download can occupy several GB while it is being merged,
  and up to three jobs run at once. If the volume fills, downloads will fail mid-job.
  Either keep headroom on this disk or point `ytdlp.work-dir` at one with more room.
- To clear everything immediately, use the **Clear** button in the UI (per link), or
  stop the server and delete the contents of `.work`.

---

## 4. Features in full

### 4.1 Link analysis

- Accepts single videos, playlists, Shorts and YouTube Music links.
- Returns title, uploader, duration, thumbnail, true pixel dimensions, and the list
  of formats **that specific video actually publishes** — never a fixed menu, so an
  option is never offered that cannot be delivered.
- Detects music sources and audio-only content, and switches the interface to audio
  formats automatically.
- Resolution labels use broadcast shorthand: `4320p · UHD-8K`, `2160p · UHD`,
  `1440p · QHD`, `1080p · FHD`, `720p · HD`. Non-standard heights (common on vertical
  video) are shown as plain values rather than being mislabelled.
- Each analysed link opens its own tab with independent settings and its own download
  list. The URL bar clears after analysis, ready for the next link.
- A circular refresh button re-reads one link's formats from the server without
  reloading the page or disturbing other jobs in flight.

### 4.2 Audio

- MP3, M4A, OPUS and WAV.
- Highest available audio quality is selected automatically.
- Metadata is embedded, and cover art is embedded for every container that supports
  it. WAV and WEBM are skipped deliberately — they cannot hold a cover image, and
  attempting it aborts the whole job.

### 4.3 Video, and the Auto / Advanced model

Every format decision — for a single video and for each playlist item — follows one
consistent shape:

```
Video ─┬─ Auto      → MP4, original quality         → resolution → download
       └─ Advanced  → container → compression model → resolution → download
```

- **Auto** — MP4, streams merged losslessly, nothing re-encoded. Fastest path and no
  quality loss. This is the default and covers most day-to-day use.
- **Advanced** — exposes container and compression for people who need a specific
  deliverable.

**Containers:** MP4, MKV, WEBM.

### 4.4 Compression models

The list is built at startup from what the host's `ffmpeg` genuinely reports, and is
further filtered by what the chosen container can legally hold. H.265 is never offered
inside WEBM because WEBM cannot store it — the combination is removed from the
interface rather than failing later in the job.

| Model | Measured size | Encoder | Character |
|---|---|---|---|
| **Original** | full size | — | No re-encode. Best quality, fastest. |
| **H.265 / HEVC** | 65–90% | `hevc_videotoolbox` | *Recommended.* Hardware-encoded, so it stays quick. |
| **H.264 / AVC** | 80–90% | `h264_videotoolbox` | Maximum compatibility — older phones, TVs, editing suites. |
| **AV1** | 40–55% | `libsvtav1` | *Best savings.* Software-encoded, noticeably slower. |
| **VP9** | ~100% | `libvpx-vp9` | For players that require VP9. Does not shrink YouTube sources. |

Every percentage was **measured on real downloads at 360p and 720p**, not taken from
codec marketing. Ranges are quoted rather than a single flattering number because the
saving depends on resolution and on what codec YouTube served in the first place.

Two badges highlight the extremes: **Best quality** on Original, **Best savings** on
AV1, plus **Recommended** on H.265 as the practical balance of size and speed.

Only the first video stream is re-encoded. Audio, subtitles and embedded cover art are
copied through untouched, so nothing is lost that the download already gained.

### 4.5 Playlists

- **Download all as one `.zip`** — a single archive of the whole playlist.
- **Per-item selection** — tick individual videos; each carries its **own** type
  (video or audio), quality, container and compression. One playlist can produce a
  mixture of MP4s and MP3s in a single pass.
- **Honest zip gating.** The zip option appears **only** when every item genuinely
  offers the same resolutions, and then only at the resolutions they share. When they
  differ, the reason is shown and the view switches to per-item selection. A single
  quality setting applied across items that don't share it would quietly hand people
  different files without telling them.
- Each item's formats are probed individually, so the options shown per video are real.

### 4.6 Trimming

- A YouTube-style dual-handle range slider rather than typed timestamps.
- If the pasted link carries a timestamp (`?t=90`), the slider opens pre-set to it.
- Trimming is opt-in, and revertible in one click.
- Cuts are forced to keyframes so clips start cleanly.

### 4.7 Job control and progress

- Live progress with **transfer speed, ETA, elapsed time** and final file size.
- **Pause / resume** (process suspend and continue) and **cancel** (terminates the
  job and removes its partial files) on any running job.
- Distinct phases are surfaced: queued, analysing, downloading, merging, compressing,
  packaging, ready.
- Finished files are pre-fetched in the background when small enough, so **Save file**
  is instant.
- A live countdown shows how long the file remains on the server.
- **Retry** re-runs a failed job — or one whose file the server lost on a restart —
  with the exact settings it used the first time.
- **Clear** removes one link's finished files from the server on demand. The Downloads
  section and its Clear button are always present, with a "no downloads yet"
  placeholder card when empty, on every screen size.

### 4.8 Session persistence

- **Survives refresh and reopening.** Analysed tabs, their per-tab settings, and the
  download history are saved in the browser and restored on load. A reload never loses
  the user's place, and a phone that closes and reopens the site returns to the same
  state — the download it left running keeps going on the server, since jobs are
  server-side.
- **Refresh safety.** On desktop, a refresh while a download is live raises a native
  confirmation first. On mobile it just continues, matching how people expect an app
  to behave.
- **Settings are per tab.** Each panel's choices are stored on its tab, so switching
  tabs — or a full page refresh — keeps every selection intact while downloads carry
  on running.
- **Closing a tab** deletes that link's files from the server. If a download is still
  running, or a finished file was never saved, it asks for confirmation first;
  otherwise it closes cleanly.

### 4.9 Duplicate guard

A request that would produce a byte-identical file to one already downloading or
finished is intercepted. The user is asked whether to reuse the existing copy or
download it again.

This is the main defence against the same 4K video being fetched and stored five times
by five different people — it saves bandwidth, disk and time. Identity is computed
from URL, media type, resolution, container, compression, playlist selection and trim
range, so genuinely different requests are never blocked. Failed and cancelled jobs
never count as duplicates, since retrying those is the entire point.

### 4.10 Reliability

- **yt-dlp freshness guard.** The server compares its installed version against the
  latest release and runs `brew upgrade` when behind. An outdated yt-dlp is the single
  most common cause of downloads failing for no visible reason. The check runs in the
  background and never blocks a download, and its status is shown in the header.
- **Concurrency cap.** A bounded worker pool (default 3) with a queue, so a team all
  clicking *Download* at once queues neatly instead of overwhelming the machine.
- **Transfer retries.** Fragment, extractor and general retries, added after testing
  showed YouTube intermittently dropping fragments under parallel load — failures that
  succeeded on a second attempt.
- **Robust quality selection.** `bestvideo[height<=N]+bestaudio` with fallbacks, so a
  chosen quality never triggers the "Requested format is not available" retry loop.

### 4.11 Interface

- **Two-column workspace.** On a wide screen the analysed panel sits on the left at a
  comfortable fixed width, and the downloads section fills the empty space to its
  right, top-aligned below the search bar. The panel's size is unaffected by whether
  downloads are present. Below 1000px the two columns stack.
- **Tabs.** Every analysed link becomes a tab — shown even for a single link — in one
  strip that always keeps the active tab fully in view. On desktop, overflow is
  navigated by circular `<` and `>` arrow buttons that appear only when the strip
  overflows; tabs fade under the arrows via a mask gradient so they pass beneath
  smoothly rather than being cut off, and each arrow hides when its end is reached
  so it never floats over content it can't move to. Selecting a tab that sits
  under the fade scrolls it clear of both the arrow and the fade band, so it lands
  fully visible. On mobile, the strip is scrolled natively by touch and the arrows
  are hidden.
- **Mobile downloads sheet.** Below 750px the downloads column moves off the page
  and is reached from a ⋮ button placed next to Log out. Tapping it slides an
  off-canvas panel in from the right, containing the same Downloads section (list,
  Clear, retry, etc.). It closes by tapping the dimmed backdrop or the ← button in
  the sheet's top-left corner; taps inside the sheet don't close it. A small red
  counter on the ⋮ shows how many jobs the active tab currently has.
- **Responsive.** Below 750px the layout goes full-bleed and re-stacks: thumbnails go
  full width above their controls instead of shrinking into stamps, segmented switches
  span the row, and the header actions group at the right. It reads as an app rather
  than a scaled-down web page.
- **Responsive images.** Thumbnails ship a `srcset`, so a phone fetches a small
  variant instead of downloading a full-size image to draw it a few hundred pixels
  wide — a real saving across a playlist of twenty.
- **Clean, true-shape thumbnails.** The preview frame takes the image's own aspect
  ratio and the image fills it exactly — no black bars and no gradient backdrop, at any
  aspect, square album art included.
- **Light and dark themes**, each with separately tuned control surfaces so neither
  looks washed out.
- **Liquid-glass design language** — layered specular highlights, lit edges and depth
  shadows, with distinct hover states for selected and unselected controls, and
  circular theme and log-out buttons.
- **Restrained motion** — subtle entrance and hover transitions with an eased progress
  bar, fully disabled under `prefers-reduced-motion`.
- **Font Awesome icons** throughout, inheriting theme colour.
- **Session login.** Cookie-based, surviving refresh and server restart, clearing when
  the browser closes.

---

## 5. Tools and technologies

| Layer | Technology | Why |
|---|---|---|
| UI | **React 19** | Component state suits per-item playlist settings |
| Build | **Vite** | Fast builds; outputs straight into the backend's static resources |
| Icons | **Font Awesome** | Single consistent icon set, theme-aware |
| Backend | **Java 21** | Records, switch expressions, modern process API |
| Framework | **Spring Boot 4** | REST, static hosting, scheduling, filters in one runtime |
| JSON | **Jackson 3** | Parses yt-dlp's JSON output |
| Build tool | **Gradle** | Produces a single runnable jar |
| Extraction | **yt-dlp** | The most actively maintained extractor available |
| Media | **ffmpeg / ffprobe** | Merging, compression, and verifying real output |
| Acceleration | **Apple VideoToolbox** | Hardware H.265/H.264 — roughly an order of magnitude faster than software |
| Service | **launchd** | Keeps the server running and restarts it automatically |
| Remote access | **Cloudflare Tunnel** | HTTPS access from outside the LAN without opening ports |

---

## 6. HTTP API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/login` · `/api/logout` · `GET /api/me` | Session auth |
| `POST` | `/api/analyze` | Probe a URL: metadata, formats, playlist items |
| `GET` | `/api/codecs` | Compression models this host can deliver |
| `GET` | `/api/playlist/formats` | Whether a playlist's items share resolutions |
| `POST` | `/api/jobs` | Queue a download (`409` when it would duplicate one) |
| `GET` | `/api/jobs` · `/api/jobs/{id}` | Job list / single job status |
| `GET` | `/api/jobs/{id}/file` | Download the finished file |
| `POST` | `/api/jobs/{id}/pause` · `/resume` · `/cancel` | Job control |
| `POST` | `/api/jobs/clear` | Delete finished files for specific jobs |
| `GET` | `/api/ytdlp/status` | Version and freshness of yt-dlp |
| `GET` | `/api/health` | Liveness check |

---

## 7. Operating the server

| Task | Command |
|---|---|
| Build everything | `bash build.sh` |
| Start in the foreground | `bash start.sh` |
| Rebuild and reload the service | `bash restart.sh` |
| Install always-on service | `launchctl load -w ~/Library/LaunchAgents/com.predatorfx.ytdlp-web.plist` |

Three LaunchAgents are used in the full deployment: the web server itself, the
Cloudflare tunnel, and a watcher that keeps the published address current.

**Key configuration** (`server/src/main/resources/application.properties`, excluded
from version control):

| Key | Meaning |
|---|---|
| `server.port` / `server.address` | Where it listens; `0.0.0.0` for LAN access |
| `app.auth.username` / `app.auth.password` | Team credentials |
| `ytdlp.work-dir` | Where downloads are staged |
| `ytdlp.max-concurrent-jobs` | Simultaneous heavy jobs |
| `ytdlp.file-ttl-minutes` | How long finished files survive |
| `ytdlp.update-check-interval-minutes` | Freshness-guard interval |
| `ytdlp.bin` / `ffmpeg.bin` / `brew.bin` | **Absolute** paths to binaries |

---

## 8. Engineering decisions and issues resolved

Each of these cost real debugging time and is recorded so it is not rediscovered:

- **Lossless by default.** The original CLI re-encoded with `h264_nvenc`, which is
  NVIDIA-only and unavailable on a Mac. Streams are merged instead — faster and
  lossless.
- **Absolute binary paths.** Under `launchd`, Homebrew's `bin` is not on `PATH`, and
  Java's `ProcessBuilder` resolves program names against the JVM's inherited `PATH`,
  ignoring per-subprocess environment edits. Without absolute paths, jobs failed with
  `error=2`.
- **Homebrew, not `yt-dlp -U`.** Self-update via `yt-dlp -U` hangs on a brew-managed
  install; `brew upgrade` is used instead.
- **WEBM needs constrained stream selection.** WEBM can hold only VP8/VP9/AV1 video
  with Opus/Vorbis audio. A plain "best video + best audio" pick returns AVC1 + M4A,
  and merging that into `.webm` fails outright. **Every WEBM download was failing** at
  every resolution until stream selection was restricted for that container.
- **Compression must target bitrate, not quality.** YouTube already ships efficient
  AV1/VP9, so quality-based encoder settings produced files *larger* than the source —
  one measurement returned **441 MB from a 101 MB source**. Rate control now targets a
  share of the source bitrate, which is what makes the advertised saving real.
- **SVT-AV1 rejects a rate cap** outside CRF mode and refuses to start, so the cap is
  applied only to the VideoToolbox encoders.
- **AppleDouble files leaked into zips.** macOS writes `._name.mp4` side-cars on
  network volumes; they carry a media extension and were being packaged alongside the
  real videos, appearing as junk for anyone on Windows. They are now filtered out.
- **Thumbnails are matched by aspect.** YouTube serves several per video, and for
  Shorts the default is a padded 4:3 image. The candidate set is filtered to the
  video's shape so vertical content is not letterboxed inside a wide frame.
- **`max-height` cannot be combined with `aspect-ratio`.** The box clamps but the
  image keeps its aspect-derived height, and `overflow: hidden` then crops it. Height
  is limited by capping the *width* instead.
- **Progress is polled, not streamed.** Server-Sent Events are buffered by some
  reverse proxies, which made progress appear frozen and then jump straight to
  complete.
- **Build output is relocated** to a local disk, because this project lives on a
  volume where AppleDouble files break Gradle's cleanup.

### Testing performed

Compression and container handling were verified by an automated matrix that ran every
container against every compression model across a resolution sweep, then inspected
each finished file with `ffprobe` to confirm it genuinely carried the requested codec
and did not exceed the requested resolution — not merely that the job exited zero.
That matrix is what surfaced the WEBM and SVT-AV1 failures above. Zip output was
verified end to end: built on the server, downloaded as a user, and checked for
archive integrity and contents.

---

## 9. Known limitations

- **Throughput over a free tunnel.** A free Cloudflare quick tunnel is the current
  ceiling on remote download speed, and it mints a new hostname on every restart. A
  named tunnel on an owned domain fixes both — pending a domain decision.
- **Files are temporary.** Nothing is archived; finished files expire after two hours
  by design.
- **Browsers cannot save silently.** A download always goes through the browser's own
  save flow; small files are pre-fetched so it feels instant.
- **Disk headroom.** The staging volume is near capacity — see section 3.
- **Playlist uniformity is strict.** The zip option requires *identical* resolution
  sets across items, so it will not appear for most mixed playlists. This is
  deliberate, and can be relaxed to "common resolutions" if preferred.
- **Compression takes time.** Hardware H.265/H.264 are quick; AV1 and VP9 are
  software-encoded and slow on long or high-resolution material.

---

## 10. Usage and security notes

- **Internal tool.** Downloading is governed by the source site's terms — use it for
  content the team is permitted to download.
- **Credentials** live only in `application.properties`, which is excluded from
  version control. They should be rotated if ever exposed.
- **Public addresses.** The application is login-protected, but any public tunnel
  hostname should still be treated as sensitive and shared only within the team.

---

<sub>Built by PredatorFX · for ChaitusMedia team use</sub>
