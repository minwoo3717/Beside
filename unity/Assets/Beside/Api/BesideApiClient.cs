using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using Newtonsoft.Json;
using UnityEngine;
using UnityEngine.Networking;

namespace Beside.Api
{
    /// <summary>One photo to upload. mimeType must be image/jpeg, image/png or image/webp (server checks the part type).</summary>
    public struct Photo
    {
        public byte[] bytes;
        public string fileName;
        public string mimeType;

        public static string MimeFromFileName(string name)
        {
            string ext = Path.GetExtension(name).ToLowerInvariant();
            if (ext == ".png") return "image/png";
            if (ext == ".webp") return "image/webp";
            return "image/jpeg";
        }
    }

    /// <summary>Error of one API call. code is an ERROR_CODES.md code or an app-side code (NETWORK_ERROR, BAD_RESPONSE).</summary>
    public sealed class ApiError
    {
        public string code;
        public string message;     // developer text, never shown to the user
        public long httpStatus;
        public string jobId;
        /// <summary>Retry-After of the response in seconds (QUEUE_FULL), 0 when absent or not a positive integer.
        /// The app does not send the request again before this many seconds have passed.</summary>
        public int retryAfterSeconds;

        public const string Network = "NETWORK_ERROR";
        public const string BadResponse = "BAD_RESPONSE";

        public override string ToString() => $"{code} (HTTP {httpStatus}) {message}" + (retryAfterSeconds > 0 ? $" Retry-After {retryAfterSeconds}" : "");

        /// <summary>Retry-After as delta-seconds: a positive integer only (no sign, fraction or HTTP date), else 0.</summary>
        public static int ParseRetryAfter(string value)
        {
            if (string.IsNullOrEmpty(value)) return 0;
            return int.TryParse(value.Trim(), NumberStyles.None, CultureInfo.InvariantCulture, out int seconds) && seconds > 0 ? seconds : 0;
        }
    }

    /// <summary>
    /// Backend API v1 boundary (docs/api/openapi.yaml). Coroutine based; every call carries X-Request-Id.
    /// Unknown JSON fields are ignored by Newtonsoft, unknown enum values become Unknown through the DTOs.
    /// </summary>
    public sealed class BesideApiClient
    {
        public string BaseUrl { get; private set; }
        public int TimeoutSeconds = 60;
        public int DownloadTimeoutSeconds = 300;

        public BesideApiClient(string baseUrl)
        {
            BaseUrl = (baseUrl ?? "").TrimEnd('/');
        }

        // ---------- POST /api/v1/jobs ----------

        /// <summary>idempotencyKey: keep the same key when the same photos are sent again after a failed upload
        /// (the server then returns the same jobId). Null = use requestId.</summary>
        public IEnumerator UploadPhotos(IList<Photo> photos, string requestId,
            Action<JobCreatedResponse> onJobCreated, Action<ApiError> onError, string idempotencyKey = null)
        {
            var sections = new List<IMultipartFormSection>();
            foreach (var p in photos)
                sections.Add(new MultipartFormFileSection(ApiV1Routes.PhotosField, p.bytes, p.fileName, p.mimeType));

            using (var req = UnityWebRequest.Post(Url(ApiV1Routes.Jobs), sections))
            {
                Prepare(req, requestId);
                req.SetRequestHeader(ApiV1Routes.IdempotencyKeyHeader, idempotencyKey ?? requestId); // same key -> same jobId on a retried upload
                yield return req.SendWebRequest();

                if (!Succeeded(req, out var error)) { onError(error); yield break; }

                var body = Parse<JobCreatedResponse>(req.downloadHandler.text);
                string jobId = body != null && !string.IsNullOrEmpty(body.jobId)
                    ? body.jobId
                    : ApiV1Routes.JobIdFromLocation(req.GetResponseHeader("Location"));
                if (string.IsNullOrEmpty(jobId))
                {
                    onError(new ApiError { code = ApiError.BadResponse, message = "no jobId in body or Location", httpStatus = req.responseCode });
                    yield break;
                }
                onJobCreated(new JobCreatedResponse { jobId = jobId });
            }
        }

