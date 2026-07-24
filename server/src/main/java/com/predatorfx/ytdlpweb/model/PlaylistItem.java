package com.predatorfx.ytdlpweb.model;

/**
 * One entry of a playlist, so the UI can list items with a thumbnail instead of a
 * single opaque "download the whole playlist" checkbox.
 *
 * @param index           1-based position (matches yt-dlp's --playlist-items numbering)
 * @param title           entry title
 * @param durationSeconds length, may be null for unavailable entries
 * @param thumbnail       image URL
 * @param url             the entry's own watch URL, so the UI can probe its formats and
 *                        queue it as its own job with its own quality/container
 */
/**
 * @param thumbnailSrcset candidate thumbnail sizes as an HTML srcset ("url 320w, …"),
 *                        so a phone downloads a small image instead of scaling down a
 *                        1280px one
 */
public record PlaylistItem(int index, String title, Long durationSeconds, String thumbnail, String url,
                           String thumbnailSrcset) {
}
