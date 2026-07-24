package com.predatorfx.ytdlpweb.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.file.Path;

/**
 * A running/finished download job. Fields are volatile because the worker thread
 * mutates them while polling/status threads read them.
 */
public class Job {

    private final String id;
    private final DownloadRequest request;

    private volatile JobStatus status = JobStatus.QUEUED;
    private volatile int progress = 0;          // 0-100 for the current item
    private volatile String phase = "Queued";   // human-readable status line
    private volatile Integer playlistIndex;     // current item (1-based) for playlists
    private volatile Integer playlistCount;     // total items for playlists
    private volatile String title;              // resolved media title
    private volatile String fileName;           // final delivered file name
    private volatile String error;

    // Live transfer info (parsed from yt-dlp output)
    private volatile String speed;              // e.g. "12.3MiB/s"
    private volatile String eta;                // e.g. "00:42"

    // Set once the file is ready — what the UI shows on a finished card
    private volatile String container;          // actual extension, e.g. "mp4"
    private volatile Integer height;            // real video height, null for audio
    private volatile Long fileSize;             // bytes
    private volatile Long elapsedMs;            // how long the server took
    private volatile Long expiresAt;            // when the temp file is deleted

    @JsonIgnore
    private volatile Path filePath;             // file streamed to the browser

    private final long createdAt = System.currentTimeMillis();
    private volatile Long startedAt;
    private volatile Long finishedAt;

    @JsonIgnore
    private volatile boolean canceled = false;

    public Job(String id, DownloadRequest request) {
        this.id = id;
        this.request = request;
        // Title is known from the analyze step, so a card never has to say "Preparing…".
        this.title = (request != null && request.title() != null && !request.title().isBlank())
                ? request.title() : null;
    }

    public String getId() { return id; }
    public DownloadRequest getRequest() { return request; }
    public JobStatus getStatus() { return status; }
    public int getProgress() { return progress; }
    public String getPhase() { return phase; }
    public Integer getPlaylistIndex() { return playlistIndex; }
    public Integer getPlaylistCount() { return playlistCount; }
    public String getTitle() { return title; }
    public String getFileName() { return fileName; }
    public String getError() { return error; }
    public String getSpeed() { return speed; }
    public String getEta() { return eta; }
    public String getContainer() { return container; }
    public Integer getHeight() { return height; }
    public Long getFileSize() { return fileSize; }
    public Long getElapsedMs() { return elapsedMs; }
    public Long getExpiresAt() { return expiresAt; }
    public long getCreatedAt() { return createdAt; }
    public Long getStartedAt() { return startedAt; }
    public Long getFinishedAt() { return finishedAt; }

    @JsonIgnore
    public Path getFilePath() { return filePath; }

    public void setStatus(JobStatus status) { this.status = status; }
    public void setProgress(int progress) { this.progress = progress; }
    public void setPhase(String phase) { this.phase = phase; }
    public void setPlaylistIndex(Integer playlistIndex) { this.playlistIndex = playlistIndex; }
    public void setPlaylistCount(Integer playlistCount) { this.playlistCount = playlistCount; }
    public void setTitle(String title) { this.title = title; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public void setError(String error) { this.error = error; }
    public void setSpeed(String speed) { this.speed = speed; }
    public void setEta(String eta) { this.eta = eta; }
    public void setContainer(String container) { this.container = container; }
    public void setHeight(Integer height) { this.height = height; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public void setElapsedMs(Long elapsedMs) { this.elapsedMs = elapsedMs; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
    public void setFilePath(Path filePath) { this.filePath = filePath; }
    public void setStartedAt(Long startedAt) { this.startedAt = startedAt; }
    public void setFinishedAt(Long finishedAt) { this.finishedAt = finishedAt; }

    @JsonIgnore
    public boolean isCanceled() { return canceled; }
    public void setCanceled(boolean canceled) { this.canceled = canceled; }
}
