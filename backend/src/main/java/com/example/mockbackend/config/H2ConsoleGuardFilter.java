package com.example.mockbackend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The H2 console (on in the mock and real profiles, for job_runs exports) answers only requests made directly on this
 * machine. H2's own guard (web-allow-others=false) looks at the TCP peer, and anything a tunnel or proxy on the same
 * host forwards (cloudflared or ngrok for the phone, unity/README.md) arrives from 127.0.0.1: the console, which runs
 * SQL and through it Java as the server, would be open to whoever has the tunnel URL. A console request that carries a
 * forwarding header or names a host other than localhost is answered 404, as if the console were off. prod turns the
 * console off altogether (application-prod.properties).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class H2ConsoleGuardFilter extends OncePerRequestFilter {
    /** Headers a reverse proxy or tunnel adds (cloudflared, ngrok, Caddy, nginx, Tailscale serve). */
    static final List<String> FORWARDING_HEADERS = List.of("Forwarded", "X-Forwarded-For", "X-Forwarded-Host",
            "X-Forwarded-Proto", "X-Real-IP", "CF-Connecting-IP", "True-Client-IP");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final String consolePath;

    public H2ConsoleGuardFilter(@Value("${spring.h2.console.path:/h2-console}") String consolePath) {
        this.consolePath = consolePath;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // The decoded path Tomcat mapped (path parameters such as ";x=1" removed), not the raw request URI.
        String path = request.getServletPath() + Objects.toString(request.getPathInfo(), "");
        return !(path.equals(consolePath) || path.startsWith(consolePath + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isDirectLocal(request)) {
            chain.doFilter(request, response);
        } else {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    /** No forwarding header and a Host of localhost, 127.0.0.1 or [::1] (any port). */
    public static boolean isDirectLocal(HttpServletRequest request) {
        for (String header : FORWARDING_HEADERS) {
            if (request.getHeader(header) != null) {
                return false;
            }
        }
        String host = request.getHeader("Host");
        if (host == null) {
            return false;
        }
        String name = host.trim().toLowerCase(Locale.ROOT);
        int end = name.startsWith("[") ? name.indexOf(']') + 1 : name.indexOf(':');
        if (end > 0) {
            name = name.substring(0, end);
        }
        return LOCAL_HOSTS.contains(name);
    }
}
