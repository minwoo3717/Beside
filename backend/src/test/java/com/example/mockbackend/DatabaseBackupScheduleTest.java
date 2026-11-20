package com.example.mockbackend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduled H2 backup runs in Korean time even when the JVM's zone is UTC (the VM's container): the scheduled
 * cron carries storage.backup.zone. And the server's prod settings back up every hour and keep two days of files,
 * which deploy/backup/offsite_backup.sh copies off the VM (RPO 1 h).
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

    @Test
    void prodBacksUpEveryHourAndKeepsTwoDays() throws IOException {
        // The main file itself: the test resources only have config/application-prod.properties, which turns the
        // backup off in tests (an hourly cron would otherwise write zips into backend/storage/backup mid-run).
        Properties prod = new Properties();
        try (InputStream in = DatabaseBackupScheduleTest.class.getResourceAsStream("/application-prod.properties")) {
            assertThat(in).isNotNull();
            prod.load(in);
        }
        assertThat(prod.getProperty("storage.backup.enabled")).isEqualTo("true");
        assertThat(prod.getProperty("storage.backup.zone")).isEqualTo("Asia/Seoul");
        ZoneId seoul = ZoneId.of(prod.getProperty("storage.backup.zone"));
        CronExpression cron = CronExpression.parse(prod.getProperty("storage.backup.cron"));
        // Minute 5 of every hour, away from the offsite upload at minute 20 (deploy/backup/beside-backup.timer).
        ZonedDateTime first = cron.next(ZonedDateTime.of(2026, 11, 20, 9, 0, 0, 0, seoul));
        assertThat(first).isEqualTo(ZonedDateTime.of(2026, 11, 20, 9, 5, 0, 0, seoul));
        ZonedDateTime at = first;
        for (int i = 0; i < 24; i++) {
            ZonedDateTime next = cron.next(at);
            assertThat(Duration.between(at, next)).isEqualTo(Duration.ofHours(1));
            at = next;
        }
        // 48 files at one per hour: the VM disk keeps the last two days, the bucket keeps 30 days (db/ lifecycle).
        assertThat(prod.getProperty("storage.backup.keep")).isEqualTo("48");
    }
}
