package com.predatorfx.ytdlpweb.model;

/**
 * One selectable video quality shown in the UI.
 *
 * @param height   pixel height (e.g. 2160)
 * @param label    display label (e.g. "2160p (4K)")
 * @param note     extra info (e.g. codec / fps), may be null
 * @param filesize approximate size in bytes, may be null
 */
public record VideoFormatOption(int height, String label, String note, Long filesize) {
}
