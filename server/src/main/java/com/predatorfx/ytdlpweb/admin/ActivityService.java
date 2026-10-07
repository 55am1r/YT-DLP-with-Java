package com.predatorfx.ytdlpweb.admin;

import com.predatorfx.ytdlpweb.model.DownloadRequest;
import com.predatorfx.ytdlpweb.model.Job;
import com.predatorfx.ytdlpweb.model.JobStatus;
import com.predatorfx.ytdlpweb.service.JobFinishedEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The admin panel's memory: which devices use EZ-Tube, from where, and what they download.
 *
 * Fed from the request path (ActivityFilter, the auth and download controllers) and from
 * JobService's finish events; read by the admin API. Everything lives in memory behind one
 * lock and is persisted through {@link ActivityStore}: devices as a snapshot every 30 s when
 * changed, downloads and events as appended lines. Download records are replaced, never
 * edited, so {@link #downloads()} can hand out a cheap snapshot.
 */
@Service
public class ActivityService {

    private static final Logger log = LoggerFactory.getLogger(ActivityService.class);

    /** A gap this long between requests counts as a new visit. */
    static final Duration NEW_VISIT_AFTER = Duration.ofMinutes(30);
    static final int MAX_IPS_PER_DEVICE = 20;
    static final int MAX_EVENTS = 20_000;

    /** What the browser reports about itself when the app opens. */
    public record Hello(String timezone, String language, String screen, Boolean touch, Boolean secure,
                        String permission) {
    }

    /** The answer to the location card: "granted" with a position, or declined/denied/unavailable. */
    public record LocationReport(Double lat, Double lon, Double accuracy, String outcome) {
    }

    private final ActivityStore store;
    private final GeoIpService geo;
    private final int retentionDays;
    private final Clock clock;

    private final Object lock = new Object();
    private final Map<String, DeviceRecord> devices = new HashMap<>();
    private final Map<String, DownloadRecord> downloads = new LinkedHashMap<>(); // by job id, oldest first
    private final Deque<ActivityEvent> events = new ArrayDeque<>();
    private final Set<String> blockedIps = new LinkedHashSet<>();
    private Announcement announcement;
    private boolean devicesDirty;

    @Autowired
    public ActivityService(GeoIpService geo,
                           @Value("${app.admin.data-dir:${user.home}/.ytdlp-web/admin-data}") String dataDir,
                           @Value("${app.admin.retention-days:365}") int retentionDays) {
        this(new ActivityStore(Path.of(dataDir)), geo, retentionDays, Clock.systemUTC());
    }

    ActivityService(ActivityStore store, GeoIpService geo, int retentionDays, Clock clock) {
        this.store = store;
        this.geo = geo;
        this.retentionDays = Math.max(1, retentionDays);
        this.clock = clock;
    }

    @PostConstruct
    void init() {
        ActivityStore.Loaded loaded = store.load();
        long now = clock.millis();
        synchronized (lock) {
            loaded.devices().forEach(d -> devices.put(d.getId(), d));
            for (DownloadRecord r : loaded.downloads()) {
                if (!isFinal(r.getStatus())) {
                    // A restart wipes every job in flight; record how it really ended.
                    r = r.copy();
                    r.setStatus(JobStatus.FAILED.name());
                    r.setError("Interrupted — the server restarted");
                    r.setFinishedAt(now);
                }
                downloads.put(r.getJobId(), r);
            }
            events.addAll(loaded.events());
            blockedIps.addAll(loaded.settings().blockedIps());
            announcement = loaded.settings().announcement();
        }
        compact(); // also writes the interrupted records back
        devices().forEach(d -> geo.request(d.getLastIp()));
        log.info("Admin records ready in {} · {} devices · {} downloads", store.dir(), devices.size(), downloads.size());
    }

    @PreDestroy
    void shutdown() {
        flush();
    }

    // ------------------------------------------------------------------ recording

    /** A signed-in request from this device: keeps last-seen, visits and the IP trail current. */
    public void touch(String deviceId, ClientInfo client, String userAgent, boolean admin) {
        if (deviceId == null || client == null) {
            return;
        }
        long now = clock.millis();
        boolean newIp;
        synchronized (lock) {
            DeviceRecord d = devices.get(deviceId);
            if (d == null) {
                d = new DeviceRecord();
                d.setId(deviceId);
                d.setFirstSeen(now);
                d.setVisits(1);
                devices.put(deviceId, d);
            } else if (now - d.getLastSeen() > NEW_VISIT_AFTER.toMillis()) {
                d.setVisits(d.getVisits() + 1);
            }
            d.setLastSeen(now);
            if (admin) {
                d.setAdmin(true);
            }
            String ua = clip(userAgent, 512);
            if (ua != null && !ua.equals(d.getUserAgent())) {
                d.setUserAgent(ua);
                describe(d);
            }
            newIp = !client.ip().equals(d.getLastIp());
            d.setLastIp(client.ip());
            d.setVia(client.via());
            trackIp(d, client.ip(), now);
            devicesDirty = true;
        }
        if (newIp) {
            geo.request(client.ip());
        }
    }

    public void hello(String deviceId, Hello hello) {
        if (deviceId == null || hello == null) {
            return;
        }
        synchronized (lock) {
            DeviceRecord d = devices.get(deviceId);
            if (d == null) {
                return;
            }
            d.setTimezone(clip(hello.timezone(), 64));
            d.setLanguage(clip(hello.language(), 35));
            d.setScreen(clip(hello.screen(), 20));
            d.setTouch(Boolean.TRUE.equals(hello.touch()));
            describe(d); // a touch screen turns a "Mac" into an iPad
            String permission = hello.permission();
            boolean cannotAsk = Boolean.FALSE.equals(hello.secure()) || "unsupported".equals(permission);
            if ("denied".equals(permission)) {
                withdrawLocation(d, DeviceRecord.CONSENT_DENIED);
            } else if (cannotAsk && DeviceRecord.CONSENT_UNKNOWN.equals(d.getConsent())) {
                d.setConsent(DeviceRecord.CONSENT_UNAVAILABLE);
            } else if (!cannotAsk && DeviceRecord.CONSENT_UNAVAILABLE.equals(d.getConsent())) {
                d.setConsent(DeviceRecord.CONSENT_UNKNOWN); // reachable over HTTPS now: it can be asked
            }
            devicesDirty = true;
        }
    }

    /** @throws IllegalArgumentException for an unknown outcome or an impossible position */
    public void location(String deviceId, ClientInfo client, LocationReport report) {
        String outcome = report == null ? null : report.outcome();
        if (DeviceRecord.CONSENT_GRANTED.equals(outcome)) {
            validatePosition(report);
        } else if (!DeviceRecord.CONSENT_DECLINED.equals(outcome) && !DeviceRecord.CONSENT_DENIED.equals(outcome)
                && !DeviceRecord.CONSENT_UNAVAILABLE.equals(outcome)) {
            throw new IllegalArgumentException("Unknown location outcome");
        }
        String before;
        String after;
        synchronized (lock) {
            DeviceRecord d = deviceId == null ? null : devices.get(deviceId);
            if (d == null) {
                return;
            }
            before = d.getConsent();
            switch (outcome) {
                case DeviceRecord.CONSENT_GRANTED -> {
                    d.setLat(report.lat());
                    d.setLon(report.lon());
                    d.setAccuracy(report.accuracy());
                    d.setLocatedAt(clock.millis());
                    d.setConsent(DeviceRecord.CONSENT_GRANTED);
                }
                case DeviceRecord.CONSENT_DECLINED, DeviceRecord.CONSENT_DENIED -> withdrawLocation(d, outcome);
                default -> {
                    if (!DeviceRecord.CONSENT_GRANTED.equals(d.getConsent())) {
                        d.setConsent(DeviceRecord.CONSENT_UNAVAILABLE);
                    }
                }
            }
            after = d.getConsent();
            devicesDirty = true;
        }
        if (!after.equals(before)) {
            recordEvent(ActivityEvent.LOCATION, deviceId, client == null ? null : client.ip(), after, null, null);
        }
    }

    public void recordEvent(String type, String deviceId, String ip, String detail, String url, String title) {
        ActivityEvent e = new ActivityEvent(clock.millis(), type, deviceId, ip, clip(detail, 300), clip(url, 2000),
                clip(title, 300));
        synchronized (lock) {
            events.addLast(e);
            while (events.size() > MAX_EVENTS) {
                events.removeFirst();
            }
        }
        store.appendEvent(e);
    }

    /** A job was just queued by this device. */
    public void recordDownload(Job job, String deviceId, ClientInfo client) {
        DownloadRequest req = job.getRequest();
        DownloadRecord r = new DownloadRecord();
        r.setJobId(job.getId());
        r.setAt(job.getCreatedAt());
        r.setDeviceId(deviceId);
        if (client != null) {
            r.setIp(client.ip());
            r.setVia(client.via());
        }
        if (req != null) {
            r.setUrl(clip(req.url(), 2000));
            r.setKind(req.isAudio() ? "audio" : "video");
            r.setFormat(req.targetExtension());
            r.setHeight(req.isAudio() ? null : req.heightOrDefault());
            r.setCodec(req.isAudio() ? null : req.codecOrDefault());
            r.setClip(req.hasClipRange() ? or(req.startTime(), "start") + "–" + or(req.endTime(), "end") : null);
            r.setPlaylist(req.playlist());
            r.setItemCount(req.hasItemSelection() ? req.items().size() : null);
        }
        r.setTitle(clip(job.getTitle(), 300));
        r.setStatus(job.getStatus().name());
        synchronized (lock) {
            downloads.put(r.getJobId(), r);
        }
        store.appendDownload(r);
    }

    @EventListener
    public void onJobFinished(JobFinishedEvent event) {
        try {
            Job job = event.job();
            String status = job.getStatus().name();
            DownloadRecord next;
            synchronized (lock) {
                DownloadRecord cur = downloads.get(job.getId());
                if (cur == null || (status.equals(cur.getStatus()) && job.getFinishedAt() != null
                        && job.getFinishedAt().equals(cur.getFinishedAt()))) {
                    return; // unknown job, or the same finish reported twice
                }
                next = cur.copy();
                if (cur.getFinishedAt() != null) {
                    next.setAttempts(cur.getAttempts() + 1); // it finished before: this was a retry
                }
                next.setStatus(status);
                next.setError(job.getStatus() == JobStatus.FAILED ? clip(job.getError(), 500) : null);
                if (job.getTitle() != null) {
                    next.setTitle(clip(job.getTitle(), 300));
                }
                next.setFileName(job.getFileName());
                next.setFileSize(job.getFileSize());
                next.setQualityLabel(job.getQualityLabel());
                next.setContainer(job.getContainer());
                next.setElapsedMs(job.getElapsedMs());
                next.setFinishedAt(job.getFinishedAt() != null ? job.getFinishedAt() : clock.millis());
                downloads.put(job.getId(), next);
            }
            store.appendDownload(next);
        } catch (RuntimeException e) { // bookkeeping must never disturb the download worker
            log.warn("Could not record the end of a job: {}", e.toString());
        }
    }

    /** A device fetched the finished file — the moment it really reached someone. */
    public void recordSave(String jobId, String deviceId) {
        if (jobId == null || deviceId == null) {
            return;
        }
        DownloadRecord next;
        synchronized (lock) {
            DownloadRecord cur = downloads.get(jobId);
            if (cur == null || cur.getSavedBy().contains(deviceId)) {
                return;
            }
            next = cur.copy();
            next.getSavedBy().add(deviceId);
            if (next.getSavedAt() == null) {
                next.setSavedAt(clock.millis());
            }
            downloads.put(jobId, next);
        }
        store.appendDownload(next);
    }

    // ------------------------------------------------------------------ admin changes

    public Optional<DeviceRecord> updateDevice(String id, String nickname, Boolean blocked) {
        DeviceRecord copy;
        synchronized (lock) {
            DeviceRecord d = id == null ? null : devices.get(id);
            if (d == null) {
                return Optional.empty();
            }
            if (nickname != null) {
                d.setNickname(nickname.isBlank() ? null : clip(nickname, 40));
            }
            if (blocked != null) {
                d.setBlocked(blocked);
            }
            devicesDirty = true;
            copy = d.copy();
        }
        flush(); // an admin's change shouldn't wait for the next timed save
        return Optional.of(copy);
    }

    /** @throws IllegalArgumentException if {@code ip} isn't an IP address */
    public void setIpBlocked(String ip, boolean blocked) {
        if (!ClientInfo.isIpLiteral(ip)) {
            throw new IllegalArgumentException("Not an IP address");
        }
        ActivityStore.Settings settings;
        synchronized (lock) {
            if (blocked) {
                blockedIps.add(ip);
            } else {
                blockedIps.remove(ip);
            }
            settings = settings();
        }
        store.saveSettings(settings);
    }

    public boolean isBlocked(String deviceId, String ip) {
        synchronized (lock) {
            if (ip != null && blockedIps.contains(ip)) {
                return true;
            }
            DeviceRecord d = deviceId == null ? null : devices.get(deviceId);
            return d != null && d.isBlocked();
        }
    }

    /** Blank text clears the banner. */
    public Announcement setAnnouncement(String text, String level) {
        Announcement a = text == null || text.isBlank() ? null
                : new Announcement(clip(text, 300), "warn".equals(level) ? "warn" : "info", clock.millis());
        ActivityStore.Settings settings;
        synchronized (lock) {
            announcement = a;
            settings = settings();
        }
        store.saveSettings(settings);
        return a;
    }

    // ------------------------------------------------------------------ reading

    public Announcement announcement() {
        synchronized (lock) {
            return announcement;
        }
    }

    public List<DeviceRecord> devices() {
        synchronized (lock) {
            return devices.values().stream().map(DeviceRecord::copy).toList();
        }
    }

    public Optional<DeviceRecord> device(String id) {
        synchronized (lock) {
            DeviceRecord d = id == null ? null : devices.get(id);
            return d == null ? Optional.empty() : Optional.of(d.copy());
        }
    }

    /** Oldest first. The records are never modified afterwards — don't modify them either. */
    public List<DownloadRecord> downloads() {
        synchronized (lock) {
            return new ArrayList<>(downloads.values());
        }
    }

    /** Oldest first. */
    public List<ActivityEvent> events() {
        synchronized (lock) {
            return new ArrayList<>(events);
        }
    }

    public Set<String> blockedIps() {
        synchronized (lock) {
            return Set.copyOf(blockedIps);
        }
    }

    // ------------------------------------------------------------------ persistence

    @Scheduled(fixedDelay = 30_000L)
    public void flush() {
        List<DeviceRecord> snapshot;
        synchronized (lock) {
            if (!devicesDirty) {
                return;
            }
            devicesDirty = false;
            snapshot = devices.values().stream().map(DeviceRecord::copy).toList();
        }
        store.saveDevices(snapshot);
    }

    /**
     * Forget what's older than the retention period and squeeze each job's history down to its
     * latest line. Runs at startup and nightly. Holds the lock while rewriting so no record
     * changed meanwhile can be lost between the snapshot and the new file.
     */
    @Scheduled(cron = "0 30 4 * * *")
    public void compact() {
        long cutoff = clock.millis() - Duration.ofDays(retentionDays).toMillis();
        synchronized (lock) {
            downloads.values().removeIf(r -> r.getAt() < cutoff);
            events.removeIf(e -> e.at() < cutoff);
            // Blocked devices are kept: forgetting one would quietly lift its block.
            if (devices.values().removeIf(d -> d.getLastSeen() < cutoff && !d.isBlocked())) {
                devicesDirty = true;
            }
            store.rewrite(new ArrayList<>(downloads.values()), new ArrayList<>(events));
        }
        flush();
    }

    // ------------------------------------------------------------------ helpers

    private ActivityStore.Settings settings() {
        return new ActivityStore.Settings(List.copyOf(blockedIps), announcement);
    }

    private static void describe(DeviceRecord d) {
        UserAgents.Parsed p = UserAgents.parse(d.getUserAgent(), d.isTouch());
        d.setOs(p.os());
        d.setBrowser(p.browser());
        d.setDeviceType(p.deviceType());
    }

    private static void trackIp(DeviceRecord d, String ip, long now) {
        for (IpSeen s : d.getIps()) {
            if (ip.equals(s.getIp())) {
                s.setLastSeen(now);
                s.setHits(s.getHits() + 1);
                return;
            }
        }
        IpSeen s = new IpSeen();
        s.setIp(ip);
        s.setFirstSeen(now);
        s.setLastSeen(now);
        s.setHits(1);
        d.getIps().add(s);
        if (d.getIps().size() > MAX_IPS_PER_DEVICE) {
            d.getIps().stream().min(Comparator.comparingLong(IpSeen::getLastSeen)).ifPresent(d.getIps()::remove);
        }
    }

    /** Consent taken back: the precise position goes with it. */
    private static void withdrawLocation(DeviceRecord d, String consent) {
        d.setConsent(consent);
        d.setLat(null);
        d.setLon(null);
        d.setAccuracy(null);
        d.setLocatedAt(null);
    }

    private static void validatePosition(LocationReport r) {
        boolean ok = r.lat() != null && r.lon() != null
                && Double.isFinite(r.lat()) && Double.isFinite(r.lon())
                && Math.abs(r.lat()) <= 90 && Math.abs(r.lon()) <= 180
                && (r.accuracy() == null || (Double.isFinite(r.accuracy()) && r.accuracy() >= 0));
        if (!ok) {
            throw new IllegalArgumentException("Not a valid position");
        }
    }

    private static boolean isFinal(String status) {
        return JobStatus.COMPLETED.name().equals(status) || JobStatus.FAILED.name().equals(status)
                || JobStatus.CANCELED.name().equals(status);
    }

    private static String or(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }

    static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= max ? t : t.substring(0, max);
    }
}
