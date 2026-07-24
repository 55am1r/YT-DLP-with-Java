package com.predatorfx.ytdlpweb.model;

import java.util.List;

/**
 * Metadata returned after probing a URL, so the UI can show the video/playlist
 * and offer the right quality choices before a download starts.
 *
 * @param music       true for music sources (or anything with no video streams) — the UI
 *                    then offers audio formats only
 * @param items       playlist entries, empty for a single video
 * @param videoWidth  native pixel width of the video, so the UI can size the thumbnail to
 *                    the real aspect ratio instead of forcing everything into 16:9
 * @param videoHeight native pixel height
 */
public record AnalyzeResult(
        String url,
        boolean playlist,
        String title,
        String uploader,
        Long durationSeconds,
        String thumbnail,
        boolean music,
        Integer playlistCount,
        List<VideoFormatOption> videoFormats,
        List<PlaylistItem> items,
        Integer videoWidth,
        Integer videoHeight) {
}
