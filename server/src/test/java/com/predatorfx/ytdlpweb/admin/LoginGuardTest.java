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

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            guard.recordFailure(IP);
        }
    }

    @Test
    void locksOnTenthFailureWithinWindow() {
        for (int i = 1; i <= 9; i++) {
            assertFalse(guard.recordFailure(IP), "failure " + i + " must not lock");
            assertEquals(0, guard.lockedForMillis(IP));
        }
        assertTrue(guard.recordFailure(IP));
        assertEquals(Duration.ofMinutes(10).toMillis(), guard.lockedForMillis(IP));
        assertTrue(guard.lockedIps().containsKey(IP));
    }

    @Test
    void failuresOutsideWindowDoNotAccumulate() {
        fail(9);
        clock.advance(Duration.ofMinutes(16));
        assertFalse(guard.recordFailure(IP));
        assertEquals(0, guard.lockedForMillis(IP));
    }

    @Test
    void lockExpiresAfterTenMinutes() {
        fail(10);
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertEquals(0, guard.lockedForMillis(IP));
        assertFalse(guard.lockedIps().containsKey(IP));
    }

    @Test
    void successClearsFailures() {
        fail(9);
        guard.recordSuccess(IP);
        assertFalse(guard.recordFailure(IP));
    }

    @Test
    void unlockClearsLockAndStartsCountingOver() {
        fail(10);
        guard.unlock(IP);
        assertEquals(0, guard.lockedForMillis(IP));
        assertFalse(guard.recordFailure(IP));
    }

    @Test
    void otherIpsAreUnaffected() {
        fail(10);
        assertEquals(0, guard.lockedForMillis("49.37.10.21"));
    }
}
