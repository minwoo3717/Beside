package com.example.mockbackend;

import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.service.InferenceHealthMonitor.State;
import com.example.mockbackend.service.InferenceHealthMonitor.StateChanged;
import com.example.mockbackend.service.InferenceHealthMonitor.Status;
import com.example.mockbackend.service.JobFinishedEvent;
import com.example.mockbackend.service.WebhookNotifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Finished runs reach the team webhook; a failing webhook never changes the job. */
@SpringBootTest
@AutoConfigureMockMvc
class WebhookNotifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> received = new CopyOnWriteArrayList<>();
    private static final AtomicInteger answer = new AtomicInteger(204);
    private static HttpServer webhook;

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        webhook = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        webhook.createContext("/hook", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(answer.get(), -1);
            exchange.close();
        });
        webhook.start();
        Files.write(storage.resolve("sample-dog.glb"), "glTF sample".getBytes(StandardCharsets.UTF_8));
        registry.add("notify.webhook-url", () -> "http://127.0.0.1:" + webhook.getAddress().getPort() + "/hook");
        registry.add("notify.public-base-url", () -> "https://beside.example/");
        registry.add("storage.upload.dir", () -> storage.resolve("uploads").toString());
        registry.add("storage.result.sample", () -> storage.resolve("sample-dog.glb").toString());
        registry.add("mock.worker.pending-ms", () -> "50");
        registry.add("mock.worker.processing-ms", () -> "50");
    }

    @AfterAll
    static void stop() {
        webhook.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void reset() {
        received.clear();
        answer.set(204);
    }

    @Test
    void completedAndFailedRunsArePosted() throws Exception {
        String done = upload("dog.jpg");
        String failed = upload("fail-dog.jpg");

        await().atMost(Duration.ofSeconds(10)).until(() -> received.size() >= 2);
        List<String> contents = received.stream().map(WebhookNotifierTest::content).toList();
        assertThat(contents).anySatisfy(text -> assertThat(text)
                .contains("3D 모델 완성").contains(done.substring(0, 8)).contains("https://beside.example/admin/"));
        assertThat(contents).anySatisfy(text -> assertThat(text)
                .contains("3D 생성 실패").contains(failed.substring(0, 8)).contains("INFERENCE_FAILED"));
    }

    @Test
    void failingWebhookLeavesTheJobCompleted() throws Exception {
        answer.set(500);
        String jobId = upload("dog.jpg");
        await().atMost(Duration.ofSeconds(10)).until(() -> !received.isEmpty());
        mvc.perform(get("/api/v1/jobs/{id}", jobId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void messagesAreShortAndStateChangesSkipTheNormalStart() {
        String text = WebhookNotifier.jobMessage(new JobFinishedEvent("0123456789abcdef", 2, JobStatus.COMPLETED, null,
                125_000L, "real", "animallift@abc:latest+rig", false), "");
        assertThat(text).isEqualTo("✅ 3D 모델 완성 · 작업 `01234567` · 2분 5초 · 2번째 시도 · animallift@abc:latest+rig");
        assertThat(WebhookNotifier.jobMessage(new JobFinishedEvent("j", 1, JobStatus.COMPLETED, null, 900L, "real", null, true), ""))
                .contains("패스스루");

        Instant now = Instant.now();
        Status unknown = new Status(State.UNKNOWN, null, null, now, "not checked yet");
        Status up = new Status(State.UP, "cuda", now, now, "ok");
        Status down = new Status(State.DOWN, null, now, now, "ConnectException");
        assertThat(WebhookNotifier.stateMessage(new StateChanged(unknown, up))).isNull();
        assertThat(WebhookNotifier.stateMessage(new StateChanged(up, down))).contains("연결할 수 없어요").contains("ConnectException");
        assertThat(WebhookNotifier.stateMessage(new StateChanged(down, up))).contains("연결됨 (cuda)");
    }

    @Test
    void emptyUrlTurnsNotificationsOff() {
        assertThat(new WebhookNotifier("", "", JSON).enabled()).isFalse();
        assertThat(new WebhookNotifier(" ", "", JSON).enabled()).isFalse();
    }

    private String upload(String filename) throws Exception {
        String body = mvc.perform(multipart("/api/v1/jobs")
                        .file(new MockMultipartFile("photos", filename, "image/jpeg", new byte[16])))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).path("jobId").asText();
    }

    private static String content(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            return node.path("content").asText();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
