package com.predatorfx.ytdlpweb.admin;

/**
 * A banner the admin shows to everyone — "Server restarts at 6 pm".
 *
 * @param level "info" or "warn"
 * @param at    when it was posted; the browser uses it to remember a dismissal
 */
public record Announcement(String text, String level, long at) {
}
