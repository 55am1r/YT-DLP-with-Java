package com.predatorfx.ytdlpweb.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoIpServiceTest {

    /** ipwho.is's answer for Google DNS, captured 2026-10-07. */
    private static final String IPWHOIS_8888 = """
            {"ip":"8.8.8.8","success":true,"type":"IPv4","continent":"North America","continent_code":"NA",
             "country":"United States","country_code":"US","region":"California","region_code":"CA",
             "city":"San Jose","latitude":37.3393939,"longitude":-121.8949553,"is_eu":false,"postal":"95025",
             "connection":{"asn":15169,"org":"Google LLC","isp":"Google LLC","domain":"google.com"},
             "timezone":{"id":"America/Los_Angeles","abbr":"PDT","is_dst":true,"offset":-25200,"utc":"-07:00"}}""";
    /** ipinfo.io's answer for the same address, captured 2026-10-07. */
    private static final String IPINFO_8888 = """
            {"ip":"8.8.8.8","hostname":"dns.google","city":"Mountain View","region":"California","country":"US",
             "loc":"38.0088,-122.1175","org":"AS15169 Google LLC","postal":"94043",
             "timezone":"America/Los_Angeles","anycast":true}""";
    /** What ipwho.is says about the caller itself (the Mac); made-up values. */
    private static final String IPWHOIS_SELF = """
            {"ip":"49.37.10.20","success":true,"country":"India","country_code":"IN","region":"Telangana",
             "city":"Hyderabad","latitude":17.385,"longitude":78.4867,"connection":{"isp":"Example Fibernet"},
             "timezone":{"id":"Asia/Kolkata"}}""";

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock();
    private final List<String> calls = new ArrayList<>();

    private GeoIpService service(Function<String, String> fetch) {
        return new GeoIpService(true, url -> {
            calls.add(url);
            return fetch.apply(url);
        }, clock, dir.resolve("geo-cache.json"), Runnable::run);
    }

    private static UncheckedIOException down(String why) {
        return new UncheckedIOException(new IOException(why));
    }

    @Test
    void parsesIpWhoIs() {
        GeoInfo g = GeoIpService.parseIpWhoIs(ActivityStore.JSON.readTree(IPWHOIS_8888), "8.8.8.8", 42);
        assertEquals("San Jose", g.city());
        assertEquals("California", g.region());
        assertEquals("United States", g.country());
        assertEquals("US", g.countryCode());
        assertEquals(37.3393939, g.lat(), 1e-9);
        assertEquals(-121.8949553, g.lon(), 1e-9);
        assertEquals("Google LLC", g.isp());
        assertEquals("America/Los_Angeles", g.timezone());
        assertEquals("ipwho.is", g.source());
        assertEquals(42, g.fetchedAt());
        assertEquals("San Jose, California, United States", g.place());
    }

    @Test
    void parsesIpInfo() {
        GeoInfo g = GeoIpService.parseIpInfo(ActivityStore.JSON.readTree(IPINFO_8888), "8.8.8.8", 42);
        assertEquals("Mountain View", g.city());
        assertEquals("United States", g.country());
        assertEquals("US", g.countryCode());
        assertEquals(38.0088, g.lat(), 1e-9);
        assertEquals(-122.1175, g.lon(), 1e-9);
        assertEquals("Google LLC", g.isp());
        assertEquals("ipinfo.io", g.source());
    }

    @Test
    void refusalsParseToNull() {
        assertNull(GeoIpService.parseIpWhoIs(ActivityStore.JSON.readTree("{\"success\":false,\"message\":\"Reserved range\"}"), "x", 1));
        assertNull(GeoIpService.parseIpInfo(ActivityStore.JSON.readTree("{\"ip\":\"10.0.0.1\",\"bogon\":true}"), "x", 1));
        assertNull(GeoIpService.parseIpInfo(ActivityStore.JSON.readTree("{\"error\":{\"title\":\"Rate limit exceeded\"}}"), "x", 1));
    }

    @Test
    void looksUpOnceThenServesFromCache() {
        GeoIpService geo = service(url -> IPWHOIS_8888);
        assertTrue(geo.cached("8.8.8.8").isEmpty());

        geo.request("8.8.8.8");
        geo.request("8.8.8.8");

        assertEquals(List.of("https://ipwho.is/8.8.8.8"), calls);
        assertEquals("San Jose", geo.cached("8.8.8.8").orElseThrow().city());
    }

    @Test
    void fallsBackThenBacksOff() {
        GeoIpService geo = service(url -> {
            if (url.contains("ipwho.is")) {
                throw down("429 Too Many Requests");
            }
            return IPINFO_8888;
        });
        geo.request("8.8.8.8");
        assertEquals("Mountain View", geo.cached("8.8.8.8").orElseThrow().city());

        calls.clear();
        GeoIpService offline = service(url -> {
            throw down("no network");
        });
        offline.request("1.1.1.1");
        assertEquals(2, calls.size(), "one try per provider");
        offline.request("1.1.1.1");
        assertEquals(2, calls.size(), "no new tries within 30 minutes");
        clock.advance(Duration.ofMinutes(31));
        offline.request("1.1.1.1");
        assertEquals(4, calls.size());
        assertTrue(offline.cached("1.1.1.1").isEmpty());
    }

    @Test
    void privateIpResolvesToServerLocation() {
        GeoIpService geo = service(url -> IPWHOIS_SELF);

        geo.request("192.168.1.20");
        geo.request("10.0.0.5");

        assertEquals(List.of("https://ipwho.is/"), calls, "one lookup of the Mac's own address serves every LAN user");
        GeoInfo g = geo.cached("192.168.1.20").orElseThrow();
        assertEquals("192.168.1.20", g.ip());
        assertEquals("Hyderabad", g.city());
        assertTrue(g.approximate());
    }

    @Test
    void staleEntriesAreRefreshed() {
        GeoIpService geo = service(url -> IPWHOIS_8888);
        geo.request("8.8.8.8");
        clock.advance(Duration.ofDays(15));
        assertEquals("San Jose", geo.cached("8.8.8.8").orElseThrow().city(), "stale is still better than nothing");
        geo.request("8.8.8.8");
        assertEquals(2, calls.size());
    }

    @Test
    void cacheSurvivesRestart() {
        service(url -> IPWHOIS_8888).request("8.8.8.8");

        GeoIpService restarted = service(url -> {
            throw new AssertionError("should have come from geo-cache.json: " + url);
        });
        restarted.request("8.8.8.8");

        assertEquals("San Jose", restarted.cached("8.8.8.8").orElseThrow().city());
    }

    @Test
    void disabledOrGarbageNeverFetches() {
        GeoIpService off = new GeoIpService(false, url -> {
            throw new AssertionError(url);
        }, clock, dir.resolve("off.json"), Runnable::run);
        off.request("8.8.8.8");
        assertTrue(off.cached("8.8.8.8").isEmpty());

        GeoIpService geo = service(url -> IPWHOIS_8888);
        geo.request(null);
        geo.request("evil.example");
        assertTrue(calls.isEmpty());
    }
}
