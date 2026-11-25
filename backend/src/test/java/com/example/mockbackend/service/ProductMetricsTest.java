package com.example.mockbackend.service;

import com.example.mockbackend.config.UploadRejectionCounter;
import com.example.mockbackend.exception.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * The server-side funnel meters (docs/METRICS.md §5.1): every series registered at 0, counts by code and outcome, and
 * UploadRejectionCounter counting the POST /api/v1/jobs refusals Spring raises before the service, without answering.
 */
class ProductMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProductMetrics metrics = new ProductMetrics(registry);

    @Test
    void everySeriesExistsAtZeroFromTheStart() {
        assertThat(rejected()).hasSize(12).allSatisfy((code, count) -> assertThat(count).isZero());
        assertThat(rejected().keySet()).containsExactlyInAnyOrder("INVALID_REQUEST", "NO_PHOTOS", "TOO_MANY_PHOTOS",
                "UNSUPPORTED_IMAGE_TYPE", "UNREADABLE_IMAGE", "IMAGE_TOO_SMALL", "NO_ANIMAL_DETECTED", "TOO_MANY_ANIMALS",
                "ANIMAL_TOO_SMALL", "IMAGE_TOO_DARK", "IMAGE_TOO_BLURRY", "PAYLOAD_TOO_LARGE");
        assertThat(views()).containsExactly(entry("expired", 0.0), entry("not_found", 0.0), entry("ok", 0.0),
                entry("revoked", 0.0));
        assertThat(registry.get("beside.share.created").counter().count()).isZero();
    }

    @Test
    void everyRefusalCodeOfTheUploadIsA400Or413OfTheApi() {
        assertThat(ProductMetrics.UPLOAD_REJECTIONS).allSatisfy(code ->
                assertThat(code.httpStatus().value()).as(code.name()).isIn(400, 413));
        // The photo check's reasons are all here (JobServiceImpl counts them by the code of the same name).
        assertThat(ProductMetrics.UPLOAD_REJECTIONS).map(ErrorCode::name).containsAll(Arrays.stream(
                com.example.mockbackend.preprocess.PhotoCheck.Reason.values()).map(Enum::name).toList());
        assertThat(ProductMetrics.UPLOAD_REJECTIONS).as("the queue cap has its own counter, beside.jobs.refused")
                .doesNotContain(ErrorCode.QUEUE_FULL);
    }

    @Test
    void countsByCodeAndOutcomeAndIgnoresWhatHasNoSeries() {
        metrics.uploadRejected(ErrorCode.NO_ANIMAL_DETECTED);
        metrics.uploadRejected(ErrorCode.NO_ANIMAL_DETECTED);
        metrics.uploadRejected(ErrorCode.TOO_MANY_PHOTOS);
        metrics.uploadRejected(ErrorCode.QUEUE_FULL);   // not an upload refusal: no new series
        metrics.uploadRejected(null);
        metrics.shareCreated();
        metrics.shareViewed(ProductMetrics.ShareView.OK);
        metrics.shareViewed(ProductMetrics.ShareView.REVOKED);
        metrics.shareViewed(null);

        assertThat(rejected()).contains(entry("NO_ANIMAL_DETECTED", 2.0), entry("TOO_MANY_PHOTOS", 1.0))
                .hasSize(12);
        assertThat(registry.get("beside.share.created").counter().count()).isEqualTo(1.0);
        assertThat(views()).containsExactly(entry("expired", 0.0), entry("not_found", 0.0), entry("ok", 1.0),
                entry("revoked", 1.0));
    }

    @Test
    void refusalsBeforeTheServiceAreCountedOnlyForTheCreateRouteAndNeverAnswered() {
        UploadRejectionCounter counter = new UploadRejectionCounter(metrics);
        MockHttpServletRequest create = new MockHttpServletRequest("POST", "/api/v1/jobs");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(counter.resolveException(create, response, null, new MaxUploadSizeExceededException(5 * 1024 * 1024)))
                .as("GlobalExceptionHandler still answers").isNull();
        counter.resolveException(create, response, null, new MissingServletRequestPartException("photos"));
        counter.resolveException(create, response, null, new MultipartException("Failed to parse multipart"));
        counter.resolveException(create, response, null, new HttpMediaTypeNotSupportedException("text/plain"));
        counter.resolveException(create, response, null, new IllegalStateException("not about the upload"));
        // Other routes: the 413 of the metrics.csv import, a retry, the frozen v0 upload.
        counter.resolveException(new MockHttpServletRequest("POST", "/api/v1/admin/client-metrics"), response, null,
                new MaxUploadSizeExceededException(5 * 1024 * 1024));
        counter.resolveException(new MockHttpServletRequest("POST", "/api/jobs"), response, null,
                new MaxUploadSizeExceededException(5 * 1024 * 1024));
        counter.resolveException(new MockHttpServletRequest("GET", "/api/v1/jobs"), response, null,
                new MissingServletRequestPartException("photos"));

        assertThat(rejected()).contains(entry("PAYLOAD_TOO_LARGE", 1.0), entry("NO_PHOTOS", 1.0),
                entry("INVALID_REQUEST", 1.0));
        assertThat(rejected().values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(3.0);
        assertThat(response.isCommitted()).isFalse();
        assertThat(counter.getOrder()).as("before ExceptionHandlerExceptionResolver (order 0)").isNegative();
    }

    private Map<String, Double> rejected() {
        Map<String, Double> counts = new TreeMap<>();
        for (Counter counter : registry.get("beside.jobs.upload.rejected").counters()) {
            counts.put(counter.getId().getTag("code"), counter.count());
        }
        return counts;
    }

    private Map<String, Double> views() {
        Map<String, Double> counts = new TreeMap<>();
        for (Counter counter : registry.get("beside.share.views").counters()) {
            counts.put(counter.getId().getTag("outcome"), counter.count());
        }
        return counts;
    }
}
