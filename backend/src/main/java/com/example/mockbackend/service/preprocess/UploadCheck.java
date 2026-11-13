package com.example.mockbackend.service.preprocess;

import com.example.mockbackend.preprocess.PhotoCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The first-photo check inside POST /api/v1/jobs, before anything is stored. At most
 * preprocess.check.max-concurrency photos are checked at once (CPU and memory of a 2-vCPU VM); a request that waits
 * longer than preprocess.check.wait-ms is let through unchecked, and so is one whose check fails on the server's side
 * (the user decided: accept and record rather than refuse with 500). The caller turns a refusal into the 400.
 */
@Component
public class UploadCheck {
    private static final Logger log = LoggerFactory.getLogger(UploadCheck.class);

    /**
     * @param checkMs     time spent here, waiting for a slot included; null when the check is off
     * @param checkResult PASSED, SKIPPED:BUSY, SKIPPED:ERROR, or null when off or refused
     * @param refused     the reason when the photo is refused (the API error code of the same name), else null
     * @param detail      developer text for the error message and the log
     */
    public record Outcome(Long checkMs, String checkResult, PhotoCheck.Reason refused, String detail) {
        public static final Outcome OFF = new Outcome(null, null, null, null);

        public boolean isRefused() {
            return refused != null;
        }
    }

    private final PreprocessModels models;
    private final PreprocessProperties props;
    private final Semaphore slots;

    public UploadCheck(PreprocessModels models, PreprocessProperties props) {
        this.models = models;
        this.props = props;
        this.slots = new Semaphore(Math.max(1, props.getCheck().getMaxConcurrency()), true);
    }

    /** Checks the first photo of an upload. Never throws for the photo's sake: a bad photo is a refused Outcome. */
    public Outcome check(MultipartFile first) {
        if (!models.checkEnabled()) {
            return Outcome.OFF;
        }
        long start = System.nanoTime();
        try {
            if (!slots.tryAcquire(props.getCheck().getWaitMs(), TimeUnit.MILLISECONDS)) {
                log.warn("photo check: no slot within {} ms, '{}' accepted unchecked (SKIPPED:BUSY)",
                        props.getCheck().getWaitMs(), first.getOriginalFilename());
                return new Outcome(ms(start), "SKIPPED:BUSY", null, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(ms(start), "SKIPPED:BUSY", null, null);
        }
        try {
            // Read inside the slot: requests waiting for it do not each hold a 5 MB array.
            PhotoCheck check = new PhotoCheck(models.detector(), props.checkRules(), props.decodeLimits(),
                    props.getCheck().getDecodeLongSide());
            PhotoCheck.Result result = check.check(first.getBytes());
            long checkMs = ms(start);
            if (!result.accepted()) {
                log.info("photo check refused {}: {} ({} ms, decode {} ms, detect {} ms)", result.reason(),
                        result.detail(), checkMs, result.decodeMs(), result.detectMs());
                return new Outcome(checkMs, null, result.reason(), result.detail());
            }
            log.info("photo check passed: {} ({} ms, decode {} ms, detect {} ms)", result.detail(), checkMs,
                    result.decodeMs(), result.detectMs());
            return new Outcome(checkMs, "PASSED", null, result.detail());
        } catch (Exception | OutOfMemoryError e) {
            log.warn("photo check could not run, '{}' accepted unchecked (SKIPPED:ERROR): {}",
                    first.getOriginalFilename(), e.toString());
            return new Outcome(ms(start), "SKIPPED:ERROR", null, null);
        } finally {
            slots.release();
        }
    }

    private static long ms(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }
}
