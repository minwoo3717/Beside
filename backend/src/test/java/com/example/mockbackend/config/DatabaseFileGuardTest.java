package com.example.mockbackend.config;

import com.example.mockbackend.MockBackendApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Failure drill FD5 (2026-11-20): with its database file deleted, the prod server used to start on a new empty H2
 * database (health UP, every job 404). Now prod refuses to start without the file; other profiles, and a prod server
 * told to on purpose (a new server), still create one.
 */
class DatabaseFileGuardTest {
    @TempDir
    Path dir;

    @Test
    void prodDoesNotCreateAMissingDatabaseAndTheOtherProfilesDo() throws IOException {
        Properties base = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        Properties prod = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-prod.properties"));
        assertThat(base.getProperty("storage.db.create-if-missing")).isEqualTo("true");
        assertThat(prod.getProperty("storage.db.create-if-missing")).isEqualTo("false");
    }

    /** The real application, profiles real,prod, an H2 file URL in a folder without the file: no start, no file made. */
    @Test
    void aProdServerWithoutItsDatabaseFileDoesNotStartAndCreatesNothing() {
        Path db = dir.resolve("db").resolve("beside-real");
        SpringApplication app = application();

        assertThatThrownBy(() -> run(app, db, null)).satisfies(thrown -> {
            Throwable root = thrown;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not exist")
                    .hasMessageContaining("storage.db.create-if-missing=false")
                    .hasMessageContaining("RUNBOOK.md 'DB 복구'")
                    .hasMessageContaining("STORAGE_DB_CREATEIFMISSING=true");
        });
        assertThat(dir.resolve("db").resolve("beside-real.mv.db")).as("H2 never opened the URL").doesNotExist();
    }

    /** A new server, on purpose: the empty database is made, and the next start finds it without the override. */
    @Test
    void createIfMissingTrueStartsAnEmptyDatabaseOnceAndItIsThereNextTime() {
        Path db = dir.resolve("db").resolve("beside-real");
        try (ConfigurableApplicationContext first = run(application(), db, "true")) {
            assertThat(first.isActive()).isTrue();
        }
        assertThat(dir.resolve("db").resolve("beside-real.mv.db")).isRegularFile();
        try (ConfigurableApplicationContext again = run(application(), db, null)) {
            assertThat(again.isActive()).isTrue();
        }
    }

    @Test
    void onlyAnH2FileUrlWithoutItsFileIsRefused() throws IOException {
        Path existing = Files.write(dir.resolve("kept.mv.db"), new byte[]{0});
        String keptUrl = "jdbc:h2:file:" + dir.resolve("kept") + ";DB_CLOSE_ON_EXIT=FALSE";
        DatabaseFileGuard.check(keptUrl, false);                                  // the file is there
        DatabaseFileGuard.check("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", false);     // tests
        DatabaseFileGuard.check("jdbc:postgresql://db/beside", false);            // not H2
        DatabaseFileGuard.check(null, false);
        DatabaseFileGuard.check("jdbc:h2:file:" + dir.resolve("gone"), true);    // allowed to create
        assertThatThrownBy(() -> DatabaseFileGuard.check("JDBC:H2:FILE:" + dir.resolve("gone") + ";X=1", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gone.mv.db");
        assertThat(DatabaseFileGuard.databaseFile("jdbc:h2:file:./storage/db/beside-real;DB_CLOSE_ON_EXIT=FALSE"))
                .isEqualTo(Path.of("./storage/db/beside-real.mv.db"));
        assertThat(DatabaseFileGuard.databaseFile("jdbc:h2:file:~/beside"))
                .isEqualTo(Path.of(System.getProperty("user.home") + "/beside.mv.db"));
        assertThat(existing).exists();
    }

    /** Profiles real,prod as on the VM; the test config keeps the preprocessing models and the H2 backup off. */
    private static SpringApplication application() {
        return new SpringApplicationBuilder(MockBackendApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("real", "prod")
                .build();
    }

    /**
     * Command-line arguments, so they beat the test config (config/application.properties: an in-memory database) as
     * they would the VM's files; {@code createIfMissing} null leaves the profile's value (prod: false).
     */
    private ConfigurableApplicationContext run(SpringApplication app, Path db, String createIfMissing) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=jdbc:h2:file:" + db + ";DB_CLOSE_ON_EXIT=FALSE",
                "--storage.upload.dir=" + dir.resolve("uploads"),
                "--storage.result.dir=" + dir.resolve("results"),
                "--storage.tmp.dir=" + dir.resolve("tmp"),
                "--storage.events.path=",
                "--storage.backup.enabled=false",
                "--inference.health-poll-ms=600000",
                "--management.server.port=-1"));
        if (createIfMissing != null) {
            args.add("--storage.db.create-if-missing=" + createIfMissing);
        }
        return app.run(args.toArray(String[]::new));
    }
}
