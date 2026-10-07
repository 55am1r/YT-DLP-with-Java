package com.predatorfx.ytdlpweb.admin;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Slows down password guessing. The site is reachable from the whole internet through the
 * tunnel and the passwords are short, so after 10 wrong logins from one IP within 15 minutes
 * that IP is refused for 10 minutes — the admin can lift it early from the Security tab.
 * Keyed by IP, not device: a guesser simply drops cookies.
 */
@Component
public class LoginGuard {

    static final int MAX_FAILURES = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK = Duration.ofMinutes(10);

    private final Clock clock;
    private final Map<String, Deque<Long>> failures = new HashMap<>();
    private final Map<String, Long> lockedUntil = new HashMap<>();

    public LoginGuard() {
        this(Clock.systemUTC());
    }

    LoginGuard(Clock clock) {
        this.clock = clock;
    }

    /** How much longer this IP is locked out, or 0. */
    public synchronized long lockedForMillis(String ip) {
        Long until = lockedUntil.get(ip);
        if (until == null) {
            return 0;
        }
        long left = until - clock.millis();
        if (left <= 0) {
            lockedUntil.remove(ip);
            return 0;
        }
        return left;
    }

    /** Count a wrong password; true when this one tipped the IP into a lockout. */
    public synchronized boolean recordFailure(String ip) {
        long now = clock.millis();
        if (failures.size() > 1000) { // a wide scan: forget IPs that have gone quiet
            failures.values().removeIf(q -> now - q.peekLast() > WINDOW.toMillis());
        }
        Deque<Long> recent = failures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        recent.addLast(now);
        while (now - recent.peekFirst() > WINDOW.toMillis()) {
            recent.pollFirst();
        }
        if (recent.size() >= MAX_FAILURES) {
            failures.remove(ip);
            lockedUntil.put(ip, now + LOCK.toMillis());
            return true;
        }
        return false;
    }

    public synchronized void recordSuccess(String ip) {
        failures.remove(ip);
    }

    public synchronized void unlock(String ip) {
        lockedUntil.remove(ip);
        failures.remove(ip);
    }

    /** Currently locked IPs → epoch millis when each lock ends. */
    public synchronized Map<String, Long> lockedIps() {
        long now = clock.millis();
        lockedUntil.values().removeIf(until -> until <= now);
        return Map.copyOf(lockedUntil);
    }
}
