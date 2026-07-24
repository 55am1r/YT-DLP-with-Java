package com.predatorfx.ytdlpweb.web;

import com.predatorfx.ytdlpweb.model.AnalyzeResult;
import com.predatorfx.ytdlpweb.model.DownloadRequest;
import com.predatorfx.ytdlpweb.model.Job;
import com.predatorfx.ytdlpweb.model.JobStatus;
import com.predatorfx.ytdlpweb.service.JobService;
import com.predatorfx.ytdlpweb.service.YtDlpService;
import com.predatorfx.ytdlpweb.service.YtDlpUpdateService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** REST surface consumed by the React UI. */
@RestController
@RequestMapping("/api")
public class DownloadController {

    private final YtDlpService ytdlp;
    private final JobService jobs;
    private final YtDlpUpdateService updates;

    public DownloadController(YtDlpService ytdlp, JobService jobs, YtDlpUpdateService updates) {
        this.ytdlp = ytdlp;
        this.jobs = jobs;
        this.updates = updates;
    }

    public record UrlRequest(String url) {}

    /** Probe a URL: title, thumbnail, duration, playlist?, quality choices. */
    @PostMapping("/analyze")
    public AnalyzeResult analyze(@RequestBody UrlRequest body) {
        if (body == null || body.url() == null || body.url().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A URL is required");
        }
        try {
            return ytdlp.analyze(body.url().trim());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Queue a download job. */
    @PostMapping("/jobs")
    public Job start(@RequestBody DownloadRequest req) {
        if (req == null || req.url() == null || req.url().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A URL is required");
        }
        return jobs.submit(req);
    }

    @GetMapping("/jobs")
    public List<Job> list() {
        return jobs.all();
    }

    @GetMapping("/jobs/{id}")
    public Job status(@PathVariable String id) {
        Job job = jobs.get(id);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such job");
        }
        return job;
    }

    /** Live progress stream for a job. */
    @GetMapping("/jobs/{id}/events")
    public SseEmitter events(@PathVariable String id) {
        return jobs.subscribe(id);
    }

    @PostMapping("/jobs/{id}/pause")
    public Map<String, Object> pause(@PathVariable String id) {
        return Map.of("ok", jobs.pause(id));
    }

    @PostMapping("/jobs/{id}/resume")
    public Map<String, Object> resume(@PathVariable String id) {
        return Map.of("ok", jobs.resume(id));
    }

    @PostMapping("/jobs/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        return Map.of("ok", jobs.cancel(id));
    }

    public record ClearRequest(List<String> ids) {}

    /** Clear the server files for one link's downloads (not the whole session). */
    @PostMapping("/jobs/clear")
    public Map<String, Object> clear(@RequestBody(required = false) ClearRequest body) {
        int n = (body == null || body.ids() == null) ? 0 : jobs.clear(body.ids());
        return Map.of("cleared", n);
    }

    /** Stream the finished file to the requester's browser as a download. */
    @GetMapping("/jobs/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable String id) throws IOException {
        Job job = jobs.get(id);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such job");
        }
        if (job.getStatus() != JobStatus.COMPLETED || job.getFilePath() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "File is not ready yet");
        }
        Path path = job.getFilePath();
        if (!Files.exists(path)) {
            throw new ResponseStatusException(HttpStatus.GONE, "File has expired and was cleaned up");
        }

        String fileName = job.getFileName() != null ? job.getFileName() : path.getFileName().toString();
        MediaType type = MediaTypeFactory.getMediaType(fileName).orElse(MediaType.APPLICATION_OCTET_STREAM);
        ContentDisposition cd = ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(cd);

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(type)
                .contentLength(Files.size(path))
                .body(new FileSystemResource(path));
    }

    /** yt-dlp version status. Pass ?refresh=true to force a check now. */
    @GetMapping("/ytdlp/status")
    public YtDlpUpdateService.UpdateStatus ytdlpStatus(@RequestParam(defaultValue = "false") boolean refresh) {
        return refresh ? updates.ensureFresh(true) : updates.current();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }
}
