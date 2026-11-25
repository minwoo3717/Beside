package com.example.mockbackend.config;

import com.example.mockbackend.api.v1.ApiV1Paths;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.service.ProductMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Counts the upload refusals of POST /api/v1/jobs that Spring raises before JobServiceImpl runs, in
 * beside.jobs.upload.rejected (docs/METRICS.md §5.1): 413 PAYLOAD_TOO_LARGE (multipart limits, raised while the
 * request is parsed, before a handler is mapped), 400 NO_PHOTOS (no {@code photos} part) and 400 INVALID_REQUEST (a
 * broken multipart body). It only counts: it answers nothing (null) and runs before the resolver of
 * GlobalExceptionHandler, which writes the response as before. JobServiceImpl counts the refusals of its own checks.
 */
@Component
public class UploadRejectionCounter implements HandlerExceptionResolver, Ordered {
    private final ProductMetrics productMetrics;

    public UploadRejectionCounter(ProductMetrics productMetrics) {
        this.productMetrics = productMetrics;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler,
                                         Exception ex) {
        try {
            if ("POST".equals(request.getMethod()) && ApiV1Paths.JOBS.equals(request.getRequestURI())) {
                ErrorCode code = codeOf(ex);
                if (code != null) {
                    productMetrics.uploadRejected(code);
                }
            }
        } catch (RuntimeException ignored) {
            // counting never changes the answer
        }
        return null; // the next resolver (GlobalExceptionHandler) answers
    }

    /** The code GlobalExceptionHandler answers these with; null for everything else (counted elsewhere or not at all). */
    static ErrorCode codeOf(Exception ex) {
        if (ex instanceof MaxUploadSizeExceededException) {
            return ErrorCode.PAYLOAD_TOO_LARGE;
        }
        if (ex instanceof MissingServletRequestPartException) {
            return ErrorCode.NO_PHOTOS;
        }
        if (ex instanceof MultipartException) {
            return ErrorCode.INVALID_REQUEST;
        }
        return null;
    }

    /** Before ExceptionHandlerExceptionResolver (order 0), after DefaultErrorAttributes (highest precedence). */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