        // ---------- GET /api/v1/jobs/{jobId} ----------

        public IEnumerator GetJob(string jobId, string requestId,
            Action<JobResponse> onJob, Action<ApiError> onError)
        {
            using (var req = UnityWebRequest.Get(Url(ApiV1Routes.Job(jobId))))
            {
                Prepare(req, requestId);
                yield return req.SendWebRequest();

                if (!Succeeded(req, out var error)) { onError(error); yield break; }
                var job = Parse<JobResponse>(req.downloadHandler.text);
                if (job == null || string.IsNullOrEmpty(job.status))
                {
                    onError(new ApiError { code = ApiError.BadResponse, message = "unexpected JobResponse", httpStatus = req.responseCode, jobId = jobId });
                    yield break;
                }
                onJob(job);
            }
        }

        // ---------- GET {asset.url} -> file ----------

        /// <summary>Downloads the GLB straight to savePath (no large byte[] in memory). Deletes a partial file on error.</summary>
        public IEnumerator DownloadAsset(string assetRelativeUrl, string savePath, string requestId,
            Action<string, long> onSaved, Action<ApiError> onError)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(savePath));
            string tmp = savePath + ".part";

            using (var req = new UnityWebRequest(Url(assetRelativeUrl), UnityWebRequest.kHttpVerbGET))
            {
                req.downloadHandler = new DownloadHandlerFile(tmp) { removeFileOnAbort = true };
                Prepare(req, requestId);
                req.timeout = DownloadTimeoutSeconds;
                yield return req.SendWebRequest();

                // DownloadHandlerFile has no .text (it throws), so the error envelope is read from the small .part file
                string errorBody = req.result == UnityWebRequest.Result.ProtocolError ? ReadSmallText(tmp) : null;
                if (!Succeeded(req, out var error, errorBody))
                {
                    TryDelete(tmp);
                    onError(error);
                    yield break;
                }
            }

