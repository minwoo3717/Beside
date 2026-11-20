package com.example.mockbackend.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Refuses to start on a missing H2 database file unless storage.db.create-if-missing is true (the default; prod sets
 * false). Failure drill FD5 (2026-11-20): with its file gone, H2 quietly created an empty database, health stayed UP
 * and every existing job answered 404 until someone noticed. The same happens when the backend is started on the wrong
 * (new, empty) volume. Without the file a prod server now stops with a message that says how to restore it
 * (docs/ops/RUNBOOK.md 'DB 복구') or, on a new server, how to start it once with an empty database (the container's
 * STORAGE_DB_CREATEIFMISSING=true; on the VM compose sets it from the shell's STORAGE_DB_CREATE_IF_MISSING=true). The
 * VM watchdog then reports the crash loop.
 * <p>
 * Runs before any bean is created (a bean factory post-processor), so before the data source opens the file. Only an
 * H2 file URL ({@code jdbc:h2:file:}) is checked: tests use in-memory databases.
 */
@Component
public class DatabaseFileGuard implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {
    static final String URL_PREFIX = "jdbc:h2:file:";
    static final String CREATE_IF_MISSING = "storage.db.create-if-missing";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        check(environment.getProperty("spring.datasource.url"),
                environment.getProperty(CREATE_IF_MISSING, Boolean.class, Boolean.TRUE));
    }

    @Override
    public int getOrder() {
        return PriorityOrdered.HIGHEST_PRECEDENCE;
    }

    /** Throws when {@code url} names an H2 file database whose file is missing and creating one is not allowed. */
    static void check(String url, boolean createIfMissing) {
        Path file = databaseFile(url);
        if (createIfMissing || file == null || Files.isRegularFile(file)) {
            return;
        }
        throw new IllegalStateException("H2 database file " + file.toAbsolutePath().normalize() + " does not exist and "
                + CREATE_IF_MISSING + "=false (prod): not starting with an empty database (failure drill FD5). "
                + "If the file was lost, restore it from a backup (docs/ops/RUNBOOK.md 'DB 복구'). If the backend runs on "
                + "the wrong storage volume, fix the mount. Only on a new server: start once with " + CREATE_IF_MISSING
                + "=true (container environment STORAGE_DB_CREATEIFMISSING=true; with deploy/docker-compose.yml "
                + "STORAGE_DB_CREATE_IF_MISSING=true in front of docker compose up, deploy/README.md 'VM 첫 설정').");
    }

    /**
     * The .mv.db file of an H2 file URL ("jdbc:h2:file:./storage/db/beside-real;DB_CLOSE_ON_EXIT=FALSE"), relative
     * paths against the working directory and a leading "~" against the home directory as H2 does; null for any
     * other URL (in-memory, another database, none).
     */
    static Path databaseFile(String url) {
        if (url == null || url.length() < URL_PREFIX.length()
                || !url.substring(0, URL_PREFIX.length()).toLowerCase(Locale.ROOT).equals(URL_PREFIX)) {
            return null;
        }
        String name = url.substring(URL_PREFIX.length());
        int options = name.indexOf(';');
        name = (options < 0 ? name : name.substring(0, options)).strip();
        if (name.isEmpty()) {
            return null;
        }
        if (name.equals("~") || name.startsWith("~/") || name.startsWith("~\\")) {
            name = System.getProperty("user.home") + name.substring(1);
        }
        try {
            return Path.of(name + ".mv.db");
        } catch (InvalidPathException e) {
            return null;   // H2 itself will refuse the URL
        }
    }
}
