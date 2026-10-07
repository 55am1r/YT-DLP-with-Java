package com.predatorfx.ytdlpweb.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserAgentsTest {

    private static final String MAC_SAFARI = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/18.6 Safari/605.1.15";
    private static final String ANDROID_CHROME = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36";
    private static final String WINDOWS_CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

    private static void expect(String label, String type, String ua, boolean touch) {
        UserAgents.Parsed p = UserAgents.parse(ua, touch);
        assertEquals(label, p.label(), ua);
        assertEquals(type, p.deviceType(), ua);
    }

    @Test
    void iPhoneSafari() {
        expect("iPhone · Safari", "phone", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1", true);
    }

    @Test
    void iPhoneChromeAndFirefoxAndEdge() {
        String base = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) ";
        expect("iPhone · Chrome", "phone", base + "CriOS/140.0.7339.101 Mobile/15E148 Safari/604.1", true);
        expect("iPhone · Firefox", "phone", base + "FxiOS/143.0 Mobile/15E148 Safari/605.1.15", true);
        expect("iPhone · Edge", "phone", base + "EdgiOS/140.0.3485.94 Version/18.0 Mobile/15E148 Safari/604.1", true);
    }

    /** iPadOS asks for desktop sites with a Mac user-agent; only the touch screen gives it away. */
    @Test
    void iPadPosingAsMac() {
        expect("iPad · Safari", "tablet", MAC_SAFARI, true);
        expect("Mac · Safari", "desktop", MAC_SAFARI, false);
    }

    @Test
    void androidPhoneAndTablet() {
        expect("Android · Chrome", "phone", ANDROID_CHROME, true);
        expect("Android · Chrome", "tablet", ANDROID_CHROME.replace(" Mobile ", " "), true);
    }

    @Test
    void samsungInternet() {
        expect("Android · Samsung Internet", "phone", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) SamsungBrowser/28.0 Chrome/130.0.0.0 Mobile Safari/537.36", true);
    }

    @Test
    void windowsBrowsers() {
        expect("Windows · Chrome", "desktop", WINDOWS_CHROME, false);
        expect("Windows · Edge", "desktop", WINDOWS_CHROME + " Edg/140.0.0.0", false);
        expect("Windows · Opera", "desktop", WINDOWS_CHROME + " OPR/122.0.0.0", false);
        expect("Windows · Firefox", "desktop",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:143.0) Gecko/20100101 Firefox/143.0", false);
    }

    @Test
    void macAndLinuxAndChromeOs() {
        expect("Mac · Chrome", "desktop", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36", false);
        expect("Mac · Firefox", "desktop",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:143.0) Gecko/20100101 Firefox/143.0", false);
        expect("Linux · Firefox", "desktop", "Mozilla/5.0 (X11; Linux x86_64; rv:143.0) Gecko/20100101 Firefox/143.0", false);
        expect("ChromeOS · Chrome", "desktop", "Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36", false);
    }

    @Test
    void unknownAgents() {
        expect("Unknown device", "desktop", null, false);
        expect("Unknown device", "desktop", "", false);
        expect("Unknown device", "desktop", "curl/8.7.1", false);
    }
}
