package com.example.mockbackend.service;

import com.example.mockbackend.api.v1.dto.AdminDisk;
import com.example.mockbackend.api.v1.dto.AdminJobList;
import com.example.mockbackend.api.v1.dto.AdminJobSummary;
import com.example.mockbackend.api.v1.dto.AdminOverview;
import com.example.mockbackend.api.v1.dto.AdminPercentiles;
import com.example.mockbackend.api.v1.dto.AdminQueue;
import com.example.mockbackend.api.v1.dto.AdminRun;
import com.example.mockbackend.api.v1.dto.AdminStats;
import com.example.mockbackend.api.v1.dto.AdminStatsGroup;
import com.example.mockbackend.api.v1.dto.Timings;
import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.JobTimings;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.repository.JobRunRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only views for the admin dashboard and the measurement export (PLAN S5, docs/api/openapi.yaml tag admin).
 * Nothing here changes a job. Access control is Caddy Basic auth in front of /api/v1/admin/* — there is no auth code
 * in Spring. Responses never carry server paths.
 */
@Service
@RequiredArgsConstructor
public class AdminService {
    /** docs/METRICS.md §1.1 column names in order: the CSV header and the AdminRun component order. */
    public static final List<String> RUN_COLUMNS = List.of(
            "jobId", "attempt", "finishedAt", "workerType", "status", "errorCode",
            "uploadBytes", "uploadMs", "queuedMs", "processingMs", "totalMs",
            "inferMs", "gpuPeakMB", "modelParams", "outputVertices", "outputTriangles", "convertMs", "glbBytes",
            "passthrough", "modelVersion");
    /** Group name (stats) and filter value (runs) for runs without a modelVersion. */
    public static final String NO_MODEL_VERSION = "(none)";
    static final int DEFAULT_JOBS_LIMIT = 20;
    static final int MAX_JOBS_LIMIT = 100;
    private static final String CRLF = "\r\n";
    private static final Instant NO_LOWER_BOUND = Instant.EPOCH;
    private static final Instant NO_UPPER_BOUND = Instant.parse("9999-01-01T00:00:00Z");
    /** The dashboard polls every 5 s; walking the storage tree once a minute is enough. */
    private static final long STORAGE_SCAN_TTL_MS = 60_000;
    private static final Comparator<GroupKey> GROUP_ORDER =
            Comparator.comparing(GroupKey::modelVersion).thenComparing(GroupKey::workerType);

    private final JobRepository jobRepository;
    private final JobRunRepository jobRunRepository;
    private final JobWorker jobWorker;
    private final Environment environment;
    /** InferenceHealthMonitor in profile real; empty in mock (overview.inference = null). */
    private final ObjectProvider<InferenceStatusSource> inferenceStatusSource;
    private final AtomicReference<StorageScan> lastStorageScan = new AtomicReference<>();
    private final Logger log = LoggerFactory.getLogger(AdminService.class);

    /** The folder that holds the H2 files, uploads, results and logs. Only its size and file system are reported. */
    @Value("${storage.root.dir:storage}")
    private String storageRoot;

    // ------------------------------------------------------------------ overview

    public AdminOverview overview() {
        Instant now = Instant.now();
        List<Job> inFlight = jobRepository.findByStatusIn(List.of(JobStatus.PENDING, JobStatus.PROCESSING));
        List<Job> pending = inFlight.stream().filter(job -> job.getStatus() == JobStatus.PENDING).toList();
        List<Job> processing = inFlight.stream().filter(job -> job.getStatus() == JobStatus.PROCESSING).toList();

        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (JobStatus status : JobStatus.values()) {
            long count = switch (status) {
                case PENDING -> pending.size();
                case PROCESSING -> processing.size();
                default -> jobRepository.countByStatus(status);
            };
            statusCounts.put(status.name(), count);
        }
        AdminQueue queue = new AdminQueue(pending.size(), processing.size(),
                longest(pending, job -> JobTimings.of(job, now).queuedMs()),
                longest(processing, job -> JobTimings.of(job, now).processingMs()));
        return new AdminOverview(now, profile(), jobWorker.type(), statusCounts, queue, disk(), inference());
    }

    private static Long longest(List<Job> jobs, Function<Job, Long> elapsed) {
        return jobs.stream().map(elapsed).filter(Objects::nonNull).max(Long::compare).orElse(null);
    }

    /** Same value as GET /api/v1/healthz profile. */
    private String profile() {
        String[] active = environment.getActiveProfiles();
        return String.join(",", active.length > 0 ? active : environment.getDefaultProfiles());
    }

    private Object inference() {
        InferenceStatusSource source = inferenceStatusSource.getIfAvailable();
        if (source == null) {
            return null;
        }
        try {
            return source.inferenceStatus();
        } catch (RuntimeException e) {
            log.warn("Inference status for the admin overview is unavailable: {}", e.toString());
            return null;
        }
    }

