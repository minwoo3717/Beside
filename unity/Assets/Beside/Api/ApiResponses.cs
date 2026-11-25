using System;
using System.Collections.Generic;

namespace Beside.Api
{
    /// <summary>Body of 202 responses from POST /api/v1/jobs and POST /api/v1/jobs/{jobId}/retry.</summary>
    [Serializable]
    public class JobCreatedResponse
    {
        public string jobId;
    }

    /// <summary>GET /api/v1/jobs (development/debug only).</summary>
    [Serializable]
    public class JobListResponse
    {
        public List<JobResponse> items;
        /// <summary>null on the last page.</summary>
        public string nextCursor;
    }

    /// <summary>Envelope of every v1 error response (HTTP 400 / 404 / 409 / 413 / 500, and 503 QUEUE_FULL of the
    /// queue cap — 안건 #21, 2026-11-21 결정, API v1.1). A 503 carries a Retry-After header, which BesideApiClient
    /// reads into ApiError.retryAfterSeconds.</summary>
    [Serializable]
    public class ErrorResponse
    {
        public ErrorDetail error;
    }

    /// <summary>
    /// error.code is one of docs/api/ERROR_CODES.md; pick the user-facing text by code and fall back to a generic
    /// message for codes this build does not know. message is for logs only.
    /// </summary>
    [Serializable]
    public class ErrorDetail
    {
        public string code;
        public string message;
        /// <summary>Set when the error concerns a specific job.</summary>
        public string jobId;
    }

    /// <summary>POST /api/v1/events body (openapi ProductEventBatch, docs/METRICS.md §6). One session's events, 1~50.
    /// installId is a random GUID kept in PlayerPrefs, never a device id. Written with Newtonsoft only, so not
    /// [Serializable]: Unity's serializer would skip the props dictionary (analyzer UAC1015).</summary>
    public class ProductEventBatch
    {
        public string installId;
        public string sessionId;
        public string appVersion;
        /// <summary>"android" (the app, editor included) | "web" (share viewer)</summary>
        public string platform;
        public List<ProductEvent> events;
    }

    /// <summary>One product event (openapi ProductEvent). Null fields are left out of the JSON.
    /// props: only the enums, whole numbers and booleans of METRICS §6.1; the server drops anything else.</summary>
    public class ProductEvent
    {
        public string name;
        /// <summary>ISO 8601 UTC ("o" format).</summary>
        public string at;
        public int seq;
        public string jobId;
        public string screen;
        public Dictionary<string, object> props;
    }

    /// <summary>202 of POST /api/v1/events.</summary>
    [Serializable]
    public class ProductEventsAccepted
    {
        public int accepted;
        public int dropped;
    }

    /// <summary>GET /api/v1/healthz — used to verify the configured base URL before uploading.</summary>
    [Serializable]
    public class HealthResponse
    {
        public string status;
        /// <summary>"mock" | "real"</summary>
        public string profile;
        /// <summary>"mock" | "real"</summary>
        public string workerType;
    }
}
