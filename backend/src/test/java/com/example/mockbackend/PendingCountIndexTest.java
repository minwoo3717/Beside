package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.repository.JobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The count the queue cap reads on every v1 create and retry while it is on (REVIEW_CHECKLIST #21, decided
 * 2026-11-21): PENDING jobs queued after a cutoff, and the stale ones at or before it. The jobs table has no
 * retention yet (#23), so the count must come from idx_jobs_status_queued_at_id (status, queuedAt, id), not a scan;
 * ddl-auto=update adds the index to existing database files. The index is the one review/api-v1.1 defines for its
 * queue position, so either branch can merge first.
 */
@SpringBootTest(properties = "beside.test.context=pending-count") // its own context: no rows of other test classes
class PendingCountIndexTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobRepository jobRepository;

    private final List<String> saved = new ArrayList<>();

    @AfterEach
    void removeTheRowsOfThisTest() {
        jobRepository.deleteAllById(saved);
    }

    @Test
    void theStatusAndQueuedAtIndexExistsWithItsColumnsInOrder() {
        List<String> indexes = jdbc.queryForList(
                        "select index_name from information_schema.indexes where table_name = 'JOBS'", String.class)
                .stream().map(name -> name.toUpperCase(Locale.ROOT)).toList();
        assertThat(indexes).contains("IDX_JOBS_CREATED_AT_ID", "IDX_JOBS_STATUS_QUEUED_AT_ID");

        List<String> columns = jdbc.queryForList("select column_name from information_schema.index_columns"
                        + " where table_name = 'JOBS' and index_name = 'IDX_JOBS_STATUS_QUEUED_AT_ID' order by ordinal_position",
                String.class);
        assertThat(columns).containsExactly("STATUS", "QUEUED_AT", "ID");
    }

    @Test
    void pendingJobsAreCountedOnEachSideOfTheCutoffAndOtherStatesNever() {
        Instant cutoff = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(40, ChronoUnit.MINUTES); // stored as is
        save(JobStatus.PENDING, cutoff.plusMillis(1));
        save(JobStatus.PENDING, cutoff.plus(1, ChronoUnit.HOURS));
        save(JobStatus.PENDING, cutoff);                        // at the cutoff: stale
        save(JobStatus.PENDING, cutoff.minus(1, ChronoUnit.DAYS));
        save(JobStatus.PROCESSING, cutoff.plus(1, ChronoUnit.HOURS));
        save(JobStatus.FAILED, cutoff.plus(1, ChronoUnit.HOURS));
        save(JobStatus.COMPLETED, cutoff.minus(1, ChronoUnit.HOURS));

        assertThat(jobRepository.countByStatusAndQueuedAtAfter(JobStatus.PENDING, cutoff)).isEqualTo(2);
        assertThat(jobRepository.countByStatusAndQueuedAtLessThanEqual(JobStatus.PENDING, cutoff)).isEqualTo(2);
    }

    private void save(JobStatus status, Instant queuedAt) {
        Job job = new Job();
        job.setId(UUID.randomUUID().toString());
        job.setStatus(status);
        job.setCreatedAt(queuedAt);
        job.setUpdatedAt(queuedAt);
        job.setQueuedAt(queuedAt);
        job.setProgress(0.0);
        job.setAttempt(1);
        jobRepository.save(job);
        saved.add(job.getId());
    }
}
