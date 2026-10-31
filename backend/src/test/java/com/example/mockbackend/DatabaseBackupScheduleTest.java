package com.example.mockbackend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The daily H2 backup runs at 04:30 Korean time even when the JVM's zone is UTC (the VM's container): the scheduled
 * cron carries storage.backup.zone.
 */
@SpringBootTest
class DatabaseBackupScheduleTest {

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.backup.enabled", () -> "true");
        registry.add("storage.backup.dir", () -> storage.resolve("backup").toString());
        registry.add("storage.backup.cron", () -> "0 30 4 * * *");
    }

    @Autowired
    ScheduledTaskHolder scheduled;

    @Test
    void theBackupRunsAtHalfPastFourInKorea() {
        CronTask backup = scheduled.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance).map(CronTask.class::cast)
                .filter(task -> task.toString().contains("DatabaseBackup"))
                .findFirst().orElseThrow();
        // From 2026-11-01 09:00 KST (00:00 UTC) the next run is 2026-11-02 04:30 KST = 2026-11-01 19:30 UTC
        // (in the JVM's own zone, UTC on the VM, it would have been 04:30 UTC = 13:30 KST).
        Clock at = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
        Instant next = backup.getTrigger().nextExecution(new SimpleTriggerContext(at));
        assertThat(next).isEqualTo(Instant.parse("2026-11-01T19:30:00Z"));
    }
}
