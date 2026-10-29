package com.example.mockbackend;

import com.example.mockbackend.service.DatabaseBackup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/** Online copy of the H2 file database (BACKUP TO) and pruning to the newest storage.backup.keep files. */
@SpringBootTest
class DatabaseBackupTest {

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + storage.resolve("db/beside-backup-test").toAbsolutePath()
                + ";DB_CLOSE_ON_EXIT=FALSE");
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.backup.enabled", () -> "true");
        registry.add("storage.backup.dir", () -> storage.resolve("backup").toString());
        registry.add("storage.backup.keep", () -> "2");
        registry.add("storage.backup.cron", () -> "-"); // the test calls it directly
    }

    @Autowired
    DatabaseBackup backup;

    @Test
    void writesAZipOfTheDatabaseAndKeepsTheNewest() throws IOException {
        Path first = backup.backupNow();
        assertThat(first).isRegularFile();
        try (ZipFile zip = new ZipFile(first.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName)).anyMatch(name -> name.endsWith(".mv.db"));
        }

        backup.backupNow();
        Path third = backup.backupNow();

        List<Path> kept;
        try (Stream<Path> files = Files.list(storage.resolve("backup"))) {
            kept = files.sorted().toList();
        }
        assertThat(kept).hasSize(2).contains(third).doesNotContain(first);
    }
}
