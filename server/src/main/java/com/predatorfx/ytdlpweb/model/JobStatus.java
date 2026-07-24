package com.predatorfx.ytdlpweb.model;

/** Lifecycle of a download job, surfaced to the UI. */
public enum JobStatus {
    QUEUED,
    CHECKING_UPDATES,
    ANALYZING,
    DOWNLOADING,
    PAUSED,
    PROCESSING,   // ffmpeg merge / thumbnail embed / metadata
    COMPRESSING,  // re-encoding to the chosen codec (Advanced tab)
    PACKAGING,    // zipping a playlist into one file
    COMPLETED,
    CANCELED,
    FAILED
}
