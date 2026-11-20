package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Why admission.stale-after-ms defaults to {@link AdmissionGate#defaultStaleAfterMs} and not to the offline wait plus
 * a margin (review of 2026-11-20): profile real with the GPU off, more queued jobs than worker threads (2 here, 8 in
 * prod). The real worker starts a job's offline wait when a thread takes the job, so the jobs past the first round of
 * threads stay PENDING for a second whole wait. The cap must keep counting them: a stale-after shorter than that wait
 * would let the queue grow past admission.max-pending in exactly the outage the cap is for.
 */
@SpringBootTest(properties = {"spring.task.execution.pool.core-size=2", "admission.max-pending=4",
        "admission.retry-after-seconds=45"})
@ActiveProfiles("real")
class AdmissionGateStaleBoundTest {
    private static final long OFFLINE_WAIT_MS = 800;
    private static final long INFERENCE_TIMEOUT_MS = 100;

    @TempDir
    static Path storage;

    private static HttpServer inference;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("inference.base-url", () -> "http://127.0.0.1:" + inference.getAddress().getPort());
        registry.add("inference.timeout-ms", () -> String.valueOf(INFERENCE_TIMEOUT_MS));
        registry.add("inference.offline-wait-ms", () -> String.valueOf(OFFLINE_WAIT_MS));
        registry.add("inference.offline-poll-ms", () -> "50");
        registry.add("inference.health-poll-ms", () -> "600000"); // only the worker's own checks during the test
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.dir", () -> storage.resolve("results").toString());
    }

    /** A GPU PC that is off: /healthz never reports a loaded model, so no job reaches /infer. */
    @BeforeAll
    static void startStoppedInferenceService() throws IOException {
        inference = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        inference.createContext("/healthz", exchange -> {
            byte[] body = "{\"detail\":\"model loading\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        inference.start();
    }

    @AfterAll
    static void stopInferenceService() {
        inference.stop(0);
    }

    @Autowired
    JobServiceImpl jobService;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    AdmissionGate admissionGate;

    @Test
    void jobsPastTheFirstRoundOfThreadsWaitLongerThanOneOfflineWaitAndTheDefaultCoversIt() {
        long bound = AdmissionGate.defaultStaleAfterMs(4, 1, 2, OFFLINE_WAIT_MS, INFERENCE_TIMEOUT_MS);
        assertThat(admissionGate.staleAfterMs()).as("the gate reads the worker's settings").isEqualTo(bound);

        List<String> jobs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            jobs.add(jobService.submitJobV1(List.of(photo()), null).getId());
        }
        assertThatThrownBy(() -> jobService.submitJobV1(List.of(photo()), null))
                .isInstanceOfSatisfying(ApiException.class, full -> assertThat(full.getCode()).isEqualTo(ErrorCode.QUEUE_FULL));

        await().atMost(Duration.ofSeconds(20)).until(() -> jobs.stream()
                .allMatch(id -> jobRepository.findById(id).map(Job::getStatus).orElse(null) == JobStatus.FAILED));
        long longestPendingMs = jobs.stream()
                .map(id -> jobRepository.findById(id).orElseThrow())
                .mapToLong(job -> Duration.between(job.getQueuedAt(), job.getFinishedAt()).toMillis())
                .max().orElseThrow();
        // Jobs 3 and 4 get a thread when jobs 1 and 2 give up, then wait a whole offline wait of their own (~1.6 s).
        assertThat(longestPendingMs).as("PENDING for more than one offline wait")
                .isGreaterThanOrEqualTo(OFFLINE_WAIT_MS * 3 / 2);
        assertThat(longestPendingMs).as("within the default without its 10 min margin")
                .isLessThanOrEqualTo(bound - AdmissionGate.STALE_SLACK_MS);
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("photos", "dog.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});
    }
}
