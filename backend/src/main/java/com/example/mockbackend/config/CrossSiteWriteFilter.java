package com.example.mockbackend.config;

import com.example.mockbackend.api.v1.dto.ErrorDetail;
import com.example.mockbackend.api.v1.dto.ErrorResponse;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Refuses a write to the v1 API (POST, PUT, PATCH, DELETE) that a browser sends from a page of another site
 * (review finding, 2026-11-21). In production the team pages and APIs sit behind Caddy Basic auth, and a browser that has
 * logged in attaches those credentials to every request to this host, also a form POST another site submits: such a
 * "simple" request needs no CORS preflight, so without this a page on another site could, for example, upload a crafted
 * metrics.csv into the measurement tables through POST /api/v1/admin/client-metrics.
 * <p>
 * A browser says where a request comes from: {@code Sec-Fetch-Site} (current browsers), else {@code Origin} (sent with
 * every cross-origin POST). Allowed: {@code Sec-Fetch-Site} same-origin or none (typed by the user), or without it an
 * Origin whose host and port are this request's Host. Requests without either header are not from a web page (the
 * Unity app, curl, k6, tests) and pass. Our own pages are same-origin and pass. A refused request gets the v1 envelope
 * with 400 INVALID_REQUEST (no new status code or error code in the contract; no legitimate client ever sees it).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class CrossSiteWriteFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> ALLOWED_FETCH_SITES = Set.of("same-origin", "none");

    private final ObjectMapper objectMapper;
    private final Logger log = LoggerFactory.getLogger(CrossSiteWriteFilter.class);

    public CrossSiteWriteFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath() + Objects.toString(request.getPathInfo(), "");
        return SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT)) || !path.startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isSameSiteOrNotABrowser(request.getHeader("Sec-Fetch-Site"), request.getHeader("Origin"), host(request))) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Cross-site {} {} refused (Sec-Fetch-Site={}, Origin={})", request.getMethod(),
                GlobalExceptionHandler.pathForLog(request.getRequestURI()),
                request.getHeader("Sec-Fetch-Site"), request.getHeader("Origin"));
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(new ErrorDetail(
                ErrorCode.INVALID_REQUEST.name(), "Write requests from a page of another site are refused", null)));
    }

    /** The Host header (Caddy passes the browser's through), else the server name and a non-default port. */
    private static String host(HttpServletRequest request) {
        String header = request.getHeader("Host");
        if (header != null && !header.isBlank()) {
            return header;
        }
        int port = request.getServerPort();
        boolean defaultPort = port <= 0 || ("http".equalsIgnoreCase(request.getScheme()) && port == 80)
                || ("https".equalsIgnoreCase(request.getScheme()) && port == 443);
        return defaultPort ? request.getServerName() : request.getServerName() + ":" + port;
    }

    /** The rule above, on the raw header values (null = absent). */
    static boolean isSameSiteOrNotABrowser(String fetchSite, String origin, String host) {
        if (fetchSite != null) {
            return ALLOWED_FETCH_SITES.contains(fetchSite.trim().toLowerCase(Locale.ROOT));
        }
        if (origin == null) {
            return true;
        }
        return host != null && host.trim().equalsIgnoreCase(authority(origin));
    }

    /** "https://beside.example:8443" → "beside.example:8443", default ports left out like a Host header; null if not a URL. */
    private static String authority(String origin) {
        try {
            URI uri = URI.create(origin.trim());
            if (uri.getHost() == null || uri.getScheme() == null) {
                return null; // "null" (opaque origin) and anything else unusable: never same-site
            }
            int port = uri.getPort();
            boolean defaultPort = port == -1 || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
                    || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443);
            return defaultPort ? uri.getHost() : uri.getHost() + ":" + port;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
