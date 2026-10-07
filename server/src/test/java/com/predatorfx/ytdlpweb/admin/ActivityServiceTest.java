package com.predatorfx.ytdlpweb.admin;

import com.predatorfx.ytdlpweb.model.DownloadRequest;
import com.predatorfx.ytdlpweb.model.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ActivityService's own rules, on a real store in a temp dir, with a hand-moved clock. */
class ActivityServiceTest {

    private static final ClientInfo HOME = new ClientInfo("49.37.10.20", "internet");

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock();
    private final List<String> geoCalls = new ArrayList<>();
    private final List<ActivityService> started = new ArrayList<>();

    @AfterEach
    void stop() {
        started.forEach(ActivityService::shutdown);
    }

    /** Geo-IP that is offline: every lookup fails, and every attempt is counted. */
    private GeoIpService offlineGeo() {
        return new GeoIpService(true, url -> {
            geoCalls.add(url);
            throw new UncheckedIOException(new IOException("offline"));
        }, clock, dir.resolve("geo-cache.json"), Runnable::run);
    }

    private ActivityService start(int maxEvents, int failedLoginsPerHour) {
        ActivityService s = new ActivityService(new ActivityStore(dir), offlineGeo(), 365, clock, maxEvents, failedLoginsPerHour);
        s.init();
        started.add(s);
        return s;
    }

    private static Job job(String id) {
        return new Job(id, new DownloadRequest("https://youtu.be/dQw4w9WgXcQ", "audio", null, null, false,
                "mp3", "A song", null, null, null, null, false));
    }

    private long count(ActivityService s, String type) {
        return s.events().stream().filter(e -> type.equals(e.type())).count();
    }

    /** Both geo services down when a device first appears: it must be tried again later, not left off the map for good. */
    @Test
    void failedGeoLookupIsRetriedOnALaterVisit() {
        ActivityService s = start(100, 300);

        s.touch("dev-1", HOME, null, false);
        assertEquals(2, geoCalls.size(), "one try per provider");
        s.touch("dev-1", HOME, null, false);
        assertEquals(2, geoCalls.size(), "backing off for 30 minutes");
        clock.advance(Duration.ofMinutes(31));
        s.touch("dev-1", HOME, null, false);
        assertEquals(4, geoCalls.size(), "tried again on the next visit");
    }

    @Test
    void restartMarksUnfinishedDownloadsInterrupted() {
        ActivityService before = start(100, 300);
        before.recordDownload(job("j1"), "dev-1", HOME);
        before.shutdown();
        started.remove(before);

        DownloadRecord r = start(100, 300).downloads().get(0);

        assertEquals("FAILED", r.getStatus());
        assertTrue(r.getError().contains("restarted"), r.getError());
    }

    /** The in-memory view is capped; the file must still keep everything inside the retention period. */
    @Test
    void compactionKeepsEveryEventWithinRetention() {
        ActivityService s = start(5, 300);
        s.recordEvent(ActivityEvent.LOGIN, "dev-1", HOME.ip(), null, null, null);
        clock.advance(Duration.ofDays(200));
        for (int i = 0; i < 8; i++) {
            s.recordEvent(ActivityEvent.ANALYZE, "dev-1", HOME.ip(), null, "https://youtu.be/x" + i, "t" + i);
        }
        s.compact();
        assertEquals(9, new ActivityStore(dir).load().events().size(), "all 9 are inside 365 days");
        assertEquals(5, s.events().size(), "memory keeps the newest 5");

        clock.advance(Duration.ofDays(200)); // the LOGIN is now 400 days old, the lookups 200
        s.compact();
        assertEquals(8, new ActivityStore(dir).load().events().size());
    }

    /** Anyone on the internet can make wrong logins; they must not crowd out what the admin did. */
    @Test
    void wrongLoginsCannotPushOutTheAuditTrail() {
        ActivityService s = start(5, 300);
        s.recordEvent(ActivityEvent.ADMIN_ACTION, "dev-admin", "203.0.113.1", "Blocked device X", null, null);
        for (int i = 0; i < 20; i++) {
            s.recordEvent(ActivityEvent.LOGIN_FAILED, null, "198.51.100." + i, null, null, null);
        }
        assertEquals(1, count(s, ActivityEvent.ADMIN_ACTION));
    }

    /** A flood is summarised, not written line by line — and only the listed ones are looked up. */
    @Test
    void wrongLoginFloodIsSummarised() {
        ActivityService s = start(1000, 3);
        for (int i = 0; i < 10; i++) {
            s.recordEvent(ActivityEvent.LOGIN_FAILED, null, "198.51.100." + i, null, null, null);
        }
        assertEquals(3, count(s, ActivityEvent.LOGIN_FAILED));
        assertEquals(6, geoCalls.size(), "two providers × the three listed addresses");

        clock.advance(Duration.ofMinutes(61));
        s.recordEvent(ActivityEvent.LOGIN_FAILED, null, "198.51.100.99", null, null, null);

        assertEquals(5, count(s, ActivityEvent.LOGIN_FAILED), "3 + a summary of the 7 unlisted + the new one");
        assertTrue(s.events().stream().anyMatch(e -> e.detail() != null && e.detail().startsWith("7 more wrong logins")));
        assertEquals(5, new ActivityStore(dir).load().events().size());
    }

    /** Forgetting a blocked device after a year would quietly lift its block. */
    @Test
    void blockedDevicesSurviveRetention() {
        ActivityService s = start(100, 300);
        s.touch("dev-blocked", HOME, null, false);
        s.touch("dev-idle", HOME, null, false);
        s.updateDevice("dev-blocked", null, true);

        clock.advance(Duration.ofDays(366));
        s.compact();

        assertTrue(s.device("dev-blocked").isPresent());
        assertTrue(s.device("dev-idle").isEmpty());
    }
}
