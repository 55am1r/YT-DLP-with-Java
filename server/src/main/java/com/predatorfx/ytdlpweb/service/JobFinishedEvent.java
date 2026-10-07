package com.predatorfx.ytdlpweb.service;

import com.predatorfx.ytdlpweb.model.Job;

/**
 * Published when a job reaches COMPLETED, FAILED or CANCELED — again after each retry — so
 * the admin records learn how a download ended without JobService knowing they exist.
 */
public record JobFinishedEvent(Job job) {
}
