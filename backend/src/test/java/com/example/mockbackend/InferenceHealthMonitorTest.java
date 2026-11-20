package com.example.mockbackend;

import com.example.mockbackend.service.InferenceHealthMonitor;
import com.example.mockbackend.service.InferenceHealthMonitor.Alert;
import com.example.mockbackend.service.InferenceHealthMonitor.AlertChanged;
import com.example.mockbackend.service.InferenceHealthMonitor.State;
import com.example.mockbackend.service.InferenceHealthMonitor.StateChanged;
import com.example.mockbackend.service.InferenceHealthMonitor.Status;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The GPU PC's /healthz read as UP / LOADING / DOWN, with an event on every change of state, and the 2026-11-20
 * fields tokenOk / busyForMs / loadError turned into alert events (FD17 token mismatch, FD13 stuck run, FD18 model
 * load failure) that never move the state.
 */
class InferenceHealthMonitorTest {
    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> tokenSeen = new AtomicReference<>();
    private final List<Object> events = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/healthz", exchange -> {
            tokenSeen.set(exchange.getRequestHeaders().getFirst("X-Inference-Token"));
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void loadedModelOrPassthroughIsUpOtherwiseLoading() {
        InferenceHealthMonitor monitor = monitor("");
        assertThat(monitor.current().state()).isEqualTo(State.UNKNOWN);

        answer(true, false);
        assertThat(monitor.refresh().state()).isEqualTo(State.UP);
        assertThat(monitor.current().device()).isEqualTo("cuda");
        assertThat(monitor.current().ready()).isTrue();

        answer(false, false);
        assertThat(monitor.refresh().state()).isEqualTo(State.LOADING);
        assertThat(monitor.current().ready()).isFalse();

        answer(false, true);
        assertThat(monitor.refresh().state()).isEqualTo(State.UP);
    }

    @Test
    void errorStatusGarbageOrNoServerIsDown() {
        InferenceHealthMonitor monitor = monitor("");
        status.set(500);
        body.set("{}");
        assertThat(monitor.refresh().state()).isEqualTo(State.DOWN);

        status.set(200);
        body.set("not json");
        assertThat(monitor.refresh().state()).isEqualTo(State.DOWN);

        server.stop(0);
        server = null;
        assertThat(monitor.refresh().state()).isEqualTo(State.DOWN);
        assertThat(monitor.current().detail()).doesNotContain("127.0.0.1");
    }

    @Test
    void publishesOnlyChangesAndKeepsWhenTheStateBegan() {
        InferenceHealthMonitor monitor = monitor("");
        answer(true, false);
        monitor.refresh();
        var upSince = monitor.current().since();
        monitor.refresh();
        assertThat(monitor.current().since()).isEqualTo(upSince);

        status.set(503);
        monitor.refresh();
        monitor.refresh();

        assertThat(events).hasSize(2);
        StateChanged first = (StateChanged) events.get(0);
        StateChanged second = (StateChanged) events.get(1);
        assertThat(first.previous().state()).isEqualTo(State.UNKNOWN);
        assertThat(first.current().state()).isEqualTo(State.UP);
        assertThat(second.previous().state()).isEqualTo(State.UP);
        assertThat(second.current().state()).isEqualTo(State.DOWN);
    }

    @Test
    void sendsTheSharedTokenWhenConfigured() {
        answer(true, false);
        monitor("s3cret").refresh();
        assertThat(tokenSeen.get()).isEqualTo("s3cret");
        monitor("").refresh();
        assertThat(tokenSeen.get()).isNull();
    }

    @Test
    void readsTokenOkAndBusyForMsWithoutAlertingWhenAllIsWell() {
        InferenceHealthMonitor monitor = monitor("s3cret");
        answer(true, false, ",\"tokenOk\":true,\"busyForMs\":8700");
        Status reading = monitor.refresh();
        assertThat(tokenSeen.get()).isEqualTo("s3cret");
        assertThat(reading.tokenOk()).isTrue();
        assertThat(reading.busyForMs()).isEqualTo(8700L);
        assertThat(alerts()).isEmpty();
    }

    /** FD17: /healthz is UP but every job would get 401. One message when it starts, one when it ends, none between. */
    @Test
    void aRejectedTokenIsAlertedOnceAndItsRecoveryOnce() {
        InferenceHealthMonitor monitor = monitor("s3cret");
        answer(true, false, ",\"tokenOk\":false,\"busyForMs\":0");
        for (int i = 0; i < 3; i++) {
            Status reading = monitor.refresh();
            assertThat(reading.state()).isEqualTo(State.UP);   // jobs are still dispatched as today
            assertThat(reading.ready()).isTrue();
            assertThat(reading.tokenOk()).isFalse();
        }
        assertThat(alerts()).singleElement().satisfies(alert -> {
            assertThat(alert.alert()).isEqualTo(Alert.TOKEN_REJECTED);
            assertThat(alert.active()).isTrue();
        });

        answer(true, false, ",\"tokenOk\":true,\"busyForMs\":0");
        monitor.refresh();
        monitor.refresh();
        assertThat(alerts()).hasSize(2);
        assertThat(alerts().get(1).alert()).isEqualTo(Alert.TOKEN_REJECTED);
        assertThat(alerts().get(1).active()).isFalse();
        assertThat(stateChanges()).hasSize(1);   // UNKNOWN -> UP only: the alerts never move the state
    }

