package com.predatorfx.ytdlpweb.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityStoreTest {

    @TempDir
    Path dir;

    private static DeviceRecord device(String id) {
        DeviceRecord d = new DeviceRecord();
        d.setId(id);
        d.setFirstSeen(1_000);
        d.setLastSeen(2_000);
        d.setNickname("Ravi's iPhone");
        d.setConsent(DeviceRecord.CONSENT_GRANTED);
        d.setLat(17.385);
        d.setLon(78.4867);
        IpSeen ip = new IpSeen();
        ip.setIp("49.37.10.20");
        ip.setFirstSeen(1_000);
        ip.setLastSeen(2_000);
        ip.setHits(3);
        d.getIps().add(ip);
        return d;
    }

    private static DownloadRecord download(String jobId, String status) {
        DownloadRecord r = new DownloadRecord();
        r.setJobId(jobId);
        r.setAt(3_000);
        r.setDeviceId("dev-1");
        r.setUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        r.setTitle("A song");
        r.setStatus(status);
        return r;
    }

    private static List<String> ids(List<DownloadRecord> records) {
        return records.stream().map(DownloadRecord::getJobId).toList();
    }

    @Test
    void roundTripsEverything() {
        ActivityStore store = new ActivityStore(dir);
        store.saveDevices(List.of(device("dev-1")));
        store.appendDownload(download("j1", "QUEUED"));
        store.appendEvent(new ActivityEvent(5_000, ActivityEvent.LOGIN, "dev-1", "49.37.10.20", null, null, null));
        store.saveSettings(new ActivityStore.Settings(List.of("203.0.113.9"),
                new Announcement("Restart at 6 pm", "warn", 7_000)));

        ActivityStore.Loaded loaded = new ActivityStore(dir).load();

        DeviceRecord d = loaded.devices().get(0);
        assertEquals("Ravi's iPhone", d.getNickname());
        assertEquals(17.385, d.getLat());
        assertEquals(DeviceRecord.CONSENT_GRANTED, d.getConsent());
        assertEquals("49.37.10.20", d.getIps().get(0).getIp());
        assertEquals(3, d.getIps().get(0).getHits());
        assertEquals(List.of("j1"), ids(loaded.downloads()));
        assertEquals(ActivityEvent.LOGIN, loaded.events().get(0).type());
        assertEquals(List.of("203.0.113.9"), loaded.settings().blockedIps());
        assertEquals("Restart at 6 pm", loaded.settings().announcement().text());
    }

    @Test
    void laterDownloadLineWins() {
        ActivityStore store = new ActivityStore(dir);
        store.appendDownload(download("j1", "QUEUED"));
        store.appendDownload(download("j2", "QUEUED"));
        DownloadRecord done = download("j1", "COMPLETED");
        done.setFileSize(1234L);
        store.appendDownload(done);

        List<DownloadRecord> got = new ActivityStore(dir).load().downloads();

        assertEquals(List.of("j1", "j2"), ids(got));
        assertEquals("COMPLETED", got.get(0).getStatus());
        assertEquals(1234L, got.get(0).getFileSize());
    }

    /** A crash mid-write leaves half a line; it is skipped, and new lines don't glue onto it. */
    @Test
    void skipsCorruptLinesAndRepairsTheTail() throws IOException {
        new ActivityStore(dir).appendDownload(download("j1", "QUEUED"));
        Files.writeString(dir.resolve("downloads.jsonl"), "{\"jobId\":", StandardOpenOption.APPEND);

        ActivityStore restarted = new ActivityStore(dir);
        assertEquals(List.of("j1"), ids(restarted.load().downloads()));
        restarted.appendDownload(download("j2", "QUEUED"));

        assertEquals(List.of("j1", "j2"), ids(new ActivityStore(dir).load().downloads()));
    }

    @Test
    void rewriteKeepsOnlyGivenRecords() {
        ActivityStore store = new ActivityStore(dir);
        store.appendDownload(download("j1", "COMPLETED"));
        store.appendDownload(download("j2", "COMPLETED"));
        ActivityEvent keep = new ActivityEvent(9_000, ActivityEvent.ANALYZE, "dev-1", "49.37.10.20", null, "u", "t");
        store.appendEvent(new ActivityEvent(1_000, ActivityEvent.LOGIN, "dev-1", "49.37.10.20", null, null, null));
        store.appendEvent(keep);

        store.rewrite(List.of(download("j2", "COMPLETED")), List.of(keep));

        ActivityStore.Loaded loaded = new ActivityStore(dir).load();
        assertEquals(List.of("j2"), ids(loaded.downloads()));
        assertEquals(List.of(keep), loaded.events());
    }

    @Test
    void missingDirLoadsEmpty() {
        ActivityStore.Loaded loaded = new ActivityStore(dir.resolve("not-there-yet")).load();
        assertTrue(loaded.devices().isEmpty());
        assertTrue(loaded.downloads().isEmpty());
        assertTrue(loaded.events().isEmpty());
        assertTrue(loaded.settings().blockedIps().isEmpty());
        assertNull(loaded.settings().announcement());
    }

    /** A damaged devices.json is moved aside, so the next save can't overwrite what's left of it. */
    @Test
    void corruptDevicesFileIsSetAside() throws IOException {
        Files.writeString(dir.resolve("devices.json"), "[{\"id\":");

        assertTrue(new ActivityStore(dir).load().devices().isEmpty());

        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("devices.json.corrupt-")));
        }
    }

    /** A property that copy() forgets would silently vanish from what the admin sees. */
    @Test
    void copyPreservesEveryProperty() {
        String deviceJson = """
                {"id":"d","firstSeen":1,"lastSeen":2,"visits":3,"lastIp":"1.2.3.4","via":"lan","userAgent":"ua",
                 "os":"Mac","browser":"Safari","deviceType":"desktop","nickname":"n","blocked":true,"admin":true,
                 "timezone":"Asia/Kolkata","language":"te-IN","screen":"1x2","touch":true,"consent":"granted",
                 "lat":1.5,"lon":2.5,"accuracy":10.0,"locatedAt":4,
                 "ips":[{"ip":"1.2.3.4","firstSeen":1,"lastSeen":2,"hits":5}]}""";
        String downloadJson = """
                {"jobId":"j","at":1,"deviceId":"d","ip":"1.2.3.4","via":"lan","url":"u","title":"t","kind":"video",
                 "format":"mp4","height":2160,"codec":"none","clip":"0-1","playlist":true,"itemCount":3,
                 "status":"COMPLETED","error":"e","fileName":"f","fileSize":9,"qualityLabel":"q","container":"mp4",
                 "elapsedMs":7,"finishedAt":8,"attempts":2,"savedBy":["d"],"savedAt":6}""";

        DeviceRecord d = ActivityStore.JSON.readValue(deviceJson, DeviceRecord.class);
        String deviceOut = ActivityStore.JSON.writeValueAsString(d);
        assertFalse(deviceOut.contains("null"), deviceOut);
        assertEquals(deviceOut, ActivityStore.JSON.writeValueAsString(d.copy()));

        DownloadRecord r = ActivityStore.JSON.readValue(downloadJson, DownloadRecord.class);
        String downloadOut = ActivityStore.JSON.writeValueAsString(r);
        assertFalse(downloadOut.contains("null"), downloadOut);
        assertEquals(downloadOut, ActivityStore.JSON.writeValueAsString(r.copy()));
    }

    @Test
    void copiesAreIndependent() {
        DeviceRecord original = device("dev-1");
        DeviceRecord copy = original.copy();
        copy.getIps().get(0).setHits(99);
        copy.getIps().clear();
        assertEquals(3, original.getIps().get(0).getHits());

        DownloadRecord r = download("j1", "COMPLETED");
        r.getSavedBy().add("dev-2");
        DownloadRecord rc = r.copy();
        rc.getSavedBy().add("dev-3");
        assertEquals(List.of("dev-2"), r.getSavedBy());
    }
}
