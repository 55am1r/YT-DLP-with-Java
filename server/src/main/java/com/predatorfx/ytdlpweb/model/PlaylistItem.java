package com.predatorfx.ytdlpweb.model;

/**
 * One entry of a playlist, so the UI can list items with a thumbnail instead of a
 * single opaque "download the whole playlist" checkbox.
 *
 * @param index           1-based position (matches yt-dlp's --playlist-items numbering)
 * @param title           entry title
 * @param durationSeconds length, may be null for unavailable entries
 * @param thumbnail       image URL
 */
public record PlaylistItem(int index, String title, Long durationSeconds, String thumbnail) {
}
