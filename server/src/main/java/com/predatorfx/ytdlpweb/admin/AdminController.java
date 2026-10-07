package com.predatorfx.ytdlpweb.admin;

import com.predatorfx.ytdlpweb.model.Job;
import com.predatorfx.ytdlpweb.service.JobService;
import com.predatorfx.ytdlpweb.web.AdminOnly;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The admin panel's API. AuthFilter lets only an admin session in here; every change made
 * through it is written to the activity log as an ADMIN_ACTION.
 */
@RestController
@RequestMapping("/api/admin")
@AdminOnly
public class AdminController {

    private final AdminReports reports;
    private final ActivityService activity;
    private final JobService jobs;
    private final LoginGuard guard;

    public AdminController(AdminReports reports, ActivityService activity, JobService jobs, LoginGuard guard) {
        this.reports = reports;
        this.activity = activity;
        this.jobs = jobs;
        this.guard = guard;
    }

    public record DeviceUpdate(String nickname, Boolean blocked) {}

    public record IpRequest(String ip, Boolean blocked) {}

    public record AnnouncementRequest(String text, String level) {}

    @GetMapping("/dashboard")
    public AdminReports.Dashboard dashboard(HttpServletRequest req) {
        return reports.dashboard(ActivityFilter.deviceId(req));
    }

    @GetMapping("/insights")
    public AdminReports.Insights insights(@RequestParam(defaultValue = "30") int days) {
        return reports.insights(Math.max(1, Math.min(365, days)));
    }

    @GetMapping("/downloads")
    public AdminReports.Page<AdminReports.DownloadView> downloads(
            @RequestParam(required = false) String q, @RequestParam(required = false) String device,
            @RequestParam(required = false) String status, @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") int days, @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return reports.downloads(new AdminReports.DownloadFilter(q, device, status, kind, days), offset, limit);
    }

    /**
     * The same log as a spreadsheet (with a BOM so Excel reads Telugu and Hindi titles right).
     * No ".csv" in the path: Cloudflare caches some file extensions by default.
     */
    @GetMapping("/downloads/export")
    public ResponseEntity<byte[]> downloadsCsv(
            @RequestParam(required = false) String q, @RequestParam(required = false) String device,
            @RequestParam(required = false) String status, @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") int days) {
        String csv = "﻿" + reports.csv(new AdminReports.DownloadFilter(q, device, status, kind, days));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("ez-tube-downloads-" + LocalDate.now() + ".csv").build().toString())
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/devices/{id}")
    public AdminReports.DeviceDetail device(@PathVariable String id) {
        return reports.device(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such device"));
    }

    @PostMapping("/devices/{id}")
    public AdminReports.DeviceView updateDevice(@PathVariable String id, @RequestBody(required = false) DeviceUpdate body,
                                                HttpServletRequest req) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to change");
        }
        if (Boolean.TRUE.equals(body.blocked()) && id.equals(ActivityFilter.deviceId(req))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can't block yourself — this is your own device");
        }
        DeviceRecord before = activity.device(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such device"));
        activity.updateDevice(id, body.nickname(), body.blocked());
        List<String> changes = new ArrayList<>();
        if (body.blocked() != null && body.blocked() != before.isBlocked()) {
            changes.add((body.blocked() ? "Blocked" : "Unblocked") + " device " + AdminReports.name(before));
        }
        String nickname = body.nickname() == null ? null : body.nickname().strip();
        if (nickname != null && !Objects.equals(nickname.isEmpty() ? null : nickname, before.getNickname())) {
            changes.add("Renamed " + AdminReports.name(before) + " to " + (nickname.isEmpty() ? "(no name)" : nickname));
        }
        if (!changes.isEmpty()) {
            audit(req, String.join("; ", changes));
        }
        return reports.deviceView(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such device"));
    }

    @PostMapping("/ips/block")
    public Map<String, Object> blockIp(@RequestBody(required = false) IpRequest body, HttpServletRequest req) {
        String ip = body == null || body.ip() == null ? null : body.ip().strip();
        boolean block = body == null || body.blocked() == null || body.blocked();
        if (!ClientInfo.isIpLiteral(ip)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That isn't an IP address");
        }
        if (block && ip.equals(ActivityFilter.client(req).ip())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You can't block yourself — that's the address you're using right now");
        }
        activity.setIpBlocked(ip, block);
        audit(req, (block ? "Blocked IP " : "Unblocked IP ") + ip);
        return Map.of("ok", true, "blockedIps", activity.blockedIps().stream().sorted().toList());
    }

    @PostMapping("/ips/unlock")
    public Map<String, Object> unlock(@RequestBody(required = false) IpRequest body, HttpServletRequest req) {
        String ip = body == null || body.ip() == null ? null : body.ip().strip();
        if (!ClientInfo.isIpLiteral(ip)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That isn't an IP address");
        }
        guard.unlock(ip);
        audit(req, "Lifted the login lockout on " + ip);
        return Map.of("ok", true);
    }

    @PostMapping("/jobs/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, HttpServletRequest req) {
        Job job = jobs.get(id);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such job");
        }
        boolean ok = jobs.cancel(id);
        if (ok) {
            audit(req, "Canceled the download \"" + (job.getTitle() == null ? id : job.getTitle()) + "\"");
        }
        return Map.of("ok", ok);
    }

    @PostMapping("/announcement")
    public Map<String, Object> announce(@RequestBody(required = false) AnnouncementRequest body, HttpServletRequest req) {
        Announcement a = activity.setAnnouncement(body == null ? null : body.text(), body == null ? null : body.level());
        audit(req, a == null ? "Cleared the announcement" : "Posted an announcement: " + a.text());
        return Collections.singletonMap("announcement", a);
    }

    private void audit(HttpServletRequest req, String detail) {
        activity.recordEvent(ActivityEvent.ADMIN_ACTION, ActivityFilter.deviceId(req), ActivityFilter.client(req).ip(),
                detail, null, null);
    }
}
