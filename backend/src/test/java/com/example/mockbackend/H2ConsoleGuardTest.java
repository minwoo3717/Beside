package com.example.mockbackend;

import com.example.mockbackend.config.H2ConsoleGuardFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The H2 console of the mock profile (also on in real) through the real Tomcat: a request made on this machine reaches
 * it, one forwarded by a tunnel or proxy on the same host (it arrives from 127.0.0.1 too) gets 404. MockMvc cannot
 * show this: it never reaches servlets other than Spring's DispatcherServlet.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class H2ConsoleGuardTest {

    @LocalServerPort
    int port;

    @Test
    void aDirectLocalRequestReachesTheConsole() throws IOException {
        assertThat(status("/h2-console/", "localhost:" + port)).isIn(200, 302);
        assertThat(status("/h2-console/", "127.0.0.1:" + port)).isIn(200, 302);
    }

    @Test
    void aTunnelledOrProxiedRequestIsAnsweredAsIfTheConsoleWereOff() throws IOException {
        assertThat(status("/h2-console/", "abc.trycloudflare.com")).isEqualTo(404);           // tunnel keeps its Host
        assertThat(status("/h2-console/", "localhost:" + port, "X-Forwarded-For: 203.0.113.7")).isEqualTo(404);
        assertThat(status("/h2-console/login.do", "localhost", "CF-Connecting-IP: 203.0.113.7")).isEqualTo(404);
        assertThat(status("/h2-console;x=1/", "abc.trycloudflare.com")).isEqualTo(404);       // path parameter
        assertThat(status("/h2%2Dconsole/", "abc.trycloudflare.com")).isEqualTo(404);         // encoded name
    }

    @Test
    void otherPathsAreNotTouched() throws IOException {
        assertThat(status("/api/v1/healthz", "abc.trycloudflare.com", "X-Forwarded-For: 203.0.113.7")).isEqualTo(200);
    }

    @Test
    void hostNamesWithPortsAndIpv6() {
        assertThat(H2ConsoleGuardFilter.isDirectLocal(request("[::1]:8080"))).isTrue();
        assertThat(H2ConsoleGuardFilter.isDirectLocal(request("LOCALHOST"))).isTrue();
        assertThat(H2ConsoleGuardFilter.isDirectLocal(request("localhost.evil.example"))).isFalse();
        assertThat(H2ConsoleGuardFilter.isDirectLocal(request("192.168.0.10:8080"))).isFalse();
        assertThat(H2ConsoleGuardFilter.isDirectLocal(request(null))).isFalse();
    }

    private static MockHttpServletRequest request(String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/h2-console/");
        request.removeHeader("Host");
        if (host != null) {
            request.addHeader("Host", host);
        }
        return request;
    }

    /** Status code of a raw HTTP/1.1 GET (raw: java.net.http does not let a client choose the Host header). */
    private int status(String path, String host, String... headers) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            StringBuilder request = new StringBuilder("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\n");
            for (String header : headers) {
                request.append(header).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            String statusLine = new String(in.readNBytes(12), StandardCharsets.US_ASCII); // "HTTP/1.1 200"
            return Integer.parseInt(statusLine.substring(9, 12));
        }
    }
}
