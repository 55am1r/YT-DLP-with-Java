package com.predatorfx.ytdlpweb.admin;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Who is really on the other end of a request, and how they reached the Mac.
 *
 * Internet users arrive through cloudflared, which runs on this Mac and connects over
 * loopback — the socket says 127.0.0.1 and the real address is in CF-Connecting-IP. That
 * header is believed only when the socket IS loopback: otherwise anyone on the LAN could
 * send it and pass as any IP, dodging a block or faking where they are.
 *
 * @param via "internet" (tunnel or a public address), "lan" (same network as the Mac) or
 *            "local" (a browser on the Mac itself)
 */
public record ClientInfo(String ip, String via) {

    private static final Pattern IPV4 = Pattern.compile(
            "((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
    // Hex digits and colons (plus an optional dotted tail like ::ffff:1.2.3.4) — never a host
    // name, so the InetAddress parsing below can only parse a literal, never ask DNS.
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*");

    public static ClientInfo of(HttpServletRequest req) {
        return resolve(req.getRemoteAddr(), req.getHeader("CF-Connecting-IP"), req.getHeader("X-Forwarded-For"));
    }

    public static ClientInfo resolve(String remoteAddr, String cfConnectingIp, String xForwardedFor) {
        String socket = normalize(remoteAddr);
        if (socket == null) {
            return new ClientInfo("unknown", "internet");
        }
        if (isLoopback(socket)) {
            String forwarded = firstIp(cfConnectingIp);
            if (forwarded == null) {
                forwarded = firstIp(xForwardedFor);
            }
            if (forwarded != null) {
                return new ClientInfo(forwarded, isPrivate(forwarded) ? "lan" : "internet");
            }
            return new ClientInfo(socket, "local");
        }
        return new ClientInfo(socket, isPrivate(socket) ? "lan" : "internet");
    }

    /** LAN, loopback, link-local, carrier-grade NAT or IPv6 unique-local — no public location. */
    public static boolean isPrivate(String ip) {
        InetAddress a = parse(ip);
        if (a == null) {
            return false;
        }
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64; // 100.64.0.0/10
        }
        return (b[0] & 0xFE) == 0xFC; // fc00::/7
    }

    public static boolean isIpLiteral(String s) {
        return parse(s) != null;
    }

    private static boolean isLoopback(String ip) {
        InetAddress a = parse(ip);
        return a != null && a.isLoopbackAddress();
    }

    private static InetAddress parse(String s) {
        if (s == null || !(IPV4.matcher(s).matches() || IPV6.matcher(s).matches())) {
            return null;
        }
        try {
            return InetAddress.getByName(s);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static String firstIp(String header) {
        if (header == null) {
            return null;
        }
        int comma = header.indexOf(',');
        String ip = normalize(comma >= 0 ? header.substring(0, comma) : header);
        return ip != null && isIpLiteral(ip) ? ip : null;
    }

    private static String normalize(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        String s = ip.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.regionMatches(true, 0, "::ffff:", 0, 7) && IPV4.matcher(s.substring(7)).matches()) {
            s = s.substring(7);
        }
        return s;
    }
}
