package com.predatorfx.ytdlpweb.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand, so expiry rules can be checked without sleeping. */
final class TestClock extends Clock {

    private Instant now = Instant.parse("2026-10-07T10:00:00Z");

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

    void advance(Duration d) {
        now = now.plus(d);
    }
}
