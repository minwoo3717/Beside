package com.example.mockbackend.api.v1;

import com.example.mockbackend.domain.AssetVariant;

/** API v1 route constants. Must match docs/api/openapi.yaml. */
public final class ApiV1Paths {
    public static final String BASE = "/api/v1";
    public static final String JOBS = BASE + "/jobs";
    public static final String HEALTHZ = BASE + "/healthz";

    private ApiV1Paths() {
    }

    public static String job(String jobId) {
        return JOBS + "/" + jobId;
    }

    /** Relative URL (server root) exposed as AssetInfo.url. */
    public static String asset(String jobId, AssetVariant variant) {
        return job(jobId) + "/asset?variant=" + variant.name();
    }

    // ------------------------------------------------------------------ share links (tag "share")

    public static final String SHARES = BASE + "/shares";
    /** Web viewer page of a share link. Not an API route: SharePageController serves it outside /api/v1. */
    public static final String SHARE_PAGE = "/s/";

    public static String share(String token) {
        return SHARES + "/" + token;
    }

    /** Relative URL exposed as ShareAsset.url. */
    public static String shareAsset(String token) {
        return share(token) + "/asset";
    }

    /** Relative URL exposed as ShareCreatedResponse.url. */
    public static String sharePage(String token) {
        return SHARE_PAGE + token;
    }
}
