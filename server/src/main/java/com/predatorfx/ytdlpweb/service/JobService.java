package com.predatorfx.ytdlpweb.service;

import com.predatorfx.ytdlpweb.model.DownloadRequest;
import com.predatorfx.ytdlpweb.model.Job;
import com.predatorfx.ytdlpweb.model.JobStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Owns the bounded worker pool + job registry and streams live progress to
 * browsers over Server-Sent Events. The concurrency cap is what keeps 30-40
 * teammates from overwhelming the Mac with simultaneous 4K downloads.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final YtDlpService ytdlp;
    private final YtDlpUpdateService updates;

    @Value("${ytdlp.max-concurrent-jobs:3}")
    private int maxConcurrent;

    @Value("${ytdlp.file-ttl-minutes:120}")
    private long ttlMinutes;

    private ExecutorService pool;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public JobService(YtDlpService ytdlp, YtDlpUpdateService updates) {
        this.ytdlp = ytdlp;
        this.updates = updates;
    }

    @PostConstruct
    void init() throws IOException {
        pool = Executors.newFixedThreadPool(Math.max(1, maxConcurrent));
        Files.createDirectories(ytdlp.workDir());
        wipeWorkDir();
        log.info("JobService ready · max {} concurrent jobs · work dir {}", maxConcurrent, ytdlp.workDir());

        // Proactively warm the freshness guard so the UI badge shows real status and
        // the first user's job doesn't have to wait for a possible brew upgrade.
        Thread warm = new Thread(() -> {
            try {
                updates.ensureFresh(false);
            } catch (Exception ignored) {
                // best effort
            }
        }, "ytdlp-warmup");
        warm.setDaemon(true);
        warm.start();
    }

    @PreDestroy
    void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    public Job submit(DownloadRequest req) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Job job = new Job(id, req);
        jobs.put(id, job);
        pool.submit(() -> run(job));
        return job;
    }

    public Job get(String id) {
        return jobs.get(id);
    }

    public List<Job> all() {
        return jobs.values().stream()
                .sorted(Comparator.comparingLong(Job::getCreatedAt).reversed())
                .toList();
    }

    // ----------------------------------------------------------- CONTROLS

    public boolean pause(String id) {
        Job job = jobs.get(id);
        if (job == null || job.getStatus() != JobStatus.DOWNLOADING) {
            return false;
        }
        if (ytdlp.pause(id)) {
            job.setStatus(JobStatus.PAUSED);
            job.setPhase("Paused");
            push(job);
            return true;
        }
        return false;
    }

    public boolean resume(String id) {
        Job job = jobs.get(id);
        if (job == null || job.getStatus() != JobStatus.PAUSED) {
            return false;
        }
        if (ytdlp.resume(id)) {
            job.setStatus(JobStatus.DOWNLOADING);
            job.setPhase("Resuming…");
            push(job);
            return true;
        }
        return false;
    }

    public boolean cancel(String id) {
        Job job = jobs.get(id);
        if (job == null || isTerminal(job.getStatus())) {
            return false;
        }
        job.setCanceled(true);
        job.setPhase("Canceling…");
        push(job);
        // If a process is live, killing it lets the worker finalize CANCELED + cleanup.
        boolean running = ytdlp.cancel(id);
        if (!running) {
            // Not started yet (queued) — mark it canceled now; the worker will bail early.
            job.setStatus(JobStatus.CANCELED);
            job.setPhase("Canceled");
            job.setFinishedAt(System.currentTimeMillis());
            push(job);
            completeEmitters(id);
        }
        return true;
    }

    private void run(Job job) {
        try {
            if (job.isCanceled()) {
                return; // canceled while still queued
            }
            // Keep yt-dlp fresh in the background — never block the download on it.
            updates.refreshInBackground();

            ytdlp.download(job, this::push);
        } catch (Exception e) {
            if (job.isCanceled()) {
                job.setStatus(JobStatus.CANCELED);
                job.setPhase("Canceled");
                job.setFinishedAt(System.currentTimeMillis());
                push(job);
            } else {
                job.setStatus(JobStatus.FAILED);
                job.setError(e.getMessage() == null ? e.toString() : e.getMessage());
                job.setPhase("Failed");
                job.setFinishedAt(System.currentTimeMillis());
                log.warn("Job {} failed: {}", job.getId(), e.toString());
                push(job);
            }
        } finally {
            completeEmitters(job.getId());
        }
    }

    // ----------------------------------------------------------------- SSE

    public SseEmitter subscribe(String jobId) {
        SseEmitter emitter = new SseEmitter(0L); // never time out
        Job job = jobs.get(jobId);
        if (job == null) {
            try {
                emitter.send(SseEmitter.event().name("error").data("No such job"));
            } catch (IOException ignored) {
                // client already gone
            }
            emitter.complete();
            return emitter;
        }
        emitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(jobId, emitter));
        emitter.onTimeout(() -> removeEmitter(jobId, emitter));
        emitter.onError(e -> removeEmitter(jobId, emitter));
        trySend(emitter, job);
        if (isTerminal(job.getStatus())) {
            emitter.complete();
        }
        return emitter;
    }

    private void push(Job job) {
        List<SseEmitter> list = emitters.get(job.getId());
        if (list != null) {
            for (SseEmitter e : list) {
                trySend(e, job);
            }
        }
    }

    private void trySend(SseEmitter e, Job job) {
        try {
            e.send(SseEmitter.event().name("update").data(job, MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            removeEmitter(job.getId(), e);
        }
    }

    private void completeEmitters(String jobId) {
        List<SseEmitter> list = emitters.get(jobId);
        if (list != null) {
            for (SseEmitter e : list) {
                try {
                    e.complete();
                } catch (Exception ignored) {
                    // already complete
                }
            }
        }
    }

    private void removeEmitter(String jobId, SseEmitter e) {
        List<SseEmitter> list = emitters.get(jobId);
        if (list != null) {
            list.remove(e);
        }
    }

    private static boolean isTerminal(JobStatus s) {
        return s == JobStatus.COMPLETED || s == JobStatus.FAILED || s == JobStatus.CANCELED;
    }

    // ------------------------------------------------------------- CLEANUP

    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    public void cleanupExpired() {
        long ttl = Duration.ofMinutes(ttlMinutes).toMillis();
        long now = System.currentTimeMillis();
        for (Job job : jobs.values()) {
            Long fin = job.getFinishedAt();
            if (fin != null && now - fin > ttl) {
                deleteJob(job.getId());
            }
        }
    }

    private void deleteJob(String id) {
        Job job = jobs.remove(id);
        emitters.remove(id);
        if (job == null) {
            return;
        }
        deleteDir(ytdlp.workDir().resolve(id));
        log.info("Cleaned up expired job {}", id);
    }

    private void wipeWorkDir() {
        try (Stream<Path> s = Files.list(ytdlp.workDir())) {
            s.filter(Files::isDirectory).forEach(JobService::deleteDir);
        } catch (IOException e) {
            log.warn("Could not wipe work dir on startup: {}", e.toString());
        }
    }

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
