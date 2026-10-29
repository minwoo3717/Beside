package com.example.mockbackend.service;

import com.example.mockbackend.api.v1.dto.AdminClientMetric;
import com.example.mockbackend.api.v1.dto.AdminClientMetricImport;
import com.example.mockbackend.api.v1.dto.AdminE2eRow;
import com.example.mockbackend.api.v1.dto.AdminImportError;
import com.example.mockbackend.domain.ClientMetric;
import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.ClientMetricRepository;
import com.example.mockbackend.repository.JobRunRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Unity app's measurements (docs/METRICS.md §3) brought to the server without changing the app (§3.1): a team
 * member pulls metrics.csv from the phone with adb and uploads it on the admin page. Lines are checked one by one, so
 * one broken line does not lose the rest; a wrong header rejects the whole file because then every column could be
 * shifted. The e2e table joins each app line with the job's last COMPLETED run from job_runs.
 */
@Service
@RequiredArgsConstructor
public class ClientMetricService {
    /** docs/METRICS.md §3 — MetricsRecorder.Header of the app; also the export header and the AdminClientMetric order. */
    public static final List<String> COLUMNS = List.of(
            "jobId", "uploadMs", "waitMs", "downloadMs", "loadMs", "e2eMs", "avgFps", "minFps", "memMB", "device");
    /** docs/METRICS.md §3.1 — server and inference names (server uploadMs as serverUploadMs), then the app's. */
    public static final List<String> E2E_COLUMNS = List.of(
            "jobId", "modelVersion", "workerType", "passthrough", "attempt", "finishedAt",
            "uploadBytes", "serverUploadMs", "queuedMs", "processingMs", "totalMs",
            "inferMs", "gpuPeakMB", "modelParams", "outputVertices", "outputTriangles", "convertMs", "glbBytes",
            "uploadMs", "waitMs", "downloadMs", "loadMs", "e2eMs", "avgFps", "minFps", "memMB", "device");
    static final int MAX_JOB_ID_LENGTH = 64;
    static final int MAX_DEVICE_LENGTH = 256;
    private static final Pattern WHOLE = Pattern.compile("\\d{1,18}");
    /** Plain decimals as Unity writes them with InvariantCulture ("58.3"); no exponent, sign, NaN or Infinity. */
    private static final Pattern DECIMAL = Pattern.compile("\\d{1,15}(\\.\\d{1,15})?");
    private static final int SHOWN_CELL_LENGTH = 40;

    private final ClientMetricRepository clientMetrics;
    private final JobRunRepository runs;
    private final Logger log = LoggerFactory.getLogger(ClientMetricService.class);

    // ------------------------------------------------------------------ import

