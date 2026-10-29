package com.example.mockbackend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Daily online copy of the H2 file database (prod: storage.backup.enabled=true). H2's BACKUP TO writes a consistent
 * zip while the server keeps running, so the VM never has to stop for a backup. Files are
 * {storage.backup.dir}/beside-{time}.zip; only the newest storage.backup.keep stay. A failed backup is logged and
 * retried at the next schedule; it never touches jobs.
 */
@Component
@ConditionalOnProperty(name = "storage.backup.enabled", havingValue = "true")
public class DatabaseBackup {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault());

    private final JdbcTemplate jdbc;
    private final Path dir;
    private final int keep;
    private final Logger log = LoggerFactory.getLogger(DatabaseBackup.class);

    public DatabaseBackup(JdbcTemplate jdbc,
                          @Value("${storage.backup.dir:storage/backup}") String dir,
                          @Value("${storage.backup.keep:7}") int keep) {
        this.jdbc = jdbc;
        this.dir = Path.of(dir);
        this.keep = Math.max(1, keep);
    }

    @Scheduled(cron = "${storage.backup.cron:0 30 4 * * *}")
    public void scheduled() {
        try {
            backupNow();
        } catch (IOException | RuntimeException e) {
            log.error("H2 backup failed: {}", e.toString());
        }
    }

    /** Writes one backup now and prunes old ones; returns the new file. */
    public Path backupNow() throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve("beside-" + STAMP.format(Instant.now()) + ".zip").toAbsolutePath();
        while (Files.exists(target)) {
            // Same millisecond as the previous backup: wait for the next one, so names stay unique and in time order.
            Thread.onSpinWait();
            target = dir.resolve("beside-" + STAMP.format(Instant.now()) + ".zip").toAbsolutePath();
        }
        jdbc.execute("BACKUP TO '" + target.toString().replace("'", "''") + "'");
        log.info("H2 backup written: {} ({} bytes)", target.getFileName(), Files.size(target));
        prune();
        return target;
    }

    private void prune() throws IOException {
        List<Path> backups;
        try (Stream<Path> files = Files.list(dir)) {
            backups = files.filter(file -> file.getFileName().toString().matches("beside-.*\\.zip"))
                    .sorted(Comparator.comparing((Path file) -> file.getFileName().toString()).reversed())
                    .toList();
        }
        for (Path old : backups.subList(Math.min(keep, backups.size()), backups.size())) {
            Files.deleteIfExists(old);
        }
    }
}
