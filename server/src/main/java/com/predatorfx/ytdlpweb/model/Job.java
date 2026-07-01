package com.predatorfx.ytdlpweb.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.file.Path;

/**
 * A running/finished download job. Fields are volatile because the worker thread
 * mutates them while SSE/status threads read them.
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

    @JsonIgnore
    private volatile Path filePath;             // file streamed to the browser

    private final long createdAt = System.currentTimeMillis();
    private volatile Long finishedAt;

    @JsonIgnore
    private volatile boolean canceled = false;

    public Job(String id, DownloadRequest request) {
        this.id = id;
        this.request = request;
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
    public long getCreatedAt() { return createdAt; }
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
    public void setFilePath(Path filePath) { this.filePath = filePath; }
    public void setFinishedAt(Long finishedAt) { this.finishedAt = finishedAt; }

    @JsonIgnore
    public boolean isCanceled() { return canceled; }
    public void setCanceled(boolean canceled) { this.canceled = canceled; }
}
