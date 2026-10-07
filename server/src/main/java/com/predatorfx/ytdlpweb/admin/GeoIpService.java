package com.predatorfx.ytdlpweb.admin;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/**
 * Turns an IP address into a city, for devices that haven't shared a precise location.
 *
 * Asks ipwho.is first and ipinfo.io if that fails — both are free, HTTPS, need no account.
 * Lookups run on one background thread and never hold up a request; answers are cached for
 * 14 days (in geo-cache.json, so restarts don't re-ask) and a failed address isn't retried
 * for 30 minutes. A LAN or loopback address has no public location of its own, so it gets the
 * Mac's — looked up once — marked approximate.
 */
@Service
public class GeoIpService {

    private static final Logger log = LoggerFactory.getLogger(GeoIpService.class);

    static final Duration CACHE_TTL = Duration.ofDays(14);
    static final Duration RETRY_AFTER = Duration.ofMinutes(30);
    /** Cache key for the Mac's own public location. */
    private static final String SELF = "self";

    private final boolean enabled;
    private final Function<String, String> fetch;
    private final Clock clock;
    private final Path cacheFile;
    private final Executor executor;
    private final Map<String, GeoInfo> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> failedAt = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    @Autowired
    public GeoIpService(@Value("${app.geo.enabled:true}") boolean enabled,
                        @Value("${app.admin.data-dir:${user.home}/.ytdlp-web/admin-data}") String dataDir) {
        this(enabled, httpFetcher(), Clock.systemUTC(), Path.of(dataDir).resolve("geo-cache.json"),
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "geo-lookup");
                    t.setDaemon(true);
                    return t;
                }));
    }

    GeoIpService(boolean enabled, Function<String, String> fetch, Clock clock, Path cacheFile, Executor executor) {
        this.enabled = enabled;
        this.fetch = fetch;
        this.clock = clock;
        this.cacheFile = cacheFile;
        this.executor = executor;
        loadCache();
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService pool) {
            pool.shutdownNow();
        }
    }

    /** What is known about this address now — possibly stale, never waits for the network. */
    public Optional<GeoInfo> cached(String ip) {
        if (!ClientInfo.isIpLiteral(ip)) {
            return Optional.empty();
        }
        if (ClientInfo.isPrivate(ip)) {
            GeoInfo self = cache.get(SELF);
            return self == null ? Optional.empty() : Optional.of(self.approximateFor(ip));
        }
        return Optional.ofNullable(cache.get(ip));
    }

    /** Look this address up in the background unless the cache already has a fresh answer. */
    public void request(String ip) {
        if (!enabled || !ClientInfo.isIpLiteral(ip)) {
            return;
        }
        String key = ClientInfo.isPrivate(ip) ? SELF : ip;
        long now = clock.millis();
        GeoInfo have = cache.get(key);
        if (have != null && now - have.fetchedAt() < CACHE_TTL.toMillis()) {
            return;
        }
        Long failed = failedAt.get(key);
        if (failed != null && now - failed < RETRY_AFTER.toMillis()) {
            return;
        }
        if (!inFlight.add(key)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    lookup(key);
                } finally {
                    inFlight.remove(key);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(key); // shutting down
        }
    }

    private void lookup(String key) {
        long now = clock.millis();
        boolean self = SELF.equals(key);
        GeoInfo info = attempt(self ? "https://ipwho.is/" : "https://ipwho.is/" + key,
                body -> parseIpWhoIs(ActivityStore.JSON.readTree(body), key, now));
        if (info == null) {
            info = attempt(self ? "https://ipinfo.io/json" : "https://ipinfo.io/" + key + "/json",
                    body -> parseIpInfo(ActivityStore.JSON.readTree(body), key, now));
        }
        if (info == null) {
            failedAt.put(key, now);
            return;
        }
        failedAt.remove(key);
        cache.put(key, self ? info.approximateFor(info.ip()) : info);
        saveCache();
    }

    private GeoInfo attempt(String url, Function<String, GeoInfo> parse) {
        try {
            return parse.apply(fetch.apply(url));
        } catch (RuntimeException e) { // offline, rate-limited, or an answer we can't read
            log.info("Geo lookup via {} failed: {}", URI.create(url).getHost(), e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ parsing

    static GeoInfo parseIpWhoIs(JsonNode n, String ip, long now) {
        JsonNode ok = n == null ? null : n.get("success");
        if (ok == null || !ok.isBoolean() || !ok.booleanValue()) {
            return null;
        }
        JsonNode conn = n.get("connection");
        JsonNode tz = n.get("timezone");
        return new GeoInfo(orElse(text(n, "ip"), ip), text(n, "city"), text(n, "region"), text(n, "country"),
                upper(text(n, "country_code")), number(n, "latitude"), number(n, "longitude"),
                conn == null ? null : orElse(text(conn, "isp"), text(conn, "org")),
                tz == null ? null : text(tz, "id"), "ipwho.is", now, false);
    }

    static GeoInfo parseIpInfo(JsonNode n, String ip, long now) {
        if (n == null || n.has("error") || n.has("bogon")) {
            return null;
        }
        Double lat = null;
        Double lon = null;
        String loc = text(n, "loc");
        if (loc != null) {
            String[] p = loc.split(",");
            try {
                if (p.length == 2) {
                    lat = Double.parseDouble(p[0].trim());
                    lon = Double.parseDouble(p[1].trim());
                }
            } catch (NumberFormatException ignored) {
                lat = null;
                lon = null;
            }
        }
        String code = upper(text(n, "country"));
        if (lat == null && code == null) {
            return null;
        }
        String org = text(n, "org");
        return new GeoInfo(orElse(text(n, "ip"), ip), text(n, "city"), text(n, "region"),
                code == null ? null : Locale.of("", code).getDisplayCountry(Locale.ENGLISH), code, lat, lon,
                org == null ? null : org.replaceFirst("^AS\\d+\\s+", ""), text(n, "timezone"), "ipinfo.io", now, false);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asString();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Double number(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isNumber() ? v.doubleValue() : null;
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static String orElse(String a, String b) {
        return a != null ? a : b;
    }

    // ------------------------------------------------------------------ cache file + HTTP

    private void loadCache() {
        if (cacheFile == null || !Files.exists(cacheFile)) {
            return;
        }
        try {
            Map<String, GeoInfo> saved = ActivityStore.JSON.readValue(
                    Files.readString(cacheFile, StandardCharsets.UTF_8), new TypeReference<Map<String, GeoInfo>>() { });
            if (saved != null) {
                cache.putAll(saved);
            }
        } catch (IOException | JacksonException e) {
            log.warn("Ignoring unreadable geo cache {}: {}", cacheFile, e.toString());
        }
    }

    private synchronized void saveCache() {
        if (cacheFile == null) {
            return;
        }
        Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        try {
            ActivityStore.ensurePrivateDir(cacheFile.getParent());
            Files.writeString(tmp, ActivityStore.JSON.writeValueAsString(cache), StandardCharsets.UTF_8);
            Files.move(tmp, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | JacksonException e) {
            log.warn("Could not save geo cache {}: {}", cacheFile, e.toString());
        }
    }

    private static Function<String, String> httpFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return url -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .header("User-Agent", "EZ-Tube admin panel (self-hosted)")
                    .GET()
                    .build();
            try {
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) {
                    throw new IOException("HTTP " + res.statusCode());
                }
                return res.body();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UncheckedIOException(new IOException("interrupted"));
            }
        };
    }
}
