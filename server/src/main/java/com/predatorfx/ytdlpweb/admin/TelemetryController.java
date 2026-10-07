package com.predatorfx.ytdlpweb.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * What the downloader page tells the server about its device (time zone, screen, the answer
 * to the location card) and the admin's announcement it shows. Team login required — the
 * paths aren't on AuthFilter's open list.
 */
@RestController
@RequestMapping("/api")
public class TelemetryController {

    private final ActivityService activity;

    public TelemetryController(ActivityService activity) {
        this.activity = activity;
    }

    @PostMapping("/telemetry/hello")
    public Map<String, Object> hello(@RequestBody(required = false) ActivityService.Hello body, HttpServletRequest req) {
        activity.hello(ActivityFilter.deviceId(req), body);
        return Map.of("ok", true);
    }

    @PostMapping("/telemetry/location")
    public Map<String, Object> location(@RequestBody(required = false) ActivityService.LocationReport body,
                                        HttpServletRequest req) {
        try {
            activity.location(ActivityFilter.deviceId(req), ActivityFilter.client(req), body);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return Map.of("ok", true);
    }

    /** The current banner, or {} when there is none. */
    @GetMapping("/announcement")
    public Map<String, Object> announcement() {
        Announcement a = activity.announcement();
        return a == null ? Map.of() : Map.of("text", a.text(), "level", a.level(), "at", a.at());
    }
}