    /** FD13: /healthz is UP while one run has been on the GPU for longer than inference.stuck-alert-ms. */
    @Test
    void aRunBusyLongerThanTheLimitIsAlertedOnceAndItsEndOnce() {
        InferenceHealthMonitor monitor = monitor("s3cret", 120_000);
        for (long busy : new long[]{0, 8_700, 120_000}) {   // up to the limit: normal
            answer(true, false, ",\"tokenOk\":true,\"busyForMs\":" + busy);
            monitor.refresh();
        }
        assertThat(alerts()).isEmpty();

        for (long busy : new long[]{120_001, 300_000, 600_000}) {
            answer(true, false, ",\"tokenOk\":true,\"busyForMs\":" + busy);
            Status reading = monitor.refresh();
            assertThat(reading.ready()).isTrue();
        }
        assertThat(alerts()).singleElement().satisfies(alert -> {
            assertThat(alert.alert()).isEqualTo(Alert.STUCK);
            assertThat(alert.active()).isTrue();
            assertThat(alert.current().busyForMs()).isEqualTo(120_001L);
            assertThat(alert.stuckAlertMs()).isEqualTo(120_000L);
        });

        answer(true, false, ",\"tokenOk\":true,\"busyForMs\":0");
        monitor.refresh();
        answer(true, false, ",\"tokenOk\":true,\"busyForMs\":9000");   // the next run, a normal one
        monitor.refresh();
        assertThat(alerts()).hasSize(2);
        assertThat(alerts().get(1).alert()).isEqualTo(Alert.STUCK);
        assertThat(alerts().get(1).active()).isFalse();
        assertThat(stateChanges()).hasSize(1);
    }

    /**
     * FD18: the service answers but its model failed to load (a wrong checkpoint). Still LOADING, so jobs wait PENDING
     * as today; one alert when loadError appears, one when it clears (the restarted service loads again).
     */
    @Test
    void aModelLoadErrorIsAlertedOnceAndItsClearingOnceWithoutChangingTheState() {
        InferenceHealthMonitor monitor = monitor("s3cret");
        answer(false, false, ",\"tokenOk\":true,\"busyForMs\":0,\"loadError\":null");   // still loading: no error
        Status loading = monitor.refresh();
        assertThat(loading.loadFailed()).isFalse();
        assertThat(loading.loadError()).isNull();
        assertThat(alerts()).isEmpty();

        String error = "FileNotFoundError: [Errno 2] No such file or directory: '<path>'";
        answer(false, false, ",\"tokenOk\":true,\"busyForMs\":0,\"loadError\":\"" + error + "\"");
        for (int i = 0; i < 3; i++) {
            Status reading = monitor.refresh();
            assertThat(reading.state()).isEqualTo(State.LOADING);   // jobs keep waiting PENDING as before
            assertThat(reading.ready()).isFalse();
            assertThat(reading.loadFailed()).isTrue();
            assertThat(reading.loadError()).isEqualTo(error);
        }
        assertThat(alerts()).singleElement().satisfies(alert -> {
            assertThat(alert.alert()).isEqualTo(Alert.MODEL_LOAD_FAILED);
            assertThat(alert.active()).isTrue();
            assertThat(alert.current().loadError()).isEqualTo(error);
        });

        status.set(503);                                                                  // restarted ...
        monitor.refresh();
        answer(false, false, ",\"tokenOk\":true,\"busyForMs\":0,\"loadError\":null");   // ... and loading again
        monitor.refresh();
        answer(true, false, ",\"tokenOk\":true,\"busyForMs\":0,\"loadError\":null");
        monitor.refresh();
        assertThat(alerts()).hasSize(2);
        assertThat(alerts().get(1).alert()).isEqualTo(Alert.MODEL_LOAD_FAILED);
        assertThat(alerts().get(1).active()).isFalse();
        assertThat(stateChanges()).extracting(change -> change.current().state())
                .containsExactly(State.LOADING, State.DOWN, State.LOADING, State.UP);
    }

    /** The service's text is kept as one short line: a newline in it cannot forge a log line. */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aLoadErrorIsKeptAsOneShortLine(CapturedOutput output) {
        InferenceHealthMonitor monitor = monitor("");
        answer(false, false, ",\"loadError\":\"RuntimeError: first\\nFORGED line " + "x".repeat(500) + "\"");
        Status reading = monitor.refresh();
        assertThat(reading.loadFailed()).isTrue();
        assertThat(reading.loadError()).startsWith("RuntimeError: first FORGED line x").doesNotContain("\n")
                .hasSizeLessThanOrEqualTo(200);
        assertThat(output.getAll()).contains("RuntimeError: first FORGED line").doesNotContain("first\nFORGED");
    }

