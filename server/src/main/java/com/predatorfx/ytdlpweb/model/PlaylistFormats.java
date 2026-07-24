package com.predatorfx.ytdlpweb.model;

import java.util.List;

/**
 * Result of probing every item in a playlist to see whether they all offer the same
 * resolutions.
 *
 * A single zip is only honest when the whole playlist can actually be downloaded at one
 * setting — otherwise "Download all at 4K" quietly gives some items 4K and others
 * whatever they happened to have. When {@code uniform} is false the UI drops the zip
 * option and makes the user pick items individually.
 *
 * @param uniform  true when every item exposes exactly the same set of heights
 * @param common   the heights shared by all items — what the zip may be offered at
 * @param probed   how many items were successfully read
 * @param total    how many items the playlist has
 * @param reason   why zipping is unavailable, for the UI to show verbatim
 */
public record PlaylistFormats(
        boolean uniform,
        List<VideoFormatOption> common,
        int probed,
        int total,
        String reason) {
}
