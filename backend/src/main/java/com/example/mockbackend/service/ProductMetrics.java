package com.example.mockbackend.service;

import com.example.mockbackend.exception.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The server side of the product funnel (docs/METRICS.md §5.1): upload attempted, refused, created, completed, GLB
 * downloaded, shared, share opened. The other steps already have meters (beside.jobs.created, beside.jobs.refused,
 * beside.jobs.finished, beside.asset.downloads); this class adds the ones in between:
 * <ul>
 *   <li>{@code beside.jobs.upload.rejected} (code): POST /api/v1/jobs refused the upload itself, 400 or 413. JobServiceImpl
 *       counts its own checks; UploadRejectionCounter the ones Spring raises before the controller runs. QUEUE_FULL
 *       is beside.jobs.refused, not here.</li>
 *   <li>{@code beside.share.created}: share links made.</li>
 *   <li>{@code beside.share.views} (outcome): the share viewer opening a link, GET /api/v1/shares/{token}. The token
 *       is never a tag.</li>
 * </ul>
 * Every series is registered at startup, so dashboards show 0 instead of "no data". Never throws.
 */
@Component
public class ProductMetrics {
    /** The codes POST /api/v1/jobs refuses an upload with, in docs/api/ERROR_CODES.md order. */
    public static final List<ErrorCode> UPLOAD_REJECTIONS = List.of(ErrorCode.INVALID_REQUEST, ErrorCode.NO_PHOTOS,
            ErrorCode.TOO_MANY_PHOTOS, ErrorCode.UNSUPPORTED_IMAGE_TYPE, ErrorCode.UNREADABLE_IMAGE,
            ErrorCode.IMAGE_TOO_SMALL, ErrorCode.NO_ANIMAL_DETECTED, ErrorCode.TOO_MANY_ANIMALS,
            ErrorCode.ANIMAL_TOO_SMALL, ErrorCode.IMAGE_TOO_DARK, ErrorCode.IMAGE_TOO_BLURRY,
            ErrorCode.PAYLOAD_TOO_LARGE);

    /**
     * How a share link opened: {@code ok}; {@code revoked} (also when it has expired since); {@code expired};
     * {@code not_found} for an unknown or malformed token and a link whose job or file is gone. The API answers all
     * three the same 404 SHARE_NOT_FOUND.
     */
    public enum ShareView {
        OK, NOT_FOUND, EXPIRED, REVOKED;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Map<ErrorCode, Counter> uploadRejected = new EnumMap<>(ErrorCode.class);
    private final Map<ShareView, Counter> shareViews = new EnumMap<>(ShareView.class);
    private final Counter sharesCreated;

    public ProductMetrics(MeterRegistry registry) {
        for (ErrorCode code : UPLOAD_REJECTIONS) {
            uploadRejected.put(code, Counter.builder("beside.jobs.upload.rejected")
                    .description("Uploads POST /api/v1/jobs refused with 400 or 413, by error code (QUEUE_FULL not included)")
                    .tag("code", code.name())
                    .register(registry));
        }
        for (ShareView outcome : ShareView.values()) {
            shareViews.put(outcome, Counter.builder("beside.share.views")
                    .description("Share links opened by the viewer (GET /api/v1/shares/{token}), by outcome")
                    .tag("outcome", outcome.tag())
                    .register(registry));
        }
        this.sharesCreated = Counter.builder("beside.share.created")
                .description("Share links created")
                .register(registry);
    }

    /** One refused upload. A code outside {@link #UPLOAD_REJECTIONS} is not counted (no new series at run time). */
    public void uploadRejected(ErrorCode code) {
        Counter counter = code == null ? null : uploadRejected.get(code);
        if (counter != null) {
            counter.increment();
        }
    }

    public void shareCreated() {
        sharesCreated.increment();
    }

    public void shareViewed(ShareView outcome) {
        Counter counter = outcome == null ? null : shareViews.get(outcome);
        if (counter != null) {
            counter.increment();
        }
    }
}
