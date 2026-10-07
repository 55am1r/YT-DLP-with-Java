package com.predatorfx.ytdlpweb.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A trimmed clip uses yt-dlp's --download-sections, which seeks inside the video.
 * YouTube does not expose seekable data for a live stream that is still live, or one
 * that has only just ended and is still being processed ("post_live"): the section
 * download then fetches almost nothing and the merge dies with "Error opening output
 * files: Invalid argument" / "ffmpeg exited with code 183". The full download of the
 * same video is fine — it is only clipping that breaks — so we refuse the clip up front
 * with a clear reason instead of letting it fail cryptically.
 */
class ClipAvailabilityTest {

    @Test
    void liveAndStillProcessingCannotBeClipped() {
        assertTrue(YtDlpService.clippingUnavailable("is_live"));
        assertTrue(YtDlpService.clippingUnavailable("post_live"));
    }

    @Test
    void finishedAndNeverLiveVideosClipFine() {
        assertFalse(YtDlpService.clippingUnavailable("not_live")); // processed VOD, incl. a former live
        assertFalse(YtDlpService.clippingUnavailable(null));       // yt-dlp reported nothing — a normal video
        assertFalse(YtDlpService.clippingUnavailable(""));
        assertFalse(YtDlpService.clippingUnavailable("NA"));
    }

    @Test
    void theStatusIsReadCaseInsensitively() {
        assertTrue(YtDlpService.clippingUnavailable("POST_LIVE"));
        assertTrue(YtDlpService.clippingUnavailable("Is_Live"));
    }
}
