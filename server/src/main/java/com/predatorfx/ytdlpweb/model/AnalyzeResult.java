package com.predatorfx.ytdlpweb.model;

import java.util.List;

/**
 * Metadata returned after probing a URL, so the UI can show the video/playlist
 * and offer the right quality choices before a download starts.
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
        List<VideoFormatOption> videoFormats) {
}