    /**
     * Stores the lines of an uploaded metrics.csv. Synchronized so two uploads of the same file cannot both pass the
     * duplicate check (the team uploads by hand; there is no need for more concurrency than that).
     * @throws ApiException 400 INVALID_REQUEST for a missing or empty file or a header other than {@link #COLUMNS}
     */
    public synchronized AdminClientMetricImport importCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("Upload the app's metrics.csv as the multipart part 'file'");
        }
        String text;
        try {
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded metrics.csv", e);
        }
        List<Csv.Record> records = Csv.parse(text);
        if (records.isEmpty() || !records.get(0).cells().equals(COLUMNS)) {
            String got = records.isEmpty() ? "nothing" : shorten(String.join(",", records.get(0).cells()), 200);
            throw invalid("The first line must be exactly '" + String.join(",", COLUMNS) + "' (docs/METRICS.md §3), got '" + got + "'");
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        List<AdminImportError> errors = new ArrayList<>();
        List<ClientMetric> candidates = new ArrayList<>();
        for (Csv.Record record : records.subList(1, records.size())) {
            if (record.isBlank()) {
                continue;
            }
            try {
                candidates.add(toMetric(record, now));
            } catch (LineException bad) {
                errors.add(new AdminImportError(record.line(), bad.getMessage()));
            }
        }

        Set<ClientMetric.DedupKey> known = new HashSet<>();
        Set<String> jobIds = new LinkedHashSet<>();
        candidates.forEach(metric -> jobIds.add(metric.getJobId()));
        if (!jobIds.isEmpty()) {
            clientMetrics.findByJobIdIn(jobIds).forEach(stored -> known.add(stored.dedupKey()));
        }
        List<ClientMetric> fresh = new ArrayList<>();
        int skipped = 0;
        for (ClientMetric metric : candidates) {
            if (known.add(metric.dedupKey())) {
                fresh.add(metric);
            } else {
                skipped++;
            }
        }
        clientMetrics.saveAll(fresh);
        log.info("metrics.csv imported: {} new, {} duplicates, {} bad lines", fresh.size(), skipped, errors.size());
        return new AdminClientMetricImport(fresh.size(), skipped, List.copyOf(errors));
    }

    private static ClientMetric toMetric(Csv.Record record, Instant now) {
        if (record.unterminated()) {
            throw new LineException("a quoted cell is not closed before the end of the file");
        }
        List<String> cells = record.cells().stream().map(String::strip).toList();
        if (cells.size() != COLUMNS.size()) {
            throw new LineException("expected " + COLUMNS.size() + " cells, got " + cells.size());
        }
        String jobId = cells.get(0);
        if (jobId.isEmpty()) {
            throw new LineException("jobId is empty");
        }
        if (jobId.length() > MAX_JOB_ID_LENGTH) {
            throw new LineException("jobId is longer than " + MAX_JOB_ID_LENGTH + " characters");
        }
        String device = cells.get(9);
        if (device.length() > MAX_DEVICE_LENGTH) {
            throw new LineException("device is longer than " + MAX_DEVICE_LENGTH + " characters");
        }
        return ClientMetric.of(jobId, whole(cells, 1), whole(cells, 2), whole(cells, 3), whole(cells, 4), whole(cells, 5),
                decimal(cells, 6), decimal(cells, 7), whole(cells, 8), device.isEmpty() ? null : device, now);
    }

    private static long whole(List<String> cells, int index) {
        String value = present(cells, index);
        if (!WHOLE.matcher(value).matches()) {
            throw new LineException(COLUMNS.get(index) + " is not a whole number of at least 0: '" + shorten(value, SHOWN_CELL_LENGTH) + "'");
        }
        return Long.parseLong(value);
    }

    private static double decimal(List<String> cells, int index) {
        String value = present(cells, index);
        if (!DECIMAL.matcher(value).matches()) {
            throw new LineException(COLUMNS.get(index) + " is not a number of at least 0 with '.' decimals: '" + shorten(value, SHOWN_CELL_LENGTH) + "'");
        }
        return Double.parseDouble(value);
    }

    private static String present(List<String> cells, int index) {
        String value = cells.get(index);
        if (value.isEmpty()) {
            throw new LineException(COLUMNS.get(index) + " is empty");
        }
        return value;
    }

    /** A rejected data line; the message becomes AdminImportError.reason. */
    private static final class LineException extends RuntimeException {
        LineException(String message) {
            super(message, null, false, false);
        }
    }

    // ------------------------------------------------------------------ export

    /** Every imported line in import order. */
    public List<AdminClientMetric> list() {
        return clientMetrics.findAllByOrderByIdAsc().stream()
                .map(m -> new AdminClientMetric(m.getJobId(), m.getUploadMs(), m.getWaitMs(), m.getDownloadMs(),
                        m.getLoadMs(), m.getE2eMs(), m.getAvgFps(), m.getMinFps(), m.getMemMB(), m.getDevice()))
                .toList();
    }

    public static byte[] toCsv(List<AdminClientMetric> rows) {
        return Csv.write(COLUMNS, rows.stream().map(m -> Arrays.<Object>asList(m.jobId(), m.uploadMs(), m.waitMs(),
                m.downloadMs(), m.loadMs(), m.e2eMs(), m.avgFps(), m.minFps(), m.memMB(), m.device())).toList());
    }

    /**
     * One row per imported line (import order) with the job's last COMPLETED run; lines whose run was a passthrough are
     * left out unless asked for. Lines without a run keep empty server cells.
     */
    public List<AdminE2eRow> e2e(boolean includePassthrough) {
        List<ClientMetric> metrics = clientMetrics.findAllByOrderByIdAsc();
        Map<String, JobRun> completed = new HashMap<>();
        Set<String> jobIds = new HashSet<>();
        metrics.forEach(metric -> jobIds.add(metric.getJobId()));
        if (!jobIds.isEmpty()) {
            for (JobRun run : runs.findByJobIdIn(jobIds)) {
                if (run.getStatus() == JobStatus.COMPLETED) {
                    completed.merge(run.getJobId(), run, (a, b) -> a.getAttempt() >= b.getAttempt() ? a : b);
                }
            }
        }
        List<AdminE2eRow> rows = new ArrayList<>();
        for (ClientMetric m : metrics) {
            JobRun r = completed.get(m.getJobId());
            if (r != null && !includePassthrough && Boolean.TRUE.equals(r.getPassthrough())) {
                continue;
            }
            rows.add(new AdminE2eRow(m.getJobId(),
                    r == null ? null : r.getModelVersion(), r == null ? null : r.getWorkerType(),
                    r == null ? null : r.getPassthrough(), r == null ? null : r.getAttempt(),
                    r == null ? null : r.getFinishedAt(), r == null ? null : r.getUploadBytes(),
                    r == null ? null : r.getUploadMs(), r == null ? null : r.getQueuedMs(),
                    r == null ? null : r.getProcessingMs(), r == null ? null : r.getTotalMs(),
                    r == null ? null : r.getInferMs(), r == null ? null : r.getGpuPeakMB(),
                    r == null ? null : r.getModelParams(), r == null ? null : r.getOutputVertices(),
                    r == null ? null : r.getOutputTriangles(), r == null ? null : r.getConvertMs(),
                    r == null ? null : r.getGlbBytes(),
                    m.getUploadMs(), m.getWaitMs(), m.getDownloadMs(), m.getLoadMs(), m.getE2eMs(),
                    m.getAvgFps(), m.getMinFps(), m.getMemMB(), m.getDevice()));
        }
        return rows;
    }

    public static byte[] e2eCsv(List<AdminE2eRow> rows) {
        return Csv.write(E2E_COLUMNS, rows.stream().map(r -> Arrays.<Object>asList(r.jobId(), r.modelVersion(),
                r.workerType(), r.passthrough(), r.attempt(), r.finishedAt(), r.uploadBytes(), r.serverUploadMs(),
                r.queuedMs(), r.processingMs(), r.totalMs(), r.inferMs(), r.gpuPeakMB(), r.modelParams(),
                r.outputVertices(), r.outputTriangles(), r.convertMs(), r.glbBytes(), r.uploadMs(), r.waitMs(),
                r.downloadMs(), r.loadMs(), r.e2eMs(), r.avgFps(), r.minFps(), r.memMB(), r.device())).toList());
    }

    // ------------------------------------------------------------------ shared

    private static String shorten(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest(ErrorCode.INVALID_REQUEST, message);
    }
}
