package com.predatorfx.ytdlpweb.model;

/**
 * A download request from the browser.
 *
 * @param url          the YouTube (or other yt-dlp supported) URL
 * @param kind         "audio" or "video"
 * @param height       target max resolution height for video (e.g. 2160); ignored for audio
 * @param container    "mp4", "mkv" or "webm" for video; ignored for audio
 * @param playlist     if true and the URL is a playlist, download every item
 * @param audioFormat  audio container for audio jobs, defaults to "mp3"
 */
public record DownloadRequest(
        String url,
        String kind,
        Integer height,
        String container,
        boolean playlist,
        String audioFormat) {

    public boolean isAudio() {
        return kind == null || kind.equalsIgnoreCase("audio");
    }

    public String containerOrDefault() {
        return (container == null || container.isBlank()) ? "mp4" : container.toLowerCase();
    }

    public String audioFormatOrDefault() {
        return (audioFormat == null || audioFormat.isBlank()) ? "mp3" : audioFormat.toLowerCase();
    }

    public int heightOrDefault() {
        return (height == null || height <= 0) ? 1080 : height;
    }
}
