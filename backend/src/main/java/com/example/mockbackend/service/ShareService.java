package com.example.mockbackend.service;

import com.example.mockbackend.domain.AssetVariant;
import com.example.mockbackend.domain.ShareLink;
import com.example.mockbackend.exception.ApiException;
import com.example.mockbackend.exception.ErrorCode;
import com.example.mockbackend.repository.ShareLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Share links of completed jobs (docs/api/openapi.yaml tag "share").
 * Creating a link is part of the app API and answers with the caller's own job id on errors. Everything reached by a
 * token is public: there every problem (unknown, malformed, expired or revoked token, job deleted or no longer
 * COMPLETED, file missing) is the same 404 SHARE_NOT_FOUND without a job id, so the share page never learns the job.
 */
@Service
public class ShareService {
    public static final int MIN_TTL_DAYS = 1;
    public static final int MAX_TTL_DAYS = 30;
    /** 128 bits. */
    static final int TOKEN_BYTES = 16;
    /** Browsers and Scene Viewer may keep the GLB this long at most (less when the link expires sooner). */
    static final long MAX_CACHE_SECONDS = Duration.ofHours(1).toSeconds();
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final ShareLinkRepository shareLinks;
    private final JobService jobService;
    private final int defaultTtlDays;
    private final SecureRandom random = new SecureRandom();
    private final Logger log = LoggerFactory.getLogger(ShareService.class);

    public ShareService(ShareLinkRepository shareLinks, JobService jobService,
                        @Value("${share.ttl-days:7}") int defaultTtlDays) {
        if (defaultTtlDays < MIN_TTL_DAYS || defaultTtlDays > MAX_TTL_DAYS) {
            throw new IllegalStateException("share.ttl-days must be between " + MIN_TTL_DAYS + " and " + MAX_TTL_DAYS
                    + ", got " + defaultTtlDays);
        }
        this.shareLinks = shareLinks;
        this.jobService = jobService;
        this.defaultTtlDays = defaultTtlDays;
    }

    /**
     * A new link for a COMPLETED job with its base GLB; every call issues another token.
     * @param ttlDays 1~30, null for share.ttl-days
     * @throws ApiException 400 INVALID_REQUEST, 404 JOB_NOT_FOUND / ASSET_NOT_FOUND, 409 JOB_NOT_COMPLETED
     */
    public ShareLink create(String jobId, Integer ttlDays) {
        int days = ttlDays != null ? ttlDays : defaultTtlDays;
        if (days < MIN_TTL_DAYS || days > MAX_TTL_DAYS) {
            throw ApiException.badRequest(ErrorCode.INVALID_REQUEST,
                    "ttlDays must be between " + MIN_TTL_DAYS + " and " + MAX_TTL_DAYS);
        }
        jobService.resolveAsset(jobId, AssetVariant.base);
        Instant now = now();
        ShareLink link = ShareLink.issue(newToken(), jobId, now, now.plus(Duration.ofDays(days)));
        shareLinks.save(link);
        log.info("Share created: jobId={} token={} expiresAt={}", jobId, abbreviate(link.getToken()), link.getExpiresAt());
        return link;
    }

    /** The active link and the GLB it shares. @throws ApiException 404 SHARE_NOT_FOUND */
    public SharedAsset open(String token) {
        ShareLink link = activeLink(token).orElseThrow(ShareService::shareNotFound);
        Path file;
        long bytes;
        try {
            file = jobService.resolveAsset(link.getJobId(), AssetVariant.base);
            bytes = Files.size(file);
        } catch (ApiException | IOException gone) {
            log.info("Share token={} points at jobId={} without a servable asset: {}",
                    abbreviate(token), link.getJobId(), gone.getMessage());
            throw shareNotFound();
        }
        return new SharedAsset(link, file, bytes);
    }

    /**
     * Ends an active link now. A token answers the same on every public route: what GET calls not found (already
     * revoked, expired, job gone) is not found here either.
     * @throws ApiException 404 SHARE_NOT_FOUND
     */
    public void revoke(String token) {
        ShareLink link = open(token).link();
        link.revoke(now());
        shareLinks.save(link);
        log.info("Share revoked: jobId={} token={}", link.getJobId(), abbreviate(token));
    }

    /** Ends every still active link of a job (for deleting the job). Returns how many were active. */
    public int revokeAllForJob(String jobId) {
        Instant now = now();
        List<ShareLink> active = shareLinks.findByJobId(jobId).stream().filter(link -> link.isActiveAt(now)).toList();
        active.forEach(link -> link.revoke(now));
        shareLinks.saveAll(active);
        if (!active.isEmpty()) {
            log.info("Shares revoked for jobId={}: {}", jobId, active.size());
        }
        return active.size();
    }

    /** Cache lifetime of the shared GLB: an hour, or the time left when the link expires sooner. */
    public long cacheSeconds(ShareLink link) {
        long left = Duration.between(Instant.now(), link.getExpiresAt()).getSeconds();
        return Math.max(0, Math.min(MAX_CACHE_SECONDS, left));
    }

    private Optional<ShareLink> activeLink(String token) {
        if (token == null || !TOKEN_FORMAT.matcher(token).matches()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return shareLinks.findById(token).filter(link -> link.isActiveAt(now));
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return BASE64URL.encodeToString(bytes);
    }

    /** Stored and served with millisecond precision, so the value read back equals the value answered at creation. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /** The token is the only secret of a link: logs keep a prefix to correlate requests, never the whole value. */
    private static String abbreviate(String token) {
        return token == null || token.length() <= 6 ? "?" : token.substring(0, 6) + "...";
    }

    private static ApiException shareNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.SHARE_NOT_FOUND, "Share link not found or expired", null);
    }

    /** An active link with the file it serves. {@code bytes} is the file size answered as ShareAsset.bytes. */
    public record SharedAsset(ShareLink link, Path file, long bytes) {
    }
}
