package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.service.JobService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A stopping server ends its job runs at once instead of waiting for them (failover drill 2026-11-26, finding 4:
 * {@code docker compose stop backend} ended in SIGKILL, exit 137, after about 11 s). Since Spring Framework 6.1 the
 * {@code @Async} executor takes part in the lifecycle stop and waits for its running tasks, up to
 * spring.lifecycle.timeout-per-shutdown-phase (30 s by Spring's default): a run waiting for a stopped GPU, for /infer
 * or in the mock's sleep held the whole stop while Docker gives 10 s, and H2 was never closed.
 * <p>
 * Each case starts a context of its own on a file H2 (with Tomcat and the management server, like the VM), closes it
 * while a run waits, and checks how long {@code close()} took and how the job ended. The database is read after the
 * close, so the run must have written its end while the database was still open (before the fix the worker was only
 * interrupted after the database had closed, and the job stayed PENDING or PROCESSING until the next start).
 */
class ShutdownTimeTest {
    /**
     * Well under Docker's 10 s and under the 5 s cap of application.properties (spring.lifecycle.timeout-per-shutdown-phase):
     * the runs themselves must end, the cap must not be what ends the stop.
     */
    private static final Duration FAST = Duration.ofSeconds(3);

    @TempDir
    Path dir;

    private HttpServer inference;
    private ExecutorService handlers;
    private final CountDownLatch releaseInfer = new CountDownLatch(1);
    private final AtomicInteger healthChecks = new AtomicInteger();
    private final AtomicInteger inferCalls = new AtomicInteger();

    @AfterEach
    void stopFakeInferenceService() {
        releaseInfer.countDown();
        if (inference != null) {
            inference.stop(0);
            handlers.shutdownNow();
        }
    }

    @Test
    void aJobWaitingForAStoppedGpuEndsAtOnce() throws Exception {
        startFakeInferenceService(false);
        ConfigurableApplicationContext context = start("real",
                // prod: a job waits as PENDING while the GPU is not UP (here 503 "model loading"), polling every 60 s
                "--inference.offline-wait-ms=1800000", "--inference.offline-poll-ms=60000");
        String jobId = submit(context);
        // the startup check plus the worker's own: the worker is in its offline wait now (or about to sleep in it)
        await().atMost(Duration.ofSeconds(10)).until(() -> healthChecks.get() >= 2);
        Thread.sleep(200);

        assertStopsFast(context);
        assertEndedByTheStop(jobId, "Real worker interrupted (server shutting down)");
        assertThat(inferCalls.get()).isZero();
    }

    @Test
    void aJobWaitingForItsInferenceAnswerEndsAtOnce() throws Exception {
        startFakeInferenceService(true);
        ConfigurableApplicationContext context = start("real",
                "--inference.offline-wait-ms=1800000", "--inference.offline-poll-ms=100",
                "--inference.timeout-ms=120000");
        String jobId = submit(context);
        // /infer has the request and does not answer (a GPU run takes minutes)
        await().atMost(Duration.ofSeconds(10)).until(() -> inferCalls.get() == 1);

        assertStopsFast(context);
        assertEndedByTheStop(jobId, "Real worker interrupted (server shutting down)");
    }

    @Test
    void aMockJobEndsAtOnce() throws Exception {
        ConfigurableApplicationContext context = start("mock", "--mock.worker.pending-ms=60000");
        // the cap behind the workers' own stop, kept inside Docker's 10 s (deploy/docker-compose.yml stop_grace_period)
        assertThat(context.getEnvironment().getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("5s");
        String jobId = submit(context);
        Thread.sleep(300);

        assertStopsFast(context);
        assertEndedByTheStop(jobId, "Mock worker interrupted (server shutting down)");
    }

    private void assertStopsFast(ConfigurableApplicationContext context) {
        long started = System.nanoTime();
        context.close();
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(took).as("context close").isLessThan(FAST);
    }

    /** FAILED INTERNAL_ERROR with the worker's own message, read from the file database after the close. */
    private void assertEndedByTheStop(String jobId, String message) throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:h2:file:" + database(), "sa", "");
             PreparedStatement query = db.prepareStatement(
                     "SELECT status, error_code, error_message FROM jobs WHERE id = ?")) {
            query.setString(1, jobId);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("FAILED");
                assertThat(row.getString(2)).isEqualTo("INTERNAL_ERROR");
                assertThat(row.getString(3)).isEqualTo(message);
            }
        }
    }

    private ConfigurableApplicationContext start(String profile, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=" + profile,
                "--server.port=0",
                "--spring.datasource.url=jdbc:h2:file:" + database() + ";DB_CLOSE_ON_EXIT=FALSE",
                "--storage.upload.dir=" + dir.resolve("uploads"),
                "--storage.result.dir=" + dir.resolve("results"),
                "--storage.tmp.dir=" + dir.resolve("tmp"),
                "--inference.health-poll-ms=600000")); // only the startup check and the worker's own
        if (inference != null) {
            args.add("--inference.base-url=http://127.0.0.1:" + inference.getAddress().getPort());
        }
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(MockBackendApplication.class)
                .bannerMode(Banner.Mode.OFF)
                .registerShutdownHook(false)
                .run(args.toArray(String[]::new));
    }

    private String database() {
        return dir.resolve("db").resolve("beside").toAbsolutePath().toString().replace('\\', '/');
    }

    private static String submit(ConfigurableApplicationContext context) {
        MockMultipartFile photo = new MockMultipartFile("photos", "dog.jpg", "image/jpeg", new byte[]{1, 2, 3});
        Job job = context.getBean(JobService.class).submitJobV1(List.of(photo), "stop-" + UUID.randomUUID());
        return job.getId();
    }

    /** /healthz UP (with an instance id, as the service reports it) or 503; /infer holds the request until the test ends. */
    private void startFakeInferenceService(boolean gpuUp) throws IOException {
        inference = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        handlers = Executors.newCachedThreadPool();
        inference.setExecutor(handlers);
        inference.createContext("/healthz", exchange -> {
            healthChecks.incrementAndGet();
            if (gpuUp) {
                respond(exchange, 200, "{\"status\":\"ok\",\"device\":\"cuda\",\"modelLoaded\":true,\"passthrough\":false,"
                        + "\"modelVersion\":\"fake-animallift@1\",\"instanceId\":\"fake-1\"}");
            } else {
                respond(exchange, 503, "{\"detail\":\"model loading\"}");
            }
        });
        inference.createContext("/infer", exchange -> {
            inferCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try {
                releaseInfer.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 500, "{\"detail\":{\"code\":\"INFERENCE_FAILED\",\"message\":\"released by the test\"}}");
        });
        inference.createContext("/artifacts", exchange -> respond(exchange, 204, ""));
        inference.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
