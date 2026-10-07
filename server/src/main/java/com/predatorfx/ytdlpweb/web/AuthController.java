package com.predatorfx.ytdlpweb.web;

import com.predatorfx.ytdlpweb.admin.ActivityEvent;
import com.predatorfx.ytdlpweb.admin.ActivityFilter;
import com.predatorfx.ytdlpweb.admin.ActivityService;
import com.predatorfx.ytdlpweb.admin.AdminAuthService;
import com.predatorfx.ytdlpweb.admin.ClientInfo;
import com.predatorfx.ytdlpweb.admin.LoginGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Login / session endpoints. One login screen serves both the team and the owner: the admin
 * credential is checked first and opens the admin panel (plus the downloader); otherwise the
 * team credential opens the downloader. Wrong guesses count towards a per-IP lockout.
 */
@RestController
@RequestMapping("/api")
@PublicEndpoint
public class AuthController {

    private final AuthService auth;
    private final AdminAuthService admin;
    private final LoginGuard guard;
    private final ActivityService activity;

    public AuthController(AuthService auth, AdminAuthService admin, LoginGuard guard, ActivityService activity) {
        this.auth = auth;
        this.admin = admin;
        this.guard = guard;
        this.activity = activity;
    }

    public record LoginRequest(String username, String password) {}

    /** Used by the UI on load to decide between the app, the admin panel and the login screen. */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest req) {
        boolean isAdmin = admin.validSession(req);
        if (isAdmin || auth.validCookie(req)) {
            return ResponseEntity.ok(Map.of("authenticated", true, "username", auth.username(), "admin", isAdmin));
        }
        return ResponseEntity.status(401).body(Map.of("authenticated", false));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) LoginRequest body, HttpServletRequest req) {
        ClientInfo client = ActivityFilter.client(req);
        String device = ActivityFilter.deviceId(req);
        String agent = req.getHeader(HttpHeaders.USER_AGENT);

        long wait = guard.lockedForMillis(client.ip());
        if (wait > 0) {
            long minutes = Math.max(1, (wait + 59_999) / 60_000);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("ok", false, "error",
                    "Too many wrong attempts. Try again in " + minutes + (minutes == 1 ? " minute." : " minutes.")));
        }

        String user = body == null ? null : body.username();
        String pass = body == null ? null : body.password();
        if (admin.checkCredentials(user, pass)) {
            guard.recordSuccess(client.ip());
            activity.touch(device, client, agent, true);
            activity.recordEvent(ActivityEvent.ADMIN_LOGIN, device, client.ip(), null, null, null);
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, admin.sessionCookie(admin.startSession()).toString())
                    .header(HttpHeaders.SET_COOKIE, auth.sessionCookie().toString())
                    .body(Map.of("ok", true, "admin", true));
        }
        if (auth.checkCredentials(user, pass)) {
            guard.recordSuccess(client.ip());
            activity.touch(device, client, agent, false);
            activity.recordEvent(ActivityEvent.LOGIN, device, client.ip(), null, null, null);
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, auth.sessionCookie().toString())
                    .body(Map.of("ok", true, "admin", false));
        }

        // Nothing typed is stored — only that a wrong login came from here.
        boolean locked = guard.recordFailure(client.ip());
        activity.recordEvent(ActivityEvent.LOGIN_FAILED, device, client.ip(), null, null, null);
        if (locked) {
            activity.recordEvent(ActivityEvent.LOCKED_OUT, device, client.ip(), "Locked out for 10 minutes", null, null);
        }
        return ResponseEntity.status(401).body(Map.of("ok", false, "error", "Wrong username or password"));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest req) {
        if (admin.validSession(req) || auth.validCookie(req)) {
            activity.recordEvent(ActivityEvent.LOGOUT, ActivityFilter.deviceId(req), ActivityFilter.client(req).ip(),
                    null, null, null);
        }
        admin.endSession(req);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, auth.clearCookie().toString())
                .header(HttpHeaders.SET_COOKIE, admin.clearCookie().toString())
                .body(Map.of("ok", true));
    }
}
