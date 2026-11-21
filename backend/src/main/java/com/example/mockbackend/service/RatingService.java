package com.example.mockbackend.service;

import com.example.mockbackend.api.v1.dto.AdminRating;
import com.example.mockbackend.api.v1.dto.AdminRatingSummary;
import com.example.mockbackend.api.v1.dto.AdminRatingSummaryGroup;
import com.example.mockbackend.api.v1.dto.JobRatingAverage;
import com.example.mockbackend.api.v1.dto.JobRatingList;
import com.example.mockbackend.api.v1.dto.JobRatingRequest;
import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobRating;
import com.example.mockbackend.domain.JobRun;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRatingRepository;
import com.example.mockbackend.repository.JobRunRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

/**
 * Human quality ratings of completed jobs (docs/METRICS.md §4·§4.1): one row per job and rater, saved again =
 * overwritten. Reads join each job's last job_runs row for its modelVersion, so the admin export and summary compare
 * the baseline with improved models without the web page knowing which model made the GLB.
 */
@Service
@RequiredArgsConstructor
public class RatingService {
    /** docs/METRICS.md §4.1 column order: the CSV header and the AdminRating component order. */
    public static final List<String> EXPORT_COLUMNS = List.of(
            "jobId", "modelVersion", "rater", "faceShape", "ears", "bodyProportion", "furColor", "pattern",
            "textureArtifacts", "comment", "updatedAt", "workerType", "passthrough");
    /** Unicode code points after trimming. */
    public static final int MAX_RATER_LENGTH = 40;
    private static final Comparator<JobRating> EXPORT_ORDER = Comparator.comparing(JobRating::getUpdatedAt)
            .thenComparing(JobRating::getJobId).thenComparing(JobRating::getRater);

    private final JobRatingRepository ratings;
    private final JobRunRepository runs;
    private final JobService jobService;
    private final Logger log = LoggerFactory.getLogger(RatingService.class);

    // ------------------------------------------------------------------ per job (tag quality)

    /**
     * Saves (or overwrites) {@code rater}'s rating of a COMPLETED job. Checks the name and the body first (400), then
     * the job (404) and its status (409), as the contract lists them.
     */
    public com.example.mockbackend.api.v1.dto.JobRating put(String jobId, String rater, JobRatingRequest request) {
        String name = normalizeRater(rater);
        Job job = jobService.getJobOrThrow(jobId);
        if (job.getStatus() != JobStatus.COMPLETED) {
            throw ApiException.conflict(ErrorCode.JOB_NOT_COMPLETED, jobId,
                    "Only COMPLETED jobs can be rated; current status is " + job.getStatus());
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        JobRating saved;
        try {
            saved = ratings.saveAndFlush(ratings.findByJobIdAndRater(jobId, name)
                    .map(existing -> {
                        existing.update(request.scores(), request.comment(), now);
                        return existing;
                    })
                    .orElseGet(() -> JobRating.create(jobId, name, request.scores(), request.comment(), now)));
        } catch (DataIntegrityViolationException raced) {
            // The same rater saved this job from another tab a moment ago: overwrite that row instead.
            JobRating first = ratings.findByJobIdAndRater(jobId, name).orElseThrow(() -> raced);
            first.update(request.scores(), request.comment(), now);
            saved = ratings.saveAndFlush(first);
        }
        log.info("Rating saved: jobId={} rater={} scores={}", jobId, name, request.scores().values());
        return toDto(saved);
    }

    /** Every rating of the job in rater-name order and the per-item mean (null without ratings). @throws ApiException 404 */
    public JobRatingList list(String jobId) {
        jobService.getJobOrThrow(jobId);
        List<JobRating> rows = new ArrayList<>(ratings.findByJobId(jobId));
        rows.sort(Comparator.comparing(JobRating::getRater));
        JobRatingAverage average = rows.isEmpty() ? null : new JobRatingAverage(rows.size(),
                mean(rows, JobRating::getFaceShape), mean(rows, JobRating::getEars),
                mean(rows, JobRating::getBodyProportion), mean(rows, JobRating::getFurColor),
                mean(rows, JobRating::getPattern), mean(rows, JobRating::getTextureArtifacts));
        return new JobRatingList(rows.stream().map(RatingService::toDto).toList(), average);
    }

    /**
     * The rater name of the path: trimmed, 1~40 code points, no '/', '\', ';' or control characters, not only dots
     * (those cannot travel as one path segment through Tomcat and Caddy). @throws ApiException 400 INVALID_REQUEST
     */
    static String normalizeRater(String rater) {
        String name = rater == null ? "" : rater.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > MAX_RATER_LENGTH) {
            throw invalid("rater must be 1~" + MAX_RATER_LENGTH + " characters after trimming");
        }
        boolean forbidden = name.codePoints().anyMatch(c -> c == '/' || c == '\\' || c == ';' || Character.isISOControl(c));
        if (forbidden || name.chars().allMatch(c -> c == '.')) {
            throw invalid("rater must not contain '/', '\\', ';' or control characters, nor be only dots");
        }
        return name;
    }

