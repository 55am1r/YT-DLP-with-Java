package com.predatorfx.ytdlpweb.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.predatorfx.ytdlpweb.model.AnalyzeResult;
import com.predatorfx.ytdlpweb.model.DownloadRequest;
import com.predatorfx.ytdlpweb.model.Job;
import com.predatorfx.ytdlpweb.model.JobStatus;
import com.predatorfx.ytdlpweb.model.PlaylistFormats;
import com.predatorfx.ytdlpweb.model.PlaylistItem;
import com.predatorfx.ytdlpweb.model.VideoFormatOption;
import com.predatorfx.ytdlpweb.util.Processes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Wraps yt-dlp. Ported from the original SongDownloader CLI, with two important
 * changes for a Mac team-server:
 *   1. No re-encoding (the old h264_nvenc path is NVIDIA-only and would fail on a
 *      Mac and wreck 4K quality) — we merge streams losslessly instead.
 *   2. Robust "height&lt;=" format selection so a chosen quality never triggers the
 *      "Requested format is not available" retry loop the CLI suffered from.
 */
@Service
public class YtDlpService {

    private static final Logger log = LoggerFactory.getLogger(YtDlpService.class);

    /** Marks our own progress line, so it is never confused with yt-dlp's prose. */
    private static final String EZ = "[EZ]";

    private static final Pattern ITEM = Pattern.compile("Downloading item (\\d+) of (\\d+)");
    private static final Pattern PL_TITLE = Pattern.compile("Downloading playlist: (.+)");

    /**
     * Machine-readable progress, so nothing has to be recovered from human text.
     *
     * --progress-template REPLACES yt-dlp's "[download] 56.0% of 723.45MiB at 32.96MiB/s"
     * lines rather than adding to them (verified), so this one line has to carry every
     * field the parser needs — percent, speed and ETA included. The old regexes that
     * scraped those out of the prose are gone with it: they can no longer match anything.
     *
     * Fields: status|downloaded|total|percent|speed|eta|vcodec. Any field can be the literal
     * "NA"; downloaded/total are integers, speed is a float, eta is an integer. vcodec is
     * the codec of the stream being fetched — "none" for the audio one — which is how an
     * Auto job learns on its first tick that it will need converting to H.264.
     */
    private static final String PROGRESS_TEMPLATE = "download:" + EZ
            + "%(progress.status)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s"
            + "|%(progress._percent_str)s|%(progress.speed)s|%(progress.eta)s|%(info.vcodec)s";

    /**
     * Containers yt-dlp can embed a cover image into. WAV and WEBM cannot — passing
     * --embed-thumbnail for those aborts the whole job with "Postprocessing: Supported
     * filetypes for thumbnail embedding are: …".
     */
    private static final Set<String> THUMBNAIL_OK = Set.of(
            "mp3", "mka", "mkv", "ogg", "opus", "flac", "m4a", "mp4", "m4v", "mov");

    /** Past this, probing every item costs more than the zip option is worth. */
    private static final int MAX_UNIFORMITY_PROBE = 100;

    private static final Set<String> MEDIA_EXT = Set.of(
            "mp3", "m4a", "opus", "ogg", "wav", "flac", "aac",
            "mp4", "mkv", "webm", "mov", "m4v");

    @Value("${ytdlp.bin:yt-dlp}")
    private String bin;

    @Value("${ffmpeg.bin:ffmpeg}")
    private String ffmpegBin;

    @Value("${ytdlp.work-dir}")
    private String workDirCfg;

    private final ObjectMapper mapper = new ObjectMapper();

    private final CodecCatalog codecs;

    /** Live yt-dlp processes by job id, so downloads can be paused/canceled. */
    private final Map<String, Process> processes = new ConcurrentHashMap<>();

    /** Uniformity verdicts by playlist URL — probing every item is far too slow to redo. */
    private final Map<String, PlaylistFormats> playlistFormatCache = new ConcurrentHashMap<>();

    public YtDlpService(CodecCatalog codecs) {
        this.codecs = codecs;
    }

    public Path workDir() {
        return Path.of(workDirCfg);
    }

    // ------------------------------------------------------------------ ANALYZE

    /** Probe a URL and return what the UI needs to show + the quality choices. */
    public AnalyzeResult analyze(String url) throws IOException {
        List<String> cmd = List.of(bin, "-J", "--flat-playlist", "--no-warnings", "--no-progress", "--ignore-config", url);
        Processes.Result r;
        try {
            r = Processes.run(cmd, Duration.ofSeconds(90));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while analyzing URL");
        }
        if (r.code() != 0 || r.stdout().isBlank()) {
            throw new IOException(firstError(r.stderr()));
        }

        JsonNode root = mapper.readTree(r.stdout());
        boolean playlist = "playlist".equals(root.path("_type").asText("")) || root.has("entries");
        String title = text(root, "title");
        String uploader = firstText(root, "uploader", "channel", "playlist_uploader", "uploader_id");
        Long duration = (root.has("duration") && root.get("duration").isNumber()) ? root.get("duration").asLong() : null;
        // Real pixel size drives both the thumbnail we pick and how the UI shapes it,
        // so a vertical video never gets squeezed into a landscape frame.
        int[] dim = videoDimensions(root);
        double ratio = (dim[0] > 0 && dim[1] > 0) ? (double) dim[0] / dim[1] : 16.0 / 9.0;
        String thumb = pickThumbnail(root, ratio);

        Integer count = null;
        List<VideoFormatOption> formats;
        List<PlaylistItem> items = List.of();
        if (playlist) {
            if (root.has("playlist_count")) {
                count = root.get("playlist_count").asInt();
            } else if (root.has("entries")) {
                count = root.get("entries").size();
            }
            formats = standardTiers();
            items = parseEntries(root);
        } else {
            formats = parseFormats(root); // empty when the source has no video streams
        }

        // Music sites — and anything with no video streams at all — get audio-only options.
        boolean music = isMusicUrl(url) || (!playlist && formats.isEmpty());
        if (formats.isEmpty() && !music) {
            formats = standardTiers();
        }
        return new AnalyzeResult(url, playlist, title, uploader, duration, thumb, music, count, formats, items,
                dim[0] > 0 ? dim[0] : null, dim[1] > 0 ? dim[1] : null,
                thumbnailSrcset(root, ratio));
    }

    /** Formats for one video URL — used when probing playlist items one by one. */
    public List<VideoFormatOption> probeFormats(String url) throws IOException {
        AnalyzeResult a = analyze(url);
        return a.videoFormats() == null ? List.of() : a.videoFormats();
    }

