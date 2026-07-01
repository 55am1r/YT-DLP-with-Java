package com.predatorfx.ytdlpweb.service;

import tools.jackson.databind.ObjectMapper;
import com.predatorfx.ytdlpweb.util.Processes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * The "freshness guard" the user asked for: before any download runs, make sure
 * yt-dlp is current. Outdated yt-dlp is the #1 cause of downloads silently failing.
 *
 * IMPORTANT: yt-dlp here is installed via Homebrew, so we update with
 * `brew upgrade yt-dlp`. We must NOT call `yt-dlp -U` — on brew installs it hangs.
 *
 * The result is cached for {@code ytdlp.update-check-interval-minutes} so we don't
 * hit the network on every single request.
 */
@Service
public class YtDlpUpdateService {

    private static final Logger log = LoggerFactory.getLogger(YtDlpUpdateService.class);

    @Value("${ytdlp.bin:yt-dlp}")
    private String bin;

    @Value("${ytdlp.brew-formula:yt-dlp}")
    private String brewFormula;

    @Value("${brew.bin:brew}")
    private String brewBin;

    @Value("${ytdlp.update-check-interval-minutes:180}")
    private long intervalMinutes;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private volatile String installed;
    private volatile String latest;
    private volatile long lastCheck = 0L;
    private volatile String lastMessage = "Not checked yet";

    public record UpdateStatus(String installed, String latest, boolean upToDate, boolean updated, String message) {}

    /**
     * Ensure yt-dlp is fresh. Cheap when called within the cache window; otherwise
     * checks GitHub for the latest release and runs `brew upgrade` if behind.
     */
    public synchronized UpdateStatus ensureFresh(boolean force) {
        long now = System.currentTimeMillis();
        boolean stale = force || (now - lastCheck) > Duration.ofMinutes(intervalMinutes).toMillis();
        if (!stale && installed != null) {
            return new UpdateStatus(installed, latest, isUpToDate(), false, "cached · " + lastMessage);
        }

        installed = readInstalled();
        latest = fetchLatest(); // null if offline / rate-limited
        boolean updated = false;
        String msg;

        if (latest == null) {
            msg = "Couldn't reach GitHub to check latest — using installed " + installed;
        } else if (compare(installed, latest) < 0) {
            log.info("yt-dlp {} is behind latest {} — upgrading via Homebrew", installed, latest);
            String before = installed;
            boolean ok = brewUpgrade();
            installed = readInstalled();
            updated = ok && !installed.equals(before);
            msg = updated
                    ? "Updated yt-dlp " + before + " → " + installed
                    : "Tried to upgrade; now " + installed + " (Homebrew formula may lag " + latest + ")";
        } else {
            msg = "yt-dlp " + installed + " is up to date";
        }

        lastMessage = msg;
        lastCheck = System.currentTimeMillis();
        log.info(msg);
        return new UpdateStatus(installed, latest, isUpToDate(), updated, msg);
    }

    /** Last known status without triggering any check. */
    public UpdateStatus current() {
        return new UpdateStatus(installed, latest, isUpToDate(), false, lastMessage);
    }

    private boolean isUpToDate() {
        return installed != null && latest != null && compare(installed, latest) >= 0;
    }

    private String readInstalled() {
        try {
            Processes.Result r = Processes.run(List.of(bin, "--version"), Duration.ofSeconds(20));
            String v = r.stdout().trim();
            return v.isEmpty() ? "unknown" : v;
        } catch (Exception e) {
            log.warn("Failed to read yt-dlp version: {}", e.toString());
            return installed == null ? "unknown" : installed;
        }
    }

    private String fetchLatest() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "ytdlp-web")
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            String tag = mapper.readTree(resp.body()).path("tag_name").asText(null);
            return tag == null ? null : tag.replaceFirst("^v", "");
        } catch (Exception e) {
            log.warn("Latest-version check failed: {}", e.toString());
            return null;
        }
    }

    private boolean brewUpgrade() {
        try {
            // `brew upgrade` auto-runs `brew update` first if its formulae are stale.
            Processes.Result r = Processes.run(List.of(brewBin, "upgrade", brewFormula), Duration.ofMinutes(6));
            if (r.code() != 0) {
                log.warn("brew upgrade exited {}: {}", r.code(), r.stderr().strip());
            }
            return r.code() == 0;
        } catch (Exception e) {
            log.warn("brew upgrade failed: {}", e.toString());
            return false;
        }
    }

    /** Compare date-style versions like 2026.03.17 numerically. Negative if a &lt; b. */
    static int compare(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? digits(pa[i]) : 0;
            int y = i < pb.length ? digits(pb[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int digits(String s) {
        StringBuilder d = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                d.append(c);
            }
        }
        return d.isEmpty() ? 0 : Integer.parseInt(d.toString());
    }
}
