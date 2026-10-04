using System;

namespace Beside.Api
{
    /// <summary>JobResponse.status. Unknown is the client-side fallback for values this build does not know.</summary>
    public enum JobStatus
    {
        Unknown = 0,
        Pending,
        Processing,
        Completed,
        Failed
    }

    /// <summary>AssetInfo.variant / ?variant= query. Wire values are lower-case ("base", "hair").</summary>
    public enum AssetVariant
    {
        Unknown = 0,
        Base,
        Hair
    }

    /// <summary>
    /// Wire ↔ enum conversion. Contract rule: an unknown value must never crash the app, it maps to Unknown.
    /// DTOs keep the raw string so logs can show what the server actually sent.
    /// </summary>
    public static class ApiEnums
    {
        public static JobStatus ParseJobStatus(string wire)
        {
            switch (wire)
            {
                case "PENDING": return JobStatus.Pending;
                case "PROCESSING": return JobStatus.Processing;
                case "COMPLETED": return JobStatus.Completed;
                case "FAILED": return JobStatus.Failed;
                default: return JobStatus.Unknown;
            }
        }

        public static AssetVariant ParseAssetVariant(string wire)
        {
            switch (wire)
            {
                case "base": return AssetVariant.Base;
                case "hair": return AssetVariant.Hair;
                default: return AssetVariant.Unknown;
            }
        }

        public static string ToWire(this AssetVariant variant)
        {
            switch (variant)
            {
                case AssetVariant.Base: return "base";
                case AssetVariant.Hair: return "hair";
                default: throw new ArgumentException("AssetVariant.Unknown cannot be sent to the server", nameof(variant));
            }
        }

        /// <summary>COMPLETED or FAILED: polling stops. Unknown is not terminal so the client keeps polling (and logs it).</summary>
        public static bool IsTerminal(this JobStatus status)
        {
            return status == JobStatus.Completed || status == JobStatus.Failed;
        }
    }
}
