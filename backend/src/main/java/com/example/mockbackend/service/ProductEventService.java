package com.example.mockbackend.service;

import com.example.mockbackend.api.v1.dto.AdminFunnel;
import com.example.mockbackend.api.v1.dto.AdminFunnelReason;
import com.example.mockbackend.api.v1.dto.AdminFunnelScreen;
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

    /** One step of a session funnel (METRICS §6.2): the name shown and the event that reaches it. */
    record FunnelStep(String step, String event, Predicate<Hit> matches) {
    }

    /** One stored event as the funnel sees it, with its checked props (empty when none). */
    record Hit(String name, String screen, Instant at, int seq, Map<String, Object> props) {
        Object prop(String key) {
            return props.get(key);
        }

        boolean is(String name, String screen) {
            return this.name.equals(name) && (screen == null || screen.equals(this.screen));
        }
    }

    private static final String LIBRARY = "library";

    static final List<FunnelStep> FUNNEL = List.of(
            new FunnelStep("home", "screen_view(screen=home)", hit -> hit.is("screen_view", "home")),
            new FunnelStep("photos_selected", "photos_selected(count!=0)",
                    hit -> hit.is("photos_selected", null) && !isZero(hit.prop("count"))),
            new FunnelStep("generate_tap", "generate_tap", hit -> hit.is("generate_tap", null)),
            new FunnelStep("model_ready", "model_ready", hit -> hit.is("model_ready", null)),
            new FunnelStep("ar", "screen_view(screen=ar)", hit -> hit.is("screen_view", "ar")),
            // every placement since 2026-11-27: a saved dog's is the library funnel's, an old app's (no source) is a job's
            new FunnelStep("model_placed", "model_placed(source!=library)",
                    hit -> hit.is("model_placed", null) && !LIBRARY.equals(hit.prop("source"))));
    /** Opening a saved dog (PR #39's library) and placing it (openapi adminFunnel librarySteps). */
    static final List<FunnelStep> LIBRARY_FUNNEL = List.of(
            new FunnelStep("home", "screen_view(screen=home)", hit -> hit.is("screen_view", "home")),
            new FunnelStep("library", "screen_view(screen=library)", hit -> hit.is("screen_view", LIBRARY)),
            new FunnelStep("saved_dog_open", "saved_dog_open(ok=true)",
                    hit -> hit.is("saved_dog_open", null) && Boolean.TRUE.equals(hit.prop("ok"))),
            new FunnelStep("ar", "screen_view(screen=ar)", hit -> hit.is("screen_view", "ar")),
            new FunnelStep("model_placed", "model_placed(source=library)",
                    hit -> hit.is("model_placed", null) && LIBRARY.equals(hit.prop("source"))));
    /** Events whose prop says why a session may have left (openapi adminFunnel dropReasons): name → prop. */
    static final Map<String, String> DROP_REASONS = Map.of("job_cancel_tap", "stage", "job_failed_shown", "code",
            "photos_selected", "count", "app_error", "kind");
    /** A session whose last event is newer than this (before to, or now) may still be in use: no drop analysis. */
    static final Duration STILL_ACTIVE = Duration.ofMinutes(30);
    private static final Set<String> FUNNEL_NAMES = Set.of("screen_view", "photos_selected", "generate_tap", "model_ready",
            "model_placed", "saved_dog_open", "job_cancel_tap", "job_failed_shown", "app_error");
    private static final Comparator<Hit> ORDER = Comparator.comparing(Hit::at).thenComparingInt(Hit::seq);

    private final ProductEventRepository repository;
    private final ProductEventMetrics metrics;
    private final ObjectMapper objectMapper;
    private final AdminService adminService;
    private final String env;
    private final EventRateLimiter limiter;
    private final double batchesPerSecond;
    private final Object writeLock = new Object();
    private final AtomicLong lastRateLog = new AtomicLong(System.nanoTime() - RATE_LOG_EVERY_NANOS);
    private final AtomicInteger rateLimitedSinceLog = new AtomicInteger();
    private final Logger log = LoggerFactory.getLogger(ProductEventService.class);

    public ProductEventService(ProductEventRepository repository, ProductEventMetrics metrics, ObjectMapper objectMapper,
                               AdminService adminService,
                               @Value("${management.metrics.tags.env:local}") String env,
                               @Value("${events.rate.batches-per-second:50}") double batchesPerSecond,
                               @Value("${events.rate.burst:100}") int burst) {
        this.repository = repository;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.adminService = adminService;
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
     * The session funnels of METRICS §6.2 (openapi adminFunnel): per session, each step is the first matching event after
     * the previous step's event (ordered by the client's at, then seq); a session that misses a step is not counted in the
     * later ones. Sessions that reached the first step but not the last, and whose last event came in more than
     * {@link #STILL_ACTIVE} before to (or now), give the drop analysis: their last screen and the reasons they saw.
     */
    public AdminFunnel funnel(String from, String to) {
        TimeRange range = TimeRange.parse(from, to);
        long sessions = repository.countSessions(range.from(), range.to());
        Map<String, List<Hit>> bySession = new HashMap<>();
        for (Object[] row : repository.findFunnelRows(range.from(), range.to(), FUNNEL_NAMES)) {
            Map<String, Object> props = props((String) row[5]);
            bySession.computeIfAbsent((String) row[0], key -> new ArrayList<>())
                    .add(new Hit((String) row[1], (String) row[2], (Instant) row[3], ((Number) row[4]).intValue(),
                            props == null ? Map.of() : props));
        }
        bySession.values().forEach(hits -> hits.sort(ORDER));

        Map<String, Integer> reachedMain = new HashMap<>();
        List<AdminFunnelStep> steps = steps(FUNNEL, bySession, reachedMain);
        List<AdminFunnelStep> librarySteps = steps(LIBRARY_FUNNEL, bySession, new HashMap<>());

        Instant reference = blank(to) || range.to().isAfter(Instant.now()) ? Instant.now() : range.to();
        Instant cutoff = reference.minus(STILL_ACTIVE);
        Map<String, Instant> lastReceived = new HashMap<>();
        for (Object[] row : repository.findLastReceivedAt(range.from(), range.to())) {
            lastReceived.put((String) row[0], (Instant) row[1]);
        }
        Map<String, Long> lastScreens = new HashMap<>();
        Map<List<String>, Long> reasons = new HashMap<>();
        reachedMain.forEach((session, reached) -> {
            Instant last = lastReceived.get(session);
            if (reached == 0 || reached == FUNNEL.size() || last == null || !last.isBefore(cutoff)) {
                return;
            }
            List<Hit> hits = bySession.get(session);
            String screen = null;
            Set<List<String>> seen = new HashSet<>();
            for (Hit hit : hits) {
                if (hit.name().equals(ProductEventCatalog.SCREEN_VIEW)) {
                    screen = hit.screen();
                }
                List<String> reason = dropReason(hit);
                if (reason != null) {
                    seen.add(reason);
                }
            }
            if (screen != null) {
                lastScreens.merge(screen, 1L, Long::sum);
            }
            seen.forEach(reason -> reasons.merge(reason, 1L, Long::sum));
        });

        List<AdminFunnelScreen> screens = lastScreens.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.<String, Long>comparingByKey()))
                .map(entry -> new AdminFunnelScreen(entry.getKey(), entry.getValue()))
                .toList();
        Comparator<AdminFunnelReason> reasonOrder = Comparator.comparingLong(AdminFunnelReason::sessions).reversed()
                .thenComparing(AdminFunnelReason::event).thenComparing(AdminFunnelReason::key).thenComparing(AdminFunnelReason::value);
        List<AdminFunnelReason> dropReasons = reasons.entrySet().stream()
                .map(entry -> new AdminFunnelReason(entry.getKey().get(0), entry.getKey().get(1), entry.getKey().get(2),
                        entry.getValue()))
                .sorted(reasonOrder)
                .toList();
        return new AdminFunnel(blank(from) ? null : range.from(), blank(to) ? null : range.to(), sessions, steps,
                screens, dropReasons, librarySteps, adminService.jobCompletion(range.from(), range.to()));
    }

    /**
     * Counts one funnel over the sessions (hits already in order) and records in {@code reachedBySession} how many
     * steps each session reached.
     */
    private static List<AdminFunnelStep> steps(List<FunnelStep> funnel, Map<String, List<Hit>> bySession,
                                               Map<String, Integer> reachedBySession) {
        int stepCount = funnel.size();
        long[] reached = new long[stepCount];
        List<List<Long>> gapsMs = new ArrayList<>();
        for (int k = 0; k < stepCount; k++) {
            gapsMs.add(new ArrayList<>());
        }
        bySession.forEach((session, hits) -> {
            int index = -1;
            int steps = 0;
            Instant previous = null;
            for (int k = 0; k < stepCount; k++) {
                int found = -1;
                for (int i = index + 1; i < hits.size(); i++) {
                    if (funnel.get(k).matches().test(hits.get(i))) {
                        found = i;
                        break;
                    }
                }
                if (found < 0) {
                    break;
                }
                reached[k]++;
                steps++;
                Instant at = hits.get(found).at();
                if (previous != null) {
                    gapsMs.get(k).add(Duration.between(previous, at).toMillis());
                }
                previous = at;
                index = found;
            }
            reachedBySession.put(session, steps);
        });

        List<AdminFunnelStep> steps = new ArrayList<>(stepCount);
        for (int k = 0; k < stepCount; k++) {
            FunnelStep step = funnel.get(k);
            double conversion = reached[0] == 0 ? 0 : 100.0 * reached[k] / reached[0];
            Double fromPrevious = k == 0 ? null : reached[k - 1] == 0 ? 0.0 : 100.0 * reached[k] / reached[k - 1];
            Long medianMs = k == 0 ? null : AdminService.nearestRank(gapsMs.get(k).stream().sorted().toList(), 50);
            long dropped = k == 0 ? 0 : reached[k - 1] - reached[k];
            steps.add(new AdminFunnelStep(step.step(), step.event(), reached[k], conversion, fromPrevious,
                    medianMs == null ? null : medianMs / 1000.0, dropped));
        }
        return steps;
    }

    /** (event, prop, value) when this event is a drop reason (photos_selected only with count 0), else null. */
    private static List<String> dropReason(Hit hit) {
        String key = DROP_REASONS.get(hit.name());
        if (key == null) {
            return null;
        }
        Object value = hit.prop(key);
        if (hit.name().equals("photos_selected") && !isZero(value)) {
            return null;
        }
        return value == null ? null : List.of(hit.name(), key, String.valueOf(value));
    }

    private static boolean isZero(Object value) {
        return value instanceof Number number && number.longValue() == 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
