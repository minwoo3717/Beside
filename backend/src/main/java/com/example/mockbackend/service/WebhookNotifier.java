package com.example.mockbackend.service;

import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.service.InferenceHealthMonitor.State;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PreDestroy;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Team chat notifications (Discord incoming webhook: POST {"content": "..."}) when a run ends and when the GPU
 * inference service comes up or goes away. Off while notify.webhook-url is empty (the default). The listeners only
 * hand the message to this class's own single sender thread after JobFinisher has saved and measured the run, so a
 * slow or failing webhook only costs a log line and never waits behind (or holds up) the shared @Async pool, whose
 * threads real-job dispatches may be blocking on the inference slot. Messages carry a short job id and no server
 * paths or addresses; with notify.public-base-url they link to the job page or the dashboard when this build has one.
 */
@Component
public class WebhookNotifier {
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** Discord rejects longer content. */
    static final int MAX_CONTENT = 2000;

    private final URI webhook;
    private final String publicBaseUrl;
    private final boolean hasJobPage = new ClassPathResource("static/job/index.html").exists();
    private final boolean hasAdminPage = new ClassPathResource("static/admin/index.html").exists();
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "webhook-notifier");
        thread.setDaemon(true);
        return thread;
    });
    private final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    public WebhookNotifier(@Value("${notify.webhook-url:}") String webhookUrl,
                           @Value("${notify.public-base-url:}") String publicBaseUrl,
                           ObjectMapper objectMapper) {
        this.webhook = webhookUrl == null || webhookUrl.isBlank() ? null : URI.create(webhookUrl.trim());
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT).build();
    }

    public boolean enabled() {
        return webhook != null;
    }

    @EventListener
    public void onJobFinished(JobFinishedEvent event) {
        if (enabled()) {
            String message = jobMessage(event, linkFor(event.jobId()));
            sender.execute(() -> send(message));
        }
    }

    @EventListener
    public void onInferenceStateChanged(InferenceHealthMonitor.StateChanged event) {
        String message = stateMessage(event);
        if (enabled() && message != null) {
            sender.execute(() -> send(message));
        }
    }

    @PreDestroy
    void stop() {
        sender.shutdown();
    }

    /** The page a message links to: the job page or the dashboard when this build serves one, else none. */
    String linkFor(String jobId) {
        if (publicBaseUrl.isEmpty()) {
            return "";
        }
        if (hasJobPage) {
            return publicBaseUrl + "/job/?id=" + jobId;
        }
        return hasAdminPage ? publicBaseUrl + "/admin/" : "";
    }

    /** {@code link} is appended on its own line when not empty. */
    public static String jobMessage(JobFinishedEvent event, String link) {
        StringBuilder text = new StringBuilder();
        if (event.status() == JobStatus.COMPLETED) {
            text.append("✅ 3D 모델 완성 · 작업 `").append(shortId(event.jobId())).append('`');
        } else {
            text.append("⚠️ 3D 생성 실패 · 작업 `").append(shortId(event.jobId())).append("` · ")
                    .append(event.errorCode() == null ? "INTERNAL_ERROR" : event.errorCode());
        }
        if (event.totalMs() != null) {
            text.append(" · ").append(duration(event.totalMs()));
        }
        if (event.attempt() > 1) {
            text.append(" · ").append(event.attempt()).append("번째 시도");
        }
        if (event.passthrough()) {
            text.append(" · 패스스루 샘플(측정 제외)");
        } else if (event.modelVersion() != null) {
            text.append(" · ").append(event.modelVersion());
        }
        if (event.workerType() != null && !"real".equals(event.workerType())) {
            text.append(" · ").append(event.workerType());
        }
        if (link != null && !link.isEmpty()) {
            text.append('\n').append(link);
        }
        return text.toString();
    }

    /** Null when nothing is worth a message: the first reading after startup being UP is the normal case. */
    public static String stateMessage(InferenceHealthMonitor.StateChanged event) {
        State from = event.previous().state();
        State to = event.current().state();
        if (from == State.UNKNOWN && to == State.UP) {
            return null;
        }
        return switch (to) {
            case UP -> "🟢 GPU 추론 서버 연결됨" + (event.current().device() == null ? "" : " (" + event.current().device() + ")")
                    + " — 기다리던 작업을 이어서 처리합니다";
            case LOADING -> "🟡 GPU 추론 서버는 켜졌지만 모델이 아직 준비되지 않았어요";
            case DOWN -> "🔴 GPU 추론 서버에 연결할 수 없어요 (" + event.current().detail() + ") — 새 작업은 대기열에서 기다립니다";
            case UNKNOWN -> null;
        };
    }

    static String shortId(String jobId) {
        return jobId == null ? "?" : jobId.substring(0, Math.min(8, jobId.length()));
    }

    static String duration(long ms) {
        long seconds = Math.round(ms / 1000.0);
        if (seconds < 60) {
            return seconds + "초";
        }
        return (seconds / 60) + "분 " + (seconds % 60) + "초";
    }

    private void send(String content) {
        String clipped = content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT) : content;
        try {
            String body = objectMapper.writeValueAsString(Map.of("content", clipped));
            HttpRequest request = HttpRequest.newBuilder(webhook)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                log.warn("webhook answered HTTP {}", response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            log.warn("webhook not sent: {}", e.getClass().getSimpleName());
        }
    }
}
