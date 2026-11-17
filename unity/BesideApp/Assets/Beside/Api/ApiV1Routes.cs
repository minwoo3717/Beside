namespace Beside.Api
{
    /// <summary>
    /// API v1 routes and URL builders. Must match docs/api/openapi.yaml.
    /// Pure C# (no UnityEngine) so it compiles in any project and in edit-mode tests.
    /// </summary>
    public static class ApiV1Routes
    {
        public const string BasePath = "/api/v1";
        public const string Jobs = BasePath + "/jobs";
        public const string Health = BasePath + "/healthz";

        /// <summary>Multipart field name for photos (1~10, image/jpeg|png|webp, each ≤ 5 MB, total ≤ 20 MB).</summary>
        public const string PhotosField = "photos";
        public const string IdempotencyKeyHeader = "Idempotency-Key";
        public const string GlbContentType = "model/gltf-binary";

        public const int MaxPhotos = 10;
        public const long MaxPhotoBytes = 5L * 1024 * 1024;
        public const long MaxRequestBytes = 20L * 1024 * 1024;

        public static string Job(string jobId)
        {
            return Jobs + "/" + jobId;
        }

        /// <summary>Prefer JobResponse.asset.url when present; this builder is for direct requests.</summary>
        public static string Asset(string jobId, AssetVariant variant = AssetVariant.Base)
        {
            return Job(jobId) + "/asset?variant=" + variant.ToWire();
        }

        public static string Retry(string jobId)
        {
            return Job(jobId) + "/retry";
        }

        public static string List(int limit = 20, string cursor = null)
        {
            string url = Jobs + "?limit=" + limit;
            return string.IsNullOrEmpty(cursor) ? url : url + "&cursor=" + System.Uri.EscapeDataString(cursor);
        }

        /// <summary>Joins the configured base URL (e.g. http://192.168.0.10:8080) with a server-relative path such as asset.url.</summary>
        public static string Combine(string baseUrl, string path)
        {
            if (string.IsNullOrEmpty(baseUrl)) return path;
            if (string.IsNullOrEmpty(path)) return baseUrl;
            return baseUrl.TrimEnd('/') + (path.StartsWith("/") ? path : "/" + path);
        }

        /// <summary>Extracts the jobId from a Location header value like "/api/v1/jobs/{jobId}". Returns null when it does not match.</summary>
        public static string JobIdFromLocation(string location)
        {
            if (string.IsNullOrEmpty(location)) return null;
            int index = location.IndexOf(Jobs + "/", System.StringComparison.Ordinal);
            if (index < 0) return null;
            string rest = location.Substring(index + Jobs.Length + 1);
            int end = rest.IndexOfAny(new[] { '/', '?', '#' });
            return end < 0 ? rest : rest.Substring(0, end);
        }
    }
}