            long bytes = new FileInfo(tmp).Length;
            TryDelete(savePath);
            File.Move(tmp, savePath);
            onSaved(savePath, bytes);
        }

        // ---------- POST /api/v1/jobs/{jobId}/retry ----------

        public IEnumerator RetryJob(string jobId, string requestId,
            Action<JobCreatedResponse> onAccepted, Action<ApiError> onError)
        {
            using (var req = UnityWebRequest.PostWwwForm(Url(ApiV1Routes.Retry(jobId)), ""))
            {
                Prepare(req, requestId);
                yield return req.SendWebRequest();

                if (!Succeeded(req, out var error)) { onError(error); yield break; }
                var body = Parse<JobCreatedResponse>(req.downloadHandler.text);
                onAccepted(body ?? new JobCreatedResponse { jobId = jobId });
            }
        }

        // ---------- POST /api/v1/jobs/{jobId}/cancel (API v1.2) ----------

        /// <summary>200 with the job, now FAILED(CANCELED). 409 JOB_NOT_PENDING when generation already started or the
        /// job already ended; 404 JOB_NOT_FOUND.</summary>
        public IEnumerator CancelJob(string jobId, string requestId,
            Action<JobResponse> onCanceled, Action<ApiError> onError)
        {
            using (var req = UnityWebRequest.PostWwwForm(Url(ApiV1Routes.Cancel(jobId)), ""))
            {
                Prepare(req, requestId);
                yield return req.SendWebRequest();

                if (!Succeeded(req, out var error)) { onError(error); yield break; }
                onCanceled(Parse<JobResponse>(req.downloadHandler.text));
            }
        }

        // ---------- GET /api/v1/healthz ----------

        public IEnumerator Health(Action<HealthResponse> onOk, Action<ApiError> onError)
        {
            using (var req = UnityWebRequest.Get(Url(ApiV1Routes.Health)))
            {
                Prepare(req, Guid.NewGuid().ToString("N"));
                req.timeout = 10;
                yield return req.SendWebRequest();

                if (!Succeeded(req, out var error)) { onError(error); yield break; }
                var health = Parse<HealthResponse>(req.downloadHandler.text);
                if (health == null) onError(new ApiError { code = ApiError.BadResponse, message = "unexpected health JSON" });
                else onOk(health);
            }
        }

        // ---------- helpers ----------

        string Url(string path) => ApiV1Routes.Combine(BaseUrl, path);

        void Prepare(UnityWebRequest req, string requestId)
        {
            req.timeout = TimeoutSeconds;
            req.SetRequestHeader("X-Request-Id", requestId);
            req.SetRequestHeader("ngrok-skip-browser-warning", "1");   // ngrok free tier: skip the HTML warning page (ignored elsewhere)
        }

        /// <summary>True on HTTP 2xx. Otherwise fills error from the v1 error envelope when present.
        /// errorBody: the response body when the download handler cannot give it as text (DownloadHandlerFile).</summary>
        static bool Succeeded(UnityWebRequest req, out ApiError error, string errorBody = null)
        {
            error = null;
            if (req.result == UnityWebRequest.Result.Success) return true;

            error = new ApiError { httpStatus = req.responseCode, message = req.error };
            if (req.result == UnityWebRequest.Result.ConnectionError || req.result == UnityWebRequest.Result.DataProcessingError)
            {
                error.code = ApiError.Network;
                return false;
            }

            // ProtocolError (4xx / 5xx): try the { error: { code, message, jobId } } envelope
            error.retryAfterSeconds = ApiError.ParseRetryAfter(ResponseHeader(req, "Retry-After"));
            var envelope = Parse<ErrorResponse>(errorBody ?? ReadText(req));
            if (envelope?.error != null && !string.IsNullOrEmpty(envelope.error.code))
            {
                error.code = envelope.error.code;
                error.message = envelope.error.message;
                error.jobId = envelope.error.jobId;
            }
            else
            {
                error.code = req.responseCode == 404 ? "NOT_FOUND" : "INTERNAL_ERROR";
            }
            return false;
        }

        /// <summary>A response header by name, or null. Header names are case-insensitive and may arrive in lower case
        /// (HTTP/2), so the full list is searched when the direct lookup finds nothing.</summary>
        static string ResponseHeader(UnityWebRequest req, string name)
        {
            string value = req.GetResponseHeader(name);
            if (value != null) return value;
            var all = req.GetResponseHeaders();
            if (all == null) return null;
            foreach (var kv in all)
                if (string.Equals(kv.Key, name, StringComparison.OrdinalIgnoreCase)) return kv.Value;
            return null;
        }

        static T Parse<T>(string json) where T : class
        {
            if (string.IsNullOrEmpty(json)) return null;
            try { return JsonConvert.DeserializeObject<T>(json); }
            catch (Exception e) { Debug.LogWarning($"[BesideApi] JSON parse failed: {e.Message}\n{json}"); return null; }
        }

        /// <summary>Response body as text, or null. Never throws (DownloadHandlerFile.text throws NotSupportedException).</summary>
        static string ReadText(UnityWebRequest req)
        {
            if (req.downloadHandler == null || req.downloadHandler is DownloadHandlerFile) return null;
            try { return req.downloadHandler.text; } catch (Exception) { return null; }
        }

        /// <summary>Text of a small file (an error envelope saved by DownloadHandlerFile), or null. Never throws.</summary>
        static string ReadSmallText(string path)
        {
            try { return File.Exists(path) && new FileInfo(path).Length < 64 * 1024 ? File.ReadAllText(path) : null; }
            catch (Exception) { return null; }
        }

        static void TryDelete(string path)
        {
            try { if (File.Exists(path)) File.Delete(path); } catch { /* ignore */ }
        }
    }
}
