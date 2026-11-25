package com.example.mockbackend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One id per request for the logs (docs/CONTRACT.md §2 'X-Request-Id'): the {@code X-Request-Id} header the app already
 * sends (BesideApiClient.Prepare) when it is 1~64 characters of {@code A-Z a-z 0-9 . _ : -}, otherwise a new UUID. The
 * id is in the SLF4J MDC as {@code requestId} while the request runs (text logs show it as {@code rid=}, prod JSON logs
 * as the field {@code requestId}) and goes back to the client as the {@code X-Request-Id} response header, so a phone's
 * {@code [BesideMetric]} line and the server's lines can be matched. Anything else in the header is not trusted: it
 * would let a client write CR/LF or a very long text into every log line of its request.
 * <p>
 * First of all filters, so the other filters' lines (CrossSiteWriteFilter) carry it too. The /error dispatch of the same
 * request keeps the same id. The MDC entry is removed when the request leaves the filter: Tomcat reuses its threads.
 * Job workers run on other threads and put {@code jobId} and {@code attempt} there themselves (MDC does not follow
 * {@code @Async}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
    private static final Pattern ACCEPTED = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    /** The /error dispatch after an exception or sendError runs through here too, with the id it already had. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getAttribute(ATTRIBUTE) instanceof String kept ? kept : accept(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, id);
        if (!response.isCommitted()) {
            response.setHeader(HEADER, id);
        }
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** The client's id when it is safe to log as it is, else a new random UUID. */
    static String accept(String header) {
        return header != null && ACCEPTED.matcher(header).matches() ? header : UUID.randomUUID().toString();
    }
}
