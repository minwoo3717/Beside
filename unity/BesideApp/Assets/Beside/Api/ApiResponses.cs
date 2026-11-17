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

    /// <summary>Envelope of every v1 error response (HTTP 400 / 404 / 409 / 413 / 500).</summary>
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
