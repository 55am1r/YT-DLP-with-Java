package com.predatorfx.ytdlpweb.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Every line below was captured from a real yt-dlp run with the app's own flags.
 * This parser runs inside the output-reading loop, so anything it throws on would
 * abort a live download — the "NA", float and malformed cases are the ones that matter.
 */
class ProgressParseTest {

    @Test
    void plainTick() {
        var p = YtDlpService.Progress.parse("[EZ]downloading|130048|57588768|  0.2%|3707188.224445411|15");
        assertEquals(130048L, p.downloaded());
        assertEquals(57588768L, p.total());
        assertEquals(3707188.224445411, p.speed(), 0.001);
        assertEquals("00:15", p.eta());
        assertEquals(0.2, p.percent(), 0.001);
        assertFalse(p.finished());
    }

    /** The first few ticks of every stream, and every tick after a resume. */
    @Test
    void speedAndEtaAreNaAtTheStart() {
        var p = YtDlpService.Progress.parse("[EZ]downloading|1024|57588768|  0.0%|NA|NA");
        assertEquals(1024L, p.downloaded());
        assertEquals(57588768L, p.total());
        assertNull(p.speed());
        assertNull(p.eta());
    }

    /** m3u8/HLS reports no total for the whole transfer — a real path for this app. */
    @Test
    void unknownTotal() {
        var p = YtDlpService.Progress.parse("[EZ]downloading|232744|NA| 100.0%|2688854.05|0");
        assertEquals(232744L, p.downloaded());
        assertNull(p.total());
        assertEquals("00:00", p.eta());
    }

    /** On this tick yt-dlp redefines speed as total/elapsed — an average, not a rate. */
    @Test
    void finishedTickIsFlagged() {
        var p = YtDlpService.Progress.parse("[EZ]finished|38801943|38801943|100.0%|33488520.74|NA");
        assertTrue(p.finished());
    }

    @Test
    void longEtaGetsAnHourField() {
        var p = YtDlpService.Progress.parse("[EZ]downloading|1024|999999999|  0.0%|1000.0|3725");
        assertEquals("1:02:05", p.eta());
    }

    /** The video stream names its codec; that is how Auto learns it will have to convert. */
    @Test
    void videoStreamNamesItsCodec() {
        var p = YtDlpService.Progress.parse("[EZ]downloading|1024|57588768|  0.0%|NA|NA|vp09.00.51.08");
        assertEquals("vp09.00.51.08", p.vcodec());
    }

    /** "none" is the audio stream, "NA" is unknown, and older lines have no field at all. */
    @Test
    void audioUnknownAndMissingCodecAreNull() {
        assertNull(YtDlpService.Progress.parse("[EZ]downloading|1024|99|  0.0%|NA|NA|none").vcodec());
        assertNull(YtDlpService.Progress.parse("[EZ]downloading|1024|99|  0.0%|NA|NA|NA").vcodec());
        assertNull(YtDlpService.Progress.parse("[EZ]downloading|1024|99|  0.0%|NA|NA").vcodec());
    }

    /** Only a real non-H.264 video stream is a reason to convert — never audio or an unknown. */
    @Test
    void onlyNonH264VideoNeedsConverting() {
        assertTrue(YtDlpService.needsH264Conversion("vp09.00.51.08"));
        assertTrue(YtDlpService.needsH264Conversion("av01.0.13M.08"));
        assertTrue(YtDlpService.needsH264Conversion("hev1.1.6.L120"));
        assertFalse(YtDlpService.needsH264Conversion("avc1.640028"));
        assertFalse(YtDlpService.needsH264Conversion(null));
    }

    /** Must return null, never throw — a throw here kills the download's read loop. */
    @Test
    void malformedLinesAreIgnored() {
        assertNull(YtDlpService.Progress.parse("[EZ]downloading|1024"));
        assertNull(YtDlpService.Progress.parse("[EZ]"));
        assertNull(YtDlpService.Progress.parse("[EZ]downloading|NA|NA|NA|NA|NA"));
    }
}
