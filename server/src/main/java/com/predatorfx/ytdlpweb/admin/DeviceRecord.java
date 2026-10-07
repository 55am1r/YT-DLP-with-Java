package com.predatorfx.ytdlpweb.admin;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the admin panel knows about one browser. The team shares a single login, so a
 * device — tracked by the ez_device cookie — is the closest thing to a "user" there is.
 * Persisted in devices.json; the request path updates it under ActivityService's lock.
 */
public class DeviceRecord {

    public static final String CONSENT_UNKNOWN = "unknown";         // not asked yet
    public static final String CONSENT_GRANTED = "granted";         // shares its precise location
    public static final String CONSENT_DECLINED = "declined";       // said "No thanks" on our card
    public static final String CONSENT_DENIED = "denied";           // refused the browser's prompt
    public static final String CONSENT_UNAVAILABLE = "unavailable"; // can't ask here (LAN http://, no GPS API)

    private String id;
    private long firstSeen;
    private long lastSeen;
    private int visits;
    private String lastIp;
    private String via;
    private String userAgent;
    private String os;
    private String browser;
    private String deviceType;
    private String nickname;        // set by the admin
    private boolean blocked;
    private boolean admin;          // has been used to open the admin panel
    private String timezone;        // what the browser says, e.g. "Asia/Kolkata"
    private String language;
    private String screen;          // "390x844"
    private boolean touch;
    private String consent = CONSENT_UNKNOWN;
    private Double lat;             // precise location, only with consent
    private Double lon;
    private Double accuracy;        // metres
    private Long locatedAt;
    private List<IpSeen> ips = new ArrayList<>();

    public String getId() { return id; }
    public long getFirstSeen() { return firstSeen; }
    public long getLastSeen() { return lastSeen; }
    public int getVisits() { return visits; }
    public String getLastIp() { return lastIp; }
    public String getVia() { return via; }
    public String getUserAgent() { return userAgent; }
    public String getOs() { return os; }
    public String getBrowser() { return browser; }
    public String getDeviceType() { return deviceType; }
    public String getNickname() { return nickname; }
    public boolean isBlocked() { return blocked; }
    public boolean isAdmin() { return admin; }
    public String getTimezone() { return timezone; }
    public String getLanguage() { return language; }
    public String getScreen() { return screen; }
    public boolean isTouch() { return touch; }
    public String getConsent() { return consent; }
    public Double getLat() { return lat; }
    public Double getLon() { return lon; }
    public Double getAccuracy() { return accuracy; }
    public Long getLocatedAt() { return locatedAt; }
    public List<IpSeen> getIps() { return ips; }

    public void setId(String id) { this.id = id; }
    public void setFirstSeen(long firstSeen) { this.firstSeen = firstSeen; }
    public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }
    public void setVisits(int visits) { this.visits = visits; }
    public void setLastIp(String lastIp) { this.lastIp = lastIp; }
    public void setVia(String via) { this.via = via; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public void setOs(String os) { this.os = os; }
    public void setBrowser(String browser) { this.browser = browser; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public void setBlocked(boolean blocked) { this.blocked = blocked; }
    public void setAdmin(boolean admin) { this.admin = admin; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public void setLanguage(String language) { this.language = language; }
    public void setScreen(String screen) { this.screen = screen; }
    public void setTouch(boolean touch) { this.touch = touch; }
    public void setConsent(String consent) { this.consent = consent; }
    public void setLat(Double lat) { this.lat = lat; }
    public void setLon(Double lon) { this.lon = lon; }
    public void setAccuracy(Double accuracy) { this.accuracy = accuracy; }
    public void setLocatedAt(Long locatedAt) { this.locatedAt = locatedAt; }
    public void setIps(List<IpSeen> ips) { this.ips = ips == null ? new ArrayList<>() : ips; }

    /** Deep copy — what leaves ActivityService, so readers never see a half-made update. */
    public DeviceRecord copy() {
        DeviceRecord c = new DeviceRecord();
        c.id = id;
        c.firstSeen = firstSeen;
        c.lastSeen = lastSeen;
        c.visits = visits;
        c.lastIp = lastIp;
        c.via = via;
        c.userAgent = userAgent;
        c.os = os;
        c.browser = browser;
        c.deviceType = deviceType;
        c.nickname = nickname;
        c.blocked = blocked;
        c.admin = admin;
        c.timezone = timezone;
        c.language = language;
        c.screen = screen;
        c.touch = touch;
        c.consent = consent;
        c.lat = lat;
        c.lon = lon;
        c.accuracy = accuracy;
        c.locatedAt = locatedAt;
        c.ips = new ArrayList<>(ips.stream().map(IpSeen::copy).toList());
        return c;
    }
}
