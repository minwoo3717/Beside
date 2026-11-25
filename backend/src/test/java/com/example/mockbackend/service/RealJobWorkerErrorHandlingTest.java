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
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
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
        worker = new RealJobWorker(jobRepository, new JobTransitions(jobRepository, jobFinisher), jobFinisher, inferenceClient,
                healthMonitor, cutoutStage,
                results.toString(), 1, 0, 10, 10);
        when(cutoutStage.prepare(any(Job.class))).thenReturn(PreparedPhoto.off());
    }

    @Test
    void anErrorSavingProcessingFailsTheJobWithInternalError() throws Exception {
        Job queued = job(JobStatus.PENDING);
        Job reread = job(JobStatus.PENDING);
        when(jobRepository.findById(ID)).thenReturn(Optional.of(queued), Optional.of(queued), Optional.of(queued),
                Optional.of(reread)); // cutout, slot line, PROCESSING save, failure
        when(jobRepository.save(any(Job.class))).thenThrow(new IllegalStateException("could not save"));

        assertThatCode(() -> worker.dispatch(ID, 1)).doesNotThrowAnyException();

        // The copy read again, not the one whose save failed half way (it says PROCESSING in memory only).
        verify(jobFinisher).fail(same(reread), eq(ErrorCode.INTERNAL_ERROR),
                eq("Real worker error (IllegalStateException)"), eq("real"), any(RunDetails.class));
        verify(inferenceClient, never()).infer(anyString(), anyList(), anyList(), any(InferenceClient.Watch.class));
    }

    @Test
    void whenTheDatabaseIsDownTheWorkerLogsOnceAndDoesNotThrow() {
        when(jobRepository.findById(ID)).thenThrow(new IllegalStateException("database closed"));

        assertThatCode(() -> worker.dispatch(ID, 1)).doesNotThrowAnyException();

        // Once for the cutout, once to record the failure; then it gives up (InterruptedJobRecovery at the next start).
        verify(jobRepository, times(2)).findById(ID);
        verify(jobFinisher, never()).fail(any(), any(), any(), any(), any());
        verify(jobFinisher, never()).fail(any(), any(), any(), any());
    }

    @Test
    void aRunThatAlreadyEndedIsNotFailedAgain() throws Exception {
        Job queued = job(JobStatus.PENDING);
        when(jobRepository.findById(ID)).thenReturn(Optional.of(queued), Optional.of(queued), Optional.of(queued),
                Optional.of(job(JobStatus.FAILED)));
        when(jobRepository.save(any(Job.class))).thenAnswer(call -> call.getArgument(0));
        when(inferenceClient.infer(anyString(), anyList(), anyList(), any(InferenceClient.Watch.class))).thenThrow(new IllegalStateException("bug"));
        // run() records INTERNAL_ERROR itself; something after its save throws.
        doThrow(new IllegalStateException("after the save")).when(jobFinisher)
                .fail(any(Job.class), any(ErrorCode.class), anyString(), anyString(), any(RunDetails.class));

        assertThatCode(() -> worker.dispatch(ID, 1)).doesNotThrowAnyException();

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
        RealJobWorker full = new RealJobWorker(jobRepository, new JobTransitions(jobRepository, jobFinisher), jobFinisher, inferenceClient,
                healthMonitor, cutoutStage,
                notAFolder.toString(), 1, 0, 10, 10);
        Path glb = Files.write(results.resolve("from-gpu.glb"), new byte[]{'g', 'l', 'T', 'F', 2, 0, 0, 0, 12, 0, 0, 0});
        Job queued = job(JobStatus.PENDING);
        when(jobRepository.findById(ID)).thenReturn(Optional.of(queued));
        when(jobRepository.save(any(Job.class))).thenAnswer(call -> call.getArgument(0));
        when(inferenceClient.infer(anyString(), anyList(), anyList(), any(InferenceClient.Watch.class)))
                .thenReturn(new InferenceClient.Result(glb, null, false, null));

        full.dispatch(ID, 1);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(jobFinisher).fail(same(queued), eq(ErrorCode.INTERNAL_ERROR), message.capture(), eq("real"),
                any(RunDetails.class));
        assertThat(message.getValue()).startsWith("Could not store the GLB on this server (")
                .doesNotContain(results.toString());
        verify(jobFinisher, never()).complete(any(), any(), any(), any(), any());
    }

    /**
     * Whose a failed GLB copy is. A copy that runs out of disk half way names both files in its exception
     * (FileSystemException(source, target, "No space left on device")), so the exception cannot tell: only a service's
     * file that is gone afterwards, in path mode, is the service's side (CONVERSION_FAILED); a full disk, and anything in
     * upload mode (the source is this server's own temporary file), is this server's (INTERNAL_ERROR, FD4).
     */
    @Test
    void aFailedCopyIsTheServicesOnlyWhenItsFileIsGoneInPathMode() throws Exception {
        Path there = Files.write(results.resolve("from-gpu.glb"), new byte[]{1});
        Path gone = results.resolve("swept.glb");
        assertThat(RealJobWorker.copyFailureCode(InferenceClient.Transfer.PATH, gone)).isEqualTo(ErrorCode.CONVERSION_FAILED);
        assertThat(RealJobWorker.copyFailureCode(InferenceClient.Transfer.PATH, there)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(RealJobWorker.copyFailureCode(InferenceClient.Transfer.UPLOAD, gone)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(RealJobWorker.copyFailureCode(InferenceClient.Transfer.UPLOAD, there)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(RealJobWorker.copyFailureCode(null, gone)).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    /**
     * Every line of the run carries jobId and attempt (logback-spring.xml, logging.pattern.level), from the cutout to
     * the end; the pool thread keeps neither for the next job, also after an error that left run().
     */
    @Test
    void theRunsLinesCarryTheJobAndThePoolThreadKeepsNothing() throws Exception {
        Job queued = job(JobStatus.PENDING);
        when(jobRepository.findById(ID)).thenReturn(Optional.of(queued));
        when(jobRepository.save(any(Job.class))).thenAnswer(call -> call.getArgument(0));
        Map<String, String> atCutout = new HashMap<>();
        Map<String, String> atInfer = new HashMap<>();
        when(cutoutStage.prepare(any(Job.class))).thenAnswer(call -> {
            atCutout.putAll(MDC.getCopyOfContextMap());
            return PreparedPhoto.off();
        });
        when(inferenceClient.infer(anyString(), anyList(), anyList(), any(InferenceClient.Watch.class))).thenAnswer(call -> {
            atInfer.putAll(MDC.getCopyOfContextMap());
            throw new IllegalStateException("bug");
        });

        worker.dispatch(ID, 1);

        assertThat(atCutout).containsEntry("jobId", ID).containsEntry("attempt", "1");
        assertThat(atInfer).containsEntry("jobId", ID).containsEntry("attempt", "1");
        assertThat(MDC.get("jobId")).isNull();
        assertThat(MDC.get("attempt")).isNull();

        when(jobRepository.findById(ID)).thenThrow(new IllegalStateException("database closed"));
        worker.dispatch(ID, 1);
        assertThat(MDC.get("jobId")).isNull();
        assertThat(MDC.get("attempt")).isNull();
    }

    private static Job job(JobStatus status) {
        Job job = new Job();
        job.setId(ID);
        job.setStatus(status);
        job.setAttempt(1);
        return job;
    }
}
