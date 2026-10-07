package com.predatorfx.ytdlpweb.admin;

/** One address a device has used, and when — the admin's "where has this device been" trail. */
public class IpSeen {

    private String ip;
    private long firstSeen;
    private long lastSeen;
    private int hits;

    public String getIp() { return ip; }
    public long getFirstSeen() { return firstSeen; }
    public long getLastSeen() { return lastSeen; }
    public int getHits() { return hits; }

    public void setIp(String ip) { this.ip = ip; }
    public void setFirstSeen(long firstSeen) { this.firstSeen = firstSeen; }
    public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }
    public void setHits(int hits) { this.hits = hits; }

    public IpSeen copy() {
        IpSeen c = new IpSeen();
        c.ip = ip;
        c.firstSeen = firstSeen;
        c.lastSeen = lastSeen;
        c.hits = hits;
        return c;
    }
}
