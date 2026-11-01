package com.example.mockbackend;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.repository.JobRunRepository;
import com.example.mockbackend.service.QueueEstimator;
import com.example.mockbackend.service.QueueEstimator.Position;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A PENDING job's place in the queue is read in one read-only transaction (one session, one connection) with its two
 * statements, instead of a session and a connection per statement. Runs outside the test transaction, as a poll does,
 * and counts with Hibernate's statistics.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class QueueSnapshotTest {
    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");

    @Autowired
    JobRepository jobs;

    @Autowired
    JobRunRepository runs;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @AfterEach
    void clean() {
        jobs.deleteAll();
        runs.deleteAll();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aPendingOutlookIsOneSessionWithTwoStatements() {
        jobs.save(job("earlier", JobStatus.PENDING, NOW.minusSeconds(20), null));
        jobs.save(job("running", JobStatus.PROCESSING, NOW.minusSeconds(30), NOW.minusSeconds(5)));
        Job pending = jobs.save(job("mine", JobStatus.PENDING, NOW.minusSeconds(10), null));
        QueueEstimator estimator = new QueueEstimator(jobs, runs, "real", () -> true, Clock.fixed(NOW, ZoneOffset.UTC));
        estimator.outlook(pending); // reads the (empty) median once; it is cached for the next polls

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        Position position = estimator.outlook(pending).queue();

        assertThat(position.ahead()).as("the earlier PENDING job and the PROCESSING one").isEqualTo(2);
        assertThat(statistics.getSessionOpenCount()).as("sessions").isEqualTo(1);
        assertThat(statistics.getPrepareStatementCount()).as("statements").isEqualTo(2);
    }

    private static Job job(String id, JobStatus status, Instant queuedAt, Instant processingStartedAt) {
        Job job = new Job();
        job.setId(id);
        job.setStatus(status);
        job.setCreatedAt(queuedAt);
        job.setUpdatedAt(queuedAt);
        job.setQueuedAt(queuedAt);
        job.setProcessingStartedAt(processingStartedAt);
        job.setAttempt(1);
        job.setUploads(new ArrayList<>());
        return job;
    }
}
