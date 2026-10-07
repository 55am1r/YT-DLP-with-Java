package com.predatorfx.ytdlpweb.admin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The downloads log as a spreadsheet. Titles come from YouTube and the URLs from teammates,
 * so every text cell is defused: one starting with = + - @ would run as a formula when the
 * admin opens the file, so it gets a leading apostrophe.
 */
final class AdminCsv {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String HEADER = "Time,Device,IP,Route,Location,Title,URL,Type,Format,Quality,Clip,"
            + "Status,Size (bytes),Saved to devices,Error";

    private AdminCsv() {
    }

    static String downloads(List<DownloadRecord> rows, Function<String, String> deviceName,
                            Function<String, String> placeOfIp, ZoneId zone) {
        StringBuilder sb = new StringBuilder(HEADER).append("\r\n");
        for (DownloadRecord r : rows) {
            String quality = r.getQualityLabel() != null ? r.getQualityLabel()
                    : r.getHeight() != null ? r.getHeight() + "p" : null;
            sb.append(String.join(",",
                    TIME.format(Instant.ofEpochMilli(r.getAt()).atZone(zone)),
                    cell(deviceName.apply(r.getDeviceId())),
                    cell(r.getIp()),
                    cell(r.getVia()),
                    cell(placeOfIp.apply(r.getIp())),
                    cell(r.getTitle()),
                    cell(r.getUrl()),
                    cell(r.getKind()),
                    cell(r.getFormat() == null ? null : r.getFormat().toUpperCase(Locale.ROOT)),
                    cell(quality),
                    cell(r.getClip()),
                    cell(r.getStatus()),
                    r.getFileSize() == null ? "" : String.valueOf(r.getFileSize()),
                    String.valueOf(r.getSavedBy().size()),
                    cell(r.getError())))
                    .append("\r\n");
        }
        return sb.toString();
    }

    static String cell(String s) {
        if (s == null) {
            return "";
        }
        String v = s;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
