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
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
        verify(inferenceClient, never()).infer(anyString(), anyList(), anyList(), any(InferenceClient.Watch.class));
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
        when(inferenceClient.infer(anyString(), anyList(), anyList(), any(InferenceClient.Watch.class))).thenThrow(new IllegalStateException("bug"));
        // run() records INTERNAL_ERROR itself; something after its save throws.
        doThrow(new IllegalStateException("after the save")).when(jobFinisher)
                .fail(any(Job.class), any(ErrorCode.class), anyString(), anyString(), any(RunDetails.class));

        assertThatCode(() -> worker.dispatch(ID)).doesNotThrowAnyException();

        verify(jobFinisher, times(1)).fail(any(Job.class), any(ErrorCode.class), anyString(), anyString(),
                any(RunDetails.class));
        verify(inferenceClient).cleanup(ID);
    }

    /**
     * FD4: the service sent a good GLB but this server cannot store it (disk full, here a file where the result folder
     * should be): INTERNAL_ERROR, not CONVERSION_FAILED, and the message names no server path.
     */
    @Test
    void aGlbThisServerCannotStoreIsInternalError() throws Exception {
        Path notAFolder = Files.write(results.resolve("results-is-a-file"), new byte[]{0});
        RealJobWorker full = new RealJobWorker(jobRepository, jobFinisher, inferenceClient, healthMonitor, cutoutStage,
                notAFolder.toString(), 1, 0, 10);
        Path glb = Files.write(results.resolve("from-gpu.glb"), new byte[]{'g', 'l', 'T', 'F', 2, 0, 0, 0, 12, 0, 0, 0});
        Job queued = job(JobStatus.PENDING);
        when(jobRepository.findById(ID)).thenReturn(Optional.of(queued));
        when(jobRepository.save(any(Job.class))).thenAnswer(call -> call.getArgument(0));
        when(inferenceClient.infer(anyString(), anyList(), anyList(), any(InferenceClient.Watch.class)))
                .thenReturn(new InferenceClient.Result(glb, null, false, null));

        full.dispatch(ID);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(jobFinisher).fail(same(queued), eq(ErrorCode.INTERNAL_ERROR), message.capture(), eq("real"),
                any(RunDetails.class));
        assertThat(message.getValue()).startsWith("Could not store the GLB on this server (")
                .doesNotContain(results.toString());
        verify(jobFinisher, never()).complete(any(), any(), any(), any(), any());
    }

    /**
     * Which side a failed GLB copy is on: an error that names the service's file (path mode: it vanished from the shared
     * volume between the check and the copy) is the service's (CONVERSION_FAILED); anything else is this server's disk.
     */
    @Test
    void aCopyErrorAboutTheSourceIsTheServicesSide() {
        Path source = results.resolve("from-gpu.glb");
        assertThat(RealJobWorker.isAbout(new java.nio.file.NoSuchFileException(source.toString()), source)).isTrue();
        assertThat(RealJobWorker.isAbout(new java.nio.file.FileSystemException(source.toString(), null, "gone"), source))
                .isTrue();
        assertThat(RealJobWorker.isAbout(new java.nio.file.FileSystemException(
                results.resolve("results").resolve("job-1").resolve("base.glb").toString(), null, "No space left on device"),
                source)).isFalse();
        assertThat(RealJobWorker.isAbout(new java.io.IOException("No space left on device"), source)).isFalse();
        assertThat(RealJobWorker.isAbout(new java.nio.file.NoSuchFileException(null), source)).isFalse();
    }

    private static Job job(JobStatus status) {
        Job job = new Job();
        job.setId(ID);
        job.setStatus(status);
        job.setAttempt(1);
        return job;
    }
}
