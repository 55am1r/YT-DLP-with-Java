package com.predatorfx.ytdlpweb.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Premiere and QuickTime only open an MP4 holding H.264 video with AAC audio. VP9, AV1
 * and Opus all produced the "unsupported compression type" error users reported.
 */
class UniversalCodecTest {

    @Test
    void h264WithAacPlaysEverywhere() {
        assertTrue(YtDlpService.isUniversal("h264", "aac"));
    }

    @Test
    void silentH264IsFine() {
        assertTrue(YtDlpService.isUniversal("h264", null));
        assertTrue(YtDlpService.isUniversal("h264", ""));
    }

    @Test
    void whatYouTubeServesAboveTenEightyIsNot() {
        assertFalse(YtDlpService.isUniversal("vp9", "aac"));
        assertFalse(YtDlpService.isUniversal("av1", "aac"));
        assertFalse(YtDlpService.isUniversal("hevc", "aac"));
    }

    /** The audio rule behind Advanced MP4s too: only AAC (or no audio at all) is safe. */
    @Test
    void onlyAacSurvivesInsideAnMp4() {
        assertFalse(YtDlpService.needsAac("aac"));
        assertFalse(YtDlpService.needsAac(null));  // a silent video has nothing to convert
        assertFalse(YtDlpService.needsAac(""));
        assertTrue(YtDlpService.needsAac("opus"));
        assertTrue(YtDlpService.needsAac("vorbis"));
    }

    @Test
    void opusAudioIsNotEvenWithH264() {
        assertFalse(YtDlpService.isUniversal("h264", "opus"));
        assertFalse(YtDlpService.isUniversal(null, "aac"));
    }
}
