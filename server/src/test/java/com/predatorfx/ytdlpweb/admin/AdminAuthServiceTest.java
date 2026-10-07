package com.predatorfx.ytdlpweb.admin;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAuthServiceTest {

    private static final String HASH =
            "pbkdf2-sha256$1000$AAECAwQFBgcICQoLDA0ODw==$qKL6v25LIKaeMqK085jq/7ywj5yWduUhyJJ8M8bqrjM=";

    private final TestClock clock = new TestClock();
    private final AdminAuthService admin = new AdminAuthService(HASH, clock);

    private static MockHttpServletRequest withCookie(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(AdminAuthService.COOKIE, token));
        return req;
    }

    @Test
    void disabledWhenCredentialBlank() {
        AdminAuthService off = new AdminAuthService("", clock);
        assertFalse(off.isEnabled());
        assertFalse(off.checkCredentials("", ""));
    }

    @Test
    void checksTheConfiguredCredential() {
        assertTrue(admin.isEnabled());
        assertTrue(admin.checkCredentials("TestAdmin", "S3cret-pass!"));
        assertFalse(admin.checkCredentials("testadmin", "S3cret-pass!"));
    }

    @Test
    void sessionCookieGrantsAccess() {
        String token = admin.startSession();
        assertTrue(admin.validSession(withCookie(token)));
    }

    @Test
    void unknownTokenOrNoCookieIsRejected() {
        admin.startSession();
        assertFalse(admin.validSession(withCookie("not-a-session")));
        assertFalse(admin.validSession(new MockHttpServletRequest()));
    }

    @Test
    void idleSessionExpiresAfterTwoHours() {
        String token = admin.startSession();
        clock.advance(Duration.ofHours(2).plusSeconds(1));
        assertFalse(admin.validSession(withCookie(token)));
    }

    @Test
    void activeSessionStillEndsAfterTwelveHours() {
        String token = admin.startSession();
        for (int h = 0; h < 12; h++) {
            clock.advance(Duration.ofHours(1));
            if (h < 11) {
                assertTrue(admin.validSession(withCookie(token)), "hour " + (h + 1));
            }
        }
        clock.advance(Duration.ofSeconds(1));
        assertFalse(admin.validSession(withCookie(token)));
    }

    @Test
    void endSessionRevokesIt() {
        String token = admin.startSession();
        admin.endSession(withCookie(token));
        assertFalse(admin.validSession(withCookie(token)));
    }
}
