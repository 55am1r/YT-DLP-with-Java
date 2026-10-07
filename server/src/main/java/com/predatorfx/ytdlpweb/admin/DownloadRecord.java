package com.predatorfx.ytdlpweb.admin;

import java.util.ArrayList;
import java.util.List;

/**
 * The permanent record of one download job — what was asked for, by which device, and how it
 * ended. Jobs themselves vanish with their files (2 h TTL, and every restart); this does not.
 *
 * Treated as immutable once ActivityService holds it: an update swaps in a changed copy(), so
 * the admin queries can read a snapshot without locking. Each version is appended to
 * downloads.jsonl and the last line for a job wins on load.
 */
public class DownloadRecord {

    private String jobId;
    private long at;
    private String deviceId;
    private String ip;
    private String via;
    private String url;
    private String title;
    private String kind;            // "audio" | "video"
    private String format;          // mp3 / m4a / mp4 / mkv …
    private Integer height;         // requested max height for video
    private String codec;
    private String clip;            // "00:01:00–00:02:30" when trimmed
    private boolean playlist;
    private Integer itemCount;      // selected playlist items
    private String status;          // a JobStatus name
    private String error;
    private String fileName;
    private Long fileSize;
    private String qualityLabel;
    private String container;
    private Long elapsedMs;
    private Long finishedAt;
    private int attempts = 1;
    private List<String> savedBy = new ArrayList<>(); // devices that fetched the finished file
    private Long savedAt;           // first time anyone fetched it

    public String getJobId() { return jobId; }
    public long getAt() { return at; }
    public String getDeviceId() { return deviceId; }
    public String getIp() { return ip; }
    public String getVia() { return via; }
    public String getUrl() { return url; }
    public String getTitle() { return title; }
    public String getKind() { return kind; }
    public String getFormat() { return format; }
    public Integer getHeight() { return height; }
    public String getCodec() { return codec; }
    public String getClip() { return clip; }
    public boolean isPlaylist() { return playlist; }
    public Integer getItemCount() { return itemCount; }
    public String getStatus() { return status; }
    public String getError() { return error; }
    public String getFileName() { return fileName; }
    public Long getFileSize() { return fileSize; }
    public String getQualityLabel() { return qualityLabel; }
    public String getContainer() { return container; }
    public Long getElapsedMs() { return elapsedMs; }
    public Long getFinishedAt() { return finishedAt; }
    public int getAttempts() { return attempts; }
    public List<String> getSavedBy() { return savedBy; }
    public Long getSavedAt() { return savedAt; }

    public void setJobId(String jobId) { this.jobId = jobId; }
    public void setAt(long at) { this.at = at; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public void setIp(String ip) { this.ip = ip; }
    public void setVia(String via) { this.via = via; }
    public void setUrl(String url) { this.url = url; }
    public void setTitle(String title) { this.title = title; }
    public void setKind(String kind) { this.kind = kind; }
    public void setFormat(String format) { this.format = format; }
    public void setHeight(Integer height) { this.height = height; }
    public void setCodec(String codec) { this.codec = codec; }
    public void setClip(String clip) { this.clip = clip; }
    public void setPlaylist(boolean playlist) { this.playlist = playlist; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }
    public void setStatus(String status) { this.status = status; }
    public void setError(String error) { this.error = error; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public void setQualityLabel(String qualityLabel) { this.qualityLabel = qualityLabel; }
    public void setContainer(String container) { this.container = container; }
    public void setElapsedMs(Long elapsedMs) { this.elapsedMs = elapsedMs; }
    public void setFinishedAt(Long finishedAt) { this.finishedAt = finishedAt; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public void setSavedBy(List<String> savedBy) { this.savedBy = savedBy == null ? new ArrayList<>() : savedBy; }
    public void setSavedAt(Long savedAt) { this.savedAt = savedAt; }

    public DownloadRecord copy() {
        DownloadRecord c = new DownloadRecord();
        c.jobId = jobId;
        c.at = at;
        c.deviceId = deviceId;
        c.ip = ip;
        c.via = via;
        c.url = url;
        c.title = title;
        c.kind = kind;
        c.format = format;
        c.height = height;
        c.codec = codec;
        c.clip = clip;
        c.playlist = playlist;
        c.itemCount = itemCount;
        c.status = status;
        c.error = error;
        c.fileName = fileName;
        c.fileSize = fileSize;
        c.qualityLabel = qualityLabel;
        c.container = container;
        c.elapsedMs = elapsedMs;
        c.finishedAt = finishedAt;
        c.attempts = attempts;
        c.savedBy = new ArrayList<>(savedBy);
        c.savedAt = savedAt;
        return c;
    }
}
