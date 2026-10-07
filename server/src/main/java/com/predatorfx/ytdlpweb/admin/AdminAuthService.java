package com.predatorfx.ytdlpweb.admin;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owner-only admin login on the same login screen as the team.
 *
 * Unlike the team cookie (derived from the shared credentials, valid for 90 days), an admin
 * session is a random token the server remembers: it can be revoked, it ends when the browser
 * closes, and it expires on its own — 2 h idle, 12 h at most. A server restart signs the
 * admin out, which is fine for a page the owner opens now and then.
 */
@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    public static final String COOKIE = "ez_admin";
    static final Duration MAX_AGE = Duration.ofHours(12);
    static final Duration IDLE = Duration.ofHours(2);

    private final AdminCredential credential; // null = admin login disabled
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private static final class Session {
        final long createdAt;
        volatile long lastSeen;

        Session(long createdAt) {
            this.createdAt = createdAt;
            this.lastSeen = createdAt;
        }

        boolean expired(long now) {
            return now - createdAt > MAX_AGE.toMillis() || now - lastSeen > IDLE.toMillis();
        }
    }

    @Autowired
    public AdminAuthService(@Value("${app.admin.credential:}") String encoded) {
        this(encoded, Clock.systemUTC());
    }

    AdminAuthService(String encoded, Clock clock) {
        this.clock = clock;
        this.credential = AdminCredential.parse(encoded).orElse(null);
        if (credential == null && encoded != null && !encoded.isBlank()) {
            log.warn("app.admin.credential is not a valid pbkdf2-sha256 hash — the admin panel is disabled");
        }
    }

    public boolean isEnabled() {
        return credential != null;
    }

    public boolean checkCredentials(String username, String password) {
        return credential != null && credential.matches(username, password);
    }

    /** A new admin session; the caller hands the token to the browser in {@link #sessionCookie}. */
    public String startSession() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long now = clock.millis();
        sessions.values().removeIf(s -> s.expired(now));
        sessions.put(token, new Session(now));
        return token;
    }

    /** True for a live admin session; each check counts as activity for the idle timeout. */
    public boolean validSession(HttpServletRequest req) {
        if (credential == null) {
            return false;
        }
        String token = token(req);
        Session s = token == null ? null : sessions.get(token);
        if (s == null) {
            return false;
        }
        long now = clock.millis();
        if (s.expired(now)) {
            sessions.remove(token);
            return false;
        }
        s.lastSeen = now;
        return true;
    }

    public void endSession(HttpServletRequest req) {
        String token = token(req);
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** No Max-Age: the browser forgets it on close, and the server forgets it on expiry. */
    public ResponseCookie sessionCookie(String token) {
        return ResponseCookie.from(COOKIE, token).httpOnly(true).path("/").sameSite("Lax").build();
    }

    public ResponseCookie clearCookie() {
        return ResponseCookie.from(COOKIE, "").httpOnly(true).path("/").sameSite("Lax").maxAge(0).build();
    }

    private static String token(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }
}
