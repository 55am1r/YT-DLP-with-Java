package com.predatorfx.ytdlpweb.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the admin records on disk so they outlive restarts (the work dir does not).
 *
 * <pre>
 *   devices.json     snapshot of every device, rewritten whole (small)
 *   downloads.jsonl  one line per change of a download; the last line for a job wins
 *   events.jsonl     append-only activity log
 *   settings.json    blocked IPs + the announcement
 * </pre>
 *
 * Whole-file writes go to a temp file and are renamed into place, so a crash leaves the old
 * file or the new one, never half of either. Appends are single whole lines; a line cut short
 * by a crash is skipped on load and the file is given a fresh line ending, so the next record
 * can't fuse with it. Failures are logged and swallowed — record-keeping must never break a
 * download.
 *
 * One process writes a data dir at a time ({@link #claim()}): a second EZ-Tube pointed at the
 * same folder — a test instance missing its own app.admin.data-dir — only reads, so it can't
 * overwrite the live server's records.
 */
public class ActivityStore {

    private static final Logger log = LoggerFactory.getLogger(ActivityStore.class);

    static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private static final String DEVICES = "devices.json";
    private static final String DOWNLOADS = "downloads.jsonl";
    private static final String EVENTS = "events.jsonl";
    private static final String SETTINGS = "settings.json";

    public record Settings(List<String> blockedIps, Announcement announcement) {
        public Settings {
            blockedIps = blockedIps == null ? List.of() : List.copyOf(blockedIps);
        }
    }

    public record Loaded(List<DeviceRecord> devices, List<DownloadRecord> downloads,
                         List<ActivityEvent> events, Settings settings) {
    }

    private final Path dir;
    private boolean writable = true;
    private FileChannel lockChannel;
    private FileLock lockHandle;

    public ActivityStore(Path dir) {
        this.dir = dir;
    }

    /** Take the data dir for this process; false (and read-only from now on) when another process has it. */
    public synchronized boolean claim() {
        try {
            ensureDir();
            lockChannel = FileChannel.open(dir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lockHandle = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lockHandle = null; // held by another store in this same JVM
        } catch (IOException e) {
            log.warn("Could not lock admin data dir {} ({}); continuing without the lock", dir, e.toString());
            return writable = true;
        }
        writable = lockHandle != null;
        if (!writable) {
            closeQuietly();
            log.error("Admin records in {} are in use by another EZ-Tube process — this one will only read them. "
                    + "Give it its own app.admin.data-dir.", dir);
        }
        return writable;
    }

    public synchronized void release() {
        try {
            if (lockHandle != null && lockHandle.isValid()) {
                lockHandle.release();
            }
        } catch (IOException ignored) {
            // closing the channel releases it anyway
        }
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException ignored) {
            // nothing more to do
        }
        lockChannel = null;
        lockHandle = null;
    }

    public Path dir() {
        return dir;
    }

    public synchronized Loaded load() {
        List<DeviceRecord> devices = readFile(DEVICES, new TypeReference<List<DeviceRecord>>() { }, List.of());
        Settings settings = readFile(SETTINGS, new TypeReference<Settings>() { }, new Settings(List.of(), null));
        // Later lines are newer versions of the same job; keep the first position, the last content.
        Map<String, DownloadRecord> byJob = new LinkedHashMap<>();
        for (DownloadRecord r : readLines(DOWNLOADS, DownloadRecord.class)) {
            if (r.getJobId() != null) {
                byJob.put(r.getJobId(), r);
            }
        }
        return new Loaded(new ArrayList<>(devices), new ArrayList<>(byJob.values()),
                readLines(EVENTS, ActivityEvent.class), settings);
    }

    public synchronized void saveDevices(Collection<DeviceRecord> devices) {
        if (!writable) {
            return;
        }
        writeAtomically(DEVICES, () -> JSON.writeValueAsString(List.copyOf(devices)));
    }

    public synchronized void saveSettings(Settings settings) {
        if (!writable) {
            return;
        }
        writeAtomically(SETTINGS, () -> JSON.writeValueAsString(settings));
    }

    public synchronized void appendDownload(DownloadRecord record) {
        if (writable) {
            appendLine(DOWNLOADS, record);
        }
    }

    public synchronized void appendEvent(ActivityEvent event) {
        if (writable) {
            appendLine(EVENTS, event);
        }
    }

    /** Replace the downloads log with exactly these records (retention + one line per job). */
    public synchronized void rewriteDownloads(List<DownloadRecord> downloads) {
        if (writable) {
            writeAtomically(DOWNLOADS, () -> lines(downloads));
        }
    }

    /**
     * Drop events older than {@code cutoff} from the log file itself. Works from the file, not
     * from memory (which only keeps the newest few thousand), so nothing inside the retention
     * period is lost however busy the log has been.
     */
    public synchronized void pruneEvents(long cutoff) {
        if (writable) {
            List<ActivityEvent> keep = readLines(EVENTS, ActivityEvent.class).stream().filter(e -> e.at() >= cutoff).toList();
            writeAtomically(EVENTS, () -> lines(keep));
        }
    }

    // ------------------------------------------------------------------ internals

    private interface Content {
        String get() throws JacksonException;
    }

    private <T> T readFile(String name, TypeReference<T> type, T empty) {
        Path file = dir.resolve(name);
        if (!Files.exists(file)) {
            return empty;
        }
        try {
            T value = JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), type);
            return value == null ? empty : value;
        } catch (IOException | JacksonException e) {
            // Keep the damaged file for a human; starting empty must not overwrite it.
            Path aside = dir.resolve(name + ".corrupt-" + System.currentTimeMillis());
            log.warn("Admin data file {} is unreadable ({}); moved it to {}", file, e.getMessage(), aside.getFileName());
            try {
                Files.move(file, aside);
            } catch (IOException moveFailed) {
                log.warn("Could not move {} aside: {}", file, moveFailed.toString());
            }
            return empty;
        }
    }

    private <T> List<T> readLines(String name, Class<T> type) {
        Path file = dir.resolve(name);
        List<T> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        int skipped = 0;
        try {
            endWithNewline(file);
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        out.add(JSON.readValue(line, type));
                    } catch (JacksonException e) {
                        skipped++;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Could not read admin log {}: {}", file, e.toString());
        }
        if (skipped > 0) {
            log.warn("Skipped {} unreadable line(s) in {}", skipped, file);
        }
        return out;
    }

    /** After a crash mid-append the last line has no ending; give it one before appending more. */
    private static void endWithNewline(Path file) throws IOException {
        long size = Files.size(file);
        if (size == 0) {
            return;
        }
        ByteBuffer last = ByteBuffer.allocate(1);
        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ)) {
            ch.position(size - 1);
            ch.read(last);
        }
        if (last.get(0) != '\n') {
            Files.writeString(file, "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
    }

    private void appendLine(String name, Object value) {
        try {
            ensureDir();
            Files.writeString(dir.resolve(name), JSON.writeValueAsString(value) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | JacksonException e) {
            log.warn("Could not append to admin log {}: {}", name, e.toString());
        }
    }

    private void writeAtomically(String name, Content content) {
        Path target = dir.resolve(name);
        Path tmp = dir.resolve(name + ".tmp");
        try {
            ensureDir();
            Files.writeString(tmp, content.get(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | JacksonException e) {
            log.warn("Could not write admin data file {}: {}", target, e.toString());
        }
    }

    private static String lines(List<?> values) {
        StringBuilder sb = new StringBuilder();
        for (Object v : values) {
            sb.append(JSON.writeValueAsString(v)).append('\n');
        }
        return sb.toString();
    }

    private void ensureDir() throws IOException {
        ensurePrivateDir(dir);
    }

    /** Owner-only: these files hold the team's IPs and locations. */
    static void ensurePrivateDir(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(dir);
        }
    }
}
