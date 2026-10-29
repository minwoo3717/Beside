package com.example.mockbackend;

import com.example.mockbackend.service.InferenceHealthMonitor;
import com.example.mockbackend.service.InferenceHealthMonitor.State;
import com.example.mockbackend.service.InferenceHealthMonitor.StateChanged;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

/** The GPU PC's /healthz read as UP / LOADING / DOWN, with an event on every change of state. */
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

    private InferenceHealthMonitor monitor(String token) {
        String baseUrl = "http://127.0.0.1:" + (server == null ? 1 : server.getAddress().getPort()) + "/";
        return new InferenceHealthMonitor(baseUrl, token, new ObjectMapper(), events::add);
    }

    private void answer(boolean modelLoaded, boolean passthrough) {
        status.set(200);
        body.set("{\"status\":\"ok\",\"device\":\"cuda\",\"modelLoaded\":" + modelLoaded
                + ",\"passthrough\":" + passthrough + "}");
    }
}
