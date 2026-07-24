package com.predatorfx.ytdlpweb.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.predatorfx.ytdlpweb.model.AnalyzeResult;
import com.predatorfx.ytdlpweb.model.DownloadRequest;
import com.predatorfx.ytdlpweb.model.Job;
import com.predatorfx.ytdlpweb.model.JobStatus;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final Pattern PCT = Pattern.compile("\\[download\\]\\s+(\\d{1,3}(?:\\.\\d+)?)%");
    private static final Pattern ITEM = Pattern.compile("Downloading item (\\d+) of (\\d+)");
    private static final Pattern PL_TITLE = Pattern.compile("Downloading playlist: (.+)");
    private static final Pattern SPEED = Pattern.compile("at\\s+([0-9.]+\\s*[KMGT]?i?B/s)");
    private static final Pattern ETA = Pattern.compile("ETA\\s+([0-9:]+)");

    /**
     * Containers yt-dlp can embed a cover image into. WAV and WEBM cannot — passing
     * --embed-thumbnail for those aborts the whole job with "Postprocessing: Supported
     * filetypes for thumbnail embedding are: …".
     */
    private static final Set<String> THUMBNAIL_OK = Set.of(
            "mp3", "mka", "mkv", "ogg", "opus", "flac", "m4a", "mp4", "m4v", "mov");

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

    /** Live yt-dlp processes by job id, so downloads can be paused/canceled. */
    private final Map<String, Process> processes = new ConcurrentHashMap<>();

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
        String thumb = pickThumbnail(root);
        boolean music = url.contains("music.youtube");

        Integer count = null;
        List<VideoFormatOption> formats;
        if (playlist) {
            if (root.has("playlist_count")) {
                count = root.get("playlist_count").asInt();
            } else if (root.has("entries")) {
                count = root.get("entries").size();
            }
            formats = standardTiers();
        } else {
            formats = parseFormats(root);
        }
        return new AnalyzeResult(url, playlist, title, uploader, duration, thumb, music, count, formats);
    }

    private List<VideoFormatOption> parseFormats(JsonNode root) {
        JsonNode formats = root.path("formats");
        TreeMap<Integer, Long> sizeByHeight = new TreeMap<>(Comparator.reverseOrder());
        TreeMap<Integer, String> noteByHeight = new TreeMap<>();
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
                int fps = f.path("fps").asInt(0);
                if (fps >= 50) {
                    noteByHeight.put(h, fps + "fps");
                }
            }
        }
        List<VideoFormatOption> out = new ArrayList<>();
        for (var e : sizeByHeight.entrySet()) {
            int h = e.getKey();
            Long size = e.getValue() > 0 ? e.getValue() : null;
            out.add(new VideoFormatOption(h, label(h), noteByHeight.get(h), size));
        }
        return out.isEmpty() ? standardTiers() : out;
    }

    private List<VideoFormatOption> standardTiers() {
        int[] hs = {2160, 1440, 1080, 720, 480, 360, 240, 144};
        List<VideoFormatOption> out = new ArrayList<>();
        for (int h : hs) {
            out.add(new VideoFormatOption(h, label(h), null, null));
        }
        return out;
    }

    // ----------------------------------------------------------------- DOWNLOAD

    /** Run the actual download, updating {@code job} and calling {@code onUpdate} on progress. */
    public void download(Job job, Consumer<Job> onUpdate) throws IOException, InterruptedException {
        DownloadRequest req = job.getRequest();
        Path jobDir = workDir().resolve(job.getId());
        Files.createDirectories(jobDir);

        if (job.isCanceled()) {
            finishCanceled(job, jobDir, onUpdate);
            return;
        }

        List<String> cmd = buildCommand(req, jobDir);
        log.info("Job {} running: {}", job.getId(), String.join(" ", cmd));

        job.setStatus(JobStatus.DOWNLOADING);
        job.setPhase("Starting…");
        job.setStartedAt(System.currentTimeMillis());
        onUpdate.accept(job);

        int[] lastEmitted = {-1};
        int exit;
        try {
            exit = Processes.stream(cmd, jobDir,
                    proc -> processes.put(job.getId(), proc),
                    line -> handleLine(line, job, lastEmitted, onUpdate));
        } finally {
            processes.remove(job.getId());
        }

        if (job.isCanceled()) {
            finishCanceled(job, jobDir, onUpdate);
            return;
        }
        if (exit != 0) {
            throw new IOException("yt-dlp exited with code " + exit + " (see server log)");
        }

        List<Path> produced = new ArrayList<>(listMedia(jobDir));
        if (produced.isEmpty()) {
            throw new IOException("Download finished but no media file was produced");
        }

        Path deliver;
        if (!req.playlist() || produced.size() == 1) {
            deliver = produced.stream().max(Comparator.comparingLong(YtDlpService::size)).orElseThrow();
        } else {
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
            job.setHeight(probeHeight(deliver));
        }
        long now = System.currentTimeMillis();
        job.setFinishedAt(now);
        job.setElapsedMs(job.getStartedAt() == null ? null : now - job.getStartedAt());
        job.setSpeed(null);
        job.setEta(null);
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
        if (ffmpegBin != null && ffmpegBin.contains("/")) {
            cmd.add("--ffmpeg-location");
            cmd.add(ffmpegBin);
        }
        cmd.add("--concurrent-fragments");
        cmd.add("8");
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
            cmd.add("-f");
            cmd.add("bestvideo[height<=" + h + "]+bestaudio/best[height<=" + h + "]/best");
            cmd.add("--merge-output-format");
            cmd.add(req.containerOrDefault());
        }
        return cmd;
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
        }

        if (line.contains("[Merger]") || line.contains("Merging formats")) {
            setPhase(job, JobStatus.PROCESSING, "Merging video + audio…", onUpdate);
            return;
        }
        if (line.contains("[ExtractAudio]")) {
            setPhase(job, JobStatus.PROCESSING, "Extracting audio…", onUpdate);
            return;
        }
        if (line.contains("[EmbedThumbnail]")) {
            setPhase(job, JobStatus.PROCESSING, "Embedding thumbnail…", onUpdate);
            return;
        }
        if (line.contains("[Metadata]") || line.contains("EmbedMetadata")) {
            setPhase(job, JobStatus.PROCESSING, "Writing metadata…", onUpdate);
            return;
        }

        Matcher mp = PCT.matcher(line);
        if (mp.find()) {
            double pct = Double.parseDouble(mp.group(1));
            job.setStatus(JobStatus.DOWNLOADING);
            Matcher sp = SPEED.matcher(line);
            if (sp.find()) {
                job.setSpeed(sp.group(1).replace(" ", ""));
            }
            Matcher et = ETA.matcher(line);
            if (et.find()) {
                job.setEta(et.group(1));
            }
            int emit;
            String phase;
            if (job.getPlaylistCount() != null && job.getPlaylistCount() > 0) {
                int idx = job.getPlaylistIndex() == null ? 1 : job.getPlaylistIndex();
                emit = (int) Math.floor(((idx - 1) + pct / 100.0) / job.getPlaylistCount() * 100);
                phase = "Item " + idx + "/" + job.getPlaylistCount() + " — " + (int) pct + "%";
            } else {
                emit = (int) Math.floor(pct);
                phase = "Downloading… " + emit + "%";
            }
            if (emit != lastEmitted[0]) {
                lastEmitted[0] = emit;
                job.setProgress(emit);
                job.setPhase(phase);
                onUpdate.accept(job);
            }
        }
    }

    private void setPhase(Job job, JobStatus status, String phase, Consumer<Job> onUpdate) {
        job.setStatus(status);
        job.setPhase(phase);
        onUpdate.accept(job);
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

    /** Real height of the finished video, so a card can say "1080p" instead of guessing. */
    private Integer probeHeight(Path file) {
        try {
            String ffprobe = (ffmpegBin != null && ffmpegBin.contains("/"))
                    ? Path.of(ffmpegBin).resolveSibling("ffprobe").toString()
                    : "ffprobe";
            Processes.Result r = Processes.run(List.of(ffprobe, "-v", "error",
                    "-select_streams", "v:0", "-show_entries", "stream=height",
                    "-of", "csv=p=0", file.toString()), Duration.ofSeconds(20));
            String s = r.stdout().trim();
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(0, nl).trim();
            }
            return s.isEmpty() ? null : Integer.valueOf(s);
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

    private static String label(int h) {
        String base = h + "p";
        if (h >= 4320) return base + " (8K)";
        if (h >= 2160) return base + " (4K)";
        if (h >= 1440) return base + " (2K)";
        if (h >= 1080) return base + " (Full HD)";
        if (h >= 720) return base + " (HD)";
        return base;
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
        String t = text(root, "thumbnail");
        if (t != null) {
            return t;
        }
        JsonNode th = root.path("thumbnails");
        if (th.isArray() && !th.isEmpty()) {
            return text(th.get(th.size() - 1), "url");
        }
        return null;
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
