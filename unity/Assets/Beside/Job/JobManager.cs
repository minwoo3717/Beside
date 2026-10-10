using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;
using Beside.Api;
using Beside.Metrics;
using UnityEngine;
using UnityEngine.XR.Interaction.Toolkit.Samples.StarterAssets;
using Debug = UnityEngine.Debug;

namespace Beside.Job
{
    /// <summary>
    /// Owns one job's lifecycle: upload -> poll -> (cache | download) -> load into AR -> metrics.
    /// Resumes the last job after an app restart, retries FAILED jobs through POST /retry,
    /// and reports progress through events so the UI stays free of API code.
    /// </summary>
    public class JobManager : MonoBehaviour
    {
        public enum State { Idle, Uploading, Waiting, Downloading, Loading, Ready, Failed }

        [Header("Server")]
        [SerializeField] private string baseUrl = "http://192.168.0.10:8080";
        [SerializeField] private float pollingIntervalSeconds = 2f;
        [SerializeField] private float maxWaitSeconds = 600f;

        [Header("References")]
        [SerializeField] private RuntimeModelLoader modelLoader;   // puts the GLB into the AR scene
        [SerializeField] private MetricsRecorder metrics;          // optional

        public State CurrentState { get; private set; } = State.Idle;
        public string CurrentJobId { get; private set; }
        public string LastErrorCode { get; private set; }
        public bool LastErrorRetryable { get; private set; }
        public string BaseUrl => baseUrl;
        /// <summary>The URL set in the Inspector (build default). The app can override it at runtime.</summary>
        public string DefaultBaseUrl { get; private set; }
        const string BaseUrlPrefKey = "beside.baseUrl";

        /// <summary>(state, text to show the user)</summary>
        public event Action<State, string> StateChanged;
        /// <summary>(error code, user text, retryable)</summary>
        public event Action<string, string, bool> JobFailed;
        /// <summary>(jobId, local GLB path, spawner template object)</summary>
        public event Action<string, string, GameObject> ModelReady;
        /// <summary>(jobId, placed instance) — first AR placement of the current job (e2e end point).</summary>
        public event Action<string, GameObject> ModelPlaced;

        const string JobIdPrefKey = "beside.lastJobId";
        BesideApiClient api;
        Coroutine running;
        string requestId;
        ObjectSpawner spawner;
        MetricsRecorder.Row pendingRow;      // waits for the first placement to record e2eMs
        Stopwatch pendingE2e;

        void Awake()
        {
            DefaultBaseUrl = baseUrl;
            string saved = PlayerPrefs.GetString(BaseUrlPrefKey, "");
            if (!string.IsNullOrWhiteSpace(saved)) baseUrl = saved;       // server chosen in the app wins
            api = new BesideApiClient(baseUrl);
            if (modelLoader == null) modelLoader = FindFirstObjectByType<RuntimeModelLoader>();
            if (metrics == null) metrics = FindFirstObjectByType<MetricsRecorder>();
            spawner = FindFirstObjectByType<ObjectSpawner>();
            if (spawner != null) spawner.objectSpawned += OnObjectSpawned;
        }

        void OnDestroy()
        {
            if (spawner != null) spawner.objectSpawned -= OnObjectSpawned;
        }

        /// <summary>e2eMs ends when the model is actually visible: the first AR placement after Ready.</summary>
        void OnObjectSpawned(GameObject instance)
        {
            if (pendingRow == null || instance == null || !instance.name.StartsWith("Runtime_job_" + pendingRow.jobId)) return;
            pendingRow.e2eMs = pendingE2e.ElapsedMilliseconds;
            MetricsRecorder.Log(requestId, pendingRow.jobId, "first_ar_display", pendingRow.e2eMs);
            if (metrics != null) metrics.Complete(pendingRow);   // samples FPS for 30 s, then writes the row
            ModelPlaced?.Invoke(pendingRow.jobId, instance);
            pendingRow = null;
        }

        void Start()
        {
            string saved = PlayerPrefs.GetString(JobIdPrefKey, "");
            if (!string.IsNullOrEmpty(saved)) ResumeJob(saved);
        }

        // ---------- public API ----------

        public void StartJob(IList<Photo> photos)
        {
            if (IsBusy) return;
            if (photos == null || photos.Count == 0) { Fail("NO_PHOTOS"); return; }
            if (photos.Count > ApiV1Routes.MaxPhotos) { Fail("TOO_MANY_PHOTOS"); return; }
            long total = 0;
            foreach (var p in photos)
            {
                if (p.bytes.LongLength > ApiV1Routes.MaxPhotoBytes) { Fail("PAYLOAD_TOO_LARGE"); return; }
                total += p.bytes.LongLength;
            }
            if (total > ApiV1Routes.MaxRequestBytes) { Fail("PAYLOAD_TOO_LARGE"); return; }

            running = StartCoroutine(Flow(photos, null));
        }

