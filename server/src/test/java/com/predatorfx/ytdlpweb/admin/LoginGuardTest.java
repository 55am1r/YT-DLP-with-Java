package com.predatorfx.ytdlpweb.admin;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginGuardTest {

    private static final String IP = "49.37.10.20";

    private final TestClock clock = new TestClock();
    private final LoginGuard guard = new LoginGuard(clock);

    /** One wrong login, start to finish; true when it caused a lockout. */
    private boolean wrong(String ip) {
        assertTrue(guard.tryBegin(ip), "attempt should have been allowed");
        return guard.failed(ip);
    }

    private void wrong(String ip, int times) {
        for (int i = 0; i < times; i++) {
            wrong(ip);
        }
    }

    @Test
    void locksOnTenthFailureWithinWindow() {
        for (int i = 1; i <= 9; i++) {
            assertFalse(wrong(IP), "failure " + i + " must not lock");
            assertEquals(0, guard.lockedForMillis(IP));
        }
        assertTrue(wrong(IP));
        assertEquals(Duration.ofMinutes(10).toMillis(), guard.lockedForMillis(IP));
        assertTrue(guard.lockedIps().containsKey(IP));
        assertFalse(guard.tryBegin(IP), "a locked address can't even start an attempt");
    }

    @Test
    void failuresOutsideWindowDoNotAccumulate() {
        wrong(IP, 9);
        clock.advance(Duration.ofMinutes(16));
        assertFalse(wrong(IP));
        assertEquals(0, guard.lockedForMillis(IP));
    }

    @Test
    void lockExpiresAfterTenMinutes() {
        wrong(IP, 10);
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertEquals(0, guard.lockedForMillis(IP));
        assertFalse(guard.lockedIps().containsKey(IP));
    }

    @Test
    void successClearsFailures() {
        wrong(IP, 9);
        assertTrue(guard.tryBegin(IP));
        guard.succeeded(IP);
        assertFalse(wrong(IP));
    }

    @Test
    void unlockClearsLockAndStartsCountingOver() {
        wrong(IP, 10);
        guard.unlock(IP);
        assertEquals(0, guard.lockedForMillis(IP));
        assertFalse(wrong(IP));
    }

    @Test
    void otherIpsAreUnaffected() {
        wrong(IP, 10);
        assertEquals(0, guard.lockedForMillis("49.37.10.21"));
    }

    /** Fifty guesses fired at once must not all get past the check before any is counted. */
    @Test
    void onlyTenAttemptsCanBeUnderwayAtOnce() {
        int allowed = 0;
        for (int i = 0; i < 50; i++) {
            if (guard.tryBegin(IP)) {
                allowed++;
            }
        }
        assertEquals(10, allowed);
    }

    @Test
    void releasedAttemptsDoNotCount() {
        for (int i = 0; i < 10; i++) {
            assertTrue(guard.tryBegin(IP));
            guard.released(IP); // e.g. the server was too busy to check it
        }
        assertTrue(guard.tryBegin(IP));
        assertEquals(0, guard.lockedForMillis(IP));
    }

    /** An IPv6 user has a whole /64 of addresses to rotate through; it counts as one. */
    @Test
    void ipv6LocksTheWhole64() {
        for (int i = 1; i <= 10; i++) {
            wrong("2001:db8:1:2:0:0:0:" + Integer.toHexString(i));
        }
        assertTrue(guard.lockedForMillis("2001:db8:1:2:ffff:0:0:1") > 0);
        assertEquals(0, guard.lockedForMillis("2001:db8:1:3:0:0:0:1"));
        assertTrue(guard.lockedIps().containsKey("2001:db8:1:2::/64"));

        guard.unlock("2001:db8:1:2::/64");
        assertEquals(0, guard.lockedForMillis("2001:db8:1:2:ffff:0:0:1"));
    }
}
