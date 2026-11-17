using System;
using System.Collections.Generic;

namespace Beside.Api
{
    /// <summary>
    /// GET /api/v1/jobs/{jobId} (docs/api/openapi.yaml, JobResponse). Field names equal the JSON keys.
    /// Deserialize with Newtonsoft Json; JsonUtility cannot handle nullable numbers or null objects.
    /// Unknown JSON fields are ignored by default, which is what the contract requires.
    /// </summary>
    [Serializable]
    public class JobResponse
    {
        public string id;
        /// <summary>Raw wire value: PENDING | PROCESSING | COMPLETED | FAILED (or something newer).</summary>
        public string status;
        /// <summary>0~1, null when the server does not know.</summary>
        public double? progress;
        /// <summary>ISO 8601 (UTC).</summary>
        public string createdAt;
        public string updatedAt;
        public Timings timings;
        /// <summary>Non-null only when status is COMPLETED.</summary>
        public AssetInfo asset;
        /// <summary>Non-null only when status is FAILED.</summary>
        public JobError error;
        public List<UploadedFile> uploadedFiles;

        public JobStatus Status
        {
            get { return ApiEnums.ParseJobStatus(status); }
        }

        public bool IsTerminal
        {
            get { return Status.IsTerminal(); }
        }
    }

    /// <summary>Stage durations in ms. processingMs is null before PROCESSING, totalMs is null until COMPLETED/FAILED.</summary>
    [Serializable]
    public class Timings
    {
        public long queuedMs;
        public long? processingMs;
        public long? totalMs;
    }

    /// <summary>Downloadable GLB. url is relative to the server root: combine with the base URL (ApiV1Routes.Combine).</summary>
    [Serializable]
    public class AssetInfo
    {
        public string url;
        public long bytes;
        /// <summary>Raw wire value: "base" | "hair".</summary>
        public string variant;
        /// <summary>Always "model/gltf-binary".</summary>
        public string contentType;

        public AssetVariant Variant
        {
            get { return ApiEnums.ParseAssetVariant(variant); }
        }
    }

    /// <summary>Why the job FAILED. Map code to a user-facing message via docs/api/ERROR_CODES.md; never show message directly.</summary>
    [Serializable]
    public class JobError
    {
        public string code;
        public string message;
    }

    /// <summary>Original filename (no directory part) and size of one uploaded photo.</summary>
    [Serializable]
    public class UploadedFile
    {
        public string name;
        public long bytes;
    }
}
