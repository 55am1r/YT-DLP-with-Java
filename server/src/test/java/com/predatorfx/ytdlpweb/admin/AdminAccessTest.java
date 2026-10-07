package com.predatorfx.ytdlpweb.admin;

import com.predatorfx.ytdlpweb.model.DownloadRequest;
import com.predatorfx.ytdlpweb.model.Job;
import com.predatorfx.ytdlpweb.model.JobStatus;
import com.predatorfx.ytdlpweb.service.JobFinishedEvent;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin gate, end to end through the real filters and controllers. Each test comes from
 * its own IP so one test's failed logins can't lock another out.
 */
@SpringBootTest(properties = {
        "ytdlp.work-dir=${java.io.tmpdir}/ytdlp-web-test-work",
        "brew.bin=/usr/bin/true",
        "app.admin.data-dir=${java.io.tmpdir}/ytdlp-admin-test-${random.uuid}",
        "app.geo.enabled=false",
        "app.auth.enabled=true",
        "app.auth.username=team",
        "app.auth.password=team-pass",
        // TestAdmin / S3cret-pass! — a made-up credential, see AdminCredentialTest
        "app.admin.credential=pbkdf2-sha256$1000$AAECAwQFBgcICQoLDA0ODw==$qKL6v25LIKaeMqK085jq/7ywj5yWduUhyJJ8M8bqrjM="
})
@AutoConfigureMockMvc
class AdminAccessTest {

    private static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1";

    @Autowired
    MockMvc mvc;

    @Autowired
    ActivityService activity;

    @Autowired
    ApplicationEventPublisher events;

    private static RequestPostProcessor from(String ip) {
        return r -> {
            r.setRemoteAddr(ip);
            return r;
        };
    }