    /**
     * Read every item in a playlist and decide whether they all offer the same
     * resolutions. A "download all as one zip" only makes sense when they do — otherwise
     * a single quality setting silently means different things for different items.
     *
     * Items are probed in parallel because each one costs a full yt-dlp extraction, and
     * the verdict is cached per playlist URL since it cannot change while a user is
     * looking at the page.
     */
    public PlaylistFormats playlistFormats(String url) throws IOException {
        PlaylistFormats cached = playlistFormatCache.get(url);
        if (cached != null) {
            return cached;
        }
        AnalyzeResult a = analyze(url);
        List<PlaylistItem> items = a.items();
        if (!a.playlist() || items.isEmpty()) {
            throw new IOException("That link is not a playlist");
        }
        if (items.size() > MAX_UNIFORMITY_PROBE) {
            return cache(url, new PlaylistFormats(false, List.of(), 0, items.size(),
                    "This playlist has " + items.size() + " items — too many to verify, so pick videos individually."));
        }
        // A music playlist has no video streams to compare; zipping is always fine.
        if (a.music()) {
            return cache(url, new PlaylistFormats(true, List.of(), items.size(), items.size(), null));
        }

        List<List<VideoFormatOption>> results;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(6, items.size()));
        try {
            List<Future<List<VideoFormatOption>>> futures = new ArrayList<>();
            for (PlaylistItem it : items) {
                futures.add(pool.submit(() -> it.url() == null ? null : probeFormats(it.url())));
            }
            results = new ArrayList<>();
            for (Future<List<VideoFormatOption>> f : futures) {
                try {
                    results.add(f.get());
                } catch (ExecutionException e) {
                    results.add(null); // this item could not be read
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while reading the playlist");
                }
            }
        } finally {
            pool.shutdownNow();
        }

        int failed = (int) results.stream().filter(r -> r == null).count();
        if (failed > 0) {
            return cache(url, new PlaylistFormats(false, List.of(), items.size() - failed, items.size(),
                    failed + " of " + items.size() + " videos could not be read, so they can't be zipped as one set."));
        }

