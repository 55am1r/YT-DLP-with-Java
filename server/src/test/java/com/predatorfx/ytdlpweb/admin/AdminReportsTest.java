package com.predatorfx.ytdlpweb.admin;

import com.predatorfx.ytdlpweb.model.DownloadRequest;
import com.predatorfx.ytdlpweb.model.Job;
import com.predatorfx.ytdlpweb.model.JobStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminReportsTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** 15:30 in India. */
    private static final long NOW = Instant.parse("2026-10-07T10:00:00Z").toEpochMilli();
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    private static DeviceRecord device(String id, long lastSeen, String ip) {
        DeviceRecord d = new DeviceRecord();
        d.setId(id);
        d.setFirstSeen(lastSeen - DAY);
        d.setLastSeen(lastSeen);
        d.setLastIp(ip);
        return d;
    }

    private static DownloadRecord dl(String job, String device, long at, String status, Long size) {
        DownloadRecord r = new DownloadRecord();
        r.setJobId(job);
        r.setDeviceId(device);
        r.setAt(at);
        r.setStatus(status);
        r.setFileSize(size);
        return r;
    }

    private static Job job(String id, JobStatus status) {
        Job j = new Job(id, new DownloadRequest("https://youtu.be/dQw4w9WgXcQ", "audio", null, null, false,
                "mp3", "t", null, null, null, null, false));
        j.setStatus(status);
        return j;
    }

    private static GeoInfo geo(String ip, String country) {
        return new GeoInfo(ip, "City", "Region", country, country, 1.0, 2.0, "ISP", "Asia/Kolkata", "ipwho.is", NOW, false);
    }

    @Test
    void kpisCountTodayAndSevenDays() {
        List<DeviceRecord> devices = List.of(
                device("online", NOW - 60_000, "1.1.1.1"),
                device("today", NOW - 3 * HOUR, "2.2.2.2"),
                device("week", NOW - 3 * DAY, "3.3.3.3"),
                device("old", NOW - 30 * DAY, "4.4.4.4"));
        List<DownloadRecord> downloads = List.of(
                dl("a", "online", NOW - HOUR, "COMPLETED", 100L),
                dl("b", "today", NOW - 2 * HOUR, "FAILED", null),
                dl("c", "week", NOW - 2 * DAY, "COMPLETED", 50L),
                dl("d", "old", NOW - 10 * DAY, "COMPLETED", 25L));
        List<ActivityEvent> events = List.of(
                new ActivityEvent(NOW - HOUR, ActivityEvent.LOGIN_FAILED, "x", "9.9.9.9", null, null, null),
                new ActivityEvent(NOW - 2 * DAY, ActivityEvent.LOGIN_FAILED, "x", "9.9.9.9", null, null, null));
        List<Job> jobs = List.of(job("x", JobStatus.DOWNLOADING), job("y", JobStatus.QUEUED), job("z", JobStatus.COMPLETED));
        Map<String, GeoInfo> places = Map.of("1.1.1.1", geo("1.1.1.1", "IN"), "2.2.2.2", geo("2.2.2.2", "IN"),
                "3.3.3.3", geo("3.3.3.3", "US"));

        AdminReports.Kpis k = AdminReports.kpis(devices, downloads, events, jobs,
                ip -> Optional.ofNullable(places.get(ip)), NOW, IST);

        assertEquals(1, k.onlineNow());
        assertEquals(2, k.usersToday());
        assertEquals(3, k.users7d());
        assertEquals(4, k.devicesTotal());
        assertEquals(2, k.downloadsToday());
        assertEquals(3, k.downloads7d());
        assertEquals(4, k.downloadsTotal());
        assertEquals(100L, k.bytesToday());
        assertEquals(175L, k.bytesTotal());
        assertEquals(67, k.successRate7d());
        assertEquals(1, k.activeJobs());
        assertEquals(1, k.queuedJobs());
        assertEquals(2, k.countries());
        assertEquals(1, k.failedLogins24h());
    }

    /** 2026-10-06T20:00Z is 01:30 on 7 October in India — it belongs to the 7th, hour 1. */
    @Test
    void insightsBucketByLocalDayAndHour() {
        DownloadRecord night = dl("a", "dev1", Instant.parse("2026-10-06T20:00:00Z").toEpochMilli(), "COMPLETED", 10L);
        night.setUrl("https://youtu.be/dQw4w9WgXcQ");
        night.setTitle("Song");
        night.setKind("audio");
        night.setFormat("mp3");
        DownloadRecord afternoon = dl("b", "dev2", NOW - HOUR, "COMPLETED", 20L);
        afternoon.setUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        afternoon.setTitle("Song (4K)");
        afternoon.setKind("video");
        afternoon.setFormat("mp4");
        afternoon.setHeight(2160);
        DownloadRecord tooOld = dl("c", "dev1", NOW - 9 * DAY, "COMPLETED", 5L);

        AdminReports.Insights in = AdminReports.insights(List.of(night, afternoon, tooOld), 7,
                ip -> Optional.empty(), Map.of("dev1", "Ravi", "dev2", "Sita"), NOW, IST);

        assertEquals(7, in.perDay().size());
        AdminReports.DayCount today = in.perDay().get(6);
        assertEquals("2026-10-07", today.day());
        assertEquals(2, today.downloads());
        assertEquals(2, today.devices());
        assertEquals(30L, today.bytes());
        assertEquals(1, in.perHour()[1]);
        assertEquals(1, in.perHour()[14]);
        assertEquals("dQw4w9WgXcQ", in.topVideos().get(0).key());
        assertEquals(2, in.topVideos().get(0).count());
        assertEquals(2, in.topVideos().get(0).devices());
        assertEquals(1, in.kinds().get("audio"));
        assertEquals(1, in.kinds().get("video"));
        assertEquals(1, in.qualities().get("2160p"));
        assertEquals(1, in.formats().get("MP3"));
        assertEquals("Ravi", in.topDevices().get(0).label());
    }

    @Test
    void vpnHintWhenOffsetsDiffer() {
        Instant at = Instant.ofEpochMilli(NOW);
        assertTrue(AdminReports.vpnHint("Asia/Kolkata", "America/Los_Angeles", at));
        assertFalse(AdminReports.vpnHint("Asia/Calcutta", "Asia/Kolkata", at));
        assertFalse(AdminReports.vpnHint("Europe/London", "Europe/Lisbon", at), "different zones, same clock");
        assertFalse(AdminReports.vpnHint(null, "Asia/Kolkata", at));
        assertFalse(AdminReports.vpnHint("Not/AZone", "Asia/Kolkata", at));
    }

    @Test
    void youtubeIdExtraction() {
        assertEquals("dQw4w9WgXcQ", AdminReports.youtubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", AdminReports.youtubeId("https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=42"));
        assertEquals("dQw4w9WgXcQ", AdminReports.youtubeId("https://youtu.be/dQw4w9WgXcQ?si=abc"));
        assertEquals("dQw4w9WgXcQ", AdminReports.youtubeId("https://youtube.com/shorts/dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", AdminReports.youtubeId("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVM"));
        assertNull(AdminReports.youtubeId("https://www.youtube.com/playlist?list=PL123"));
        assertNull(AdminReports.youtubeId("https://vimeo.com/123456"));
        assertNull(AdminReports.youtubeId(null));
    }

    @Test
    void placePrefersASharedPreciseLocation() {
        DeviceRecord d = device("d", NOW, "49.37.10.20");
        d.setConsent(DeviceRecord.CONSENT_GRANTED);
        d.setLat(17.4);
        d.setLon(78.5);
        GeoInfo ip = new GeoInfo("49.37.10.20", "Hyderabad", "Telangana", "India", "IN", 17.38, 78.48, "ACT",
                "Asia/Kolkata", "ipwho.is", NOW, false);

        assertEquals("precise", AdminReports.place(d, Optional.of(ip)).source());
        assertEquals(17.4, AdminReports.place(d, Optional.of(ip)).lat());

        d.setConsent(DeviceRecord.CONSENT_DECLINED);
        d.setLat(null);
        d.setLon(null);
        assertEquals("ip", AdminReports.place(d, Optional.of(ip)).source());
        assertEquals("Hyderabad, Telangana, India", AdminReports.place(d, Optional.of(ip)).label());
        assertEquals("lan", AdminReports.place(d, Optional.of(ip.approximateFor("192.168.1.20"))).source());
        assertNull(AdminReports.place(d, Optional.empty()));
    }

    @Test
    void downloadFilterMatchesTextDeviceStatusKindAndPeriod() {
        DownloadRecord a = dl("a", "dev1", NOW - HOUR, "COMPLETED", 1L);
        a.setTitle("Telugu Wedding Song");
        a.setKind("audio");
        DownloadRecord b = dl("b", "dev2", NOW - 10 * DAY, "FAILED", null);
        b.setTitle("Drone shots");
        b.setUrl("https://youtu.be/abcdefghijk");
        b.setKind("video");
        DownloadRecord c = dl("c", "dev2", NOW - 60_000, "DOWNLOADING", null);
        c.setKind("video");

        assertTrue(new AdminReports.DownloadFilter("wedding", null, null, null, 0).test(a, NOW));
        assertFalse(new AdminReports.DownloadFilter("wedding", null, null, null, 0).test(b, NOW));
        assertTrue(new AdminReports.DownloadFilter("ABCDEFG", null, null, null, 0).test(b, NOW), "matches the URL too");
        assertTrue(new AdminReports.DownloadFilter(null, "dev2", null, null, 0).test(b, NOW));
        assertTrue(new AdminReports.DownloadFilter(null, null, "FAILED", null, 0).test(b, NOW));
        assertTrue(new AdminReports.DownloadFilter(null, null, "ACTIVE", null, 0).test(c, NOW));
        assertFalse(new AdminReports.DownloadFilter(null, null, "ACTIVE", null, 0).test(a, NOW));
        assertTrue(new AdminReports.DownloadFilter(null, null, null, "video", 0).test(b, NOW));
        assertFalse(new AdminReports.DownloadFilter(null, null, null, null, 7).test(b, NOW));
    }

    /** A restart deletes every finished file still on the server — unsaved ones are lost work. */
    @Test
    void unsavedFinishedFilesMakeARestartUnsafe() {
        DownloadRecord unsaved = dl("a", "dev1", NOW, "COMPLETED", 1L);
        DownloadRecord saved = dl("b", "dev1", NOW, "COMPLETED", 1L);
        saved.getSavedBy().add("dev1");

        AdminReports.RestartCheck risky = AdminReports.restartCheck(
                List.of(job("a", JobStatus.COMPLETED), job("b", JobStatus.COMPLETED)), Map.of("a", unsaved, "b", saved));
        assertFalse(risky.safe());
        assertEquals(1, risky.unsaved());
        assertTrue(risky.note().contains("1 finished file"), risky.note());

        assertTrue(AdminReports.restartCheck(List.of(job("b", JobStatus.COMPLETED)), Map.of("b", saved)).safe());

        AdminReports.RestartCheck busy = AdminReports.restartCheck(List.of(job("c", JobStatus.DOWNLOADING)), Map.of());
        assertFalse(busy.safe());
        assertEquals(1, busy.running());
    }

    @Test
    void namesPreferTheAdminsNickname() {
        DeviceRecord d = device("d", NOW, null);
        d.setOs("iPhone");
        d.setBrowser("Safari");
        assertEquals("iPhone · Safari", AdminReports.name(d));
        d.setNickname("Ravi's iPhone");
        assertEquals("Ravi's iPhone", AdminReports.name(d));
        Function<String, String> unknown = id -> AdminReports.name(null);
        assertEquals("Unknown device", unknown.apply("x"));
    }
}
