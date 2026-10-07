package com.predatorfx.ytdlpweb.admin;

/**
 * Turns a user-agent into something an admin can recognise at a glance — "iPhone · Safari".
 * Deliberately coarse: everyone shares one team login, so this label (plus the place) is how
 * the admin tells devices apart until they give one a nickname.
 */
public final class UserAgents {

    private UserAgents() {
    }

    /** @param deviceType "phone", "tablet" or "desktop" */
    public record Parsed(String os, String browser, String deviceType) {
        public String label() {
            return "Unknown".equals(os) ? "Unknown device" : os + " · " + browser;
        }
    }

    /** @param touch the browser reported a touch screen — the only tell of an iPad in desktop mode */
    public static Parsed parse(String ua, boolean touch) {
        if (ua == null || ua.isBlank()) {
            return new Parsed("Unknown", "Other", "desktop");
        }
        String os;
        String type = "desktop";
        if (ua.contains("iPhone") || ua.contains("iPod")) {
            os = "iPhone";
            type = "phone";
        } else if (ua.contains("iPad")) {
            os = "iPad";
            type = "tablet";
        } else if (ua.contains("Android")) {
            os = "Android";
            type = ua.contains("Mobile") ? "phone" : "tablet";
        } else if (ua.contains("CrOS")) {
            os = "ChromeOS";
        } else if (ua.contains("Windows")) {
            os = "Windows";
        } else if (ua.contains("Macintosh") || ua.contains("Mac OS X")) {
            // iPadOS asks for desktop sites with a Mac user-agent; Macs have no touch screen.
            os = touch ? "iPad" : "Mac";
            type = touch ? "tablet" : "desktop";
        } else if (ua.contains("Linux") || ua.contains("X11")) {
            os = "Linux";
        } else {
            os = "Unknown";
        }
        return new Parsed(os, browser(ua), type);
    }

    // Most specific first: Edge, Opera and Samsung all also say "Chrome", and nearly
    // everything says "Safari".
    private static String browser(String ua) {
        if (ua.contains("Edg/") || ua.contains("EdgA/") || ua.contains("EdgiOS/")) {
            return "Edge";
        }
        if (ua.contains("OPR/") || ua.contains("Opera")) {
            return "Opera";
        }
        if (ua.contains("SamsungBrowser/")) {
            return "Samsung Internet";
        }
        if (ua.contains("CriOS/") || ua.contains("Chrome/") || ua.contains("Chromium/")) {
            return "Chrome";
        }
        if (ua.contains("FxiOS/") || ua.contains("Firefox/")) {
            return "Firefox";
        }
        if (ua.contains("Safari/") && ua.contains("Version/")) {
            return "Safari";
        }
        return "Other";
    }
}
