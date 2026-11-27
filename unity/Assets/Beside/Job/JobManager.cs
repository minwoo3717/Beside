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
        [Tooltip("Continue the saved job as soon as the app starts. The designed UI turns this off and offers the job on its home screen instead.")]
        [SerializeField] private bool resumeOnStart = true;

        [Header("References")]
        [SerializeField] private RuntimeModelLoader modelLoader;   // puts the GLB into the AR scene
        [SerializeField] private MetricsRecorder metrics;          // optional

        public State CurrentState { get; private set; } = State.Idle;
        public string CurrentJobId { get; private set; }
        public string LastErrorCode { get; private set; }
        public bool LastErrorRetryable { get; private set; }
        /// <summary>UTC time before which neither Retry() nor StartJob() sends anything: set by a failure that carried
        /// Retry-After (QUEUE_FULL), null otherwise. It outlives ClearSavedJob ("처음부터"), so new photos wait too, like the
        /// web Mock UI; a server switch clears it. Nothing is re-sent automatically; the UI enables its send buttons from
        /// this time.</summary>
        public DateTime? RetryAvailableAt { get; private set; }
        /// <summary>Seconds left until RetryAvailableAt (0 when Retry or StartJob can send now).</summary>
        public float RetryWaitSeconds => RetryAvailableAt.HasValue
            ? Mathf.Max(0f, (float)(RetryAvailableAt.Value - DateTime.UtcNow).TotalSeconds)
            : 0f;
        /// <summary>Read in Start: set it before then (OnEnable of a UI controller) to keep the saved job waiting.</summary>
        public bool ResumeOnStart { get => resumeOnStart; set => resumeOnStart = value; }
        /// <summary>Job saved from an earlier run (or the current one), empty when none. ResumeJob(SavedJobId) continues it.</summary>
        public string SavedJobId => PlayerPrefs.GetString(JobIdPrefKey, "");
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
        /// <summary>Numbers of the last ModelReady, read by ProductEvents (model_ready, docs/METRICS.md §6.1): the GLB came
        /// from the app cache, model load ms, status wait ms (the same loadMs / waitMs as metrics.csv).</summary>
        public (bool cached, long loadMs, long waitMs) LastReady { get; private set; }

        const string JobIdPrefKey = "beside.lastJobId";
        BesideApiClient api;
        Coroutine running;
        string requestId;
        bool lastFailureWasJob;
        List<Photo> lastPhotos;   // copy of the photos until the server accepts them (202), so Retry can send them again
        string uploadKey;         // Idempotency-Key of those photos: a repeated upload returns the same jobId
        int loadGen;              // bumped by CancelJob: a model load that finishes after a cancel is dropped
        int cancelGen;            // bumped by CancelByUser while a request is on its way: that flow cancels what it made

        /// <summary>Server timings of the job being waited for (null before the first status). Real jobs take minutes
        /// and report no progress, so the UI shows elapsed time from these.</summary>
        public JobResponse LastJobStatus { get; private set; }
        /// <summary>Seconds since the job was created, from the server's timings (falls back to the local wait).</summary>
        public float ElapsedSeconds => LastJobStatus?.timings != null
            ? (LastJobStatus.timings.queuedMs + (LastJobStatus.timings.processingMs ?? 0)) / 1000f
            : waitClock != null ? (float)waitClock.Elapsed.TotalSeconds : 0f;
        Stopwatch waitClock;
        ObjectSpawner spawner;
        MetricsRecorder.Row pendingRow;      // waits for the first placement to record e2eMs
        // uploadMs of jobs uploaded in this session: a resumed / retried run of the same job writes the real value
        readonly System.Collections.Generic.Dictionary<string, long> uploadMsByJob = new System.Collections.Generic.Dictionary<string, long>();
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
            if (!resumeOnStart) return;
            string saved = PlayerPrefs.GetString(JobIdPrefKey, "");
            if (!string.IsNullOrEmpty(saved)) ResumeJob(saved);
        }

        // ---------- public API ----------

        public void StartJob(IList<Photo> photos)
        {
            if (IsBusy) return;
            if (RetryWaitSeconds > 0f)   // QUEUE_FULL: a new upload also waits for Retry-After (checked before Fail clears it)
            {
                Debug.Log($"[JobManager] upload ignored: Retry-After has {RetryWaitSeconds:0} s left");
                return;
            }
            if (photos == null || photos.Count == 0) { Fail("NO_PHOTOS"); return; }
            if (photos.Count > ApiV1Routes.MaxPhotos) { Fail("TOO_MANY_PHOTOS"); return; }
            long total = 0;
            foreach (var p in photos)
            {
                if (p.bytes.LongLength > ApiV1Routes.MaxPhotoBytes) { Fail("PAYLOAD_TOO_LARGE"); return; }
                total += p.bytes.LongLength;
            }
            if (total > ApiV1Routes.MaxRequestBytes) { Fail("PAYLOAD_TOO_LARGE"); return; }

            lastPhotos = new List<Photo>(photos);   // the UI may clear its own list (StartOver)
            uploadKey = Guid.NewGuid().ToString("N");
            CurrentJobId = null;                    // a failed upload must not "retry" an older job
            running = StartCoroutine(Flow(lastPhotos, null));
        }

        /// <summary>Continue polling a job created earlier (app restart).</summary>
        public void ResumeJob(string jobId)
        {
            if (IsBusy) return;
            running = StartCoroutine(Flow(null, jobId));
        }

        /// <summary>Retry after a failure. A failed upload sends the same photos again (same Idempotency-Key);
        /// server job failures go through POST /retry; other app-side failures re-run the flow.
        /// QUEUE_FULL: nothing is sent before RetryAvailableAt. A refused upload keeps CurrentJobId null, so it is
        /// sent again as an upload; a refused POST /retry (the job stays FAILED) goes through POST /retry again.</summary>
        public void Retry()
        {
            if (IsBusy) return;
            if (RetryWaitSeconds > 0f)
            {
                Debug.Log($"[JobManager] retry ignored: Retry-After has {RetryWaitSeconds:0} s left");
                return;
            }
            if (string.IsNullOrEmpty(CurrentJobId))
            {
                if (lastPhotos != null) running = StartCoroutine(Flow(lastPhotos, null));
                return;
            }
            if (lastFailureWasJob || ErrorMessages.IsJobFailureCode(LastErrorCode) || LastErrorCode == ErrorMessages.QueueFull)
                running = StartCoroutine(RetryOnServer(CurrentJobId));
            else ResumeJob(CurrentJobId);
        }

        public void CancelJob()
        {
            if (running != null) StopCoroutine(running);
            running = null;
            loadGen++;   // the model load task keeps running: it must not register the cancelled model
            SetState(State.Idle, "");
        }

        /// <summary>
        /// The user's "취소": stops the app's flow like <see cref="CancelJob"/> (or forgets the job too, like
        /// <see cref="ClearSavedJob"/>, when <paramref name="forget"/>) and has the server drop what it would still make
        /// (POST /cancel, API v1.2). Before, a canceled job stayed PENDING and later took the GPU for a model nobody
        /// would download (review finding, 2026-11-25).
        /// <list type="bullet">
        /// <item>Waiting (not PROCESSING): POST /cancel now.</item>
        /// <item>Uploading (new photos or POST /retry): the request is already on its way and stopping the coroutine
        /// would not stop it, so the flow carries on unseen and cancels the job the server accepted (cancelGen).</item>
        /// </list>
        /// The answer is only logged: 409 JOB_NOT_PENDING means generation already started. A canceled job is forgotten
        /// even when <paramref name="forget"/> is false: kept, it would only reopen as the canceled-job screen.
        /// </summary>
        public void CancelByUser(bool forget)
        {
            string jobId = CurrentJobId;
            if (CurrentState == State.Uploading)
            {
                cancelGen++;   // Flow / RetryOnServer see it when their request returns
                running = null;
                loadGen++;
                if (forget) ForgetJob();
                SetState(State.Idle, "");
                return;
            }
            bool queuedOnServer = CurrentState == State.Waiting && !string.IsNullOrEmpty(jobId)
                && (LastJobStatus == null || LastJobStatus.Status != JobStatus.Processing);
            if (forget) ClearSavedJob();
            else CancelJob();
            if (queuedOnServer) CancelOnServer(jobId);
        }

        void CancelOnServer(string jobId)
        {
            StartCoroutine(api.CancelJob(jobId, Guid.NewGuid().ToString("N"),
                job =>
                {
                    Debug.Log($"[JobManager] cancel {jobId}: {(job != null ? job.Status.ToString() : "ok")}");
                    if (!IsBusy && SavedJobId == jobId) ForgetJob();   // FAILED(CANCELED) now
                },
                error => Debug.Log($"[JobManager] cancel {jobId} not done on the server: {error}")));
        }

        public void ClearSavedJob()
        {
            // Forget first: CancelJob raises StateChanged(Idle), and a listener reading SavedJobId then must not see the
            // job being dropped (after a server change the home 이어보기 card stayed on for it, review 2026-11-25).
            ForgetJob();
            CancelJob();
        }

        void ForgetJob()
        {
            PlayerPrefs.DeleteKey(JobIdPrefKey);
            PlayerPrefs.Save();
            CurrentJobId = null;
            LastErrorCode = null;
            lastPhotos = null;
            uploadKey = null;
            // RetryAvailableAt stays: the server's queue is still full for new photos ("처음부터", "다른 사진 고르기")
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
            RetryAvailableAt = null;   // Retry-After came from the old server
            Debug.Log($"[JobManager] server -> {baseUrl}");
        }

        /// <summary>Trims, adds https:// when no scheme is given, drops a trailing slash.
        /// A plain-HTTP server (LAN Mock) needs "http://" typed explicitly.</summary>
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
            int myCancelGen = cancelGen;
            var e2e = Stopwatch.StartNew();
            var row = new MetricsRecorder.Row();

            // 1. upload (skipped when resuming)
            if (photos != null)
            {
                SetState(State.Uploading, "사진을 업로드하는 중이에요");
                var sw = Stopwatch.StartNew();
                string jobId = null; ApiError err = null;
                yield return api.UploadPhotos(photos, requestId, r => jobId = r.jobId, e => err = e, uploadKey);
                row.uploadMs = sw.ElapsedMilliseconds;
                MetricsRecorder.Log(requestId, jobId, "upload", row.uploadMs, err?.code, err?.message);
                if (myCancelGen != cancelGen)   // canceled while uploading: drop the job the server made, touch nothing here
                {
                    if (jobId != null) CancelOnServer(jobId);
                    yield break;
                }
                if (err != null) { Fail(err.code, retryAfterSeconds: err.retryAfterSeconds); yield break; }   // CurrentJobId stays null

                lastPhotos = null;   // accepted: later retries go through the jobId
                CurrentJobId = jobId;
                if (jobId != null) uploadMsByJob[jobId] = row.uploadMs;
                PlayerPrefs.SetString(JobIdPrefKey, jobId);
                PlayerPrefs.Save();
            }
            else
            {
                CurrentJobId = existingJobId;
                if (existingJobId != null && uploadMsByJob.TryGetValue(existingJobId, out long up)) row.uploadMs = up;
            }
            row.jobId = CurrentJobId;

            // 2. poll until COMPLETED / FAILED
            SetState(State.Waiting, "순서를 기다리고 있어요");
            var wait = Stopwatch.StartNew();
            waitClock = wait; LastJobStatus = null;
            JobResponse job = null;
            while (true)
            {
                JobResponse got = null; ApiError err = null;
                yield return api.GetJob(CurrentJobId, requestId, j => got = j, e => err = e);
                if (err != null)
                {
                    MetricsRecorder.Log(requestId, CurrentJobId, "status_wait", wait.ElapsedMilliseconds, err.code, err.message);
                    if (err.code == "JOB_NOT_FOUND") PlayerPrefs.DeleteKey(JobIdPrefKey);
                    Fail(err.code, retryAfterSeconds: err.retryAfterSeconds);
                    yield break;
                }
                job = got;
                LastJobStatus = got;
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
                Fail(code, fromJob: true);   // e.g. INTERNAL_ERROR after a server restart -> POST /retry
                yield break;
            }
            MetricsRecorder.Log(requestId, CurrentJobId, "status_wait", row.waitMs);

            // 3. cache or download
            string path = CachePath(CurrentJobId);
            bool fromCache = IsValidCachedGlb(path);
            if (fromCache)
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
                int gen = loadGen;   // CancelJob stops this coroutine, but not the load task
                Task<GameObject> task = modelLoader.LoadAndRegister(File.ReadAllBytes(path), "job_" + CurrentJobId,
                    stillWanted: () => gen == loadGen);
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
            LastReady = (fromCache, row.loadMs, row.waitMs);   // before the events: ProductEvents reads it in ModelReady
            SetState(State.Ready, "완료! 바닥을 터치해서 배치하세요");
            ModelReady?.Invoke(CurrentJobId, path, template);
        }

        IEnumerator RetryOnServer(string jobId)
        {
            requestId = Guid.NewGuid().ToString("N");
            int myCancelGen = cancelGen;
            SetState(State.Uploading, "다시 시도하는 중이에요");
            ApiError err = null;
            yield return api.RetryJob(jobId, requestId, _ => { }, e => err = e);
            MetricsRecorder.Log(requestId, jobId, "retry", 0, err?.code, err?.message);
            if (myCancelGen != cancelGen)   // canceled while the retry was on its way: the accepted retry is canceled again
            {
                if (err == null) CancelOnServer(jobId);
                yield break;
            }
            if (err != null) { Fail(err.code, retryAfterSeconds: err.retryAfterSeconds); yield break; }   // QUEUE_FULL: the job is still FAILED
            running = StartCoroutine(Flow(null, jobId));
        }

        // ---------- helpers ----------

        /// <summary>retryAfterSeconds: Retry-After of the refusing response (QUEUE_FULL), 0 = Retry may send at once.</summary>
        void Fail(string code, bool fromJob = false, int retryAfterSeconds = 0)
        {
            running = null;
            LastErrorCode = code;
            lastFailureWasJob = fromJob;
            LastErrorRetryable = fromJob || ErrorMessages.IsRetryable(code);
            RetryAvailableAt = retryAfterSeconds > 0 ? DateTime.UtcNow.AddSeconds(retryAfterSeconds) : (DateTime?)null;   // before the events: the UI reads it
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
