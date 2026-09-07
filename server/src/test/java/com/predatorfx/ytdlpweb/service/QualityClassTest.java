package com.predatorfx.ytdlpweb.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The quality class must match what YouTube (and `yt-dlp -F`) calls each rendition.
 * The 2:1 rows below are the real format list of https://youtu.be/FtBHVrp9Fkc, which is
 * what exposed the bug: labelling by pixel height named its 4K stream "1920p".
 */
class QualityClassTest {

    @Test
    void twoToOneVideoIsNamedByWidth() {
        assertEquals(2160, YtDlpService.qualityClass(3840, 1920));
        assertEquals(1440, YtDlpService.qualityClass(2560, 1280));
        assertEquals(1080, YtDlpService.qualityClass(1920, 960));
        assertEquals(720, YtDlpService.qualityClass(1280, 640));
        assertEquals(480, YtDlpService.qualityClass(854, 428));
        assertEquals(360, YtDlpService.qualityClass(640, 320));
        assertEquals(240, YtDlpService.qualityClass(426, 214));
        assertEquals(144, YtDlpService.qualityClass(256, 128));
    }

    @Test
    void sixteenByNineIsUnchanged() {
        assertEquals(2160, YtDlpService.qualityClass(3840, 2160));
        assertEquals(1080, YtDlpService.qualityClass(1920, 1080));
        assertEquals(720, YtDlpService.qualityClass(1280, 720));
        assertEquals(360, YtDlpService.qualityClass(640, 360));
    }

    @Test
    void verticalAndSquareUseTheShortSide() {
        assertEquals(1080, YtDlpService.qualityClass(1080, 1920)); // Shorts
        assertEquals(2160, YtDlpService.qualityClass(2160, 3840));
        assertEquals(1080, YtDlpService.qualityClass(1080, 1080)); // square
        assertEquals(1080, YtDlpService.qualityClass(1440, 1080)); // 4:3
    }

    /** Odd renditions land just off a rung; both of these are "480p" to YouTube. */
    @Test
    void nearlySixteenByNineSnapsToTheRung() {
        assertEquals(480, YtDlpService.qualityClass(872, 480));
        assertEquals(480, YtDlpService.qualityClass(854, 470));
    }

    /** Nothing to go on but the height — fall back rather than invent a class. */
    @Test
    void missingWidthFallsBackToHeight() {
        assertEquals(1080, YtDlpService.qualityClass(0, 1080));
    }
}
