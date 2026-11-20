package com.example.mockbackend.service;

import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.service.InferenceHealthMonitor.Alert;
import com.example.mockbackend.service.InferenceHealthMonitor.AlertChanged;
import com.example.mockbackend.service.InferenceHealthMonitor.State;
import com.example.mockbackend.service.InferenceHealthMonitor.StateChanged;
import com.example.mockbackend.service.InferenceHealthMonitor.Status;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Team chat notifications (Discord incoming webhook: POST {"content": "..."}) when a run ends and when the GPU
 * inference service comes up or goes away. Off while notify.webhook-url is empty (the default). The listeners only
 * hand the message to this class's own single sender thread after JobFinisher has saved and measured the run, so a
 * slow or failing webhook only costs a log line and never waits behind (or holds up) the shared @Async pool, whose
 * threads real-job dispatches may be blocking on the inference slot. Messages carry a short job id and no server
 * paths or addresses; with notify.public-base-url they link to the job page or the dashboard when this build has one.
 * <p>
 * GPU messages name the server with notify.inference-label when set (e.g. "Runpod A40"). With notify.down-grace-ms
 * above 0 a DOWN is announced only if no other change follows within that time, and the UP that ends a DOWN nobody
 * heard about is not announced either: the VM reaches a Runpod Pod over a Tailscale relay that can miss one /healthz
 * check (seen 2026-11-17, back 15 s later), which would otherwise post a false "unreachable" and "connected" pair.
 * A LOADING after a short DOWN (the service restarted and loads its model) is still news and is announced.
 * <p>
 * Three alerts come from readings the service did answer (InferenceHealthMonitor.AlertChanged, 2026-11-20): the
 * service rejects the backend's token (tokenOk=false, every job fails at once), one run has held the GPU longer than
 * inference.stuck-alert-ms (busyForMs, healthz UP but inference stuck), and the model failed to load (loadError,
 * healthz LOADING for good: failure drill FD18; the message names the exception type only). Each posts once when it
 * starts and once when it ends, with no grace time.
 */