        /// <summary>Continue polling a job created earlier (app restart).</summary>
        public void ResumeJob(string jobId)
        {
            if (IsBusy) return;
            running = StartCoroutine(Flow(null, jobId));
        }

        /// <summary>Retry after a failure. Server job failures go through POST /retry; app-side failures re-run the flow.</summary>
        public void Retry()
        {
            if (IsBusy || string.IsNullOrEmpty(CurrentJobId)) return;
            if (ErrorMessages.IsJobFailureCode(LastErrorCode)) running = StartCoroutine(RetryOnServer(CurrentJobId));
            else ResumeJob(CurrentJobId);
        }

        public void CancelJob()
        {
            if (running != null) StopCoroutine(running);
            running = null;
            SetState(State.Idle, "");
        }

        public void ClearSavedJob()
        {
            CancelJob();
            PlayerPrefs.DeleteKey(JobIdPrefKey);
            PlayerPrefs.Save();
            CurrentJobId = null;
            LastErrorCode = null;
        }

        public void CheckHealth(Action<HealthResponse> onOk, Action<ApiError> onError) => StartCoroutine(api.Health(onOk, onError));

        /// <summary>Calls /healthz on a URL without switching to it (for the settings screen).</summary>
        public void TestBaseUrl(string url, Action<HealthResponse> onOk, Action<ApiError> onError) =>
            StartCoroutine(new BesideApiClient(NormalizeUrl(url)).Health(onOk, onError));

        /// <summary>Switches the server at runtime and remembers it. Empty = back to the build default.
        /// The saved job belongs to the old server, so it is cleared.</summary>
        public void SetBaseUrl(string url)
        {
            if (IsBusy) return;
            string n = NormalizeUrl(url);
            if (string.IsNullOrEmpty(n)) { PlayerPrefs.DeleteKey(BaseUrlPrefKey); n = DefaultBaseUrl; }
            else PlayerPrefs.SetString(BaseUrlPrefKey, n);
            PlayerPrefs.Save();
            if (n == baseUrl) return;
            baseUrl = n;
            api = new BesideApiClient(baseUrl);
            ClearSavedJob();
            Debug.Log($"[JobManager] server -> {baseUrl}");
        }

        /// <summary>Trims, adds http:// when no scheme is given, drops a trailing slash.</summary>
        public static string NormalizeUrl(string url)
        {
            if (string.IsNullOrWhiteSpace(url)) return "";
            url = url.Trim();
            if (!url.StartsWith("http://") && !url.StartsWith("https://")) url = "https://" + url;
            return url.TrimEnd('/');
        }

        public bool IsBusy => CurrentState is State.Uploading or State.Waiting or State.Downloading or State.Loading;

        public static string CachePath(string jobId, string variant = "base") =>
            Path.Combine(Application.persistentDataPath, "models", $"{jobId}-{variant}.glb");

        // ---------- the flow ----------

