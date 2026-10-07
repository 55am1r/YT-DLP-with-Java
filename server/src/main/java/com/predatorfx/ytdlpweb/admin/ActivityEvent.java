package com.predatorfx.ytdlpweb.admin;

/**
 * One line of the activity log: logins (good and bad), lookups, location answers and admin
 * actions. A failed login stores only when/where — never what was typed.
 *
 * @param detail free text for the admin ("Locked for 10 min", "blocked device …")
 * @param url    what was looked up, for ANALYZE
 * @param title  its title, for ANALYZE
 */
public record ActivityEvent(long at, String type, String deviceId, String ip, String detail, String url, String title) {

    public static final String LOGIN = "LOGIN";
    public static final String ADMIN_LOGIN = "ADMIN_LOGIN";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOCKED_OUT = "LOCKED_OUT";
    public static final String LOGOUT = "LOGOUT";
    public static final String ANALYZE = "ANALYZE";
    public static final String LOCATION = "LOCATION";
    public static final String ADMIN_ACTION = "ADMIN_ACTION";
}
