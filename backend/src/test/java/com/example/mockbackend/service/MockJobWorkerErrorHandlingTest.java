package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MockJobWorker.dispatch without Spring (pending-ms and processing-ms 0): a RuntimeException (reading the job, saving
 * PROCESSING, a finish that threw) never leaves the @Async method. The job is read again and, if its run has not
 * ended, fails INTERNAL_ERROR through JobFinisher; when even that fails (database down) the worker only logs, once.
 */
class MockJobWorkerErrorHandlingTest {
    private static final String ID = "job-1";

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobFinisher jobFinisher = mock(JobFinisher.class);
    private final MockJobWorker worker = new MockJobWorker(new JobTransitions(jobRepository, jobFinisher), jobFinisher);

    @Test
    void anErrorSavingProcessingFailsTheJobWithInternalError() {
        Job reread = job(JobStatus.PENDING);
        when(jobRepository.findById(ID)).thenReturn(Optional.of(job(JobStatus.PENDING)), Optional.of(reread));
        when(jobRepository.save(any(Job.class))).thenThrow(new IllegalStateException("could not save"));

        assertThatCode(() -> worker.dispatch(ID, 1)).doesNotThrowAnyException();

        verify(jobFinisher).fail(same(reread), eq(ErrorCode.INTERNAL_ERROR),
                eq("Mock worker error (IllegalStateException)"), eq("mock"));
        verify(jobFinisher, never()).complete(any(), any(), any(), any(), any());
    }

    @Test
    void whenTheDatabaseIsDownTheWorkerLogsOnceAndDoesNotThrow() {
        when(jobRepository.findById(ID)).thenThrow(new IllegalStateException("database closed"));

        assertThatCode(() -> worker.dispatch(ID, 1)).doesNotThrowAnyException();

        // Once to start, once to record the failure; then it gives up (InterruptedJobRecovery at the next start).
        verify(jobRepository, times(2)).findById(ID);
        verify(jobFinisher, never()).fail(any(), any(), any(), any());
    }

    @Test
    void aRunThatAlreadyEndedIsNotFailedAgain() {
        when(jobRepository.findById(ID)).thenReturn(Optional.of(job(JobStatus.PENDING)),
                Optional.of(job(JobStatus.COMPLETED)));
        when(jobRepository.save(any(Job.class))).thenAnswer(call -> call.getArgument(0));
        // Something after the COMPLETED save throws.
        doThrow(new IllegalStateException("after the save")).when(jobFinisher)
                .complete(any(Job.class), any(), any(Instant.class), anyString(), any(RunDetails.class));

        assertThatCode(() -> worker.dispatch(ID, 1)).doesNotThrowAnyException();

        verify(jobFinisher, never()).fail(any(), any(), any(), any());
        verify(jobFinisher, times(1)).complete(any(), any(), any(), any(), any());
    }

    private static Job job(JobStatus status) {
        Job job = new Job();
        job.setId(ID);
        job.setStatus(status);
        job.setAttempt(1);
        return job;
    }
}
