package com.predatorfx.ytdlpweb.admin;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminCsvTest {

    /** A title like =HYPERLINK(...) would otherwise run as a formula when the admin opens the export. */
    @Test
    void escapesFormulaCells() {
        assertEquals("'=1+1", AdminCsv.cell("=1+1"));
        assertEquals("'@x", AdminCsv.cell("@x"));
        assertEquals("'+x", AdminCsv.cell("+x"));
        assertEquals("'-x", AdminCsv.cell("-x"));
        assertEquals("\"'=HYPERLINK(\"\"http://evil\"\",\"\"click\"\")\"",
                AdminCsv.cell("=HYPERLINK(\"http://evil\",\"click\")"));
    }

    @Test
    void quotesCommasQuotesAndNewlines() {
        assertEquals("\"a,b\"", AdminCsv.cell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", AdminCsv.cell("say \"hi\""));
        assertEquals("\"two\nlines\"", AdminCsv.cell("two\nlines"));
        assertEquals("plain", AdminCsv.cell("plain"));
        // Spreadsheets set to ';' as the separator would split here and run the second half.
        assertEquals("\"a;=1+1\"", AdminCsv.cell("a;=1+1"));
        assertEquals("", AdminCsv.cell(null));
    }

    @Test
    void downloadsSheetHasAHeaderAndOneRowPerRecord() {
        DownloadRecord r = new DownloadRecord();
        r.setJobId("j1");
        r.setAt(Instant.parse("2026-10-07T10:00:00Z").toEpochMilli());
        r.setDeviceId("dev-1");
        r.setIp("49.37.10.20");
        r.setVia("internet");
        r.setTitle("Song, live");
        r.setUrl("https://youtu.be/dQw4w9WgXcQ");
        r.setKind("audio");
        r.setFormat("mp3");
        r.setStatus("COMPLETED");
        r.setFileSize(1234L);
        r.getSavedBy().add("dev-1");

        String csv = AdminCsv.downloads(List.of(r), id -> "Ravi's iPhone", ip -> "Hyderabad, Telangana, India",
                ZoneId.of("Asia/Kolkata"));
        String[] lines = csv.split("\r\n");

        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("Time,Device,IP,Route,Location,Title,URL,"), lines[0]);
        assertTrue(lines[1].startsWith("2026-10-07 15:30:00,Ravi's iPhone,49.37.10.20,internet,"
                + "\"Hyderabad, Telangana, India\",\"Song, live\",https://youtu.be/dQw4w9WgXcQ,audio,MP3,"), lines[1]);
        assertTrue(lines[1].contains(",COMPLETED,1234,1,"), lines[1]);
    }
}
