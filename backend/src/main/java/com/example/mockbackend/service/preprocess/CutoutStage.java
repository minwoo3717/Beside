package com.example.mockbackend.service.preprocess;

import com.example.mockbackend.domain.Job;
import com.example.mockbackend.domain.StoredUpload;
import com.example.mockbackend.preprocess.Cutout;
import com.example.mockbackend.preprocess.DecodedPhoto;
import com.example.mockbackend.preprocess.ImageDecoding;
import com.example.mockbackend.preprocess.UnreadableImageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The cutout of the real worker: after the 202 and before the inference slot (the job is still PENDING, so the CPU
 * work of the next job overlaps the GPU run of this one and the slot is never held for it). The square PNG goes to
 * {storage.preprocessed.dir}/{jobId}-{tag}.png, where tag is the start of the SHA-256 of the preprocessVersion: a retry
 * with the same settings reuses it (REUSED), changed settings or models make a new one. Never fails the job: anything
 * that goes wrong sends the original photo (FALLBACK:&lt;reason&gt;).
 */
@Component
@Profile("real")
public class CutoutStage {
    private static final Logger log = LoggerFactory.getLogger(CutoutStage.class);

    private final PreprocessModels models;
    private final PreprocessProperties props;
    private final Path dir;
    private final Semaphore slots;

    public CutoutStage(PreprocessModels models, PreprocessProperties props,
                       @Value("${storage.preprocessed.dir:storage/preprocessed}") String dir) {
        this.models = models;
        this.props = props;
        this.dir = Path.of(dir);
        this.slots = new Semaphore(Math.max(1, props.getCutout().getMaxConcurrency()), true);
    }

    /** Never throws except when the worker is interrupted (shutdown). */
    public PreparedPhoto prepare(Job job) throws InterruptedException {
        if (!models.cutoutEnabled() || job.getUploads().isEmpty()) {
            return PreparedPhoto.off();
        }
        StoredUpload first = job.getUploads().get(0);
        Cutout cutout;
        String version;
        try {
            cutout = new Cutout(models.detector(), models.segmenter(), props.cutoutRules());
            version = cutout.version();
        } catch (RuntimeException e) {
            log.warn("jobId={} no cutout (models unavailable): {}", job.getId(), e.toString());
            return new PreparedPhoto(null, null, null, "FALLBACK:ERROR", null);
        }
        String name = PreparedPhoto.cutoutName(first.name());
        Path target = dir.resolve(job.getId() + "-" + tag(version) + ".png").toAbsolutePath().normalize();
        long start = System.nanoTime();
        if (Files.isRegularFile(target)) {
            log.info("jobId={} cutout reused: {}", job.getId(), target.getFileName());
            return new PreparedPhoto(target, name, ms(start), "REUSED", version);
        }
        if (!slots.tryAcquire(props.getCutout().getWaitMs(), TimeUnit.MILLISECONDS)) {
            log.warn("jobId={} no cutout: no slot within {} ms, the original photo goes (FALLBACK:BUSY)", job.getId(),
                    props.getCutout().getWaitMs());
            return new PreparedPhoto(null, null, null, "FALLBACK:BUSY", version);
        }
        long work = System.nanoTime();
        try {
            DecodedPhoto photo = ImageDecoding.decode(Path.of(first.path()), props.getCutout().getDecodeLongSide(),
                    props.decodeLimits());
            Cutout.Result result = cutout.make(photo);
            if (!result.applied()) {
                log.info("jobId={} no cutout ({}): {} — the original photo goes", job.getId(), result.status(),
                        result.detail());
                return new PreparedPhoto(null, null, ms(work), result.status(), version);
            }
            Cutout.writePng(result.image(), target);
            long elapsed = ms(work);
            log.info("jobId={} cutout {} in {} ms (decode {} ms, detect {} ms, segment {} ms): {}", job.getId(),
                    target.getFileName(), elapsed, photo.decodeMs(), result.detectMs(), result.segmentMs(),
                    result.detail());
            return new PreparedPhoto(target, name, elapsed, "APPLIED", version);
        } catch (UnreadableImageException e) {
            log.warn("jobId={} no cutout, the stored photo cannot be decoded: {}", job.getId(), e.getMessage());
            return new PreparedPhoto(null, null, ms(work), "FALLBACK:DECODE_FAILED", version);
        } catch (Exception | OutOfMemoryError e) {
            log.warn("jobId={} no cutout: {} — the original photo goes", job.getId(), e.toString());
            return new PreparedPhoto(null, null, ms(work), "FALLBACK:ERROR", version);
        } finally {
            slots.release();
        }
    }

    static String tag(String version) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(version.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long ms(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }
}
