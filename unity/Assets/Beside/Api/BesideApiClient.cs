using System;
using System.Collections;
using System.Collections.Generic;
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

        public const string Network = "NETWORK_ERROR";
        public const string BadResponse = "BAD_RESPONSE";

        public override string ToString() => $"{code} (HTTP {httpStatus}) {message}";
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

        public IEnumerator UploadPhotos(IList<Photo> photos, string requestId,
            Action<JobCreatedResponse> onJobCreated, Action<ApiError> onError)
        {
            var sections = new List<IMultipartFormSection>();
            foreach (var p in photos)
                sections.Add(new MultipartFormFileSection(ApiV1Routes.PhotosField, p.bytes, p.fileName, p.mimeType));

            using (var req = UnityWebRequest.Post(Url(ApiV1Routes.Jobs), sections))
            {
                Prepare(req, requestId);
                req.SetRequestHeader(ApiV1Routes.IdempotencyKeyHeader, requestId); // same key -> same jobId on a retried upload
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

                if (!Succeeded(req, out var error))
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

        /// <summary>True on HTTP 2xx. Otherwise fills error from the v1 error envelope when present.</summary>
        static bool Succeeded(UnityWebRequest req, out ApiError error)
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
            var envelope = Parse<ErrorResponse>(req.downloadHandler?.text);
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

        static T Parse<T>(string json) where T : class
        {
            if (string.IsNullOrEmpty(json)) return null;
            try { return JsonConvert.DeserializeObject<T>(json); }
            catch (Exception e) { Debug.LogWarning($"[BesideApi] JSON parse failed: {e.Message}\n{json}"); return null; }
        }

        static void TryDelete(string path)
        {
            try { if (File.Exists(path)) File.Delete(path); } catch { /* ignore */ }
        }
    }
}
