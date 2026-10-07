package com.predatorfx.ytdlpweb.admin;

import com.predatorfx.ytdlpweb.model.Job;
import com.predatorfx.ytdlpweb.model.JobStatus;
import com.predatorfx.ytdlpweb.service.JobService;
import com.predatorfx.ytdlpweb.service.YtDlpService;
import com.predatorfx.ytdlpweb.service.YtDlpUpdateService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shapes the admin records into what the admin panel shows: the dashboard, insights, the
 * downloads log, one device's history. The counting is done in static functions over plain
 * lists so it can be tested without a server; the instance methods only gather the inputs.
 */
@Service
public class AdminReports {

    /** Seen this recently = online now. The open app checks the yt-dlp badge every 10 s. */
    static final Duration ONLINE = Duration.ofMinutes(2);
    /** A finished file younger than this may not have been saved by its owner yet. */
    static final Duration RECENT_FILE = Duration.ofMinutes(10);
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final Pattern YOUTUBE_ID = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?(?:[^#]*&)?v=|shorts/|live/|embed/)|youtu\\.be/)([A-Za-z0-9_-]{11})");
    private static final Pattern TUNNEL_URL = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");
    private static final Set<String> SECURITY_EVENTS = Set.of(ActivityEvent.LOGIN_FAILED, ActivityEvent.LOCKED_OUT,
            ActivityEvent.ADMIN_LOGIN, ActivityEvent.ADMIN_ACTION);

    // ------------------------------------------------------------------ what the API returns

    /** @param source "precise" (shared by the device), "ip" or "lan" (the Mac's own location) */
    public record Place(Double lat, Double lon, String label, String source) {}

    public record Precise(Double lat, Double lon, Double accuracy, Long at) {}

    public record DeviceView(String id, String name, String nickname, String label, String os, String browser,
                             String deviceType, boolean admin, boolean online, long firstSeen, long lastSeen, int visits,
                             String ip, String via, Place place, GeoInfo ipGeo, Precise precise, String consent,
                             boolean vpnHint, boolean blocked, int downloads, int downloadsToday, int active, long bytes,
                             String timezone, String language, String screen, List<IpSeen> ips) {}

    public record DownloadView(String jobId, long at, String deviceId, String deviceName, String ip, String via,
                               String place, String url, String videoId, String title, String kind, String format,
                               Integer height, String codec, String clip, boolean playlist, Integer itemCount,
                               String status, String error, String fileName, Long fileSize, String qualityLabel,
                               Long elapsedMs, Long finishedAt, int attempts, List<String> savedBy, Long savedAt) {}

    public record LiveJob(String id, String title, String status, int progress, String phase, Long speedBps,
                          String eta, Integer playlistIndex, Integer playlistCount, long createdAt, String kind,
                          String format, String deviceId, String deviceName) {}

    public record EventView(long at, String type, String deviceId, String deviceName, String ip, String place,
                            String detail, String url, String title) {}

    public record Kpis(int onlineNow, int usersToday, int users7d, int devicesTotal, int downloadsToday,
                       int downloads7d, int downloadsTotal, long bytesToday, long bytesTotal, Integer successRate7d,
                       int activeJobs, int queuedJobs, int countries, int failedLogins24h) {}

    public record SystemView(String ytdlpInstalled, String ytdlpLatest, boolean ytdlpUpToDate, Long diskFreeBytes,
                             Long diskTotalBytes, long uptimeMs, String publicUrl, String lanUrl, int runningJobs,
                             int recentFiles, boolean safeToRestart, String restartNote, String dataDir,
                             int retentionDays, long serverTime, String serverZone) {}

    public record Dashboard(Kpis kpis, List<DeviceView> devices, List<LiveJob> live, List<DownloadView> recent,
                            List<EventView> activity, List<EventView> security, Map<String, Long> lockedIps,
                            List<String> blockedIps, SystemView system, Announcement announcement, String you) {}

    public record DayCount(String day, int downloads, int devices, long bytes) {}

    public record TopItem(String key, String label, int count, int devices, String extra) {}

    public record Insights(int days, List<DayCount> perDay, int[] perHour, List<TopItem> topVideos,
                           Map<String, Integer> kinds, Map<String, Integer> qualities, Map<String, Integer> formats,
                           Map<String, Integer> statuses, List<TopItem> topPlaces, List<TopItem> topDevices) {}

    public record Page<T>(int total, List<T> items) {}

    public record DeviceDetail(DeviceView device, List<DownloadView> downloads, List<EventView> events) {}

    /** The downloads log's filters; blank means "any", days 0 means "all time". */
    public record DownloadFilter(String q, String device, String status, String kind, int days) {
        public boolean test(DownloadRecord r, long now) {
            if (days > 0 && r.getAt() < now - days * DAY) {
                return false;
            }
            if (present(device) && !device.equals(r.getDeviceId())) {
                return false;
            }
            if (present(kind) && !kind.equalsIgnoreCase(r.getKind())) {
                return false;
            }
            if (present(status)) {
                boolean match = "ACTIVE".equalsIgnoreCase(status) ? !isFinal(r.getStatus())
                        : status.equalsIgnoreCase(r.getStatus());
                if (!match) {
                    return false;
                }
            }
            if (present(q)) {
                String needle = q.strip().toLowerCase(Locale.ROOT);
                return contains(r.getTitle(), needle) || contains(r.getUrl(), needle) || contains(r.getFileName(), needle);
            }
            return true;
        }

        private static boolean contains(String haystack, String needle) {
            return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    // ------------------------------------------------------------------ wiring

    private final ActivityService activity;
    private final GeoIpService geo;
    private final JobService jobs;
    private final YtDlpUpdateService updates;
    private final YtDlpService ytdlp;
    private final LoginGuard guard;
    private final String dataDir;
    private final int retentionDays;
    private final String tunnelLog;
    private final int port;

    public AdminReports(ActivityService activity, GeoIpService geo, JobService jobs, YtDlpUpdateService updates,
                        YtDlpService ytdlp, LoginGuard guard,
                        @Value("${app.admin.data-dir:${user.home}/.ytdlp-web/admin-data}") String dataDir,
                        @Value("${app.admin.retention-days:365}") int retentionDays,
                        @Value("${app.admin.tunnel-log:/tmp/ytdlp-tunnel.log}") String tunnelLog,
                        @Value("${server.port:8080}") int port) {
        this.activity = activity;
        this.geo = geo;
        this.jobs = jobs;
        this.updates = updates;
        this.ytdlp = ytdlp;
        this.guard = guard;
        this.dataDir = dataDir;
        this.retentionDays = retentionDays;
        this.tunnelLog = tunnelLog;
        this.port = port;
    }

    public Dashboard dashboard(String you) {
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        List<DeviceRecord> devices = activity.devices();
        List<DownloadRecord> downloads = activity.downloads();
        List<ActivityEvent> events = activity.events();
        List<Job> all = jobs.all();
        Map<String, DeviceRecord> byId = byId(devices);
        Map<String, DownloadRecord> byJob = new HashMap<>();
        downloads.forEach(r -> byJob.put(r.getJobId(), r));
        Map<String, int[]> active = activeByDevice(all, byJob);

        List<DeviceView> views = devices.stream()
                .sorted(Comparator.comparingLong(DeviceRecord::getLastSeen).reversed())
                .map(d -> view(d, downloads, active, now, zone))
                .toList();
        List<LiveJob> live = all.stream()
                .filter(j -> !isFinal(j.getStatus().name()))
                .map(j -> live(j, byJob.get(j.getId()), byId))
                .toList();
        List<DownloadView> recent = newestFirst(downloads, 30).stream().map(r -> view(r, byId)).toList();
        List<EventView> feed = newestFirst(events, 60).stream().map(e -> view(e, byId)).toList();
        List<EventView> security = newestFirst(events.stream().filter(e -> SECURITY_EVENTS.contains(e.type())).toList(), 100)
                .stream().map(e -> view(e, byId)).toList();

        return new Dashboard(kpis(devices, downloads, events, all, geo::cached, now, zone), views, live, recent, feed,
                security, guard.lockedIps(), activity.blockedIps().stream().sorted().toList(), system(all, now),
                activity.announcement(), you);
    }

    public Insights insights(int days) {
        Map<String, String> names = new HashMap<>();
        activity.devices().forEach(d -> names.put(d.getId(), name(d)));
        return insights(activity.downloads(), days, geo::cached, names, System.currentTimeMillis(), ZoneId.systemDefault());
    }

    public Page<DownloadView> downloads(DownloadFilter filter, int offset, int limit) {
        List<DownloadRecord> hits = filtered(filter);
        int from = Math.max(0, Math.min(offset, hits.size()));
        int to = Math.min(hits.size(), from + Math.max(1, Math.min(limit, 500)));
        Map<String, DeviceRecord> byId = byId(activity.devices());
        return new Page<>(hits.size(), hits.subList(from, to).stream().map(r -> view(r, byId)).toList());
    }

    public String csv(DownloadFilter filter) {
        Map<String, DeviceRecord> byId = byId(activity.devices());
        return AdminCsv.downloads(filtered(filter), id -> name(byId.get(id)), this::placeOf, ZoneId.systemDefault());
    }

    public Optional<DeviceView> deviceView(String id) {
        long now = System.currentTimeMillis();
        List<DownloadRecord> downloads = activity.downloads();
        Map<String, DownloadRecord> byJob = new HashMap<>();
        downloads.forEach(r -> byJob.put(r.getJobId(), r));
        Map<String, int[]> active = activeByDevice(jobs.all(), byJob);
        return activity.device(id).map(d -> view(d, downloads, active, now, ZoneId.systemDefault()));
    }

    public Optional<DeviceDetail> device(String id) {
        return deviceView(id).map(view -> {
            Map<String, DeviceRecord> byId = byId(activity.devices());
            List<DownloadRecord> mine = activity.downloads().stream().filter(r -> id.equals(r.getDeviceId())).toList();
            List<ActivityEvent> theirs = activity.events().stream().filter(e -> id.equals(e.deviceId())).toList();
            return new DeviceDetail(view,
                    newestFirst(mine, 500).stream().map(r -> view(r, byId)).toList(),
                    newestFirst(theirs, 200).stream().map(e -> view(e, byId)).toList());
        });
    }

    // ------------------------------------------------------------------ counting (pure)

    static Kpis kpis(List<DeviceRecord> devices, List<DownloadRecord> downloads, List<ActivityEvent> events,
                     List<Job> jobs, Function<String, Optional<GeoInfo>> geo, long now, ZoneId zone) {
        long today = startOfDay(now, zone);
        long week = now - 7 * DAY;
        int online = 0;
        int usersToday = 0;
        int users7d = 0;
        Set<String> countries = new HashSet<>();
        for (DeviceRecord d : devices) {
            if (now - d.getLastSeen() <= ONLINE.toMillis()) {
                online++;
            }
            if (d.getLastSeen() >= today) {
                usersToday++;
            }
            if (d.getLastSeen() >= week) {
                users7d++;
            }
            if (d.getLastIp() != null) {
                geo.apply(d.getLastIp()).map(GeoInfo::countryCode).ifPresent(countries::add);
            }
        }
        int dlToday = 0;
        int dl7 = 0;
        int ok7 = 0;
        int failed7 = 0;
        long bytesToday = 0;
        long bytesTotal = 0;
        for (DownloadRecord r : downloads) {
            boolean done = JobStatus.COMPLETED.name().equals(r.getStatus());
            long size = done && r.getFileSize() != null ? r.getFileSize() : 0;
            bytesTotal += size;
            if (r.getAt() >= today) {
                dlToday++;
                bytesToday += size;
            }
            if (r.getAt() >= week) {
                dl7++;
                if (done) {
                    ok7++;
                } else if (JobStatus.FAILED.name().equals(r.getStatus())) {
                    failed7++;
                }
            }
        }
        int active = 0;
        int queued = 0;
        for (Job j : jobs) {
            if (j.getStatus() == JobStatus.QUEUED) {
                queued++;
            } else if (!isFinal(j.getStatus().name())) {
                active++;
            }
        }
        int failedLogins = (int) events.stream()
                .filter(e -> ActivityEvent.LOGIN_FAILED.equals(e.type()) && e.at() >= now - DAY).count();
        Integer rate = ok7 + failed7 == 0 ? null : (int) Math.round(100.0 * ok7 / (ok7 + failed7));
        return new Kpis(online, usersToday, users7d, devices.size(), dlToday, dl7, downloads.size(), bytesToday,
                bytesTotal, rate, active, queued, countries.size(), failedLogins);
    }

    static Insights insights(List<DownloadRecord> downloads, int days, Function<String, Optional<GeoInfo>> geo,
                             Map<String, String> deviceNames, long now, ZoneId zone) {
        LocalDate lastDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        LocalDate firstDay = lastDay.minusDays(days - 1L);
        int[] count = new int[days];
        long[] bytes = new long[days];
        List<Set<String>> who = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            who.add(new HashSet<>());
        }
        int[] perHour = new int[24];
        Map<String, Tally> videos = new HashMap<>();
        Map<String, Tally> places = new HashMap<>();
        Map<String, Tally> people = new HashMap<>();
        Map<String, Integer> kinds = new TreeMap<>();
        Map<String, Integer> qualities = new TreeMap<>();
        Map<String, Integer> formats = new TreeMap<>();
        Map<String, Integer> statuses = new TreeMap<>();

        for (DownloadRecord r : downloads) {
            ZonedDateTime t = Instant.ofEpochMilli(r.getAt()).atZone(zone);
            int i = (int) ChronoUnit.DAYS.between(firstDay, t.toLocalDate());
            if (i < 0 || i >= days) {
                continue;
            }
            boolean done = JobStatus.COMPLETED.name().equals(r.getStatus());
            count[i]++;
            if (done && r.getFileSize() != null) {
                bytes[i] += r.getFileSize();
            }
            if (r.getDeviceId() != null) {
                who.get(i).add(r.getDeviceId());
            }
            perHour[t.getHour()]++;
            kinds.merge(r.getKind() == null ? "other" : r.getKind(), 1, Integer::sum);
            if ("video".equals(r.getKind()) && r.getHeight() != null) {
                qualities.merge(qualityBucket(r.getHeight()), 1, Integer::sum);
            }
            if (r.getFormat() != null) {
                formats.merge(r.getFormat().toUpperCase(Locale.ROOT), 1, Integer::sum);
            }
            statuses.merge(isFinal(r.getStatus()) ? r.getStatus() : "ACTIVE", 1, Integer::sum);

            String video = youtubeId(r.getUrl());
            String key = video != null ? video : r.getUrl();
            if (key != null) {
                videos.computeIfAbsent(key, Tally::new).add(r.getTitle(), r.getDeviceId(), r.getUrl());
            }
            String place = r.getIp() == null ? null
                    : geo.apply(r.getIp()).map(GeoInfo::place).filter(p -> !p.isBlank()).orElse(null);
            places.computeIfAbsent(place == null ? "Unknown" : place, Tally::new).add(null, r.getDeviceId(), null);
            String device = r.getDeviceId() == null ? "?" : r.getDeviceId();
            people.computeIfAbsent(device, Tally::new)
                    .add(deviceNames.getOrDefault(device, "Unknown device"), device, null);
        }

        List<DayCount> perDay = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            perDay.add(new DayCount(firstDay.plusDays(i).toString(), count[i], who.get(i).size(), bytes[i]));
        }
        return new Insights(days, perDay, perHour, top(videos), kinds, qualities, formats, statuses, top(places),
                top(people));
    }

    /** One row of a top-10 list, filled as downloads are counted. */
    private static final class Tally {
        final String key;
        String label;
        String extra;
        int count;
        final Set<String> devices = new HashSet<>();

        Tally(String key) {
            this.key = key;
        }

        void add(String label, String device, String extra) {
            count++;
            if (label != null) {
                this.label = label; // downloads arrive oldest first, so the newest name wins
            }
            if (extra != null) {
                this.extra = extra;
            }
            if (device != null) {
                devices.add(device);
            }
        }

        TopItem item() {
            return new TopItem(key, label == null ? key : label, count, devices.size(), extra);
        }
    }

    private static List<TopItem> top(Map<String, Tally> tallies) {
        return tallies.values().stream()
                .map(Tally::item)
                .sorted(Comparator.comparingInt(TopItem::count).reversed()
                        .thenComparing(TopItem::label, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(10)
                .toList();
    }

    static String qualityBucket(int height) {
        if (height >= 2160) {
            return "2160p";
        }
        if (height >= 1440) {
            return "1440p";
        }
        if (height >= 1080) {
            return "1080p";
        }
        if (height >= 720) {
            return "720p";
        }
        return "480p or less";
    }

    /** The browser's clock and the IP's location disagree — a VPN or proxy is likely. */
    static boolean vpnHint(String browserZone, String ipZone, Instant at) {
        if (browserZone == null || ipZone == null) {
            return false;
        }
        try {
            return !ZoneId.of(browserZone).getRules().getOffset(at).equals(ZoneId.of(ipZone).getRules().getOffset(at));
        } catch (DateTimeException e) {
            return false;
        }
    }

    static String youtubeId(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = YOUTUBE_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** Where to put a device on the map: what it shared if it agreed to, else its IP's city. */
    static Place place(DeviceRecord d, Optional<GeoInfo> ipGeo) {
        if (DeviceRecord.CONSENT_GRANTED.equals(d.getConsent()) && d.getLat() != null && d.getLon() != null) {
            String near = ipGeo.map(GeoInfo::place).filter(p -> !p.isBlank()).orElse("Shared location");
            return new Place(d.getLat(), d.getLon(), near, "precise");
        }
        return ipGeo.filter(g -> g.lat() != null && g.lon() != null)
                .map(g -> new Place(g.lat(), g.lon(), g.place(), g.approximate() ? "lan" : "ip"))
                .orElse(null);
    }

    /** The admin's nickname for the device, else what its browser says it is. */
    static String name(DeviceRecord d) {
        if (d == null) {
            return "Unknown device";
        }
        if (d.getNickname() != null && !d.getNickname().isBlank()) {
            return d.getNickname();
        }
        return label(d);
    }

    static String label(DeviceRecord d) {
        if (d.getOs() == null) {
            return "Unknown device";
        }
        return new UserAgents.Parsed(d.getOs(), d.getBrowser(), d.getDeviceType()).label();
    }

    static boolean isFinal(String status) {
        return JobStatus.COMPLETED.name().equals(status) || JobStatus.FAILED.name().equals(status)
                || JobStatus.CANCELED.name().equals(status);
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static long startOfDay(long now, ZoneId zone) {
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private static <T> List<T> newestFirst(List<T> oldestFirst, int max) {
        List<T> out = new ArrayList<>(Math.min(max, oldestFirst.size()));
        for (int i = oldestFirst.size() - 1; i >= 0 && out.size() < max; i--) {
            out.add(oldestFirst.get(i));
        }
        return out;
    }

    // ------------------------------------------------------------------ views

    private DeviceView view(DeviceRecord d, List<DownloadRecord> downloads, Map<String, int[]> active, long now,
                            ZoneId zone) {
        long today = startOfDay(now, zone);
        int total = 0;
        int todayCount = 0;
        long bytes = 0;
        for (DownloadRecord r : downloads) {
            if (!d.getId().equals(r.getDeviceId())) {
                continue;
            }
            total++;
            if (r.getAt() >= today) {
                todayCount++;
            }
            if (JobStatus.COMPLETED.name().equals(r.getStatus()) && r.getFileSize() != null) {
                bytes += r.getFileSize();
            }
        }
        Optional<GeoInfo> ipGeo = geo.cached(d.getLastIp());
        Precise precise = d.getLat() == null || d.getLon() == null ? null
                : new Precise(d.getLat(), d.getLon(), d.getAccuracy(), d.getLocatedAt());
        boolean vpn = ipGeo.filter(g -> !g.approximate())
                .map(g -> vpnHint(d.getTimezone(), g.timezone(), Instant.ofEpochMilli(now))).orElse(false);
        int running = active.getOrDefault(d.getId(), new int[1])[0];
        return new DeviceView(d.getId(), name(d), d.getNickname(), label(d), d.getOs(), d.getBrowser(),
                d.getDeviceType(), d.isAdmin(), now - d.getLastSeen() <= ONLINE.toMillis(), d.getFirstSeen(),
                d.getLastSeen(), d.getVisits(), d.getLastIp(), d.getVia(), place(d, ipGeo), ipGeo.orElse(null), precise,
                d.getConsent(), vpn, d.isBlocked(), total, todayCount, running, bytes, d.getTimezone(),
                d.getLanguage(), d.getScreen(), d.getIps());
    }

    private DownloadView view(DownloadRecord r, Map<String, DeviceRecord> devices) {
        return new DownloadView(r.getJobId(), r.getAt(), r.getDeviceId(), name(devices.get(r.getDeviceId())), r.getIp(),
                r.getVia(), placeOf(r.getIp()), r.getUrl(), youtubeId(r.getUrl()), r.getTitle(), r.getKind(),
                r.getFormat(), r.getHeight(), r.getCodec(), r.getClip(), r.isPlaylist(), r.getItemCount(), r.getStatus(),
                r.getError(), r.getFileName(), r.getFileSize(), r.getQualityLabel(), r.getElapsedMs(), r.getFinishedAt(),
                r.getAttempts(), r.getSavedBy(), r.getSavedAt());
    }

    private EventView view(ActivityEvent e, Map<String, DeviceRecord> devices) {
        return new EventView(e.at(), e.type(), e.deviceId(), e.deviceId() == null ? null : name(devices.get(e.deviceId())),
                e.ip(), placeOf(e.ip()), e.detail(), e.url(), e.title());
    }

    private static LiveJob live(Job j, DownloadRecord r, Map<String, DeviceRecord> devices) {
        String deviceId = r == null ? null : r.getDeviceId();
        String kind = r != null ? r.getKind() : j.getRequest() != null && j.getRequest().isAudio() ? "audio" : "video";
        return new LiveJob(j.getId(), j.getTitle(), j.getStatus().name(), j.getProgress(), j.getPhase(),
                j.getSpeedBps(), j.getEta(), j.getPlaylistIndex(), j.getPlaylistCount(), j.getCreatedAt(), kind,
                r == null ? null : r.getFormat(), deviceId, deviceId == null ? null : name(devices.get(deviceId)));
    }

    private String placeOf(String ip) {
        return ip == null ? null : geo.cached(ip).map(GeoInfo::place).filter(p -> !p.isBlank()).orElse(null);
    }

    private List<DownloadRecord> filtered(DownloadFilter filter) {
        long now = System.currentTimeMillis();
        List<DownloadRecord> all = activity.downloads();
        List<DownloadRecord> hits = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (filter.test(all.get(i), now)) {
                hits.add(all.get(i));
            }
        }
        return hits;
    }

    private static Map<String, DeviceRecord> byId(List<DeviceRecord> devices) {
        Map<String, DeviceRecord> m = new HashMap<>();
        devices.forEach(d -> m.put(d.getId(), d));
        return m;
    }

    /** device id → {running jobs} */
    private static Map<String, int[]> activeByDevice(List<Job> all, Map<String, DownloadRecord> byJob) {
        Map<String, int[]> m = new HashMap<>();
        for (Job j : all) {
            DownloadRecord r = byJob.get(j.getId());
            if (r != null && r.getDeviceId() != null && !isFinal(j.getStatus().name())) {
                m.computeIfAbsent(r.getDeviceId(), k -> new int[1])[0]++;
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ the Mac itself

    private SystemView system(List<Job> all, long now) {
        YtDlpUpdateService.UpdateStatus u = updates.current();
        Long free = null;
        Long total = null;
        try {
            FileStore store = Files.getFileStore(ytdlp.workDir());
            free = store.getUsableSpace();
            total = store.getTotalSpace();
        } catch (IOException | RuntimeException ignored) {
            // the volume may be unmounted; the panel shows "unknown"
        }
        int running = (int) all.stream().filter(j -> !isFinal(j.getStatus().name())).count();
        int recent = (int) all.stream().filter(j -> j.getStatus() == JobStatus.COMPLETED && j.getFinishedAt() != null
                && now - j.getFinishedAt() < RECENT_FILE.toMillis()).count();
        String note = running > 0
                ? running + (running == 1 ? " download is" : " downloads are") + " in progress — a restart would delete "
                        + (running == 1 ? "it." : "them.")
                : recent > 0
                ? recent + (recent == 1 ? " file" : " files") + " finished in the last 10 minutes and may not be saved yet."
                : "Safe to restart — nothing is downloading.";
        return new SystemView(u.installed(), u.latest(), u.upToDate(), free, total,
                ManagementFactory.getRuntimeMXBean().getUptime(), publicUrl(), lanUrl(), running, recent,
                running == 0 && recent == 0, note, dataDir, retentionDays, now, ZoneId.systemDefault().getId());
    }

    /** The current Cloudflare quick-tunnel address, read from the tunnel's log (as tunnel-url.sh does). */
    private String publicUrl() {
        Path log = Path.of(tunnelLog);
        try {
            if (!Files.isReadable(log)) {
                return null;
            }
            long size = Files.size(log);
            long start = Math.max(0, size - 256 * 1024);
            ByteBuffer buf = ByteBuffer.allocate((int) (size - start));
            try (SeekableByteChannel ch = Files.newByteChannel(log)) {
                ch.position(start);
                while (buf.hasRemaining() && ch.read(buf) > 0) {
                    // keep reading
                }
            }
            Matcher m = TUNNEL_URL.matcher(new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8));
            String last = null;
            while (m.find()) {
                last = m.group();
            }
            return last;
        } catch (IOException e) {
            return null;
        }
    }

    /** http://<this Mac's LAN address>:<port>, for teammates in the office. */
    private String lanUrl() {
        // "Connecting" a UDP socket sends nothing; it just asks the OS which address the
        // default route would use — the right one even when en0 isn't the LAN interface.
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(InetAddress.getByName("8.8.8.8"), 53);
            InetAddress local = probe.getLocalAddress();
            if (local instanceof Inet4Address && local.isSiteLocalAddress()) {
                return "http://" + local.getHostAddress() + ":" + port;
            }
        } catch (IOException | RuntimeException ignored) {
            // fall through to scanning the interfaces
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return "http://" + a.getHostAddress() + ":" + port;
                    }
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }
}
