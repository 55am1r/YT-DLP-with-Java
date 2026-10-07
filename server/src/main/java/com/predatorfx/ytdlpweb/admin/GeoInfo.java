package com.predatorfx.ytdlpweb.admin;

import java.util.ArrayList;
import java.util.List;

/**
 * Where an IP address is, according to a free geo-IP service — roughly the city, often the
 * ISP's exchange rather than the building.
 *
 * @param source      which service answered ("ipwho.is" / "ipinfo.io")
 * @param approximate true when this is the Mac's own location standing in for a LAN address
 */
public record GeoInfo(String ip, String city, String region, String country, String countryCode,
                      Double lat, Double lon, String isp, String timezone, String source, long fetchedAt,
                      boolean approximate) {

    /** "Hyderabad, Telangana, India" — blanks and repeats ("Singapore, Singapore") dropped. */
    public String place() {
        List<String> parts = new ArrayList<>();
        for (String p : new String[] {city, region, country}) {
            if (p != null && !p.isBlank() && !parts.contains(p)) {
                parts.add(p);
            }
        }
        return String.join(", ", parts);
    }

    GeoInfo approximateFor(String ip) {
        return new GeoInfo(ip, city, region, country, countryCode, lat, lon, isp, timezone, source, fetchedAt, true);
    }
}
