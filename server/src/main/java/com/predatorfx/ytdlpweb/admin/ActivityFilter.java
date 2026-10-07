package com.predatorfx.ytdlpweb.admin;

import com.predatorfx.ytdlpweb.web.AuthService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Runs ahead of {@code AuthFilter} on every API call:
 * <ol>
 *   <li>works out the real client IP ({@link ClientInfo}) and the device (ez_device cookie,
 *       issued on first contact) and leaves both on the request for the controllers;</li>
 *   <li>turns away devices and IPs the admin has blocked (an admin session is never blocked,
 *       and /api/health stays open);</li>
 *   <li>marks signed-in devices as seen. Anonymous requests leave no record, so bots probing
 *       the public URL can't fill the device list.</li>
 * </ol>
 */
@Component
@Order(0)
public class ActivityFilter implements Filter {

    public static final String DEVICE_COOKIE = "ez_device";
    private static final String DEVICE_ATTR = "ez.device";
    private static final String CLIENT_ATTR = "ez.client";
    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    /** Browsers cap cookie lifetimes at 400 days. */
    private static final long DEVICE_COOKIE_MAX_AGE = 400L * 24 * 60 * 60;

    private final ActivityService activity;
    private final AuthService auth;
    private final AdminAuthService admin;
    private final SecureRandom random = new SecureRandom();

    public ActivityFilter(ActivityService activity, AuthService auth, AdminAuthService admin) {
        this.activity = activity;
        this.auth = auth;
        this.admin = admin;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            chain.doFilter(req, res);
            return;
        }

        // Nothing from the API belongs in a browser or Cloudflare cache — it is per-user, and
        // some of it (the CSV export, files) would otherwise be cacheable by extension.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");

        ClientInfo client = ClientInfo.of(request);
        request.setAttribute(CLIENT_ATTR, client);
        String deviceId = cookieDevice(request);
        if (deviceId == null) {
            deviceId = newDeviceId();
            response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(DEVICE_COOKIE, deviceId)
                    .httpOnly(true).path("/").sameSite("Lax").maxAge(DEVICE_COOKIE_MAX_AGE).build().toString());
        }
        request.setAttribute(DEVICE_ATTR, deviceId);

        boolean isAdmin = admin.validSession(request);
        if (!isAdmin && !path.equals("/api/health") && activity.isBlocked(deviceId, client.ip())) {
            refuseBlocked(response);
            return;
        }
        if (isAdmin || auth.validCookie(request)) {
            activity.touch(deviceId, client, request.getHeader(HttpHeaders.USER_AGENT), isAdmin);
        }
        chain.doFilter(req, res);
    }

    /** The 403 a blocked device or IP gets — also used by the MVC-level gate. */
    public static void refuseBlocked(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Access to EZ-Tube has been blocked by the admin.\",\"blocked\":true}");
    }

    /** This request's device id — set by the filter, or read from the cookie when it didn't run. */
    public static String deviceId(HttpServletRequest req) {
        return req.getAttribute(DEVICE_ATTR) instanceof String id ? id : cookieDevice(req);
    }

    public static ClientInfo client(HttpServletRequest req) {
        return req.getAttribute(CLIENT_ATTR) instanceof ClientInfo c ? c : ClientInfo.of(req);
    }

    private static String cookieDevice(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (DEVICE_COOKIE.equals(c.getName()) && c.getValue() != null && DEVICE_ID.matcher(c.getValue()).matches()) {
                return c.getValue();
            }
        }
        return null;
    }

    private String newDeviceId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