        IEnumerator Flow(IList<Photo> photos, string existingJobId)
        {
            requestId = Guid.NewGuid().ToString("N");
            var e2e = Stopwatch.StartNew();
            var row = new MetricsRecorder.Row();

            // 1. upload (skipped when resuming)
            if (photos != null)
            {
                SetState(State.Uploading, "사진을 업로드하는 중이에요");
                var sw = Stopwatch.StartNew();
                string jobId = null; ApiError err = null;
                yield return api.UploadPhotos(photos, requestId, r => jobId = r.jobId, e => err = e);
                row.uploadMs = sw.ElapsedMilliseconds;
                MetricsRecorder.Log(requestId, jobId, "upload", row.uploadMs, err?.code, err?.message);
                if (err != null) { Fail(err.code); yield break; }

                CurrentJobId = jobId;
                PlayerPrefs.SetString(JobIdPrefKey, jobId);
                PlayerPrefs.Save();
            }
            else
            {
                CurrentJobId = existingJobId;
            }
            row.jobId = CurrentJobId;

            // 2. poll until COMPLETED / FAILED
            SetState(State.Waiting, "순서를 기다리고 있어요");
            var wait = Stopwatch.StartNew();
            JobResponse job = null;
            while (true)
            {
                JobResponse got = null; ApiError err = null;
                yield return api.GetJob(CurrentJobId, requestId, j => got = j, e => err = e);
                if (err != null)
                {
                    MetricsRecorder.Log(requestId, CurrentJobId, "status_wait", wait.ElapsedMilliseconds, err.code, err.message);
                    if (err.code == "JOB_NOT_FOUND") PlayerPrefs.DeleteKey(JobIdPrefKey);
                    Fail(err.code);
                    yield break;
                }
                job = got;
                switch (job.Status)
                {
                    case JobStatus.Pending:    SetState(State.Waiting, "순서를 기다리고 있어요"); break;
                    case JobStatus.Processing: SetState(State.Waiting, "3D 모델을 만드는 중이에요"); break;
                    case JobStatus.Unknown:    Debug.LogWarning($"[JobManager] unknown status '{job.status}', keep polling"); break;
                }
                if (job.IsTerminal) break;

                if (wait.Elapsed.TotalSeconds > maxWaitSeconds)
                {
                    MetricsRecorder.Log(requestId, CurrentJobId, "status_wait", wait.ElapsedMilliseconds, ErrorMessages.WaitTimeout);
                    Fail(ErrorMessages.WaitTimeout);
                    yield break;
                }
                yield return new WaitForSecondsRealtime(pollingIntervalSeconds);
            }
            row.waitMs = wait.ElapsedMilliseconds;

            if (job.Status == JobStatus.Failed)
            {
                string code = job.error?.code ?? "INFERENCE_FAILED";
                MetricsRecorder.Log(requestId, CurrentJobId, "status_wait", row.waitMs, code, job.error?.message);
                Fail(code);
                yield break;
            }
            MetricsRecorder.Log(requestId, CurrentJobId, "status_wait", row.waitMs);

            // 3. cache or download
            string path = CachePath(CurrentJobId);
            if (IsValidCachedGlb(path))
            {
                row.downloadMs = 0;
                MetricsRecorder.Log(requestId, CurrentJobId, "download", 0, null, "cache hit", new FileInfo(path).Length);
            }
            else
            {
                SetState(State.Downloading, "모델을 다운로드하는 중이에요");
                string assetUrl = job.asset?.url ?? ApiV1Routes.Asset(CurrentJobId);
                var dl = Stopwatch.StartNew();
                long bytes = 0; ApiError err = null;
                yield return api.DownloadAsset(assetUrl, path, requestId, (p, b) => bytes = b, e => err = e);
                row.downloadMs = dl.ElapsedMilliseconds;
                string code = err?.code;
                if (code == ApiError.Network) code = ErrorMessages.DownloadFailed;
                if (code == null && !IsValidCachedGlb(path)) { code = ErrorMessages.FileCorrupt; TryDelete(path); }
                MetricsRecorder.Log(requestId, CurrentJobId, "download", row.downloadMs, code, err?.message, bytes);
                if (code != null) { Fail(code); yield break; }
            }

            // 4. load into AR
            SetState(State.Loading, "AR용으로 준비하는 중이에요");
            var ld = Stopwatch.StartNew();
            GameObject template = null;
            if (modelLoader != null)
            {
                Task<GameObject> task = modelLoader.LoadAndRegister(File.ReadAllBytes(path), "job_" + CurrentJobId);
                yield return new WaitUntil(() => task.IsCompleted);
                template = task.IsCompletedSuccessfully ? task.Result : null;
            }
            row.loadMs = ld.ElapsedMilliseconds;
            MetricsRecorder.Log(requestId, CurrentJobId, "model_load", row.loadMs, template == null ? ErrorMessages.LoadFailed : null);
            if (template == null) { Fail(ErrorMessages.LoadFailed); yield break; }

            MetricsRecorder.Log(requestId, CurrentJobId, "ready", e2e.ElapsedMilliseconds);
            pendingRow = row;            // e2eMs is finished by OnObjectSpawned (first placement)
            pendingE2e = e2e;

            running = null;
            SetState(State.Ready, "완료! 바닥을 터치해서 배치하세요");
            ModelReady?.Invoke(CurrentJobId, path, template);
        }

        IEnumerator RetryOnServer(string jobId)
        {
            requestId = Guid.NewGuid().ToString("N");
            SetState(State.Uploading, "다시 시도하는 중이에요");
            ApiError err = null;
            yield return api.RetryJob(jobId, requestId, _ => { }, e => err = e);
            MetricsRecorder.Log(requestId, jobId, "retry", 0, err?.code, err?.message);
            if (err != null) { Fail(err.code); yield break; }
            running = StartCoroutine(Flow(null, jobId));
        }

        // ---------- helpers ----------

        void Fail(string code)
        {
            running = null;
            LastErrorCode = code;
            LastErrorRetryable = ErrorMessages.IsRetryable(code);
            SetState(State.Failed, ErrorMessages.ForCode(code));
            JobFailed?.Invoke(code, ErrorMessages.ForCode(code), LastErrorRetryable);
        }

        void SetState(State s, string text)
        {
            CurrentState = s;
            StateChanged?.Invoke(s, text);
        }

        static bool IsValidCachedGlb(string path)
        {
            try
            {
                if (!File.Exists(path) || new FileInfo(path).Length < 20) return false;
                using var fs = File.OpenRead(path);
                var head = new byte[4];
                return fs.Read(head, 0, 4) == 4 && head[0] == 0x67 && head[1] == 0x6C && head[2] == 0x54 && head[3] == 0x46; // "glTF"
            }
            catch { return false; }
        }

        static void TryDelete(string path)
        {
            try { if (File.Exists(path)) File.Delete(path); } catch { /* ignore */ }
        }
    }
}
