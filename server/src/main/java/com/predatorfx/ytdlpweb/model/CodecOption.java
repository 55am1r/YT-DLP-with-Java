package com.predatorfx.ytdlpweb.model;

import java.util.List;

/**
 * A video compression choice offered in the "Advanced" tab.
 *
 * @param id          stable key sent back on the download request ("none", "hevc", …)
 * @param label       what the pill shows
 * @param note        one-line trade-off, shown under the label
 * @param badge       optional highlight: "Best quality" or "Best savings"
 * @param containers  containers this codec is legal in — webm cannot hold H.264/H.265,
 *                    so the UI hides mismatched combinations instead of failing later
 * @param sizeHint    measured size relative to the original, as a ready-to-show phrase
 *                    (e.g. "65–90% of original") — a range, because the saving depends
 *                    on resolution and on what codec YouTube served in the first place
 * @param hardware    true when the Mac's media engine does the encoding (much faster)
 */
public record CodecOption(
        String id,
        String label,
        String note,
        String badge,
        List<String> containers,
        String sizeHint,
        boolean hardware) {
}
