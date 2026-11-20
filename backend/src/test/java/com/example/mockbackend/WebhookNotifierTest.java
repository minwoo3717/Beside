package com.example.mockbackend;

import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.service.InferenceHealthMonitor.Alert;
import com.example.mockbackend.service.InferenceHealthMonitor.AlertChanged;
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
import org.springframework.core.io.ClassPathResource;
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
        // A failing-webhook test leaves the context's notifier pausing: keep that pause short for the tests after it.
        registry.add("notify.retry-base-ms", () -> "200");
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
        // The link follows the pages this build serves: job page, else dashboard, else none.
        String link = new ClassPathResource("static/job/index.html").exists() ? "https://beside.example/job/?id=" + done
                : new ClassPathResource("static/admin/index.html").exists() ? "https://beside.example/admin/" : null;
        assertThat(contents).anySatisfy(text -> {
            assertThat(text).contains("3D 모델 완성").contains(done.substring(0, 8));
            if (link == null) {
                assertThat(text).doesNotContain("https://beside.example");
            } else {
                assertThat(text).endsWith(link);
            }
        });
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

    @Test
    void gpuMessagesNameTheServerWhenALabelIsSet() {
        Instant now = Instant.now();
        Status up = new Status(State.UP, "cuda", now, now, "ok");
        Status loading = new Status(State.LOADING, null, now, now, "no model loaded");
        Status down = new Status(State.DOWN, null, now, now, "HttpTimeoutException");
        assertThat(WebhookNotifier.stateMessage(new StateChanged(up, down), "Runpod A40"))
                .isEqualTo("🔴 GPU 추론 서버(Runpod A40)에 연결할 수 없어요 (HttpTimeoutException) — 새 작업은 대기열에서 기다립니다");
        assertThat(WebhookNotifier.stateMessage(new StateChanged(down, loading), "Runpod A40"))
                .isEqualTo("🟡 GPU 추론 서버(Runpod A40)는 켜졌지만 모델이 아직 준비되지 않았어요");
        // One line: newlines and runs of spaces collapse, ends are trimmed, long labels are cut.
        assertThat(WebhookNotifier.stateMessage(new StateChanged(loading, up), "  Runpod\n\tA40 "))
                .startsWith("🟢 GPU 추론 서버(Runpod A40) 연결됨 (cuda)");
        assertThat(WebhookNotifier.stateMessage(new StateChanged(up, down), " ")).startsWith("🔴 GPU 추론 서버에 연결할 수 없어요");
        assertThat(WebhookNotifier.stateMessage(new StateChanged(up, down), "x".repeat(100)))
                .contains("(" + "x".repeat(40) + ")에");
    }

    @Test
    void withoutGraceEveryGpuChangeIsPostedAtOnce() {
        WebhookNotifier notifier = new WebhookNotifier(hookUrl(), "", "", 0, JSON);
        Instant now = Instant.now();
        Status unknown = new Status(State.UNKNOWN, null, null, now, "not checked yet");
        Status up = new Status(State.UP, "cuda", now, now, "ok");
        Status down = new Status(State.DOWN, null, now, now, "ConnectException");
        notifier.onInferenceStateChanged(new StateChanged(unknown, up));
        notifier.onInferenceStateChanged(new StateChanged(up, down));
        notifier.onInferenceStateChanged(new StateChanged(down, up));
        await().atMost(Duration.ofSeconds(5)).until(() -> gpuMessages().size() == 2);
        assertThat(gpuMessages().get(0)).startsWith("🔴 GPU 추론 서버에 연결할 수 없어요 (ConnectException)");
        assertThat(gpuMessages().get(1)).startsWith("🟢 GPU 추론 서버 연결됨 (cuda)");
    }

    @Test
    void aDownShorterThanTheGraceTimePostsNothing() {
        WebhookNotifier notifier = new WebhookNotifier(hookUrl(), "", "Runpod A40", 400, JSON);
        Instant now = Instant.now();
        Status unknown = new Status(State.UNKNOWN, null, null, now, "not checked yet");
        Status up = new Status(State.UP, "cuda", now, now, "ok");
        Status loading = new Status(State.LOADING, null, now, now, "no model loaded");
        Status down = new Status(State.DOWN, null, now, now, "HttpTimeoutException");

        notifier.onInferenceStateChanged(new StateChanged(unknown, up));      // normal start: silent
        notifier.onInferenceStateChanged(new StateChanged(up, down));         // the relay misses a check ...
        notifier.onInferenceStateChanged(new StateChanged(down, up));         // ... and is back within 400 ms
        await().during(Duration.ofMillis(1200)).atMost(Duration.ofSeconds(5)).until(() -> gpuMessages().isEmpty());

        notifier.onInferenceStateChanged(new StateChanged(up, down));         // the Pod is stopped
        await().atMost(Duration.ofSeconds(5)).until(() -> gpuMessages().size() == 1);
        assertThat(gpuMessages().get(0)).startsWith("🔴 GPU 추론 서버(Runpod A40)에 연결할 수 없어요 (HttpTimeoutException)");

        notifier.onInferenceStateChanged(new StateChanged(down, loading));    // started again, model loading
        notifier.onInferenceStateChanged(new StateChanged(loading, up));
        await().atMost(Duration.ofSeconds(5)).until(() -> gpuMessages().size() == 3);
        assertThat(gpuMessages().get(1)).startsWith("🟡 GPU 추론 서버(Runpod A40)는 켜졌지만");
        assertThat(gpuMessages().get(2)).startsWith("🟢 GPU 추론 서버(Runpod A40) 연결됨 (cuda)");

        // A restart seen as DOWN then LOADING within the grace time: only the LOADING (and the UP after it) is news.
        notifier.onInferenceStateChanged(new StateChanged(up, down));
        notifier.onInferenceStateChanged(new StateChanged(down, loading));
        notifier.onInferenceStateChanged(new StateChanged(loading, up));
        await().atMost(Duration.ofSeconds(5)).until(() -> gpuMessages().size() == 5);
        await().during(Duration.ofMillis(1200)).atMost(Duration.ofSeconds(5)).until(() -> gpuMessages().size() == 5);
        assertThat(gpuMessages().get(3)).startsWith("🟡");
        assertThat(gpuMessages().get(4)).startsWith("🟢");
    }

    @Test
    void aDownThatOutlastedTheGraceTimeIsAnnouncedEvenIfItsTimerIsLate() throws InterruptedException {
        WebhookNotifier notifier = new WebhookNotifier(hookUrl(), "", "", 200, JSON);
        Instant now = Instant.now();
        Status up = new Status(State.UP, "cuda", now, now, "ok");
        Status down = new Status(State.DOWN, null, now, now, "HttpTimeoutException");
        notifier.onInferenceStateChanged(new StateChanged(new Status(State.UNKNOWN, null, null, now, "-"), up));
        notifier.onInferenceStateChanged(new StateChanged(up, down));
        // Hold the notifier's lock past the grace time so its timer cannot run, then report the GPU back.
        synchronized (notifier) {
            Thread.sleep(500);
            notifier.onInferenceStateChanged(new StateChanged(down, up));
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> gpuMessages().size() == 2);
        await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(5)).until(() -> gpuMessages().size() == 2);
        assertThat(gpuMessages().get(0)).startsWith("🔴 GPU 추론 서버에 연결할 수 없어요 (HttpTimeoutException)");
        assertThat(gpuMessages().get(1)).startsWith("🟢 GPU 추론 서버 연결됨");
    }

    @Test
    void alertMessagesSayWhatIsWrongAndWhatToCheck() {
        Instant now = Instant.now();
        Status up = new Status(State.UP, "cuda", now, now, "ok", false, 125_000L);
        Status fine = new Status(State.UP, "cuda", now, now, "ok", true, 0L);
        assertThat(WebhookNotifier.alertMessage(new AlertChanged(Alert.TOKEN_REJECTED, true, up, 120_000), "Runpod A40"))
                .isEqualTo("🔑 GPU 추론 서버(Runpod A40)가 백엔드의 추론 토큰을 받지 않아요 — 새 작업이 모두 바로 실패합니다"
                        + " (INFERENCE_UNAVAILABLE). 양쪽 INFERENCE_TOKEN 을 확인해 주세요");
        assertThat(WebhookNotifier.alertMessage(new AlertChanged(Alert.TOKEN_REJECTED, false, fine, 120_000), ""))
                .isEqualTo("🔑 GPU 추론 서버가 추론 토큰을 다시 받아요");
        assertThat(WebhookNotifier.alertMessage(new AlertChanged(Alert.STUCK, true, up, 120_000), "Runpod A40"))
                .isEqualTo("🐢 GPU 추론 서버(Runpod A40)의 작업 하나가 2분 5초째 끝나지 않아요 (기준 2분 0초)"
                        + " — 추론이 멈췄을 수 있어요. 그동안 다른 작업은 기다리거나 시간 초과로 실패합니다");
        assertThat(WebhookNotifier.alertMessage(new AlertChanged(Alert.STUCK, false, fine, 120_000), ""))
                .isEqualTo("🟢 GPU 추론 서버의 오래 걸리던 작업이 끝났어요");
        // Nothing in them names the token, a host or a path.
        assertThat(WebhookNotifier.alertMessage(new AlertChanged(Alert.TOKEN_REJECTED, true, up, 120_000), ""))
                .doesNotContain("http").doesNotContain("/");
    }

    /** One message per change, at once (the DOWN grace time is for the relay's missed checks, not for these). */
    @Test
    void tokenAndStuckAlertsArePostedOncePerChange() {
        WebhookNotifier notifier = new WebhookNotifier(hookUrl(), "", "Runpod A40", 45_000, JSON);
        Instant now = Instant.now();
        Status rejected = new Status(State.UP, "cuda", now, now, "ok", false, 0L);
        Status stuck = new Status(State.UP, "cuda", now, now, "ok", true, 130_000L);
        Status fine = new Status(State.UP, "cuda", now, now, "ok", true, 0L);

        notifier.onInferenceAlert(new AlertChanged(Alert.STUCK, false, fine, 120_000));           // never announced: silent
        notifier.onInferenceAlert(new AlertChanged(Alert.TOKEN_REJECTED, true, rejected, 120_000));
        notifier.onInferenceAlert(new AlertChanged(Alert.TOKEN_REJECTED, true, rejected, 120_000)); // a repeat: silent
        notifier.onInferenceAlert(new AlertChanged(Alert.STUCK, true, stuck, 120_000));
        notifier.onInferenceAlert(new AlertChanged(Alert.STUCK, true, stuck, 120_000));             // a repeat: silent
        notifier.onInferenceAlert(new AlertChanged(Alert.TOKEN_REJECTED, false, fine, 120_000));
        notifier.onInferenceAlert(new AlertChanged(Alert.STUCK, false, fine, 120_000));
        notifier.onInferenceAlert(new AlertChanged(Alert.STUCK, false, fine, 120_000));            // a repeat: silent

        await().atMost(Duration.ofSeconds(5)).until(() -> alertMessages().size() == 4);
        await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(5)).until(() -> alertMessages().size() == 4);
        assertThat(alertMessages().get(0)).startsWith("🔑 GPU 추론 서버(Runpod A40)가 백엔드의 추론 토큰을 받지 않아요");
        assertThat(alertMessages().get(1)).startsWith("🐢 GPU 추론 서버(Runpod A40)의 작업 하나가 2분 10초째");
        assertThat(alertMessages().get(2)).isEqualTo("🔑 GPU 추론 서버(Runpod A40)가 추론 토큰을 다시 받아요");
        assertThat(alertMessages().get(3)).isEqualTo("🟢 GPU 추론 서버(Runpod A40)의 오래 걸리던 작업이 끝났어요");
    }

    /** FD18: the message names the exception type only, never the service's text (paths). */
    @Test
    void modelLoadMessagesNameTheErrorTypeOnly() {
        Instant now = Instant.now();
        Status failed = new Status(State.LOADING, "cuda", now, now, "no model loaded", true, 0L, true,
                "FileNotFoundError: [Errno 2] No such file or directory: '/workspace/AnimalLift/ckpt/wrong.pt'");
        Status loading = new Status(State.LOADING, "cuda", now, now, "no model loaded", true, 0L, false, null);
        String alert = WebhookNotifier.alertMessage(new AlertChanged(Alert.MODEL_LOAD_FAILED, true, failed, 120_000),
                "Runpod A40");
        assertThat(alert).isEqualTo("🧩 GPU 추론 서버(Runpod A40)가 모델을 불러오지 못했어요 (FileNotFoundError)"
                + " — 고쳐서 다시 띄우기 전에는 새 작업이 처리되지 않아요. 추론 서비스 로그를 확인해 주세요");
        assertThat(alert).doesNotContain("/").doesNotContain("workspace").doesNotContain("Errno");
        assertThat(WebhookNotifier.alertMessage(new AlertChanged(Alert.MODEL_LOAD_FAILED, false, loading, 120_000), ""))
                .isEqualTo("🧩 GPU 추론 서버의 모델 불러오기 오류가 풀렸어요");
        // A text that does not start with a type name, or none at all: a plain word instead.
        for (String odd : new String[]{"<b>bold</b>: x", "no colon here at all", "", null}) {
            Status reading = new Status(State.LOADING, null, now, now, "no model loaded", null, null, true, odd);
            assertThat(WebhookNotifier.alertMessage(new AlertChanged(Alert.MODEL_LOAD_FAILED, true, reading, 120_000), ""))
                    .startsWith("🧩 GPU 추론 서버가 모델을 불러오지 못했어요 (알 수 없는 오류) — ");
        }
        Status bare = new Status(State.LOADING, null, now, now, "no model loaded", null, null, true, "MemoryError");
        assertThat(WebhookNotifier.alertMessage(new AlertChanged(Alert.MODEL_LOAD_FAILED, true, bare, 120_000), ""))
                .contains("(MemoryError)");
    }

    @Test
    void modelLoadAlertIsPostedOncePerChange() {
        WebhookNotifier notifier = new WebhookNotifier(hookUrl(), "", "", 45_000, JSON);
        Instant now = Instant.now();
        Status failed = new Status(State.LOADING, "cuda", now, now, "no model loaded", true, 0L, true,
                "FileNotFoundError: [Errno 2] No such file or directory: '<path>'");
        Status loading = new Status(State.LOADING, "cuda", now, now, "no model loaded", true, 0L, false, null);

        notifier.onInferenceAlert(new AlertChanged(Alert.MODEL_LOAD_FAILED, false, loading, 120_000));   // never announced
        notifier.onInferenceAlert(new AlertChanged(Alert.MODEL_LOAD_FAILED, true, failed, 120_000));
        notifier.onInferenceAlert(new AlertChanged(Alert.MODEL_LOAD_FAILED, true, failed, 120_000));    // a repeat: silent
        notifier.onInferenceAlert(new AlertChanged(Alert.MODEL_LOAD_FAILED, false, loading, 120_000));
        notifier.onInferenceAlert(new AlertChanged(Alert.MODEL_LOAD_FAILED, false, loading, 120_000));  // a repeat: silent

        await().atMost(Duration.ofSeconds(5)).until(() -> alertMessages().size() == 2);
        await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(5)).until(() -> alertMessages().size() == 2);
        assertThat(alertMessages().get(0)).startsWith("🧩 GPU 추론 서버가 모델을 불러오지 못했어요 (FileNotFoundError)");
        assertThat(alertMessages().get(1)).isEqualTo("🧩 GPU 추론 서버의 모델 불러오기 오류가 풀렸어요");
    }

    @Test
    void alertsAreOffWithoutAWebhook() {
        WebhookNotifier notifier = new WebhookNotifier("", "", JSON);
        Instant now = Instant.now();
        notifier.onInferenceAlert(new AlertChanged(Alert.TOKEN_REJECTED, true,
                new Status(State.UP, "cuda", now, now, "ok", false, 0L), 120_000));
        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(5)).until(() -> alertMessages().isEmpty());
    }

    // ---------------------------------------------------------------- sending when the webhook fails (FD9)

    /**
     * FD9: with the webhook failing, messages no longer each wait out a timeout. The GPU message is tried once per
     * pause (doubling) and job results are not tried behind it; once the webhook works, the GPU message goes first and
     * the job results that waited go out together as one message, nothing lost.
     */
    @Test
    void aFailingWebhookIsTriedOncePerPauseAndGpuMessagesGoFirstWhenItWorks() throws IOException {
        try (FakeHook hook = new FakeHook()) {
            hook.answer.set(500);
            WebhookNotifier notifier = new WebhookNotifier(hook.url(), "", "FD9", 0, 300, JSON);
            Instant now = Instant.now();
            Status up = new Status(State.UP, "cuda", now, now, "ok");
            Status down = new Status(State.DOWN, null, now, now, "ConnectException");
            notifier.onInferenceStateChanged(new StateChanged(up, down));
            for (int i = 1; i <= 4; i++) {
                notifier.onJobFinished(job("fd9a000" + i, i % 2 == 0 ? JobStatus.FAILED : JobStatus.COMPLETED));
            }

            // Tries at about 0, 0.3, 0.9 s: three in the first second, all of them the GPU message.
            await().during(Duration.ofMillis(1100)).atMost(Duration.ofSeconds(5)).until(() -> hook.received.size() <= 3);
            assertThat(hook.received).isNotEmpty().allSatisfy(text -> assertThat(text).startsWith("🔴 GPU 추론 서버(FD9)"));

            hook.answer.set(204);
            await().atMost(Duration.ofSeconds(10)).until(() -> hook.delivered.size() >= 2);
            assertThat(hook.delivered.get(0)).startsWith("🔴 GPU 추론 서버(FD9)에 연결할 수 없어요");
            assertThat(hook.delivered.get(1)).startsWith("📦 작업 알림 4건을 한 번에 보내요 (✅ 2 · ⚠️ 2)")
                    .contains("`fd9a0001`").contains("`fd9a0002`").contains("`fd9a0003`").contains("`fd9a0004`");
            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> hook.delivered.size() == 2);
        }
    }

    /** Job results that fail are dropped, counted, and summed up in the first message that gets through afterwards. */
    @Test
    void jobResultsThatCouldNotBeSentAreSummedUpOnceTheWebhookWorks() throws IOException {
        try (FakeHook hook = new FakeHook()) {
            hook.answer.set(503);
            WebhookNotifier notifier = new WebhookNotifier(hook.url(), "", "", 0, 300, JSON);
            notifier.onJobFinished(job("fd9b0001", JobStatus.FAILED));
            await().atMost(Duration.ofSeconds(5)).until(() -> hook.received.size() == 1);   // tried once, failed

            hook.answer.set(204);
            notifier.onJobFinished(job("fd9b0002", JobStatus.COMPLETED));
            await().atMost(Duration.ofSeconds(5)).until(() -> hook.delivered.size() == 2);
            assertThat(hook.delivered.get(0)).startsWith("📭 디스코드 알림을 보내지 못해 빠진 알림이 있어요 (")
                    .contains("부터 실패) — ⚠️ 실패 1건. 작업 결과는 관리 페이지에서 확인해 주세요");
            assertThat(hook.delivered.get(1)).startsWith("✅ 3D 모델 완성 · 작업 `fd9b0002`");
            assertThat(hook.received).noneMatch(text -> text.contains("fd9b0001") && hook.delivered.contains(text));
            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> hook.delivered.size() == 2);
        }
    }

    /** Discord's rate limit (429 + Retry-After): the same message again after that wait, nothing dropped or summed up. */
    @Test
    void aRateLimitedMessageIsSentAgainAfterRetryAfter() throws IOException {
        try (FakeHook hook = new FakeHook()) {
            hook.rateLimitOnce.set(true);
            WebhookNotifier notifier = new WebhookNotifier(hook.url(), "", "", 0, 60_000, JSON);
            long started = System.nanoTime();
            notifier.onJobFinished(job("fd9c0001", JobStatus.COMPLETED));

            await().atMost(Duration.ofSeconds(5)).until(() -> hook.delivered.size() == 1);
            assertThat((System.nanoTime() - started) / 1_000_000).as("Retry-After 0.4 s, not the 60 s pause")
                    .isBetween(350L, 4_000L);
            assertThat(hook.received).hasSize(2).allSatisfy(text -> assertThat(text).contains("fd9c0001"));
            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> hook.delivered.size() == 1);
        }
    }

    /**
     * A full queue drops the oldest message that is waiting, never the one being sent: it may yet be delivered (and
     * then it must not be reported as lost too). Here 1 is on its way and 60 more come: 10 are dropped, not 11.
     */
    @Test
    void anOverflowNeverDropsTheMessageBeingSent() throws IOException {
        try (FakeHook hook = new FakeHook()) {
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            hook.holdNext.set(release);
            WebhookNotifier notifier = new WebhookNotifier(hook.url(), "", "", 0, 100, JSON);
            notifier.onJobFinished(job("fd9d0000", JobStatus.COMPLETED));
            await().atMost(Duration.ofSeconds(5)).until(() -> hook.received.size() == 1);   // on its way, held
            for (int i = 1; i <= 60; i++) {
                notifier.onJobFinished(job(String.format("fd9d%04d", i), JobStatus.COMPLETED));
            }
            release.countDown();

            await().atMost(Duration.ofSeconds(10)).until(() -> hook.delivered.size() >= 3);
            assertThat(hook.delivered.get(0)).startsWith("✅ 3D 모델 완성 · 작업 `fd9d0000`");
            assertThat(hook.delivered.get(1)).startsWith("📭").contains("— ✅ 완성 10건.");
            assertThat(hook.delivered.get(2)).startsWith("📦 작업 알림 50건을 한 번에 보내요 (✅ 50 · ⚠️ 0)")
                    .contains("`fd9d0011`").doesNotContain("`fd9d0010`").doesNotContain("`fd9d0000`");
            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> hook.delivered.size() == 3);
        }
    }

    /** Losses counted while the summary is on its way are not erased by it: the next summary reports them. */
    @Test
    void lossesDuringTheSummaryAreReportedNextTime() throws IOException {
        try (FakeHook hook = new FakeHook()) {
            hook.answer.set(503);
            WebhookNotifier notifier = new WebhookNotifier(hook.url(), "", "", 0, 300, JSON);
            notifier.onJobFinished(job("fd9e0000", JobStatus.FAILED));   // fails: one lost
            await().atMost(Duration.ofSeconds(5)).until(() -> hook.received.size() == 1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            hook.holdNext.set(release);
            hook.answer.set(204);
            await().atMost(Duration.ofSeconds(5)).until(() -> hook.received.size() == 2);   // the summary, held
            for (int i = 1; i <= 52; i++) {   // 50 fit: 2 are dropped while the summary is on its way
                notifier.onJobFinished(job(String.format("fd9e%04d", i), JobStatus.COMPLETED));
            }
            release.countDown();

            await().atMost(Duration.ofSeconds(10)).until(() -> hook.delivered.size() >= 3);
            assertThat(hook.delivered.get(0)).startsWith("📭").contains("— ⚠️ 실패 1건.").doesNotContain("완성");
            assertThat(hook.delivered.get(1)).startsWith("📭").contains("— ✅ 완성 2건.").doesNotContain("⚠️ 실패");
            assertThat(hook.delivered.get(2)).startsWith("📦 작업 알림 50건");
            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> hook.delivered.size() == 3);
        }
    }

    @Test
    void retryAfterIsReadInSecondsWithinLimits() {
        assertThat(WebhookNotifier.retryAfterMs("2")).isEqualTo(2_000);
        assertThat(WebhookNotifier.retryAfterMs("0.35")).isEqualTo(350);
        assertThat(WebhookNotifier.retryAfterMs("0")).isEqualTo(100);
        assertThat(WebhookNotifier.retryAfterMs("86400")).isEqualTo(300_000);
        assertThat(WebhookNotifier.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT")).isEqualTo(1_000);
        assertThat(WebhookNotifier.retryAfterMs(null)).isEqualTo(1_000);
    }

    private static JobFinishedEvent job(String jobId, JobStatus status) {
        return new JobFinishedEvent(jobId, 1, status, status == JobStatus.FAILED ? "INFERENCE_UNAVAILABLE" : null,
                9_000L, "real", null, false);
    }

    /** A webhook of its own per test (the shared one also gets the Spring context's messages). */
    private static final class FakeHook implements AutoCloseable {
        final AtomicInteger answer = new AtomicInteger(204);
        final java.util.concurrent.atomic.AtomicBoolean rateLimitOnce = new java.util.concurrent.atomic.AtomicBoolean();
        /** When set, the next message is held (after it is recorded) until the latch opens: a send in progress. */
        final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> holdNext =
                new java.util.concurrent.atomic.AtomicReference<>();
        /** Every message that arrived, and those answered with a 2xx. */
        final List<String> received = new CopyOnWriteArrayList<>();
        final List<String> delivered = new CopyOnWriteArrayList<>();
        private final HttpServer server;

        FakeHook() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/hook", exchange -> {
                String text = content(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                received.add(text);
                java.util.concurrent.CountDownLatch hold = holdNext.getAndSet(null);
                if (hold != null) {
                    try {
                        hold.await(4, java.util.concurrent.TimeUnit.SECONDS);   // under the notifier's 5 s timeout
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                int code = answer.get();
                if (rateLimitOnce.getAndSet(false)) {
                    code = 429;
                    exchange.getResponseHeaders().add("Retry-After", "0.4");
                } else if (code / 100 == 2) {
                    delivered.add(text);
                }
                exchange.sendResponseHeaders(code, -1);
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static String hookUrl() {
        return "http://127.0.0.1:" + webhook.getAddress().getPort() + "/hook";
    }

    /** GPU messages only: job messages from the other tests' uploads may still arrive. */
    private static List<String> gpuMessages() {
        return received.stream().map(WebhookNotifierTest::content).filter(text -> text.contains("GPU 추론 서버")).toList();
    }

    /** The token, stuck-run and model-load alerts among the GPU messages. */
    private static List<String> alertMessages() {
        return gpuMessages().stream().filter(text -> text.startsWith("🔑") || text.startsWith("🐢")
                || text.contains("오래 걸리던 작업") || text.startsWith("🧩")).toList();
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
