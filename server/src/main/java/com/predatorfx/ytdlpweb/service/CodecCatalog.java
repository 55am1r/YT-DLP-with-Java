package com.predatorfx.ytdlpweb.service;

import com.predatorfx.ytdlpweb.model.CodecOption;
import com.predatorfx.ytdlpweb.util.Processes;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The compression choices the "Advanced" tab offers, and the ffmpeg arguments behind
 * each one.
 *
 * The list is filtered against what this machine's ffmpeg actually reports at startup,
 * so we never offer an encoder that would fail mid-job. On Apple Silicon the
 * VideoToolbox encoders are preferred: the media engine does the work, which is roughly
 * an order of magnitude faster than libx265 and leaves the CPU free for other jobs.
 */
@Service
public class CodecCatalog {

    private static final Logger log = LoggerFactory.getLogger(CodecCatalog.class);

    /** "none" is not an encoder — it means keep the original streams untouched. */
    public static final String NONE = "none";

    /**
     * @param targetRatio fraction of the SOURCE video bitrate to aim for. YouTube already
     *                    ships AV1/VP9 encoded very efficiently, so a quality-based
     *                    setting (CRF / -q:v) reliably produced files LARGER than the
     *                    original — measured 441 MB out of a 101 MB source for H.264.
     *                    Targeting a share of the source bitrate is what makes the
     *                    advertised saving actually happen.
     */
    private record Spec(CodecOption option, String encoder, List<String> args, double targetRatio) {}

    @Value("${ffmpeg.bin:ffmpeg}")
    private String ffmpegBin;

    private final Map<String, Spec> specs = new LinkedHashMap<>();

    @PostConstruct
    void init() {
        String encoders = probeEncoders();

        // Always available — this is the current behaviour: merge, never re-encode.
        add(new Spec(new CodecOption(NONE, "Original",
                "Keeps the source streams exactly as YouTube sent them",
                "Best quality", List.of("mp4", "mkv", "webm"), "full size", false),
                null, List.of(), 1.0));

        // Every figure below was MEASURED on real YouTube downloads at 360p and 720p,
        // not taken from codec marketing. YouTube already ships efficient AV1/VP9, so
        // savings are far smaller than the usual "H.265 halves your files" claim, and
        // they shrink further at low resolutions where the audio track dominates the
        // file. Hence ranges rather than a single flattering number.

        // The practical pick: the Mac's media engine does the work, so a 4K clip
        // finishes in minutes rather than hours, and it still sheds a third of the size.
        addIf(encoders, "hevc_videotoolbox", new Spec(new CodecOption("hevc", "H.265 / HEVC",
                "A third smaller and quick — the Mac's media engine does the encoding",
                "Recommended", List.of("mp4", "mkv"), "65–90%", true),
                "hevc_videotoolbox", List.of(), 0.50));

        addIf(encoders, "h264_videotoolbox", new Spec(new CodecOption("h264", "H.264 / AVC",
                "Plays on anything — older phones, TVs, editing suites",
                null, List.of("mp4", "mkv"), "80–90%", true),
                "h264_videotoolbox", List.of(), 0.70));

        // Genuinely the smallest of the lot, but software-encoded: expect a long wait on
        // anything long or high-resolution.
        addIf(encoders, "libsvtav1", new Spec(new CodecOption("av1", "AV1",
                "Smallest files of all, but slow — it encodes on the CPU, not the media engine",
                "Best savings", List.of("mp4", "mkv", "webm"), "40–55%", false),
                "libsvtav1", List.of("-preset", "6"), 0.40));

        // libvpx overshoots a plain bitrate target badly, so it runs in constrained-
        // quality mode where -b:v is the ceiling. Even then it measured 100–107% of the
        // source: against YouTube's own VP9 it simply does not save space, and the label
        // says so rather than implying a saving that never arrives.
        addIf(encoders, "libvpx-vp9", new Spec(new CodecOption("vp9", "VP9",
                "For players that need VP9 — does not shrink YouTube sources, and it's slow",
                null, List.of("mkv", "webm"), "~100%", false),
                "libvpx-vp9", List.of("-row-mt", "1", "-crf", "33"), 0.55));

        log.info("Compression options available: {}", specs.keySet());
    }

    private void add(Spec s) {
        specs.put(s.option().id(), s);
    }

    private void addIf(String encoders, String encoder, Spec s) {
        if (encoders.contains(encoder)) {
            add(s);
        } else {
            log.warn("ffmpeg has no {} encoder — hiding the {} option", encoder, s.option().id());
        }
    }

    private String probeEncoders() {
        try {
            Processes.Result r = Processes.run(List.of(ffmpegBin, "-hide_banner", "-encoders"),
                    Duration.ofSeconds(20));
            return r.stdout() + r.stderr();
        } catch (Exception e) {
            log.warn("Could not list ffmpeg encoders ({}) — offering no-re-encode only", e.toString());
            return "";
        }
    }

    public List<CodecOption> options() {
        return specs.values().stream().map(Spec::option).toList();
    }

    /** True when this codec id exists and is legal inside the given container. */
    public boolean supports(String codecId, String container) {
        Spec s = specs.get(normalize(codecId));
        return s != null && s.option().containers().contains(container.toLowerCase());
    }

    public boolean isReencode(String codecId) {
        String id = normalize(codecId);
        return !NONE.equals(id) && specs.containsKey(id);
    }

    public String labelOf(String codecId) {
        Spec s = specs.get(normalize(codecId));
        return s == null ? "" : s.option().label();
    }

    /**
     * The "-c:v &lt;encoder&gt; …" fragment for this codec, empty when nothing to do.
     *
     * @param sourceKbps measured video bitrate of the file being re-encoded; when it is
     *                   unknown (0) we fall back to a quality target, which may not
     *                   shrink the file but will never wreck it
     */
    public List<String> encodeArgs(String codecId, long sourceKbps) {
        Spec s = specs.get(normalize(codecId));
        if (s == null || s.encoder() == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(List.of("-c:v", s.encoder()));
        out.addAll(s.args());
        if (sourceKbps > 0) {
            long target = Math.max(120, Math.round(sourceKbps * s.targetRatio()));
            out.addAll(List.of("-b:v", target + "k"));
            // Only the VideoToolbox encoders take a rate cap here. SVT-AV1 refuses to
            // open at all ("Max Bitrate only supported with CRF mode"), and libvpx runs
            // in constrained-quality mode where -b:v is already the ceiling.
            if (s.encoder().contains("videotoolbox")) {
                out.addAll(List.of(
                        "-maxrate", Math.round(target * 1.5) + "k",
                        "-bufsize", (target * 3) + "k"));
            }
        } else if (s.encoder().contains("videotoolbox")) {
            out.addAll(List.of("-q:v", "60"));
        } else if (!out.contains("-crf")) {
            out.addAll(List.of("-crf", "34"));
        }
        return out;
    }

    /** Fraction of the source bitrate this codec aims for — used to explain the outcome. */
    public double targetRatio(String codecId) {
        Spec s = specs.get(normalize(codecId));
        return s == null ? 1.0 : s.targetRatio();
    }

    public static String normalize(String codecId) {
        return (codecId == null || codecId.isBlank()) ? NONE : codecId.trim().toLowerCase();
    }
}