        // Uniform means every item exposes exactly the same heights — not merely overlapping.
        Set<Integer> first = heights(results.get(0));
        boolean uniform = results.stream().allMatch(r -> heights(r).equals(first));
        if (!uniform) {
            return cache(url, new PlaylistFormats(false, List.of(), items.size(), items.size(),
                    "These videos don't all offer the same resolutions, so pick them individually."));
        }
        // Same heights everywhere — reuse item 1's labels (fps notes and all).
        return cache(url, new PlaylistFormats(true, results.get(0), items.size(), items.size(), null));
    }

    private PlaylistFormats cache(String url, PlaylistFormats pf) {
        playlistFormatCache.put(url, pf);
        return pf;
    }

    private static Set<Integer> heights(List<VideoFormatOption> formats) {
        return formats.stream().map(VideoFormatOption::height).collect(Collectors.toCollection(TreeSet::new));
    }

    private List<VideoFormatOption> parseFormats(JsonNode root) {
        JsonNode formats = root.path("formats");
        TreeMap<Integer, Long> sizeByHeight = new TreeMap<>(Comparator.reverseOrder());
        TreeMap<Integer, String> noteByHeight = new TreeMap<>();
        TreeMap<Integer, Integer> widthByHeight = new TreeMap<>();
        if (formats.isArray()) {
            for (JsonNode f : formats) {
                if ("none".equals(f.path("vcodec").asText("none"))) {
                    continue; // audio-only stream
                }
                int h = f.path("height").asInt(0);
                if (h <= 0) {
                    continue;
                }
                long size = f.has("filesize") && f.get("filesize").isNumber()
                        ? f.get("filesize").asLong()
                        : (f.has("filesize_approx") && f.get("filesize_approx").isNumber()
                                ? f.get("filesize_approx").asLong() : 0);
                sizeByHeight.merge(h, size, Math::max);
                widthByHeight.merge(h, f.path("width").asInt(0), Math::max);
                int fps = f.path("fps").asInt(0);
                if (fps >= 50) {
                    noteByHeight.put(h, fps + "fps");
                }
            }
        }
        List<VideoFormatOption> out = new ArrayList<>();
        Set<String> claimed = new HashSet<>();
        // Reverse-ordered, so the tallest stream claims a quality class first. Without
        // this, YouTube's near-identical odd-aspect renditions (872x480 and 854x470 are
        // both "480p") would show as two buttons that fetch effectively the same file.
        for (var e : sizeByHeight.entrySet()) {
            int h = e.getKey();
            String label = label(widthByHeight.getOrDefault(h, 0), h);
            if (!claimed.add(label)) {
                continue;
            }
            Long size = e.getValue() > 0 ? e.getValue() : null;
            out.add(new VideoFormatOption(h, label, noteByHeight.get(h), size));
        }
        return out;
    }

    /** Playlist entries, so the UI can list items instead of one opaque checkbox. */
    private List<PlaylistItem> parseEntries(JsonNode root) {
        JsonNode entries = root.path("entries");
        if (!entries.isArray()) {
            return List.of();
        }
        List<PlaylistItem> out = new ArrayList<>();
        int idx = 1;
        for (JsonNode e : entries) {
            String id = text(e, "id");
            String title = firstText(e, "title", "fulltitle");
            Long dur = (e.has("duration") && e.get("duration").isNumber()) ? e.get("duration").asLong() : null;
            String thumb = pickThumbnail(e);
            if (thumb == null && id != null) {
                thumb = "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg";
            }
            String entryUrl = firstText(e, "url", "webpage_url");
            if ((entryUrl == null || !entryUrl.startsWith("http")) && id != null) {
                entryUrl = "https://www.youtube.com/watch?v=" + id;
            }
            String srcset = thumbnailSrcset(e, 16.0 / 9.0);
            if (srcset == null && id != null) {
                srcset = youtubeSrcset(id);
            }
            out.add(new PlaylistItem(idx, (title == null || title.isBlank()) ? "Item " + idx : title,
                    dur, thumb, entryUrl, srcset));
            idx++;
        }
        return out;
    }

    private static boolean isMusicUrl(String url) {
        String u = url.toLowerCase();
        return u.contains("music.youtube.") || u.contains("soundcloud.com")
                || u.contains("bandcamp.com") || u.contains("music.apple.com")
                || u.contains("spotify.com");
    }

    private List<VideoFormatOption> standardTiers() {
        int[] hs = {2160, 1440, 1080, 720, 480, 360, 240, 144};
        List<VideoFormatOption> out = new ArrayList<>();
        for (int h : hs) {
            // No width to go on — these are the standard 16:9 tiers, where the class
            // is the height, so the height fallback in qualityClass() is exactly right.
            out.add(new VideoFormatOption(h, label(0, h), null, null));
        }
        return out;
    }

    // ----------------------------------------------------------------- DOWNLOAD

    /** Run the actual download, updating {@code job} and calling {@code onUpdate} on progress. */
    public void download(Job job, Consumer<Job> onUpdate) throws IOException, InterruptedException {
        DownloadRequest req = job.getRequest();

        // A clip can't be cut from a stream YouTube is still processing — the section
        // download fetches almost nothing and the merge dies with an opaque ffmpeg error.
        // Catch it before downloading anything and tell the user why (see clippingUnavailable).
        if (req.hasClipRange()) {
            job.setStatus(JobStatus.DOWNLOADING);
            job.setPhase("Checking clip…");
            onUpdate.accept(job);
            if (clippingUnavailable(liveStatus(req.url()))) {
                throw new IOException(CLIP_LIVE_MESSAGE);
            }
        }

        Path jobDir = workDir().resolve(job.getId());
        Files.createDirectories(jobDir);

        if (job.isCanceled()) {
            finishCanceled(job, jobDir, onUpdate);
            return;
        }

        // Advanced always re-encodes; Auto finds out from its first progress tick (handleLine).
        job.setConvert(!req.isAudio() && codecs.isReencode(req.codecOrDefault()));
        job.setConvertProgress(0);

        List<String> cmd = buildCommand(req, jobDir);
        log.info("Job {} running: {}", job.getId(), String.join(" ", cmd));

        job.setStatus(JobStatus.DOWNLOADING);
        job.setPhase("Starting…");
        job.setStartedAt(System.currentTimeMillis());
        onUpdate.accept(job);

        int[] lastEmitted = {-1};
        // Keep yt-dlp's last ERROR line so a failure surfaces the actual reason
        // ("unable to open for writing", "Video unavailable", …) instead of only an
        // opaque exit code, both to the user and in the server log.
        String[] lastError = {null};
        int exit;
        try {
            exit = Processes.stream(cmd, jobDir,
                    proc -> processes.put(job.getId(), proc),
                    line -> {
                        if (line.contains("ERROR")) {
                            lastError[0] = line;
                            log.warn("Job {} yt-dlp: {}", job.getId(), line);
                        }
                        handleLine(line, job, lastEmitted, onUpdate);
                    });
        } finally {
            processes.remove(job.getId());
        }

        if (job.isCanceled()) {
            finishCanceled(job, jobDir, onUpdate);
            return;
        }
        if (exit != 0) {
            throw new IOException(lastError[0] != null
                    ? lastError[0].replaceFirst(".*ERROR:\\s*", "").trim()
                    : "yt-dlp exited with code " + exit + " (see server log)");
        }

        List<Path> produced = new ArrayList<>(listMedia(jobDir));
        if (produced.isEmpty()) {
            throw new IOException("Download finished but no media file was produced");
        }

        // Advanced tab: re-encode to the codec the user picked. Auto (wantsUniversal): only
        // when YouTube had no H.264 at that resolution, so the file still opens everywhere.
        boolean advanced = !req.isAudio() && codecs.isReencode(req.codecOrDefault());
        if (advanced || req.wantsUniversal()) {
            for (int i = 0; i < produced.size(); i++) {
                if (job.isCanceled()) {
                    finishCanceled(job, jobDir, onUpdate);
                    return;
                }
                produced.set(i, advanced
                        ? compress(produced.get(i), req, job, i + 1, produced.size(), onUpdate)
                        : makeUniversal(produced.get(i), job, i + 1, produced.size(), onUpdate));
            }
            if (job.isCanceled()) {
                finishCanceled(job, jobDir, onUpdate);
                return;
            }
        }

        Path deliver;
        if (!req.playlist() || produced.size() == 1) {
            deliver = produced.stream().max(Comparator.comparingLong(YtDlpService::size)).orElseThrow();
        } else {
            completeSourceTransfers(job);
            job.setCurrentStep("FINALIZING");
            job.setStatus(JobStatus.PACKAGING);
            job.setPhase("Packaging " + produced.size() + " files into a zip…");
            onUpdate.accept(job);
            String base = safe(job.getTitle() != null ? job.getTitle() : "playlist");
            deliver = jobDir.resolve(base + ".zip");
            zip(produced, deliver);
        }

        job.setFilePath(deliver);
        job.setFileName(deliver.getFileName().toString());
        if (job.getTitle() == null) {
            job.setTitle(stripExt(deliver.getFileName().toString()));
        }
        // What the finished card shows: real type + resolution, size, and how long it took.
        job.setContainer(ext(deliver));
        job.setFileSize(size(deliver));
        if (!req.isAudio()) {
            int[] wh = probeDimensions(deliver);
            if (wh != null) {
                job.setHeight(wh[1]);
                job.setQualityLabel(label(wh[0], wh[1]));
            }
        }
        long now = System.currentTimeMillis();
        job.setFinishedAt(now);
        job.setElapsedMs(job.getStartedAt() == null ? null : now - job.getStartedAt());
        job.setSpeedBps(null);
        job.setEta(null);
        job.setDownloadedBytes(null);
        job.setTotalBytes(null);
        job.setPrimaryProgress(100);
        job.setSecondaryProgress(100);
        job.setFinalizingProgress(100);
        if (job.isConvert()) {
            job.setConvertProgress(100);
        }
        job.setCurrentStep("FINALIZING");
        job.setProgress(100);
        job.setStatus(JobStatus.COMPLETED);
        job.setPhase("Ready to download");
        onUpdate.accept(job);
    }

    private List<String> buildCommand(DownloadRequest req, Path jobDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add(bin);
        cmd.add(req.url());
        cmd.add("--newline");
        cmd.add("--no-warnings");
        cmd.add("--ignore-config");
        cmd.add("--progress-template");
        cmd.add(PROGRESS_TEMPLATE);
        if (ffmpegIsPath()) {
            cmd.add("--ffmpeg-location");
            cmd.add(ffmpegBin);
        }
        cmd.add("--concurrent-fragments");
        cmd.add("8");
        // Matrix testing showed YouTube intermittently dropping a fragment when several
        // jobs run at once — the same request succeeded on a retry. Without these, that
        // surfaces to the user as a flat "download failed" for no visible reason.
        // Generous retry budget so a wifi switch or a brief drop on the server side is
        // absorbed inside one run — yt-dlp keeps its partial data and continues, rather
        // than failing the job and losing everything. --socket-timeout makes a dead
        // connection give up in 30s instead of hanging until the OS notices.
        cmd.add("--retries");
        cmd.add("15");
        cmd.add("--fragment-retries");
        cmd.add("40");
        cmd.add("--extractor-retries");
        cmd.add("3");
        cmd.add("--retry-sleep");
        cmd.add("3");
        cmd.add("--socket-timeout");
        cmd.add("30");
        cmd.add("--continue");
        cmd.add(req.playlist() ? "--yes-playlist" : "--no-playlist");
        cmd.add("--embed-metadata");
        if (THUMBNAIL_OK.contains(req.targetExtension())) {
            cmd.add("--embed-thumbnail"); // skipped for wav/webm, which can't hold one
        }
        if (req.hasItemSelection()) {
            cmd.add("--playlist-items");
            cmd.add(req.items().stream().map(String::valueOf).collect(Collectors.joining(",")));
        }
        if (req.hasClipRange()) {
            // Trim to a section, cutting at keyframes so the clip starts cleanly.
            cmd.add("--download-sections");
            cmd.add("*" + clipStart(req.startTime()) + "-" + clipEnd(req.endTime()));
            cmd.add("--force-keyframes-at-cuts");
        }

        String template = req.playlist()
                ? jobDir.resolve("%(playlist_index)03d - %(title)s.%(ext)s").toString()
                : jobDir.resolve("%(title)s.%(ext)s").toString();
        cmd.add("-o");
        cmd.add(template);

        if (req.isAudio()) {
            cmd.add("-x");
            cmd.add("--audio-format");
            cmd.add(req.audioFormatOrDefault());
            cmd.add("--audio-quality");
            cmd.add("0");
        } else {
            int h = req.heightOrDefault();
            if (req.wantsUniversal()) {
                // Highest resolution up to h; among equals prefer H.264, and AAC audio.
                // YouTube's default pick is AV1/VP9 + Opus, which Premiere and QuickTime
                // refuse inside an MP4. A sort (not a filter) so a 4K request is never
                // quietly downgraded to the 1080p that happens to be H.264 — whatever
                // can't be had in H.264 is converted afterwards by makeUniversal().
                cmd.add("-S");
                cmd.add("res,vcodec:h264,acodec:aac");
                cmd.add("-f");
                cmd.add("bv[height<=" + h + "]+ba/b[height<=" + h + "]/b");
            } else {
                if ("mp4".equals(req.containerOrDefault()) && codecs.isReencode(req.codecOrDefault())) {
                    // The video is about to be re-encoded, but the audio is carried over as it
                    // came. Left alone that is Opus, and an Opus track inside an MP4 is what
                    // Premiere opens silent. Prefer YouTube's own AAC stream so there is nothing
                    // to convert; the sort only ranks audio, so the video pick is unchanged.
                    cmd.add("-S");
                    cmd.add("acodec:aac");
                }
                cmd.add("-f");
                cmd.add(formatSelector(h, req.containerOrDefault()));
            }
            cmd.add("--merge-output-format");
            cmd.add(req.containerOrDefault());
        }
        return cmd;
    }

    /**
     * Which streams to fetch for a target container.
     *
     * WEBM is the awkward one: it can legally hold only VP8/VP9/AV1 video with Opus or
     * Vorbis audio. yt-dlp's plain "bestvideo+bestaudio" happily picks AVC1 + M4A, and
     * the merge into .webm then dies with "Postprocessing: Conversion failed!" — which
     * is exactly what every WEBM download used to do. Restricting the picks to webm
     * streams is what makes that container work at all.
     *
     * MP4 and MKV accept everything YouTube serves, so they keep the unrestricted
     * selection and therefore the best available quality.
     */
    private static String formatSelector(int height, String container) {
        if ("webm".equals(container)) {
            return "bestvideo[height<=" + height + "][ext=webm]+bestaudio[ext=webm]"
                    + "/best[height<=" + height + "][ext=webm]";
        }
        return "bestvideo[height<=" + height + "]+bestaudio/best[height<=" + height + "]/best";
    }

    private void handleLine(String line, Job job, int[] lastEmitted, Consumer<Job> onUpdate) {
        if (job.isCanceled() || job.getStatus() == JobStatus.PAUSED) {
            return; // ignore output while paused or after cancel
        }
        Matcher pt = PL_TITLE.matcher(line);
        if (pt.find()) {
            job.setTitle(pt.group(1).trim());
        }
        Matcher it = ITEM.matcher(line);
        if (it.find()) {
            job.setPlaylistIndex(Integer.parseInt(it.group(1)));
            job.setPlaylistCount(Integer.parseInt(it.group(2)));
            job.setPrimaryProgress(0);
            job.setSecondaryProgress(0);
            job.setFinalizingProgress(0);
            job.setCurrentStep("PRIMARY");
        }

        if (line.contains("[Merger]") || line.contains("Merging formats")) {
            completeSourceTransfers(job);
            job.setCurrentStep("FINALIZING");
            advanceFinalizing(job, 10);
            setPhase(job, JobStatus.PROCESSING, "Merging video + audio…", onUpdate);
            return;
        }
        if (line.contains("[ExtractAudio]")) {
            job.setPrimaryProgress(100);
            job.setCurrentStep("SECONDARY");
            setPhase(job, JobStatus.PROCESSING, "Extracting audio…", onUpdate);
            return;
        }
        if (line.contains("[EmbedThumbnail]")) {
            completeSourceTransfers(job);
            job.setCurrentStep("FINALIZING");
            advanceFinalizing(job, 70);
            setPhase(job, JobStatus.PROCESSING, "Embedding thumbnail…", onUpdate);
            return;
        }
        if (line.contains("[Metadata]") || line.contains("EmbedMetadata")) {
            completeSourceTransfers(job);
            job.setCurrentStep("FINALIZING");
            advanceFinalizing(job, 85);
            setPhase(job, JobStatus.PROCESSING, "Writing metadata…", onUpdate);
            return;
        }

        if (!line.startsWith(EZ)) {
            return;
        }
        Progress p = Progress.parse(line);
        if (p == null) {
            return; // malformed tick — never let it abort the read loop
        }
        job.setStatus(JobStatus.DOWNLOADING);

        // Byte counters are written on EVERY tick, deliberately OUTSIDE the
        // whole-percent guard below. The browser polls the job every 800ms rather
        // than listening on a stream, so it reads whatever is on the object at that
        // moment — throttling these to 1% steps would make a 4.5GB download's counter
        // lurch in 45MB jumps for no gain.
        job.setDownloadedBytes(p.downloaded);
        job.setTotalBytes(p.total);
        // Hold the last known rate through the 2-5 "NA" ticks that open every stream
        // and follow every resume, instead of blinking the readout out. On the final
        // tick yt-dlp redefines speed as total/elapsed — a whole-transfer average, not
        // the current rate — so that one is dropped rather than shown as a sudden halving.
        if (p.speed != null && !p.finished) {
            job.setSpeedBps(p.speed);
        }
        job.setEta(p.eta);
        updateTransferProgress(job, p);
        // Auto converts only what YouTube cannot serve as H.264 (everything above 1080p).
        // The video stream's very first tick names its codec, so the card can show the
        // conversion stage from the start rather than having it appear — and the overall
        // ring dip — halfway through.
        if (!job.isConvert() && job.getRequest().wantsUniversal() && needsH264Conversion(p.vcodec())) {
            job.setConvert(true);
        }

        int emit;
        String phase;
        if (job.getPlaylistCount() != null && job.getPlaylistCount() > 0) {
            int idx = job.getPlaylistIndex() == null ? 1 : job.getPlaylistIndex();
            emit = (int) Math.floor(((idx - 1) + p.percent / 100.0) / job.getPlaylistCount() * 100);
            phase = "Item " + idx + "/" + job.getPlaylistCount();
        } else {
            emit = (int) Math.floor(p.percent);
            phase = "Downloading…";
        }
        if (emit != lastEmitted[0]) {
            lastEmitted[0] = emit;
            job.setProgress(emit);
            job.setPhase(phase);
            onUpdate.accept(job);
        }
    }

    /**
     * One parsed progress tick. Package-private so the parser can be tested without
     * running a download — it is the only place a malformed line could throw inside
     * the output reader and kill a live job.
     */
    record Progress(long downloaded, Long total, Double speed, String eta,
                    double percent, boolean finished, String vcodec) {

        static Progress parse(String line) {
            String[] f = line.substring(EZ.length()).split("\\|", -1);
            if (f.length < 6) {
                return null;
            }
            Long downloaded = num(f[1]) == null ? null : num(f[1]).longValue();
            if (downloaded == null) {
                return null; // nothing useful without it
            }
            Double total = num(f[2]);
            Double speed = num(f[4]);
            Double etaSecs = num(f[5]);
            double percent = pct(f[3]);
            String vcodec = f.length > 6 ? f[6].trim() : "";
            return new Progress(downloaded,
                    total == null ? null : total.longValue(),
                    speed,
                    etaSecs == null ? null : clock(etaSecs.longValue()),
                    percent,
                    "finished".equals(f[0]),
                    vcodec.isEmpty() || "NA".equals(vcodec) || "none".equals(vcodec) ? null : vcodec);
        }

        /** yt-dlp writes the literal "NA" for anything it doesn't know yet. Speed and
         *  the byte estimate arrive as floats, so everything is read as one. */
        private static Double num(String s) {
            try {
                return Double.valueOf(s.trim());
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** "_percent_str" arrives padded, e.g. "  0.1%". */
        private static double pct(String s) {
            Double d = num(s.replace("%", ""));
            return d == null ? 0 : d;
        }

        private static String clock(long secs) {
            return secs >= 3600
                    ? String.format("%d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60)
                    : String.format("%02d:%02d", secs / 60, secs % 60);
        }
    }

    /** yt-dlp names H.264 "avc1.…". Null is the audio stream or an unknown, never a reason to convert. */
    static boolean needsH264Conversion(String vcodec) {
        return vcodec != null && !vcodec.startsWith("avc1");
    }

    /** Shown when a clip is asked for on a video YouTube hasn't finished processing. */
    static final String CLIP_LIVE_MESSAGE =
            "This is a live stream YouTube is still processing, so a trimmed clip can't be made yet. "
            + "Download the full video, or try the clip again in a few hours once YouTube has turned "
            + "it into a normal video.";

    /**
     * The live_status values a clip cannot be cut from. A trim uses --download-sections,
     * which seeks inside the video; YouTube exposes no seekable data while a stream is
     * still live ("is_live") or has only just ended and is still being processed into a
     * VOD ("post_live"). The section download then fetches almost nothing and the merge
     * fails with "Error opening output files: Invalid argument" / "ffmpeg exited with
     * code 183". The full download of the same video is unaffected. "not_live" (which a
     * finished former-live video reports once processed) and an unknown/absent status are
     * normal videos that clip fine.
     */
    static boolean clippingUnavailable(String liveStatus) {
        if (liveStatus == null) {
            return false;
        }
        String s = liveStatus.trim().toLowerCase();
        return s.equals("is_live") || s.equals("post_live");
    }

    /** yt-dlp's live_status for one video, or null when it can't be read. */
    private String liveStatus(String url) {
        try {
            Processes.Result r = Processes.run(
                    List.of(bin, "--no-warnings", "--ignore-config", "--no-playlist",
                            "--skip-download", "--print", "%(live_status)s", url),
                    Duration.ofSeconds(60));
            if (r.code() != 0) {
                return null; // couldn't tell — don't block the download over it
            }
            String s = r.stdout().strip();
            int nl = s.indexOf('\n');
            if (nl >= 0) {
                s = s.substring(0, nl).strip();
            }
            return s.isEmpty() || "NA".equals(s) ? null : s;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void setPhase(Job job, JobStatus status, String phase, Consumer<Job> onUpdate) {
        job.setStatus(status);
        job.setPhase(phase);
        onUpdate.accept(job);
    }

    /**
     * Keep the two source transfers distinct. A finished yt-dlp progress record marks
     * the end of one stream, so the next record belongs to the audio stream. This is
     * also safe for a single-stream fallback: the processing line that follows fills
     * in the remaining source step before the UI presents the final stage.
     */
    private void updateTransferProgress(Job job, Progress p) {
        int pct = (int) Math.floor(p.percent);
        if (job.getRequest().isAudio()) {
            job.setPrimaryProgress(pct);
            if (p.finished) {
                job.setPrimaryProgress(100);
                job.setCurrentStep("SECONDARY");
            }
            return;
        }

        if ("SECONDARY".equals(job.getCurrentStep())) {
            job.setSecondaryProgress(pct);
            if (p.finished) {
                job.setSecondaryProgress(100);
                job.setCurrentStep("FINALIZING");
            }
        } else {
            job.setPrimaryProgress(pct);
            if (p.finished) {
                job.setPrimaryProgress(100);
                job.setCurrentStep("SECONDARY");
            }
        }
    }

    private void completeSourceTransfers(Job job) {
        job.setPrimaryProgress(100);
        job.setSecondaryProgress(100);
    }

    /** Post-processors do not report byte progress, but each emitted phase is a real
     *  completed milestone. The browser fills the short gaps between them smoothly. */
    private void advanceFinalizing(Job job, int progress) {
        job.setFinalizingProgress(Math.max(job.getFinalizingProgress(), progress));
    }

    // ------------------------------------------------------------ COMPRESSION

    /**
     * Re-encode one finished file to the codec picked in the Advanced tab, in place.
     *
     * Only the first video stream is re-encoded — {@code -c copy} plus a stream-specific
     * {@code -c:v:0} override means audio, subtitles and the embedded cover art are
     * carried over untouched, so we never lose the thumbnail we just embedded and never
     * try to run cover art through a video encoder.
     */
    private Path compress(Path src, DownloadRequest req, Job job, int idx, int total, Consumer<Job> onUpdate)
            throws IOException, InterruptedException {
        String codec = req.codecOrDefault();
        String ext = ext(src);
        Path out = src.resolveSibling(stripExt(src.getFileName().toString()) + ".enc." + ext);
        double durationSec = probeDurationSeconds(src);
        long before = size(src);
        long srcKbps = videoBitrateKbps(src, durationSec, before);

        List<String> cmd = new ArrayList<>(List.of(
                ffmpegBin, "-y", "-nostdin", "-loglevel", "error",
                "-progress", "pipe:1", "-nostats",
                "-i", src.toString(),
                "-map", "0", "-c", "copy"));
        // The catalog gives "-c:v <encoder> …"; retarget it at stream v:0 only.
        List<String> enc = codecs.encodeArgs(codec, srcKbps);
        for (int i = 0; i < enc.size(); i++) {
            cmd.add("-c:v".equals(enc.get(i)) ? "-c:v:0" : enc.get(i));
        }
        if ("hevc".equals(codec) && "mp4".equals(ext)) {
            cmd.add("-tag:v:0"); // QuickTime and Premiere refuse HEVC in MP4 without it
            cmd.add("hvc1");
        }
        if ("mp4".equals(ext)) {
            // Normally a no-op, because the download already chose an AAC stream. It only
            // fires for a video YouTube has no AAC for, so those files still carry sound.
            if (needsAac(probeCodec(src, "a:0"))) {
                cmd.addAll(List.of("-c:a", "aac", "-b:a", "192k"));
            }
            cmd.add("-movflags");
            cmd.add("+faststart");
        }
        cmd.add(out.toString());

        String what = codecs.labelOf(codec);
        String prefix = total > 1 ? "Compressing " + idx + "/" + total + " to " + what : "Compressing to " + what;
        boolean ok = runEncode(cmd, out, durationSec, prefix, idx, total, job, onUpdate);
        if (job.isCanceled()) {
            return src;
        }
        if (!ok) {
            throw new IOException("Compression to " + what + " failed (see server log)");
        }

        Files.move(out, src, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        long after = size(src);
        log.info("Job {} compressed {} → {} ({} → {} bytes)", job.getId(), ext, codec, before, after);
        return src;
    }

    /**
     * Run one ffmpeg encode into {@code out}, reporting live progress on the job card.
     * Returns true when it produced a usable file; on failure or cancel {@code out} is
     * removed and false comes back, so the caller decides whether that is an error.
     */
    private boolean runEncode(List<String> cmd, Path out, double durationSec, String prefix,
                              int idx, int total, Job job, Consumer<Job> onUpdate)
            throws IOException, InterruptedException {
        job.setStatus(JobStatus.COMPRESSING);
        job.setPhase(prefix + "…");
        job.setSpeedBps(null);
        job.setEta(null);
        job.setDownloadedBytes(null);
        job.setTotalBytes(null);
        job.setConvert(true); // covers a conversion the first progress tick could not predict
        completeSourceTransfers(job);
        job.setFinalizingProgress(100); // the merge, metadata and thumbnail were yt-dlp's, and are done
        job.setCurrentStep("CONVERTING");
        onUpdate.accept(job);
        log.info("Job {} encoding: {}", job.getId(), String.join(" ", cmd));

        int[] last = {-1};
        int exit;
        try {
            exit = Processes.stream(cmd, out.getParent(),
                    proc -> processes.put(job.getId(), proc),
                    line -> {
                        // -progress output is all key=value; anything else is ffmpeg talking.
                        // Without this a failed encode only ever said "exited 234".
                        if (!line.matches("[a-z_0-9]+=.*")) {
                            log.warn("Job {} ffmpeg: {}", job.getId(), line);
                        }
                        if (job.isCanceled() || durationSec <= 0) {
                            return;
                        }
                        // ffmpeg -progress emits "out_time_us=1234567" lines.
                        if (line.startsWith("out_time_us=")) {
                            String v = line.substring("out_time_us=".length()).trim();
                            long us;
                            try {
                                us = Long.parseLong(v);
                            } catch (NumberFormatException e) {
                                return; // "N/A" before the first frame lands
                            }
                            int pct = (int) Math.min(100, Math.floor(us / 1_000_000.0 / durationSec * 100));
                            if (pct != last[0]) {
                                last[0] = pct;
                                // Across a playlist's files the bar keeps climbing instead of
                                // restarting at 0% for each one.
                                int overall = (int) Math.floor(((idx - 1) + pct / 100.0) / total * 100);
                                job.setProgress(overall);
                                job.setConvertProgress(overall);
                                job.setPhase(prefix + "… " + pct + "%");
                                onUpdate.accept(job);
                            }
                        }
                    });
        } finally {
            processes.remove(job.getId());
        }

        boolean ok = !job.isCanceled() && exit == 0 && Files.exists(out) && size(out) > 0;
        if (ok) {
            job.setConvertProgress((int) Math.floor(idx * 100.0 / total));
        }
        if (!ok) {
            Files.deleteIfExists(out);
            if (!job.isCanceled()) {
                log.warn("Job {} ffmpeg exited {}", job.getId(), exit);
            }
        }
        return ok;
    }

    /**
     * Audio that is present but is not AAC. AAC is the one audio codec every MP4 reader
     * accepts; YouTube's default audio is Opus, which Premiere does not read from an MP4 —
     * the usual reason such a file opens with the picture but no sound.
     */
    static boolean needsAac(String audio) {
        return audio != null && !audio.isEmpty() && !"aac".equals(audio);
    }

    /** What the Auto tab promises: opens in QuickTime, Premiere, Final Cut, DaVinci. */
    static boolean isUniversal(String video, String audio) {
        return "h264".equals(video) && !needsAac(audio);
    }

    /**
     * Make an Auto-mode MP4 playable everywhere, in place — and do nothing at all when it
     * already is, which is every download up to 1080p.
     *
     * YouTube serves H.264 only up to 1080p; above that it is VP9 or AV1, which Premiere
     * cannot decode and QuickTime will not play. Audio is already AAC (it is selected
     * that way), but is converted too if a video ever arrives without it. Everything
     * that is already fine is stream-copied, so the only cost is the video encode.
     */
    private Path makeUniversal(Path src, Job job, int idx, int total, Consumer<Job> onUpdate)
            throws IOException, InterruptedException {
        String video = probeCodec(src, "v:0");
        String audio = probeCodec(src, "a:0");
        if (isUniversal(video, audio)) {
            return src;
        }
        boolean needVideo = !"h264".equals(video);
        boolean needAudio = needsAac(audio);

        Path out = src.resolveSibling(stripExt(src.getFileName().toString()) + ".enc.mp4");
        double durationSec = probeDurationSeconds(src);
        long srcKbps = videoBitrateKbps(src, durationSec, size(src));
        String prefix = (total > 1 ? "Converting " + idx + "/" + total : "Converting")
                + " to H.264 for Premiere & QuickTime";

        // Hardware encoder first; if it refuses (very large frames, busy GPU) fall back to
        // libx264 rather than failing a download that already finished.
        List<List<String>> encoders = needVideo ? codecs.universalH264(srcKbps) : List.of(List.<String>of());
        if (encoders.isEmpty()) {
            throw new IOException("This server's ffmpeg has no H.264 encoder, so the video can't be"
                    + " converted for editing software");
        }
        for (List<String> enc : encoders) {
            List<String> cmd = new ArrayList<>(List.of(
                    ffmpegBin, "-y", "-nostdin", "-loglevel", "error",
                    "-progress", "pipe:1", "-nostats",
                    "-i", src.toString(),
                    "-map", "0", "-c", "copy"));
            for (int i = 0; i < enc.size(); i++) {
                cmd.add("-c:v".equals(enc.get(i)) ? "-c:v:0" : enc.get(i)); // main video only, not cover art
            }
            if (needVideo) {
                cmd.addAll(List.of("-pix_fmt:v:0", "yuv420p")); // 10-bit AV1/VP9 → what H.264 hardware takes
            }
            if (needAudio) {
                cmd.addAll(List.of("-c:a", "aac", "-b:a", "192k"));
            }
            cmd.addAll(List.of("-movflags", "+faststart", out.toString()));

            if (runEncode(cmd, out, durationSec, prefix, idx, total, job, onUpdate)) {
                Files.move(out, src, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("Job {} made universal: {}/{} → h264/aac", job.getId(), video, audio);
                return src;
            }
            if (job.isCanceled()) {
                return src;
            }
        }
        throw new IOException("Couldn't convert this video to H.264 for editing software (see server log)");
    }

    /** codec_name of one stream ("h264", "vp9", "av1", "aac", "opus"), or null when absent. */
    private String probeCodec(Path file, String stream) {
        try {
            Processes.Result r = Processes.run(List.of(ffprobeBin(), "-v", "error",
                    "-select_streams", stream, "-show_entries", "stream=codec_name",
                    "-of", "csv=p=0", file.toString()), Duration.ofSeconds(20));
            String s = r.stdout().trim();
            int nl = s.indexOf('\n');
            return s.isEmpty() ? null : (nl > 0 ? s.substring(0, nl) : s).trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Video bitrate of the source in kbit/s. Containers don't always store a per-stream
     * bitrate (WEBM and MKV frequently report nothing), so fall back to deriving it from
     * file size over duration — close enough to aim a target at.
     */
    private long videoBitrateKbps(Path file, double durationSec, long bytes) {
        try {
            Processes.Result r = Processes.run(List.of(ffprobeBin(), "-v", "error",
                    "-select_streams", "v:0", "-show_entries", "stream=bit_rate",
                    "-of", "csv=p=0", file.toString()), Duration.ofSeconds(20));
            String s = r.stdout().trim();
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(0, nl).trim();
            }
            if (!s.isEmpty() && !"N/A".equals(s)) {
                long bps = Long.parseLong(s);
                if (bps > 0) {
                    return bps / 1000;
                }
            }
        } catch (Exception ignored) {
            // fall through to the size-based estimate
        }
        if (durationSec > 0 && bytes > 0) {
            // Whole-file rate minus a rough allowance for the audio track.
            long total = Math.round(bytes * 8.0 / durationSec / 1000.0);
            return Math.max(120, total - 160);
        }
        return 0;
    }

    /** Length in seconds, used to turn ffmpeg's progress into a percentage. */
    private double probeDurationSeconds(Path file) {
        try {
            Processes.Result r = Processes.run(List.of(ffprobeBin(), "-v", "error",
                    "-show_entries", "format=duration", "-of", "csv=p=0", file.toString()),
                    Duration.ofSeconds(20));
            String s = r.stdout().trim();
            return s.isEmpty() ? 0 : Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    /** True when ffmpeg.bin is a real path rather than a bare name left to PATH.
     *  Path.of handles both "/opt/homebrew/bin/ffmpeg" and "C:\\ffmpeg\\bin\\ffmpeg.exe";
     *  the old contains("/") test saw a Windows path as a bare name. */
    private boolean ffmpegIsPath() {
        return ffmpegBin != null && Path.of(ffmpegBin).getParent() != null;
    }

    private String ffprobeBin() {
        String name = Processes.WINDOWS ? "ffprobe.exe" : "ffprobe";
        return ffmpegIsPath() ? Path.of(ffmpegBin).resolveSibling(name).toString() : name;
    }

    // ------------------------------------------------------------- CONTROLS

    /** Suspend the download (SIGSTOP). Only meaningful while downloading. */
    public boolean pause(String jobId) {
        return signal(jobId, "-STOP");
    }

    /** Resume a suspended download (SIGCONT). */
    public boolean resume(String jobId) {
        return signal(jobId, "-CONT");
    }

    /** Force-kill the job's yt-dlp process (and any ffmpeg children). */
    public boolean cancel(String jobId) {
        Process p = processes.get(jobId);
        if (p == null) {
            return false;
        }
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        return true;
    }

    private boolean signal(String jobId, String sig) {
        Process p = processes.get(jobId);
        if (p == null || !p.isAlive()) {
            return false;
        }
        boolean ok = kill(sig, p.pid());
        p.descendants().forEach(h -> kill(sig, h.pid()));
        return ok;
    }

    private boolean kill(String sig, long pid) {
        try {
            Process k = new ProcessBuilder("/bin/kill", sig, Long.toString(pid)).start();
            k.waitFor();
            return k.exitValue() == 0;
        } catch (IOException e) {
            log.warn("kill {} {} failed: {}", sig, pid, e.toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void finishCanceled(Job job, Path jobDir, Consumer<Job> onUpdate) {
        deleteDirQuietly(jobDir);
        job.setProgress(0);
        job.setSpeedBps(null);
        job.setEta(null);
        job.setDownloadedBytes(null);
        job.setTotalBytes(null);
        job.setStatus(JobStatus.CANCELED);
        job.setPhase("Canceled");
        job.setFinishedAt(System.currentTimeMillis());
        onUpdate.accept(job);
    }

    private static void deleteDirQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    // -------------------------------------------------------------- FILE OUTPUT

    private List<Path> listMedia(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    // macOS writes AppleDouble side-cars ("._name.mp4") on network volumes.
                    // They carry a media extension, so without this they end up inside the
                    // zip as junk files that Windows users see alongside the real videos.
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> MEDIA_EXT.contains(ext(p)))
                    .sorted()
                    .toList();
        }
    }

    private void zip(List<Path> files, Path target) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Path f : files) {
                zos.putNextEntry(new ZipEntry(f.getFileName().toString()));
                Files.copy(f, zos);
                zos.closeEntry();
            }
        }
    }

    // ------------------------------------------------------------------ HELPERS

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Real dimensions of the finished video, so the card can name the quality actually
     * delivered. Width matters as much as height here: a 2:1 4K file is 3840x1920, and
     * height alone understated it as "1920p".
     */
    private int[] probeDimensions(Path file) {
        try {
            Processes.Result r = Processes.run(List.of(ffprobeBin(), "-v", "error",
                    "-select_streams", "v:0", "-show_entries", "stream=width,height",
                    "-of", "csv=p=0", file.toString()), Duration.ofSeconds(20));
            String s = r.stdout().trim();
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(0, nl).trim();
            }
            String[] wh = s.split(",");
            if (wh.length < 2) {
                return null;
            }
            return new int[] {Integer.parseInt(wh[0].trim()), Integer.parseInt(wh[1].trim())};
        } catch (Exception e) {
            return null;
        }
    }

    private static String clipStart(String s) {
        return (s == null || s.isBlank()) ? "0" : s.trim();
    }

    private static String clipEnd(String s) {
        return (s == null || s.isBlank()) ? "inf" : s.trim();
    }

    private static String ext(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase();
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(0, i);
    }

    private static String safe(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /** The broadcast rungs a quality class snaps to, tallest first. */
    private static final int[] LADDER = {4320, 2160, 1440, 1080, 720, 480, 360, 240, 144};

    /**
     * The quality class YouTube itself would name this stream. It stops matching the pixel
     * height the moment a video is not 16:9: a 2:1 video's 4K rendition is 3840x1920, so
     * naming it by height called it "1920p", which reads as 1080p-class — the 4K option
     * looked like it was missing entirely.
     *
     * Wider than 16:9 → the WIDTH carries the class (3840 wide is 2160p, which is exactly
     * what `yt-dlp -F` reports in its own notes). 16:9 or narrower — 4:3, square, vertical
     * Shorts — → the short side, which is how a 1080x1920 Short is correctly "1080p".
     */
    static int qualityClass(int w, int h) {
        if (w <= 0) {
            return h;
        }
        int c = (w * 9 > h * 16) ? Math.round(w * 9f / 16f) : Math.min(w, h);
        // YouTube's odd-aspect renditions land just off a rung — 872x480 computes 491.
        // Snap within 5% so it reads "480p" rather than "491p".
        for (int rung : LADDER) {
            if (Math.abs(c - rung) * 20 <= rung) {
                return rung;
            }
        }
        return c;
    }

    private static String label(int w, int h) {
        int c = qualityClass(w, h);
        return switch (c) {
            case 4320 -> "4320p · UHD-8K";
            case 2160 -> "2160p · UHD";
            case 1440 -> "1440p · QHD";
            case 1080 -> "1080p · FHD";
            case 720 -> "720p · HD";
            default -> c + "p";
        };
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static String firstText(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = text(n, f);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String pickThumbnail(JsonNode root) {
        return pickThumbnail(root, 16.0 / 9.0);
    }

    /**
     * Prefer the largest thumbnail whose shape matches the video. YouTube ships several
     * per video, and for vertical/Shorts the default is a 16:9 image with the real frame
     * padded inside — picking by aspect keeps portrait videos looking portrait.
     */
    private static String pickThumbnail(JsonNode root, double targetRatio) {
        JsonNode th = root.path("thumbnails");
        String best = null;
        double bestDiff = Double.MAX_VALUE;
        long bestArea = 0;
        if (th.isArray()) {
            for (JsonNode t : th) {
                String url = text(t, "url");
                int w = t.path("width").asInt(0);
                int h = t.path("height").asInt(0);
                if (url == null || w <= 0 || h <= 0) {
                    continue;
                }
                double diff = Math.abs((double) w / h - targetRatio);
                long area = (long) w * h;
                if (diff < bestDiff - 0.05 || (Math.abs(diff - bestDiff) <= 0.05 && area > bestArea)) {
                    bestDiff = diff;
                    bestArea = area;
                    best = url;
                }
            }
        }
        if (best != null) {
            return best;
        }
        String t = text(root, "thumbnail");
        if (t != null) {
            return t;
        }
        return (th.isArray() && !th.isEmpty()) ? text(th.get(th.size() - 1), "url") : null;
    }

    /**
     * Every thumbnail that matches the video's shape, as an HTML srcset string.
     *
     * Without this the browser was handed one large image and scaled it down in the
     * layout — a phone downloaded a 1280px file to draw it 350px wide, paying the
     * bandwidth and decode cost for detail it then threw away. Handing over the real
     * candidates lets it pick the one that fits the slot.
     */
    private static String thumbnailSrcset(JsonNode root, double targetRatio) {
        JsonNode th = root.path("thumbnails");
        if (!th.isArray()) {
            return null;
        }
        TreeMap<Integer, String> byWidth = new TreeMap<>();
        for (JsonNode t : th) {
            String url = text(t, "url");
            int w = t.path("width").asInt(0);
            int h = t.path("height").asInt(0);
            // A comma inside a URL would be read as a srcset separator and break the list.
            if (url == null || w <= 0 || h <= 0 || url.indexOf(',') >= 0) {
                continue;
            }
            if (Math.abs((double) w / h - targetRatio) > 0.25) {
                continue; // different crop — mixing shapes would make the slot jump
            }
            byWidth.putIfAbsent(w, url);
        }
        if (byWidth.size() < 2) {
            return null; // nothing to choose between
        }
        return byWidth.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey() + "w")
                .collect(Collectors.joining(", "));
    }

    /**
     * Playlist entries come back flat, usually without a thumbnail list. YouTube always
     * serves these fixed sizes per video id, so the set can be named directly.
     */
    private static String youtubeSrcset(String id) {
        String base = "https://i.ytimg.com/vi/" + id + "/";
        return base + "default.jpg 120w, " + base + "mqdefault.jpg 320w, " + base + "hqdefault.jpg 480w";
    }

    /** Native video dimensions, taken from the highest-resolution video stream. */
    private static int[] videoDimensions(JsonNode root) {
        JsonNode formats = root.path("formats");
        int bw = 0;
        int bh = 0;
        if (formats.isArray()) {
            for (JsonNode f : formats) {
                if ("none".equals(f.path("vcodec").asText("none"))) {
                    continue;
                }
                int h = f.path("height").asInt(0);
                int w = f.path("width").asInt(0);
                if (h > bh && w > 0) {
                    bh = h;
                    bw = w;
                }
            }
        }
        if (bh == 0) {
            bh = root.path("height").asInt(0);
            bw = root.path("width").asInt(0);
        }
        return new int[] { bw, bh };
    }

    private static String firstError(String stderr) {
        if (stderr != null) {
            for (String line : stderr.split("\n")) {
                if (line.contains("ERROR")) {
                    return line.replaceFirst(".*ERROR:\\s*", "").trim();
                }
            }
        }
        return (stderr == null || stderr.isBlank()) ? "URL not supported or unavailable" : stderr.strip();
    }
}