    @Test
    void theDefaultLimitIsTwoMinutesAndZeroTurnsTheStuckAlertOff() {
        InferenceHealthMonitor byDefault = monitor("s3cret");
        answer(true, false, ",\"tokenOk\":true,\"busyForMs\":120001");
        byDefault.refresh();
        assertThat(alerts()).singleElement().satisfies(alert -> assertThat(alert.alert()).isEqualTo(Alert.STUCK));

        events.clear();
        InferenceHealthMonitor off = monitor("s3cret", 0);
        answer(true, false, ",\"tokenOk\":true,\"busyForMs\":86400000");
        off.refresh();
        assertThat(alerts()).isEmpty();
    }

    /** An older inference service sends neither field: unknown, never an alert (nor a recovery). */
    @Test
    void missingOrUnreadableFieldsAreUnknownAndNeverAlert() {
        InferenceHealthMonitor monitor = monitor("s3cret", 1_000);
        answer(true, false);   // an older service
        Status reading = monitor.refresh();
        assertThat(reading.tokenOk()).isNull();
        assertThat(reading.busyForMs()).isNull();
        assertThat(reading.loadFailed()).isNull();
        answer(true, false, ",\"tokenOk\":\"no\",\"busyForMs\":\"999999\",\"loadError\":5");
        reading = monitor.refresh();
        assertThat(reading.tokenOk()).isNull();
        assertThat(reading.busyForMs()).isNull();
        assertThat(reading.loadFailed()).isNull();
        assertThat(reading.loadError()).isNull();
        answer(true, false, ",\"tokenOk\":null,\"busyForMs\":-5,\"loadError\":\"  \"");
        reading = monitor.refresh();
        assertThat(reading.loadFailed()).isNull();
        answer(false, false);  // an older service still loading or failed: it cannot tell, and neither can we
        assertThat(monitor.refresh().loadFailed()).isNull();
        assertThat(alerts()).isEmpty();
        answer(true, false);
        assertThat(monitor.refresh().ready()).isTrue();

        // Without a token of its own the monitor sends no header, so a tokenOk in the answer is not about it.
        InferenceHealthMonitor tokenless = monitor("", 1_000);
        answer(true, false, ",\"tokenOk\":false,\"busyForMs\":0");
        assertThat(tokenless.refresh().tokenOk()).isNull();
        assertThat(alerts()).isEmpty();
    }

    /** A DOWN or LOADING-without-fields reading in between neither repeats nor clears an alert. */
    @Test
    void unknownReadingsInBetweenNeitherRepeatNorClearAnAlert() {
        InferenceHealthMonitor monitor = monitor("s3cret", 1_000);
        answer(true, false, ",\"tokenOk\":false,\"busyForMs\":5000");
        monitor.refresh();
        assertThat(alerts()).hasSize(2);   // both conditions start

        status.set(503);                    // unreachable for a while
        monitor.refresh();
        answer(false, false);               // back, an older build loading its model
        monitor.refresh();
        answer(true, false, ",\"tokenOk\":false,\"busyForMs\":7000");   // still the same two problems
        monitor.refresh();
        assertThat(alerts()).hasSize(2);

        answer(true, false, ",\"tokenOk\":true,\"busyForMs\":0");
        monitor.refresh();
        assertThat(alerts()).hasSize(4);
        assertThat(alerts().subList(2, 4)).allSatisfy(alert -> assertThat(alert.active()).isFalse());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theTokenNeverReachesTheLog(CapturedOutput output) {
        InferenceHealthMonitor monitor = monitor("s3cret-value", 1_000);
        answer(true, false, ",\"tokenOk\":false,\"busyForMs\":5000");
        monitor.refresh();
        answer(true, false, ",\"tokenOk\":true,\"busyForMs\":0");
        monitor.refresh();
        assertThat(alerts()).hasSize(4);
        assertThat(output.getAll()).contains("tokenOk=false").doesNotContain("s3cret-value");
    }

    private InferenceHealthMonitor monitor(String token) {
        String baseUrl = "http://127.0.0.1:" + (server == null ? 1 : server.getAddress().getPort()) + "/";
        return new InferenceHealthMonitor(baseUrl, token, new ObjectMapper(), events::add);
    }

    private InferenceHealthMonitor monitor(String token, long stuckAlertMs) {
        String baseUrl = "http://127.0.0.1:" + (server == null ? 1 : server.getAddress().getPort()) + "/";
        return new InferenceHealthMonitor(baseUrl, token, stuckAlertMs, new ObjectMapper(), events::add);
    }

    private List<AlertChanged> alerts() {
        return events.stream().filter(AlertChanged.class::isInstance).map(AlertChanged.class::cast).toList();
    }

    private List<StateChanged> stateChanges() {
        return events.stream().filter(StateChanged.class::isInstance).map(StateChanged.class::cast).toList();
    }

    private void answer(boolean modelLoaded, boolean passthrough) {
        answer(modelLoaded, passthrough, "");
    }

    /** {@code extra}: more JSON members, starting with a comma. */
    private void answer(boolean modelLoaded, boolean passthrough, String extra) {
        status.set(200);
        body.set("{\"status\":\"ok\",\"device\":\"cuda\",\"modelLoaded\":" + modelLoaded
                + ",\"passthrough\":" + passthrough + extra + "}");
    }
}
