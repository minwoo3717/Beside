package com.example.mockbackend.service;

import com.example.mockbackend.api.v1.dto.AdminFunnel;
import com.example.mockbackend.api.v1.dto.AdminFunnelStep;
import com.example.mockbackend.api.v1.dto.AdminProductEvent;
import com.example.mockbackend.api.v1.dto.ProductEventsAccepted;
import com.example.mockbackend.domain.ProductEventRow;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.ProductEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Product events (docs/METRICS.md §6, REVIEW_CHECKLIST #24): the public collector behind POST /api/v1/events and the
 * admin export and session funnel. The body is read as a JSON tree, not bound to the DTOs: a bad batch (envelope) is a
 * 400, but a bad event inside a good batch is dropped and counted, and a value outside ProductEventCatalog is dropped from
 * its event, so nothing a client invents reaches the table. One INFO line per batch, without the install or session id.
 */
@Service
public class ProductEventService {
    /** 32 KB: 50 events of the largest kind are about 9 KB, so this leaves room without letting a client send megabytes. */
    public static final int MAX_BODY_BYTES = 32 * 1024;
    public static final int MAX_EVENTS = 50;
    /** openapi adminEvents: the CSV header and the AdminProductEvent order. */
    public static final List<String> EXPORT_COLUMNS = List.of("receivedAt", "env", "platform", "appVersion", "installId",
            "sessionId", "seq", "at", "name", "screen", "jobId", "props");
    private static final long RATE_LOG_EVERY_NANOS = Duration.ofMinutes(1).toNanos();
    private static final TypeReference<LinkedHashMap<String, Object>> PROPS_TYPE = new TypeReference<>() {
    };

    /** One step of the session funnel (METRICS §6.2): the name shown and the event that reaches it. */
    record FunnelStep(String step, String event, Predicate<Hit> matches) {
    }

    /** One stored event as the funnel sees it. count is photos_selected's count (null when absent). */
    record Hit(String name, String screen, Instant at, int seq, Long count) {
    }

    static final List<FunnelStep> FUNNEL = List.of(
            new FunnelStep("home", "screen_view(screen=home)", hit -> hit.name().equals("screen_view") && "home".equals(hit.screen())),
            new FunnelStep("photos_selected", "photos_selected(count!=0)",
                    hit -> hit.name().equals("photos_selected") && (hit.count() == null || hit.count() != 0)),
            new FunnelStep("generate_tap", "generate_tap", hit -> hit.name().equals("generate_tap")),
            new FunnelStep("model_ready", "model_ready", hit -> hit.name().equals("model_ready")),
            new FunnelStep("ar", "screen_view(screen=ar)", hit -> hit.name().equals("screen_view") && "ar".equals(hit.screen())),
            new FunnelStep("model_placed", "model_placed", hit -> hit.name().equals("model_placed")));
    private static final Set<String> FUNNEL_NAMES =
            Set.of("screen_view", "photos_selected", "generate_tap", "model_ready", "model_placed");

    private final ProductEventRepository repository;
    private final ProductEventMetrics metrics;
    private final ObjectMapper objectMapper;
    private final String env;
    private final EventRateLimiter limiter;
    private final double batchesPerSecond;
    private final Object writeLock = new Object();
    private final AtomicLong lastRateLog = new AtomicLong(System.nanoTime() - RATE_LOG_EVERY_NANOS);
    private final AtomicInteger rateLimitedSinceLog = new AtomicInteger();
    private final Logger log = LoggerFactory.getLogger(ProductEventService.class);

    public ProductEventService(ProductEventRepository repository, ProductEventMetrics metrics, ObjectMapper objectMapper,
                               @Value("${management.metrics.tags.env:local}") String env,
                               @Value("${events.rate.batches-per-second:50}") double batchesPerSecond,
                               @Value("${events.rate.burst:100}") int burst) {
        this.repository = repository;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.env = env == null || env.isBlank() ? "local" : env;
        this.batchesPerSecond = batchesPerSecond;
        this.limiter = new EventRateLimiter(batchesPerSecond, burst, System::nanoTime);
    }

    // ------------------------------------------------------------------ collect

    /**
     * Stores the events of one batch.
     * @param body the raw request body, at most {@link #MAX_BODY_BYTES} (the controller checks the size)
     * @throws ApiException 400 INVALID_REQUEST when the batch itself is wrong; nothing is stored then
     */
    public ProductEventsAccepted ingest(byte[] body) {
        JsonNode root = parse(body);
        String installId = text(root, "installId");
        String sessionId = text(root, "sessionId");
        String appVersion = text(root, "appVersion");
        String platform = text(root, "platform");
        if (!ProductEventCatalog.isUuid(installId) || !ProductEventCatalog.isUuid(sessionId)) {
            throw invalid("installId and sessionId must be UUIDs");
        }
        installId = installId.toLowerCase(Locale.ROOT);   // one spelling, so the duplicate check sees a resent batch
        sessionId = sessionId.toLowerCase(Locale.ROOT);
        if (!ProductEventCatalog.isAppVersion(appVersion)) {
            throw invalid("appVersion must be 1 to 32 characters of [0-9A-Za-z._+-]");
        }
        if (!ProductEventCatalog.isPlatform(platform)) {
            throw invalid("platform must be android or web");
        }
        JsonNode list = root.get("events");
        if (list == null || !list.isArray() || list.isEmpty() || list.size() > MAX_EVENTS) {
            throw invalid("events must be an array of 1 to " + MAX_EVENTS + " events");
        }
        int total = list.size();
        if (!limiter.tryTake()) {
            metrics.dropped("rate_limited", total);
            logRateLimited();
            return new ProductEventsAccepted(0, total);
        }

        Map<String, Integer> drops = new LinkedHashMap<>();
        int badProps = 0;
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode event : list) {
            String reason = null;
            String name = event.isObject() ? text(event, "name") : null;
            Instant at = event.isObject() ? instant(event.get("at")) : null;
            Integer seq = event.isObject() ? seq(event.get("seq")) : null;
            String screen = event.isObject() ? text(event, "screen") : null;
            if (!event.isObject()) {
                reason = "invalid";
            } else if (!ProductEventCatalog.isName(name)) {
                reason = "unknown_name";
            } else if (at == null || seq == null) {
                reason = "invalid";
            } else if (ProductEventCatalog.SCREEN_VIEW.equals(name) && !ProductEventCatalog.isScreen(screen)) {
                reason = "invalid";   // the screen of a screen view is the event itself
            }
            if (reason != null) {
                drops.merge(reason, 1, Integer::sum);
                continue;
            }
            if (screen != null && !ProductEventCatalog.isScreen(screen)) {
                screen = null;
                badProps++;
            }
            String jobId = text(event, "jobId");
            if (jobId != null && !ProductEventCatalog.isUuid(jobId)) {
                jobId = null;
                badProps++;
            } else if (event.hasNonNull("jobId") && jobId == null) {
                badProps++;   // a number or an object
            }
            ProductEventCatalog.CleanProps props = ProductEventCatalog.clean(name, event.get("props"));
            badProps += props.dropped();
            candidates.add(new Candidate(name, at, seq, jobId == null ? null : jobId.toLowerCase(Locale.ROOT), screen,
                    json(props.kept())));
        }

        Instant receivedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        List<ProductEventRow> rows = new ArrayList<>();
        synchronized (writeLock) {
            Set<Integer> seen = new HashSet<>();
            if (!candidates.isEmpty()) {
                Set<Integer> seqs = new LinkedHashSet<>();
                candidates.forEach(candidate -> seqs.add(candidate.seq()));
                seen.addAll(repository.findStoredSeqs(sessionId, seqs));
            }
            for (Candidate candidate : candidates) {
                if (!seen.add(candidate.seq())) {
                    drops.merge("duplicate", 1, Integer::sum);
                    continue;
                }
                rows.add(ProductEventRow.of(receivedAt, installId, sessionId, appVersion, platform,
                        candidate.name(), candidate.at(), candidate.seq(), candidate.jobId(), candidate.screen(),
                        candidate.propsJson(), env));
            }
            repository.saveAll(rows);
        }
        rows.forEach(row -> metrics.stored(row.getName(), platform));
        drops.forEach(metrics::dropped);
        metrics.dropped("bad_prop", badProps);
        int dropped = drops.values().stream().mapToInt(Integer::intValue).sum();
        log.info("Product events: {} accepted, {} dropped (platform {}, app {})", rows.size(), dropped, platform, appVersion);
        return new ProductEventsAccepted(rows.size(), dropped);
    }

    private record Candidate(String name, Instant at, int seq, String jobId, String screen, String propsJson) {
    }

    private JsonNode parse(byte[] body) {
        JsonNode root;
        try {
            root = body == null || body.length == 0 ? null : objectMapper.readTree(body);
        } catch (IOException e) {
            throw invalid("The body is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw invalid("The body must be a JSON object (ProductEventBatch)");
        }
        return root;
    }

    /** A text field, or null when absent, null or not a string. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static Instant instant(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return null;
        }
        try {
            return Instant.parse(value.textValue().trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Integer seq(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            return null;
        }
        return value.intValue();
    }

    private String json(Map<String, Object> props) {
        if (props.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(props);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write checked props", e);
        }
    }

    private void logRateLimited() {
        rateLimitedSinceLog.incrementAndGet();
        long now = System.nanoTime();
        long last = lastRateLog.get();
        if (now - last >= RATE_LOG_EVERY_NANOS && lastRateLog.compareAndSet(last, now)) {
            log.warn("Product events: over the server limit ({} batches/s), {} batch(es) answered 202 without storing in the last minute",
                    batchesPerSecond, rateLimitedSinceLog.getAndSet(0));
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest(ErrorCode.INVALID_REQUEST, message);
    }

    // ------------------------------------------------------------------ admin

    /** GET /api/v1/admin/events rows received in [from, to), in arrival order. @throws ApiException 400 for a bad range */
    public List<AdminProductEvent> export(String from, String to) {
        TimeRange range = TimeRange.parse(from, to);
        return repository.findByReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtAscIdAsc(range.from(), range.to())
                .stream()
                .map(row -> new AdminProductEvent(row.getReceivedAt(), row.getEnv(), row.getPlatform(), row.getAppVersion(),
                        row.getInstallId(), row.getSessionId(), row.getSeq(), row.getAt(), row.getName(), row.getScreen(),
                        row.getJobId(), props(row.getPropsJson())))
                .toList();
    }

    /** The CSV of {@link #export}: props as its JSON text (empty when none). */
    public byte[] exportCsv(List<AdminProductEvent> rows) {
        List<List<Object>> cells = new ArrayList<>(rows.size());
        for (AdminProductEvent row : rows) {
            cells.add(Arrays.asList(row.receivedAt(), row.env(), row.platform(), row.appVersion(), row.installId(),
                    row.sessionId(), row.seq(), row.at(), row.name(), row.screen(), row.jobId(),
                    row.props() == null ? null : json(row.props())));
        }
        return Csv.write(EXPORT_COLUMNS, cells);
    }

    private Map<String, Object> props(String propsJson) {
        if (propsJson == null || propsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(propsJson, PROPS_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("product_events row with unreadable props: {}", e.getOriginalMessage());
            return null;
        }
    }

    /**
     * The session funnel of METRICS §6.2: per session, each step is the first matching event after the previous step's
     * event (ordered by the client's at, then seq); a session that misses a step is not counted in the later ones.
     */
    public AdminFunnel funnel(String from, String to) {
        TimeRange range = TimeRange.parse(from, to);
        long sessions = repository.countSessions(range.from(), range.to());
        Map<String, List<Hit>> bySession = new HashMap<>();
        for (Object[] row : repository.findFunnelRows(range.from(), range.to(), FUNNEL_NAMES)) {
            String name = (String) row[1];
            Long count = "photos_selected".equals(name) ? count((String) row[5]) : null;
            bySession.computeIfAbsent((String) row[0], key -> new ArrayList<>())
                    .add(new Hit(name, (String) row[2], (Instant) row[3], ((Number) row[4]).intValue(), count));
        }

        int stepCount = FUNNEL.size();
        long[] reached = new long[stepCount];
        List<List<Long>> gapsMs = new ArrayList<>();
        for (int k = 0; k < stepCount; k++) {
            gapsMs.add(new ArrayList<>());
        }
        Comparator<Hit> order = Comparator.comparing(Hit::at).thenComparingInt(Hit::seq);
        for (List<Hit> hits : bySession.values()) {
            hits.sort(order);
            int index = -1;
            Instant previous = null;
            for (int k = 0; k < stepCount; k++) {
                int found = -1;
                for (int i = index + 1; i < hits.size(); i++) {
                    if (FUNNEL.get(k).matches().test(hits.get(i))) {
                        found = i;
                        break;
                    }
                }
                if (found < 0) {
                    break;
                }
                reached[k]++;
                Instant at = hits.get(found).at();
                if (previous != null) {
                    gapsMs.get(k).add(Duration.between(previous, at).toMillis());
                }
                previous = at;
                index = found;
            }
        }

        List<AdminFunnelStep> steps = new ArrayList<>(stepCount);
        for (int k = 0; k < stepCount; k++) {
            FunnelStep step = FUNNEL.get(k);
            double conversion = reached[0] == 0 ? 0 : 100.0 * reached[k] / reached[0];
            Double fromPrevious = k == 0 ? null : reached[k - 1] == 0 ? 0.0 : 100.0 * reached[k] / reached[k - 1];
            Long medianMs = k == 0 ? null : AdminService.nearestRank(gapsMs.get(k).stream().sorted().toList(), 50);
            steps.add(new AdminFunnelStep(step.step(), step.event(), reached[k], conversion, fromPrevious,
                    medianMs == null ? null : medianMs / 1000.0));
        }
        return new AdminFunnel(blank(from) ? null : range.from(), blank(to) ? null : range.to(), sessions, steps);
    }

    private Long count(String propsJson) {
        Map<String, Object> props = props(propsJson);
        Object count = props == null ? null : props.get("count");
        return count instanceof Number number ? number.longValue() : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
