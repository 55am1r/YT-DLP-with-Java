package com.predatorfx.ytdlpweb.model;

/** Lifecycle of a download job, surfaced to the UI. */
public enum JobStatus {
    QUEUED,
    CHECKING_UPDATES,
    ANALYZING,
    DOWNLOADING,
    PROCESSING,   // ffmpeg merge / thumbnail embed / metadata
    PACKAGING,    // zipping a playlist into one file
    COMPLETED,
    FAILED
}
