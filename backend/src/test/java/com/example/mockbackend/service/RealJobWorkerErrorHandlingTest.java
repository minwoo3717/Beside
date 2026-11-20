package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.JobStatus;
import com.example.mockbackend.domain.RunDetails;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import com.example.mockbackend.service.preprocess.CutoutStage;
import com.example.mockbackend.service.preprocess.PreparedPhoto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
 * RealJobWorker.dispatch without Spring: a RuntimeException outside run()'s own handling (reading the job, saving
 * PROCESSING, a failure write that threw) never leaves the @Async method. The job is read again and, if its run has
 * not ended, fails INTERNAL_ERROR through JobFinisher; when even that fails (database down) the worker only logs, once.
 * The end to end cases (job FAILED in the database, slot free for the next job) are in RealJobWorkerUnexpectedErrorTest.
 */
class RealJobWorkerErrorHandlingTest {
    private static final String ID = "job-1";

    @TempDir
    Path results;

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobFinisher jobFinisher = mock(JobFinisher.class);
    private final InferenceClient inferenceClient = mock(InferenceClient.class);
    private final InferenceHealthMonitor healthMonitor = mock(InferenceHealthMonitor.class);
    private final CutoutStage cutoutStage = mock(CutoutStage.class);
    private RealJobWorker worker;

    @BeforeEach
    void worker() throws InterruptedException {
        // offline-wait-ms 0: no health check, the run goes straight to the PROCESSING save.
        worker = new RealJobWorker(jobRepository, jobFinisher, inferenceClient, healthMonitor, cutoutStage,
                results.toString(), 1, 0, 10);
        when(cutoutStage.prepare(any(Job.class))).thenReturn(PreparedPhoto.off());
    }

    @Test
    void anErrorSavingProcessingFailsTheJobWithInternalError() throws Exception {
        Job queued = job(JobStatus.PENDING);
        Job reread = job(JobStatus.PENDING);
        when(jobRepository.findById(ID)).thenReturn(Optional.of(queued), Optional.of(queued), Optional.of(reread));
        when(jobRepository.save(any(Job.class))).thenThrow(new IllegalStateException("could not save"));

        assertThatCode(() -> worker.dispatch(ID)).doesNotThrowAnyException();

        // The copy read again, not the one whose save failed half way (it says PROCESSING in memory only).
        verify(jobFinisher).fail(same(reread), eq(ErrorCode.INTERNAL_ERROR),
                eq("Real worker error (IllegalStateException)"), eq("real"), any(RunDetails.class));
        verify(inferenceClient, never()).infer(anyString(), anyList(), anyList());
    }

    @Test
    void whenTheDatabaseIsDownTheWorkerLogsOnceAndDoesNotThrow() {
        when(jobRepository.findById(ID)).thenThrow(new IllegalStateException("database closed"));

        assertThatCode(() -> worker.dispatch(ID)).doesNotThrowAnyException();

        // Once for the cutout, once to record the failure; then it gives up (InterruptedJobRecovery at the next start).
        verify(jobRepository, times(2)).findById(ID);
        verify(jobFinisher, never()).fail(any(), any(), any(), any(), any());
        verify(jobFinisher, never()).fail(any(), any(), any(), any());
    }

    @Test
    void aRunThatAlreadyEndedIsNotFailedAgain() throws Exception {
        Job queued = job(JobStatus.PENDING);
        when(jobRepository.findById(ID)).thenReturn(Optional.of(queued), Optional.of(queued),
                Optional.of(job(JobStatus.FAILED)));
        when(jobRepository.save(any(Job.class))).thenAnswer(call -> call.getArgument(0));
        when(inferenceClient.infer(anyString(), anyList(), anyList())).thenThrow(new IllegalStateException("bug"));
        // run() records INTERNAL_ERROR itself; something after its save throws.
        doThrow(new IllegalStateException("after the save")).when(jobFinisher)
                .fail(any(Job.class), any(ErrorCode.class), anyString(), anyString(), any(RunDetails.class));

        assertThatCode(() -> worker.dispatch(ID)).doesNotThrowAnyException();

        verify(jobFinisher, times(1)).fail(any(Job.class), any(ErrorCode.class), anyString(), anyString(),
                any(RunDetails.class));
        verify(inferenceClient).cleanup(ID);
    }

    private static Job job(JobStatus status) {
        Job job = new Job();
        job.setId(ID);
        job.setStatus(status);
        job.setAttempt(1);
        return job;
    }
}
