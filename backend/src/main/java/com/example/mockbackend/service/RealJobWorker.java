package com.example.mockbackend.service;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Profile "real": delegates 3D generation to the Python inference service (generation/inference_service).
 * <p>
 * TODO (docs/PLAN.md stage 4, 실 연동):
 * <ol>
 *   <li>Set PROCESSING, then POST {inference.base-url}/infer with
 *       {@code { jobId, imagePaths: uploads[].path, options: { hair: false } }} (timeout = inference.timeout-ms).</li>
 *   <li>Copy the returned glbPath to storage/results/{jobId}/base.glb (same host or shared volume is assumed,
 *       see docs/ARCHITECTURE.md) and end the run with JobFinisher.complete (it writes events.jsonl).</li>
 *   <li>Map failures: timeout → INFERENCE_TIMEOUT, connection refused / 5xx / 501 → INFERENCE_UNAVAILABLE,
 *       4xx → INFERENCE_FAILED, converter errors → CONVERSION_FAILED (JobFinisher.fail).</li>
 * </ol>
 * Until then every job fails fast with INFERENCE_UNAVAILABLE so the rest of the flow stays testable.
 */
@Service
@Profile("real")
@RequiredArgsConstructor
public class RealJobWorker implements JobWorker {
    private final JobRepository jobRepository;
    private final JobFinisher jobFinisher;
    private final Logger log = LoggerFactory.getLogger(RealJobWorker.class);

    @Value("${inference.base-url:http://localhost:8001}")
    private String inferenceBaseUrl;

    @Value("${inference.timeout-ms:600000}")
    private long timeoutMs;

    @Override
    public String type() {
        return "real";
    }

    @Async
    @Override
    public void dispatch(String jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("jobId={} not found for real worker", jobId);
            return;
        }
        jobFinisher.fail(job, ErrorCode.INFERENCE_UNAVAILABLE, "Real worker is not implemented yet (TODO). Target: POST "
                + inferenceBaseUrl + "/infer, timeoutMs=" + timeoutMs, type());
        log.warn("jobId={} stage=FAILED (real worker stub, inference service {})", jobId, inferenceBaseUrl);
    }
}
