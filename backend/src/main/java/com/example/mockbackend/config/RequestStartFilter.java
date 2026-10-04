package com.example.mockbackend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Notes when a photo upload request arrived, before Spring reads and parses the multipart body, so the server-side
 * uploadMs (docs/METRICS.md §1) covers receive + parse + store. Only POST /api/v1/jobs and v0 POST /api/jobs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestStartFilter extends OncePerRequestFilter {
    static final String START_NANOS = RequestStartFilter.class.getName() + ".startNanos";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !"POST".equalsIgnoreCase(request.getMethod()) || !("/api/v1/jobs".equals(uri) || "/api/jobs".equals(uri));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(START_NANOS, System.nanoTime());
        chain.doFilter(request, response);
    }

    /** Milliseconds since the current upload request arrived, or null when not called inside such a request. */
    public static Long elapsedMs() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getRequest().getAttribute(START_NANOS) instanceof Long start) {
            return (System.nanoTime() - start) / 1_000_000;
        }
        return null;
    }
}