    AdminDisk disk() {
        Path root = Path.of(storageRoot).toAbsolutePath();
        File existing = root.toFile();
        while (existing != null && !existing.exists()) {
            existing = existing.getParentFile();
        }
        long usable = existing == null ? 0 : existing.getUsableSpace();
        long total = existing == null ? 0 : existing.getTotalSpace();
        return new AdminDisk(usable, total, storageBytes(root));
    }

    private Long storageBytes(Path root) {
        long nowMs = System.currentTimeMillis();
        StorageScan last = lastStorageScan.get();
        if (last != null && nowMs - last.scannedAtMs() < STORAGE_SCAN_TTL_MS) {
            return last.bytes();
        }
        Long bytes = sizeOfTree(root);
        lastStorageScan.set(new StorageScan(bytes, nowMs));
        return bytes;
    }

    /** Sum of regular file sizes under {@code root}; files that vanish or cannot be read mid-walk are skipped. */
    private Long sizeOfTree(Path root) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        AtomicLong sum = new AtomicLong();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        sum.addAndGet(attributes.size());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
            return sum.get();
        } catch (IOException | RuntimeException e) {
            log.warn("Could not measure the storage folder: {}", e.toString());
            return null;
        }
    }

    private record StorageScan(Long bytes, long scannedAtMs) {
    }

    // ------------------------------------------------------------------ runs (measurement export)

    /**
     * job_runs rows with {@code from <= finishedAt < to}, oldest first. Passthrough rows are left out unless asked for;
     * {@code modelVersion} "(none)" selects rows without one. Blank filters mean "any".
     */
    public List<AdminRun> runs(String from, String to, String modelVersion, boolean includePassthrough, String workerType) {
        return finishedRuns(from, to, includePassthrough).stream()
                .filter(run -> isBlank(workerType) || workerType.equals(run.getWorkerType()))
                .filter(run -> isBlank(modelVersion) || modelVersion.equals(modelKey(run.getModelVersion())))
                .map(AdminService::toAdminRun)
                .toList();
    }

    /** RFC 4180 CSV: header line of RUN_COLUMNS, then one line per run, CRLF line ends, null as an empty cell. */
    public static byte[] toCsv(List<AdminRun> runs) {
        StringBuilder csv = new StringBuilder(String.join(",", RUN_COLUMNS)).append(CRLF);
        for (AdminRun run : runs) {
            List<Object> cells = Arrays.asList(run.jobId(), run.attempt(), run.finishedAt(), run.workerType(),
                    run.status(), run.errorCode(), run.uploadBytes(), run.uploadMs(), run.queuedMs(),
                    run.processingMs(), run.totalMs(), run.inferMs(), run.gpuPeakMB(), run.modelParams(),
                    run.outputVertices(), run.outputTriangles(), run.convertMs(), run.glbBytes(), run.passthrough(),
                    run.modelVersion());
            csv.append(cells.stream().map(AdminService::csvCell).collect(Collectors.joining(","))).append(CRLF);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        boolean quote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        return quote ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }

    private static AdminRun toAdminRun(JobRun run) {
        return new AdminRun(run.getJobId(), run.getAttempt(), run.getFinishedAt(), run.getWorkerType(),
                run.getStatus() == null ? null : run.getStatus().name(), run.getErrorCode(),
                run.getUploadBytes(), run.getUploadMs(), run.getQueuedMs(), run.getProcessingMs(), run.getTotalMs(),
                run.getInferMs(), run.getGpuPeakMB(), run.getModelParams(), run.getOutputVertices(),
                run.getOutputTriangles(), run.getConvertMs(), run.getGlbBytes(), run.getPassthrough(),
                run.getModelVersion());
    }

    private List<JobRun> finishedRuns(String from, String to, boolean includePassthrough) {
        Instant lower = instant("from", from, NO_LOWER_BOUND);
        Instant upper = instant("to", to, NO_UPPER_BOUND);
        if (lower.isAfter(upper)) {
            throw invalid("from (" + from + ") must not be after to (" + to + ")");
        }
        return jobRunRepository.findFinishedBetween(lower, upper).stream()
                .filter(run -> includePassthrough || !Boolean.TRUE.equals(run.getPassthrough()))
                .toList();
    }

    private static Instant instant(String name, String value, Instant absent) {
        if (isBlank(value)) {
            return absent;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw invalid(name + " must be an ISO 8601 instant such as 2026-11-06T00:00:00Z, got '" + value + "'");
        }
    }

    // ------------------------------------------------------------------ stats

    /**
     * Rows of {@link #runs} (without the modelVersion/workerType filters) grouped by modelVersion ("(none)" for null)
     * and workerType. Percentiles and averages use COMPLETED rows that have the value; see the adminStats description.
     */
    public AdminStats stats(String from, String to, boolean includePassthrough) {
        Map<GroupKey, List<JobRun>> groups = new TreeMap<>(GROUP_ORDER);
        for (JobRun run : finishedRuns(from, to, includePassthrough)) {
            GroupKey key = new GroupKey(modelKey(run.getModelVersion()), String.valueOf(run.getWorkerType()));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(run);
        }
        return new AdminStats(groups.entrySet().stream().map(entry -> group(entry.getKey(), entry.getValue())).toList());
    }

    private static boolean canceled(JobRun run) {
        return run.getStatus() == JobStatus.FAILED && ErrorCode.CANCELED.name().equals(run.getErrorCode());
    }

    private static AdminStatsGroup group(GroupKey key, List<JobRun> all) {
        // A canceled run is the user's choice, not the model failing: it stays out of runs, failed and successRate.
        long canceled = all.stream().filter(AdminService::canceled).count();
        List<JobRun> rows = all.stream().filter(run -> !canceled(run)).toList();
        List<JobRun> completed = rows.stream().filter(run -> run.getStatus() == JobStatus.COMPLETED).toList();
        long failed = rows.stream().filter(run -> run.getStatus() == JobStatus.FAILED).count();
        return new AdminStatsGroup(key.modelVersion(), key.workerType(), rows.size(), completed.size(), failed, canceled,
                rows.isEmpty() ? 0.0 : (double) completed.size() / rows.size(),
                percentiles(completed, JobRun::getQueuedMs),
                percentiles(completed, JobRun::getProcessingMs),
                percentiles(completed, JobRun::getTotalMs),
                percentiles(completed, JobRun::getInferMs),
                average(completed, JobRun::getGlbBytes),
                average(completed, JobRun::getGpuPeakMB));
    }

    static AdminPercentiles percentiles(List<JobRun> rows, Function<JobRun, Long> metric) {
        List<Long> ascending = rows.stream().map(metric).filter(Objects::nonNull).sorted().toList();
        return new AdminPercentiles(ascending.size(), nearestRank(ascending, 50), nearestRank(ascending, 95));
    }

    /**
     * Nearest-rank percentile: the ceil(p/100 × n)-th value (1-based) of the ascending values, never interpolated,
     * so it is always a measured value. n=20: p50 is the 10th, p95 the 19th. Null when there are no values.
     */
    static Long nearestRank(List<Long> ascending, int percent) {
        if (ascending.isEmpty()) {
            return null;
        }
        int rank = (int) ((percent * (long) ascending.size() + 99) / 100); // integer ceil, no floating-point edge
        return ascending.get(Math.max(rank, 1) - 1);
    }

    private static Double average(List<JobRun> rows, Function<JobRun, Long> metric) {
        OptionalDouble mean = rows.stream().map(metric).filter(Objects::nonNull).mapToLong(Long::longValue).average();
        return mean.isPresent() ? mean.getAsDouble() : null;
    }

    private static String modelKey(String modelVersion) {
        return isBlank(modelVersion) ? NO_MODEL_VERSION : modelVersion;
    }

    private record GroupKey(String modelVersion, String workerType) {
    }

    // ------------------------------------------------------------------ recent jobs

    /** Newest jobs first (createdAt desc, id desc) with the modelVersion of each job's last finished run. */
    public AdminJobList recentJobs(Integer limit) {
        int size = limit == null ? DEFAULT_JOBS_LIMIT : limit;
        if (size < 1 || size > MAX_JOBS_LIMIT) {
            throw invalid("limit must be between 1 and " + MAX_JOBS_LIMIT);
        }
        Instant now = Instant.now();
        List<Job> page = jobRepository.findAll(PageRequest.of(0, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))).getContent();
        Map<String, JobRun> lastRuns = new HashMap<>();
        if (!page.isEmpty()) {
            for (JobRun run : jobRunRepository.findByJobIdIn(page.stream().map(Job::getId).toList())) {
                lastRuns.merge(run.getJobId(), run, (a, b) -> a.getAttempt() >= b.getAttempt() ? a : b);
            }
        }
        return new AdminJobList(page.stream().map(job -> summary(job, lastRuns.get(job.getId()), now)).toList());
    }

    private static AdminJobSummary summary(Job job, JobRun lastRun, Instant now) {
        JobTimings timings = JobTimings.of(job, now);
        String errorCode = job.getStatus() != JobStatus.FAILED ? null
                : job.getErrorCode() != null ? job.getErrorCode() : ErrorCode.INTERNAL_ERROR.name();
        return new AdminJobSummary(job.getId(), job.getStatus(), job.getCreatedAt(), job.getAttempt(),
                new Timings(timings.queuedMs(), timings.processingMs(), timings.totalMs()),
                errorCode, lastRun == null ? null : lastRun.getModelVersion());
    }

    // ------------------------------------------------------------------ shared

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest(ErrorCode.INVALID_REQUEST, message);
    }
}
