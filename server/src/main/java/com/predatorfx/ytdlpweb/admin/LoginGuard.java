package com.predatorfx.ytdlpweb.admin;

import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Slows down password guessing. The site is reachable from the whole internet through the
 * tunnel and the passwords are short, so after 10 wrong logins from one address within 15
 * minutes that address is refused for 10 minutes — the admin can lift it early.
 *
 * An attempt reserves its place before the (slow) credential check runs, so guesses fired in
 * parallel can't all slip past before any is counted. IPv6 addresses count per /64 — one
 * household, or one attacker rotating through their whole range. Keyed by address, not
 * device: a guesser simply drops cookies.
 */
@Component
public class LoginGuard {

    static final int MAX_FAILURES = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK = Duration.ofMinutes(10);

    private final Clock clock;
    private final Map<String, Deque<Long>> failures = new HashMap<>();
    private final Map<String, Integer> inFlight = new HashMap<>();
    private final Map<String, Long> lockedUntil = new HashMap<>();

    public LoginGuard() {
        this(Clock.systemUTC());
    }

    LoginGuard(Clock clock) {
        this.clock = clock;
    }

    /** Reserve a login attempt; false = locked out, or as many attempts already underway as are left. */
    public synchronized boolean tryBegin(String ip) {
        String key = key(ip);
        long now = clock.millis();
        if (lockedFor(key, now) > 0) {
            return false;
        }
        int busy = inFlight.getOrDefault(key, 0);
        if (recentFailures(key, now) + busy >= MAX_FAILURES) {
            return false;
        }
        inFlight.put(key, busy + 1);
        return true;
    }

    /** The attempt was right: forget this address's failures. */
    public synchronized void succeeded(String ip) {
        String key = key(ip);
        release(key);
        failures.remove(key);
    }

    /** The attempt wasn't checked (server busy) — it doesn't count either way. */
    public synchronized void released(String ip) {
        release(key(ip));
    }

    /** The attempt was wrong; true when it tipped the address into a lockout. */
    public synchronized boolean failed(String ip) {
        String key = key(ip);
        long now = clock.millis();
        release(key);
        if (failures.size() > 1000) { // a wide scan: forget addresses that have gone quiet
            failures.values().removeIf(q -> now - q.peekLast() > WINDOW.toMillis());
        }
        failures.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(now);
        if (recentFailures(key, now) >= MAX_FAILURES) {
            failures.remove(key);
            lockedUntil.put(key, now + LOCK.toMillis());
            return true;
        }
        return false;
    }

    /** How much longer this address is locked out, or 0. */
    public synchronized long lockedForMillis(String ip) {
        return lockedFor(key(ip), clock.millis());
    }

    /** Lift a lockout — by address, or by a key as shown in {@link #lockedIps()}. */
    public synchronized void unlock(String ipOrKey) {
        String key = key(ipOrKey);
        lockedUntil.remove(key);
        failures.remove(key);
    }

    /** Locked addresses (an IPv6 one as its "…::/64") → epoch millis when each lock ends. */
    public synchronized Map<String, Long> lockedIps() {
        long now = clock.millis();
        lockedUntil.values().removeIf(until -> until <= now);
        return Map.copyOf(lockedUntil);
    }

    /** The bucket an address counts in: an IPv4 address itself, or an IPv6 address's /64. */
    static String key(String ip) {
        if (ip == null) {
            return "unknown";
        }
        if (ip.endsWith("::/64") || !ClientInfo.isIpLiteral(ip)) {
            return ip;
        }
        try {
            InetAddress a = InetAddress.getByName(ip); // a literal (checked above): no DNS
            if (a instanceof Inet6Address) {
                byte[] b = a.getAddress();
                return String.format("%x:%x:%x:%x::/64", word(b, 0), word(b, 2), word(b, 4), word(b, 6));
            }
            return a.getHostAddress();
        } catch (UnknownHostException e) {
            return ip;
        }
    }

    private static int word(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private long lockedFor(String key, long now) {
        Long until = lockedUntil.get(key);
        if (until == null) {
            return 0;
        }
        if (until <= now) {
            lockedUntil.remove(key);
            return 0;
        }
        return until - now;
    }

    private int recentFailures(String key, long now) {
        Deque<Long> recent = failures.get(key);
        if (recent == null) {
            return 0;
        }
        while (!recent.isEmpty() && now - recent.peekFirst() > WINDOW.toMillis()) {
            recent.pollFirst();
        }
        if (recent.isEmpty()) {
            failures.remove(key);
            return 0;
        }
        return recent.size();
    }

    private void release(String key) {
        int busy = inFlight.getOrDefault(key, 0);
        if (busy <= 1) {
            inFlight.remove(key);
        } else {
            inFlight.put(key, busy - 1);
        }
    }
}