    // ------------------------------------------------------------------ admin export (tag admin)

    /** Ratings with {@code from <= updatedAt < to}, oldest first, with their job's last run; passthrough jobs left out unless asked for. */
    public List<AdminRating> export(String from, String to, boolean includePassthrough) {
        return rated(from, to, includePassthrough).stream().map(RatingService::toAdminRating).toList();
    }

    public static byte[] toCsv(List<AdminRating> rows) {
        return Csv.write(EXPORT_COLUMNS, rows.stream().map(row -> Arrays.<Object>asList(row.jobId(), row.modelVersion(),
                row.rater(), row.faceShape(), row.ears(), row.bodyProportion(), row.furColor(), row.pattern(),
                row.textureArtifacts(), row.comment(), row.updatedAt(), row.workerType(), row.passthrough())).toList());
    }

    /**
     * The rows of {@link #export} grouped by the last run's modelVersion ("(none)" without one, as in adminStats),
     * in modelVersion order. Item means are over every rating row of the group.
     */
    public AdminRatingSummary summary(String from, String to, boolean includePassthrough) {
        Map<String, List<Rated>> groups = new TreeMap<>();
        for (Rated rated : rated(from, to, includePassthrough)) {
            String model = rated.run() == null || isBlank(rated.run().getModelVersion())
                    ? AdminService.NO_MODEL_VERSION : rated.run().getModelVersion();
            groups.computeIfAbsent(model, key -> new ArrayList<>()).add(rated);
        }
        List<AdminRatingSummaryGroup> result = new ArrayList<>();
        groups.forEach((model, members) -> {
            List<JobRating> rows = members.stream().map(Rated::rating).toList();
            long jobs = rows.stream().map(JobRating::getJobId).distinct().count();
            long raters = rows.stream().map(JobRating::getRater).distinct().count();
            result.add(new AdminRatingSummaryGroup(model, jobs, rows.size(), raters,
                    mean(rows, JobRating::getFaceShape), mean(rows, JobRating::getEars),
                    mean(rows, JobRating::getBodyProportion), mean(rows, JobRating::getFurColor),
                    mean(rows, JobRating::getPattern), mean(rows, JobRating::getTextureArtifacts)));
        });
        return new AdminRatingSummary(result);
    }

    private List<Rated> rated(String from, String to, boolean includePassthrough) {
        TimeRange range = TimeRange.parse(from, to);
        List<JobRating> rows = new ArrayList<>(ratings.findUpdatedBetween(range.from(), range.to()));
        rows.sort(EXPORT_ORDER);
        Map<String, JobRun> lastRuns = lastRuns(rows.stream().map(JobRating::getJobId).toList());
        return rows.stream()
                .map(rating -> new Rated(rating, lastRuns.get(rating.getJobId())))
                .filter(rated -> includePassthrough || rated.run() == null || !Boolean.TRUE.equals(rated.run().getPassthrough()))
                .toList();
    }

    /** The run with the highest attempt per job (a rated job is COMPLETED, so that is its finished run). */
    private Map<String, JobRun> lastRuns(Collection<String> jobIds) {
        Map<String, JobRun> last = new HashMap<>();
        if (!jobIds.isEmpty()) {
            for (JobRun run : runs.findByJobIdIn(new HashSet<>(jobIds))) {
                last.merge(run.getJobId(), run, (a, b) -> a.getAttempt() >= b.getAttempt() ? a : b);
            }
        }
        return last;
    }

    private record Rated(JobRating rating, JobRun run) {
    }

    private static AdminRating toAdminRating(Rated rated) {
        JobRating r = rated.rating();
        JobRun run = rated.run();
        return new AdminRating(r.getJobId(), run == null ? null : run.getModelVersion(), r.getRater(),
                r.getFaceShape(), r.getEars(), r.getBodyProportion(), r.getFurColor(), r.getPattern(),
                r.getTextureArtifacts(), r.getComment(), r.getUpdatedAt(),
                run == null ? null : run.getWorkerType(), run == null ? null : run.getPassthrough());
    }

    // ------------------------------------------------------------------ shared

    private static com.example.mockbackend.api.v1.dto.JobRating toDto(JobRating r) {
        return new com.example.mockbackend.api.v1.dto.JobRating(r.getJobId(), r.getRater(), r.getFaceShape(),
                r.getEars(), r.getBodyProportion(), r.getFurColor(), r.getPattern(), r.getTextureArtifacts(),
                r.getComment(), r.getCreatedAt(), r.getUpdatedAt());
    }

    private static double mean(List<JobRating> rows, ToIntFunction<JobRating> item) {
        return rows.stream().mapToInt(item).average().orElse(Double.NaN);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest(ErrorCode.INVALID_REQUEST, message);
    }
}
