<div align="center">

# ▼ EZ-Tube

**Source-quality YouTube downloads, built for a production team.**

Up to 8K · lossless by default · no ads · no caps

### [Open EZ-Tube →](https://github.com/55am1r/YT-DLP-with-Java/releases/latest)

<sub>The live link ships with every <a href="https://github.com/55am1r/YT-DLP-with-Java/releases">release</a>.
Sign in with your team login.</sub>

<br>

![React](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=white)
![Vite](https://img.shields.io/badge/Vite-8-646CFF?logo=vite&logoColor=white)
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4-6DB33F?logo=springboot&logoColor=white)
![yt-dlp](https://img.shields.io/badge/engine-yt--dlp-FF0000?logo=youtube&logoColor=white)
![FFmpeg](https://img.shields.io/badge/FFmpeg-007808?logo=ffmpeg&logoColor=white)

</div>

---

## The promise

Public download sites cap the resolution, bury the button under adverts, quietly re-encode
"4K" into something softer, and stop working whenever YouTube changes something. EZ-Tube was
built so a production team never has to put up with any of that.

- **The quality it was published at.** Video and audio are merged exactly as they came — no
  re-encode unless you ask for one. A 4K60 source arrives as 4K60.
- **Files that open in your editor.** The default MP4 opens in QuickTime, Premiere Pro,
  Final Cut Pro and DaVinci Resolve — sound included.
- **No adverts, no watermarks, no caps.** No resolution ceiling, no length limit, no daily
  quota.
- **Numbers you can trust.** Every file-size figure on this page was measured on real
  downloads, not copied from codec marketing.
- **Nothing left behind.** Finished files are deleted from the server two hours after they
  are ready.
- **It keeps working.** The download engine keeps itself current, and the service repairs its
  own public link when the connection drops.

---

## By the numbers

| | |
|---:|---|
| **8K** | the ceiling — every resolution a video publishes is offered, from 144p to 4320p, at 60 fps where the source has it |
| **0** | re-encodes by default: original streams, merged losslessly |
| **up to 60%** | smaller files with AV1 — 40–55% of the source size, measured |
| **5** | compression choices: Original, H.265, H.264, AV1, VP9 |
| **3 + 4** | video containers (MP4 · MKV · WEBM) and audio formats (MP3 · M4A · OPUS · WAV) |
| **0.8 s** | between progress updates — speed, time left, and a bar for every stage |
| **8** | pieces of each file fetched in parallel |
| **15 + 40** | automatic transfer and fragment retries, so a dropped connection doesn't fail the job |
| **3** | heavy jobs at a time; the rest queue, so 30 people clicking at once never stall the machine |
| **4** | saves running side by side, straight into your chosen folder |
| **100** | playlist items checked before a one-click zip is offered |
| **20 s** | between the service's own health checks |
| **2 h** | before a finished file is cleared from the server |
| **30–40** | teammates served from a single machine |
| **0** | cloud bill — it runs on hardware the team already owns |

---

## What you can do

**Paste any YouTube link** — a video, a Short, a playlist or a YouTube Music track — and choose
from the resolutions that video really offers, labelled the way editors talk: `2160p · UHD`,
`1440p · QHD`, `1080p · FHD`. Nothing is offered that can't be delivered.

### Pick exactly what you need

- **Video or audio.** Audio comes out as MP3, M4A, OPUS or WAV, tagged with its title and
  cover art.
- **Auto or Advanced.** *Auto* hands you an MP4 that plays anywhere. *Advanced* lets you pick
  the container and the compression model yourself.
- **Trim before you download.** A YouTube-style range slider cuts out just the part you need.
  Paste a link with a timestamp (`?t=90`) and the slider opens at that moment.
- **Playlists, item by item.** Tick the videos you want and give each its own settings — one
  pass can produce some MP4s and some MP3s. When every video shares the same resolutions, take
  the whole set as a single `.zip`.

### Watch it happen

- **Live progress** with speed, time left, and separate bars for video, audio, finishing and
  any conversion.
- **Pause, resume or cancel** any download.
- **Retry that picks up where it stopped.** A drop at 70% only fetches the missing 30%.
- **Duplicate check.** If the same file with the same settings is already on the server, take
  that copy instead of downloading it again.

### Get the file

- **Auto-save to a folder.** Choose a folder once and every finished download writes itself
  there — no dialog, no button. Each link, or each file, can go to its own folder (Chrome and
  Edge). No folder? Files go to your browser's downloads after a 30-second grace period.
- **Resumable saves.** A browser download broken by a Wi-Fi switch carries on instead of
  starting over.

### Work the way you like

- **Several links at once,** each in its own tab with its own settings and downloads.
- **Pick up where you left off.** Refresh, or close the site and come back later: your tabs,
  settings and download history are still there, and downloads keep running on the server in
  the meantime.
- **Made for phones too.** On a small screen the layout becomes a full-screen app with a
  slide-in downloads sheet.
- **Light and dark themes,** with motion that respects your system's reduce-motion setting.

---

## Formats and file sizes

| Compression | Size vs. source | Best for |
|---|---|---|
| **Original** | 100% | Untouched streams. Best quality, fastest. |
| **H.265 / HEVC** | 65–90% | *Recommended.* Hardware-encoded on Apple Silicon, so a 4K clip takes minutes, not hours. |
| **H.264 / AVC** | 80–90% | Maximum compatibility — older phones, TVs and editing suites. |
| **AV1** | 40–55% | *Best savings.* Software-encoded, so noticeably slower. |
| **VP9** | ~100% | Players that require VP9. It won't shrink a YouTube file, and EZ-Tube says so. |

Only the options the server can actually deliver are offered, and only in containers that can
hold them — H.265 is never offered inside WEBM. Auto MP4s and every re-encoded MP4 carry AAC
audio, so they never open silent in Premiere.

> **Why the sizes are honest.** YouTube already ships efficient AV1 and VP9, so a typical
> "quality" encoder setting makes files *bigger* — one test turned a 101 MB source into
> 441 MB. EZ-Tube targets a share of the source bitrate instead, which is what makes the
> savings above real.

---

## Built to stay up

- **Self-updating engine.** An outdated yt-dlp is the most common reason downloads break.
  EZ-Tube checks for a new release every 3 hours and upgrades itself in the background — never
  in the middle of your download.
- **Self-healing link.** The service checks itself every 20 seconds. If the public connection
  drops, it brings it back and re-points the team's link on its own — nobody has to reboot
  anything.
- **Retries built in.** YouTube occasionally drops a piece of a file under load; the retries
  absorb it before you ever see an error.
- **A fair queue.** Heavy work is capped so the machine stays responsive for everyone; extra
  downloads wait their turn instead of slowing every job down.
- **Tidy by design.** Each finished file shows a countdown and is cleared after two hours, so
  the disk never quietly fills up.
- **Protected access.** Team sign-in over HTTPS, with repeated wrong passwords locked out
  automatically.

---

## Under the hood

| | |
|---|---|
| **Frontend** | React 19 · Vite |
| **Backend** | Java 21 · Spring Boot 4 |
| **Media engine** | yt-dlp · FFmpeg |
| **Encoding** | VideoToolbox hardware encoders on Apple Silicon · SVT-AV1 |
| **Delivery** | Self-hosted, published over HTTPS through Cloudflare |

```
server/   Spring Boot backend — download engine, job queue, REST API
web/      React frontend
deploy/   service definitions that keep EZ-Tube running around the clock
legacy/   the original command-line downloader EZ-Tube grew from
```

This repository is the source of the hosted EZ-Tube service. To use EZ-Tube, open the link in
the [latest release](https://github.com/55am1r/YT-DLP-with-Java/releases/latest).

---

<sub>Downloading is governed by each source site's terms. Use EZ-Tube only for content you have
the right to download.</sub>

<div align="center"><sub>Built by <b>PredatorFX</b> · for the team</sub></div>