    private MvcResult login(String user, String pass, String ip, Cookie... cookies) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/login").with(from(ip))
                .header("User-Agent", IPHONE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}");
        if (cookies.length > 0) {
            req.cookie(cookies);
        }
        return mvc.perform(req).andReturn();
    }

    private static List<String> cookieNames(MvcResult r) {
        return Arrays.stream(r.getResponse().getCookies()).map(Cookie::getName).toList();
    }

    @Test
    void adminApiRejectsAnonymous() throws Exception {
        mvc.perform(get("/api/admin/dashboard").with(from("203.0.113.1"))).andExpect(status().isUnauthorized());
    }

    @Test
    void adminApiRejectsTeamSession() throws Exception {
        MvcResult team = login("team", "team-pass", "203.0.113.2");
        assertEquals(200, team.getResponse().getStatus());
        mvc.perform(get("/api/admin/dashboard").with(from("203.0.113.2")).cookie(team.getResponse().getCookies()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminLoginOpensAdminApi() throws Exception {
        MvcResult r = login("TestAdmin", "S3cret-pass!", "203.0.113.3");

        assertEquals(200, r.getResponse().getStatus());
        assertTrue(r.getResponse().getContentAsString().contains("\"admin\":true"));
        assertTrue(cookieNames(r).containsAll(List.of(AdminAuthService.COOKIE, "ed_session", ActivityFilter.DEVICE_COOKIE)));
        Cookie[] c = r.getResponse().getCookies();
        mvc.perform(get("/api/me").with(from("203.0.113.3")).cookie(c))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(true));
        int gate = mvc.perform(get("/api/admin/dashboard").with(from("203.0.113.3")).cookie(c))
                .andReturn().getResponse().getStatus();
        assertNotEquals(401, gate);
    }

    @Test
    void teamLoginIsNotAdmin() throws Exception {
        MvcResult r = login("team", "team-pass", "203.0.113.4");
        assertTrue(r.getResponse().getContentAsString().contains("\"admin\":false"));
        mvc.perform(get("/api/me").with(from("203.0.113.4")).cookie(r.getResponse().getCookies()))
                .andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    void adminLoginIsCaseSensitive() throws Exception {
        assertEquals(401, login("testadmin", "S3cret-pass!", "203.0.113.5").getResponse().getStatus());
        assertEquals(401, login("TESTADMIN", "S3cret-pass!", "203.0.113.5").getResponse().getStatus());
        assertEquals(401, login("TestAdmin", "S3CRET-PASS!", "203.0.113.5").getResponse().getStatus());
        assertEquals(401, login("TestAdmin", "s3cret-pass!", "203.0.113.5").getResponse().getStatus());
    }

    @Test
    void lockoutAfterTenFailures() throws Exception {
        String ip = "203.0.113.6";
        for (int i = 0; i < 10; i++) {
            assertEquals(401, login("team", "wrong-" + i, ip).getResponse().getStatus());
        }
        MvcResult locked = login("TestAdmin", "S3cret-pass!", ip);
        assertEquals(429, locked.getResponse().getStatus());
        assertTrue(locked.getResponse().getContentAsString().contains("Try again in 10 minutes"));
        assertTrue(activity.events().stream().anyMatch(e -> ActivityEvent.LOCKED_OUT.equals(e.type()) && ip.equals(e.ip())));
        assertEquals(10, activity.events().stream()
                .filter(e -> ActivityEvent.LOGIN_FAILED.equals(e.type()) && ip.equals(e.ip())).count());
    }

    @Test
    void blockedDeviceGets403() throws Exception {
        MvcResult team = login("team", "team-pass", "203.0.113.7");
        String device = team.getResponse().getCookie(ActivityFilter.DEVICE_COOKIE).getValue();
        activity.updateDevice(device, null, true);

        mvc.perform(get("/api/me").with(from("203.0.113.7")).cookie(team.getResponse().getCookies()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.blocked").value(true));
        mvc.perform(get("/api/health").with(from("203.0.113.7")).cookie(team.getResponse().getCookies()))
                .andExpect(status().isOk());
    }

    /** Blocking an internet IP must hold even though tunnel traffic arrives from 127.0.0.1. */
    @Test
    void blockedIpGets403EvenThroughTheTunnel() throws Exception {
        activity.setIpBlocked("203.0.113.8", true);

        mvc.perform(post("/api/login").with(from("203.0.113.8")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/me").with(from("127.0.0.1")).header("CF-Connecting-IP", "203.0.113.8"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/me").with(from("127.0.0.1")).header("CF-Connecting-IP", "203.0.113.9"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void devicesAreRecordedOnlyOnceSignedIn() throws Exception {
        MvcResult anon = mvc.perform(get("/api/me").with(from("203.0.113.10"))).andReturn();
        Cookie device = anon.getResponse().getCookie(ActivityFilter.DEVICE_COOKIE);
        assertTrue(activity.device(device.getValue()).isEmpty(), "anonymous visitors leave no record");

        login("team", "team-pass", "203.0.113.10", device);

        DeviceRecord d = activity.device(device.getValue()).orElseThrow();
        assertEquals("iPhone", d.getOs());
        assertEquals("Safari", d.getBrowser());
        assertEquals("203.0.113.10", d.getLastIp());
        assertEquals("internet", d.getVia());
        assertTrue(activity.events().stream().anyMatch(e -> ActivityEvent.LOGIN.equals(e.type())
                && device.getValue().equals(e.deviceId())));
    }

    @Test
    void helloAndLocationUpdateTheDevice() throws Exception {
        MvcResult team = login("team", "team-pass", "203.0.113.11");
        Cookie[] c = team.getResponse().getCookies();
        String device = team.getResponse().getCookie(ActivityFilter.DEVICE_COOKIE).getValue();

        mvc.perform(post("/api/telemetry/hello").with(from("203.0.113.11")).cookie(c).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timezone\":\"Asia/Kolkata\",\"language\":\"te-IN\",\"screen\":\"390x844\","
                                + "\"touch\":true,\"secure\":true,\"permission\":\"prompt\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/telemetry/location").with(from("203.0.113.11")).cookie(c).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\":17.385,\"lon\":78.4867,\"accuracy\":25,\"outcome\":\"granted\"}"))
                .andExpect(status().isOk());

        DeviceRecord d = activity.device(device).orElseThrow();
        assertEquals("Asia/Kolkata", d.getTimezone());
        assertEquals("390x844", d.getScreen());
        assertEquals(DeviceRecord.CONSENT_GRANTED, d.getConsent());
        assertEquals(17.385, d.getLat());
        assertEquals(25.0, d.getAccuracy());

        mvc.perform(post("/api/telemetry/location").with(from("203.0.113.11")).cookie(c).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\":200,\"lon\":0,\"accuracy\":5,\"outcome\":\"granted\"}"))
                .andExpect(status().isBadRequest());

        // Withdrawing consent also forgets the precise position.
        mvc.perform(post("/api/telemetry/location").with(from("203.0.113.11")).cookie(c).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"declined\"}"))
                .andExpect(status().isOk());
        d = activity.device(device).orElseThrow();
        assertEquals(DeviceRecord.CONSENT_DECLINED, d.getConsent());
        assertNull(d.getLat());
    }

    /** Location turned off in the browser later: the stored position goes, and the admin sees why. */
    @Test
    void browserDenialWithdrawsAndLogsIt() throws Exception {
        MvcResult team = login("team", "team-pass", "203.0.113.21");
        Cookie[] c = team.getResponse().getCookies();
        String device = team.getResponse().getCookie(ActivityFilter.DEVICE_COOKIE).getValue();
        mvc.perform(post("/api/telemetry/location").with(from("203.0.113.21")).cookie(c).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lat\":17.36,\"lon\":78.47,\"accuracy\":12,\"outcome\":\"granted\"}")).andExpect(status().isOk());

        mvc.perform(post("/api/telemetry/hello").with(from("203.0.113.21")).cookie(c).contentType(MediaType.APPLICATION_JSON)
                .content("{\"timezone\":\"Asia/Kolkata\",\"secure\":true,\"permission\":\"denied\"}")).andExpect(status().isOk());

        DeviceRecord d = activity.device(device).orElseThrow();
        assertEquals(DeviceRecord.CONSENT_DENIED, d.getConsent());
        assertNull(d.getLat());
        assertTrue(activity.events().stream().anyMatch(e -> ActivityEvent.LOCATION.equals(e.type())
                && device.equals(e.deviceId()) && DeviceRecord.CONSENT_DENIED.equals(e.detail())));
    }

    @Test
    void telemetryNeedsALogin() throws Exception {
        mvc.perform(post("/api/telemetry/location").with(from("203.0.113.12")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"declined\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void announcementIsShownToTheTeam() throws Exception {
        activity.setAnnouncement("Server restarts at 6 pm", "warn");
        MvcResult team = login("team", "team-pass", "203.0.113.13");

        mvc.perform(get("/api/announcement").with(from("203.0.113.13")).cookie(team.getResponse().getCookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Server restarts at 6 pm"))
                .andExpect(jsonPath("$.level").value("warn"));
        mvc.perform(get("/api/announcement").with(from("203.0.113.14"))).andExpect(status().isUnauthorized());
    }

    @Test
    void dashboardShowsTheAdminsOwnDevice() throws Exception {
        MvcResult r = login("TestAdmin", "S3cret-pass!", "203.0.113.15");
        String device = r.getResponse().getCookie(ActivityFilter.DEVICE_COOKIE).getValue();

        mvc.perform(get("/api/admin/dashboard").with(from("203.0.113.15")).cookie(r.getResponse().getCookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.you").value(device))
                .andExpect(jsonPath("$.devices[?(@.id == '" + device + "')].admin").value(true))
                .andExpect(jsonPath("$.kpis.devicesTotal").isNumber())
                .andExpect(jsonPath("$.system.retentionDays").value(365));
        mvc.perform(get("/api/admin/insights?days=7").with(from("203.0.113.15")).cookie(r.getResponse().getCookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perDay.length()").value(7));
    }

    @Test
    void adminCannotBlockOwnDeviceOrIp() throws Exception {
        MvcResult r = login("TestAdmin", "S3cret-pass!", "203.0.113.16");
        Cookie[] c = r.getResponse().getCookies();
        String device = r.getResponse().getCookie(ActivityFilter.DEVICE_COOKIE).getValue();

        mvc.perform(post("/api/admin/devices/" + device).with(from("203.0.113.16")).cookie(c)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"blocked\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/ips/block").with(from("203.0.113.16")).cookie(c)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ip\":\"203.0.113.16\",\"blocked\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/ips/block").with(from("203.0.113.16")).cookie(c)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ip\":\"not-an-ip\",\"blocked\":true}"))
                .andExpect(status().isBadRequest());
        assertTrue(activity.blockedIps().isEmpty() || !activity.blockedIps().contains("203.0.113.16"));
    }

    @Test
    void adminCanRenameAndBlockAnotherDevice() throws Exception {
        MvcResult team = login("team", "team-pass", "203.0.113.17");
        String teamDevice = team.getResponse().getCookie(ActivityFilter.DEVICE_COOKIE).getValue();
        MvcResult admin = login("TestAdmin", "S3cret-pass!", "203.0.113.18");

        mvc.perform(post("/api/admin/devices/" + teamDevice).with(from("203.0.113.18")).cookie(admin.getResponse().getCookies())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"Ravi's iPhone\",\"blocked\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ravi's iPhone"))
                .andExpect(jsonPath("$.blocked").value(true));

        mvc.perform(get("/api/me").with(from("203.0.113.17")).cookie(team.getResponse().getCookies()))
                .andExpect(status().isForbidden());
        assertTrue(activity.events().stream().anyMatch(e -> ActivityEvent.ADMIN_ACTION.equals(e.type())
                && e.detail() != null && e.detail().startsWith("Blocked")));
    }

    @Test
    void csvExportIsAnAttachment() throws Exception {
        MvcResult admin = login("TestAdmin", "S3cret-pass!", "203.0.113.19");
        MvcResult csv = mvc.perform(get("/api/admin/downloads.csv").with(from("203.0.113.19"))
                .cookie(admin.getResponse().getCookies())).andReturn();

        assertEquals(200, csv.getResponse().getStatus());
        assertTrue(csv.getResponse().getContentType().startsWith("text/csv"));
        assertTrue(csv.getResponse().getHeader("Content-Disposition").startsWith("attachment"));
        assertTrue(csv.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("Time,Device,"));
    }

    @Test
    void unknownDeviceIsNotFound() throws Exception {
        MvcResult admin = login("TestAdmin", "S3cret-pass!", "203.0.113.20");
        mvc.perform(get("/api/admin/devices/no-such-device-000").with(from("203.0.113.20"))
                        .cookie(admin.getResponse().getCookies()))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadLifecycleIsRecorded() {
        String id = "t-" + UUID.randomUUID().toString().substring(0, 8);
        DownloadRequest req = new DownloadRequest("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "audio", null, null,
                false, "mp3", "A song", null, null, null, null, false);
        Job job = new Job(id, req);

        activity.recordDownload(job, "devA", new ClientInfo("49.37.10.20", "internet"));
        DownloadRecord r = find(id);
        assertEquals("devA", r.getDeviceId());
        assertEquals("QUEUED", r.getStatus());
        assertEquals("audio", r.getKind());
        assertEquals("mp3", r.getFormat());
        assertEquals("A song", r.getTitle());

        job.setStatus(JobStatus.COMPLETED);
        job.setFileSize(1234L);
        job.setFinishedAt(System.currentTimeMillis());
        events.publishEvent(new JobFinishedEvent(job));
        events.publishEvent(new JobFinishedEvent(job)); // a repeat of the same finish changes nothing
        r = find(id);
        assertEquals("COMPLETED", r.getStatus());
        assertEquals(1234L, r.getFileSize());
        assertEquals(1, r.getAttempts());

        activity.recordSave(id, "devB");
        activity.recordSave(id, "devB");
        assertEquals(List.of("devB"), find(id).getSavedBy());
    }

    private DownloadRecord find(String jobId) {
        return activity.downloads().stream().filter(r -> jobId.equals(r.getJobId())).findFirst().orElseThrow();
    }
}