@Component
public class WebhookNotifier {
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** Discord rejects longer content. */
    static final int MAX_CONTENT = 2000;
    /** Longer labels are cut so a GPU message stays one short line. */
    static final int MAX_LABEL = 40;
    /** A Python exception class name, as the model-load alert shows it. */
    private static final Pattern EXCEPTION_TYPE = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]{0,79}");

    private final URI webhook;
    private final String publicBaseUrl;
    private final String inferenceLabel;
    private final long downGraceMs;
    private final boolean hasJobPage = new ClassPathResource("static/job/index.html").exists();
    private final boolean hasAdminPage = new ClassPathResource("static/admin/index.html").exists();
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    /** Sends, one at a time, in order. */
    private final ScheduledThreadPoolExecutor sender = daemonExecutor("webhook-notifier");
    /** Grace timers only, so a due DOWN never waits behind a slow send. */
    private final ScheduledThreadPoolExecutor timer = daemonExecutor("webhook-grace");
    private final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);
    /** The GPU state the channel last heard about (UNKNOWN until the first; a normal start counts as UP). Guarded by this. */
    private State announced = State.UNKNOWN;
    /** Counts GPU state changes, so a grace timer that another change has overtaken does nothing. Guarded by this. */
    private long changes;
    /** The DOWN waiting out its grace time, else null, and when it began (System.nanoTime). Guarded by this. */
    private Status pendingDown;
    private long pendingDownSince;
    /** The alerts the channel last heard as started (and not yet as ended). Guarded by this. */
    private final Set<Alert> alertsAnnounced = EnumSet.noneOf(Alert.class);

    public WebhookNotifier(String webhookUrl, String publicBaseUrl, ObjectMapper objectMapper) {
        this(webhookUrl, publicBaseUrl, "", 0, objectMapper);
    }

    @Autowired
    public WebhookNotifier(@Value("${notify.webhook-url:}") String webhookUrl,
                           @Value("${notify.public-base-url:}") String publicBaseUrl,
                           @Value("${notify.inference-label:}") String inferenceLabel,
                           @Value("${notify.down-grace-ms:0}") long downGraceMs,
                           ObjectMapper objectMapper) {
        this.webhook = webhookUrl == null || webhookUrl.isBlank() ? null : URI.create(webhookUrl.trim());
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
        this.inferenceLabel = cleanLabel(inferenceLabel);
        this.downGraceMs = Math.max(0, downGraceMs);
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT).build();
    }

    private static ScheduledThreadPoolExecutor daemonExecutor(String name) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
        // A DOWN still waiting when the app stops is dropped, not sent from a closing context.
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    public boolean enabled() {
        return webhook != null;
    }

    @EventListener
    public void onJobFinished(JobFinishedEvent event) {
        if (enabled()) {
            post(jobMessage(event, linkFor(event.jobId())));
        }
    }

    @EventListener
    public void onInferenceStateChanged(StateChanged event) {
        if (enabled()) {
            stateChanged(event);
        }
    }

    /**
     * Token mismatch, stuck-run and model-load alerts (InferenceHealthMonitor publishes them only when they start or
     * end). Posted at once, without the DOWN grace time: they come from readings the service did answer. A repeat of
     * what the channel already heard, or the end of an alert it never heard, posts nothing.
     */
    @EventListener
    public void onInferenceAlert(AlertChanged event) {
        if (enabled()) {
            alertChanged(event);
        }
    }

    private synchronized void alertChanged(AlertChanged event) {
        if (event.active() == alertsAnnounced.contains(event.alert())) {
            return;
        }
        if (event.active()) {
            alertsAnnounced.add(event.alert());
        } else {
            alertsAnnounced.remove(event.alert());
        }
        post(alertText(event, inferenceLabel));
    }

    /**
     * A DOWN waits notify.down-grace-ms (when above 0) and is dropped if another change comes before that time is up.
     * A change that comes later first announces the DOWN, even if its timer has not run yet.
     */
    private synchronized void stateChanged(StateChanged event) {
        long change = ++changes;
        announceOverdueDown();
        if (event.current().state() == State.DOWN && downGraceMs > 0) {
            pendingDown = event.current();
            pendingDownSince = System.nanoTime();
            try {
                timer.schedule(() -> announceDownIfLatest(change), downGraceMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                log.debug("webhook timer stopped; GPU state not announced");
            }
        } else {
            pendingDown = null;
            announce(event.current());
        }
    }

    private synchronized void announceDownIfLatest(long change) {
        if (change == changes && pendingDown != null) {
            Status down = pendingDown;
            pendingDown = null;
            announce(down);
        }
    }

    /** Caller holds the lock. */
    private void announceOverdueDown() {
        if (pendingDown != null && System.nanoTime() - pendingDownSince >= TimeUnit.MILLISECONDS.toNanos(downGraceMs)) {
            Status down = pendingDown;
            pendingDown = null;
            announce(down);
        }
    }

    /** Caller holds the lock. Says nothing when the channel already knows the state or it is the normal start. */
    private void announce(Status current) {
        State to = current.state();
        State before = announced;
        if (to == before || to == State.UNKNOWN) {
            return;
        }
        announced = to;
        if (before == State.UNKNOWN && to == State.UP) {
            return;
        }
        post(stateText(current, inferenceLabel));
    }

    private void post(String message) {
        try {
            sender.execute(() -> send(message));
        } catch (RejectedExecutionException e) {
            log.debug("webhook sender stopped; message dropped");
        }
    }

    @PreDestroy
    void stop() {
        timer.shutdown();
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
    public static String stateMessage(StateChanged event) {
        return stateMessage(event, "");
    }

    /** As {@link #stateMessage(StateChanged)}, naming the server with {@code label} when it is not blank. */
    public static String stateMessage(StateChanged event, String label) {
        if (event.previous().state() == State.UNKNOWN && event.current().state() == State.UP) {
            return null;
        }
        return stateText(event.current(), cleanLabel(label));
    }

    /** The message for an alert that starts or ends, naming the server with {@code label} when it is not blank. */
    public static String alertMessage(AlertChanged event, String label) {
        return alertText(event, cleanLabel(label));
    }

    /** {@code label} is already cleaned. Never names the token, a host or a path. */
    static String alertText(AlertChanged event, String label) {
        String server = serverName(label);
        return switch (event.alert()) {
            case TOKEN_REJECTED -> event.active()
                    ? "🔑 " + server + "가 백엔드의 추론 토큰을 받지 않아요 — 새 작업이 모두 바로 실패합니다"
                            + " (INFERENCE_UNAVAILABLE). 양쪽 INFERENCE_TOKEN 을 확인해 주세요"
                    : "🔑 " + server + "가 추론 토큰을 다시 받아요";
            case STUCK -> event.active()
                    ? "🐢 " + server + "의 작업 하나가 " + duration(busyForMs(event)) + "째 끝나지 않아요 (기준 "
                            + duration(event.stuckAlertMs()) + ") — 추론이 멈췄을 수 있어요."
                            + " 그동안 다른 작업은 기다리거나 시간 초과로 실패합니다"
                    : "🟢 " + server + "의 오래 걸리던 작업이 끝났어요";
            case MODEL_LOAD_FAILED -> event.active()
                    ? "🧩 " + server + "가 모델을 불러오지 못했어요 (" + loadErrorType(event) + ")"
                            + " — 고쳐서 다시 띄우기 전에는 새 작업이 처리되지 않아요. 추론 서비스 로그를 확인해 주세요"
                    : "🧩 " + server + "의 모델 불러오기 오류가 풀렸어요";
        };
    }

    /**
     * The exception type that starts the service's loadError ("FileNotFoundError: ..."), never the rest of the text
     * (it may describe paths); a text that does not start with a type name gives a plain word.
     */
    private static String loadErrorType(AlertChanged event) {
        String error = event.current() == null ? null : event.current().loadError();
        if (error != null) {
            int colon = error.indexOf(':');
            String type = (colon < 0 ? error : error.substring(0, colon)).strip();
            if (EXCEPTION_TYPE.matcher(type).matches()) {
                return type;
            }
        }
        return "알 수 없는 오류";
    }

    private static long busyForMs(AlertChanged event) {
        Long busy = event.current() == null ? null : event.current().busyForMs();
        return busy == null ? event.stuckAlertMs() : busy;
    }

    private static String serverName(String label) {
        return label.isEmpty() ? "GPU 추론 서버" : "GPU 추론 서버(" + label + ")";
    }

    /** {@code label} is already cleaned; null for UNKNOWN. */
    static String stateText(Status current, String label) {
        String server = serverName(label);
        return switch (current.state()) {
            case UP -> "🟢 " + server + " 연결됨" + (current.device() == null ? "" : " (" + current.device() + ")")
                    + " — 기다리던 작업을 이어서 처리합니다";
            case LOADING -> "🟡 " + server + "는 켜졌지만 모델이 아직 준비되지 않았어요";
            case DOWN -> "🔴 " + server + "에 연결할 수 없어요 (" + current.detail() + ") — 새 작업은 대기열에서 기다립니다";
            case UNKNOWN -> null;
        };
    }

    /** One line of at most MAX_LABEL characters: control characters and runs of spaces become one space. */
    static String cleanLabel(String label) {
        if (label == null) {
            return "";
        }
        String line = label.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        if (line.codePointCount(0, line.length()) <= MAX_LABEL) {
            return line;
        }
        return line.substring(0, line.offsetByCodePoints(0, MAX_LABEL)).strip();
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
