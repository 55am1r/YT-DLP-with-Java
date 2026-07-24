package com.predatorfx.ytdlpweb.model;

import java.util.List;

/**
 * A download request from the browser.
 *
 * @param url          the YouTube (or other yt-dlp supported) URL
 * @param kind         "audio" or "video"
 * @param height       target max resolution height for video (e.g. 2160); ignored for audio
 * @param container    "mp4", "mkv" or "webm" for video; ignored for audio
 * @param playlist     if true and the URL is a playlist, download every item
 * @param audioFormat  audio container for audio jobs, defaults to "mp3"
 * @param title        media title from the analyze step, so the job card never shows "Preparing…"
 * @param startTime    optional clip start, "HH:MM:SS" or seconds — trims the download
 * @param endTime      optional clip end, "HH:MM:SS" or seconds
 * @param items        optional 1-based playlist item numbers to download (multi-select)
 * @param codec        compression choice from the Advanced tab; "none" keeps the source
 *                     streams untouched, which is what the Auto tab always sends
 */
public record DownloadRequest(
        String url,
        String kind,
        Integer height,
        String container,
        boolean playlist,
        String audioFormat,
        String title,
        String startTime,
        String endTime,
        List<Integer> items,
        String codec) {

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

    public String codecOrDefault() {
        return (codec == null || codec.isBlank()) ? "none" : codec.trim().toLowerCase();
    }

    /** The container the finished file will actually use. */
    public String targetExtension() {
        return isAudio() ? audioFormatOrDefault() : containerOrDefault();
    }

    public boolean hasClipRange() {
        return (startTime != null && !startTime.isBlank()) || (endTime != null && !endTime.isBlank());
    }

    public boolean hasItemSelection() {
        return items != null && !items.isEmpty();
    }

    /**
     * Identity of the *output* this request would produce. Two requests with the same
     * signature would download the same bytes twice, so the UI asks before repeating
     * one — that is the whole point of the duplicate guard.
     */
    public String signature() {
        String sel = hasItemSelection()
                ? items().stream().sorted().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("")
                : "";
        return String.join("|",
                url() == null ? "" : url().trim(),
                isAudio() ? "audio" : "video",
                isAudio() ? audioFormatOrDefault() : String.valueOf(heightOrDefault()),
                isAudio() ? "" : containerOrDefault(),
                isAudio() ? "" : codecOrDefault(),
                String.valueOf(playlist()),
                sel,
                startTime() == null ? "" : startTime().trim(),
                endTime() == null ? "" : endTime().trim());
    }
}
