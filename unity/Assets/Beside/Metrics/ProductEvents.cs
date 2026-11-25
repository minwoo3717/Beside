using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;
using Beside.AR;
using Beside.Api;
using Beside.Job;
using Beside.Pet;
using Newtonsoft.Json;
using UnityEngine;

namespace Beside.Metrics
{
    /// <summary>
    /// Product events (click / impression / funnel, docs/METRICS.md §6, REVIEW_CHECKLIST #24): which screens the user sees
    /// and what they tap, sent to POST /api/v1/events. Separate from metrics.csv (MetricsRecorder, unchanged).
    ///  - The UI calls the static API with one line (<see cref="ScreenView"/>, <see cref="Track"/>); JobManager, ARGuidance
    ///    and PetAnimationPlayer events are subscribed here (job_failed_shown, model_ready, model_placed, ar_issue_shown,
    ///    animation_play), so those classes do not know about this one.
    ///  - installId = random GUID in PlayerPrefs "beside.installId" (never a device id), sessionId = new GUID per launch.
    ///  - Events wait in memory and in persistentDataPath/metrics/events.jsonl (offline, at most 500 lines). Sent every
    ///    30 s, at 20 queued, and when the app goes to the background; 2xx and 4xx remove the batch, a connection error or
    ///    5xx keeps it and retries after 5 / 10 / 20 s, then every 30 s. Never blocks the UI.
    ///  - Same server address and headers as the job requests (JobManager.BaseUrl, BesideApiClient: X-Request-Id).
    ///  - Off switch: put this component in the scene and untick Send Events, or define BESIDE_NO_PRODUCT_EVENTS.
    ///    Without one in the scene it installs itself (RuntimeInitializeOnLoadMethod) with the defaults below.
    ///  - Logcat: [BesideEvent].
    /// Props are only the enums, whole numbers and booleans of METRICS §6.1: no file names, addresses, tokens or typed text.
    /// </summary>
    public class ProductEvents : MonoBehaviour
    {
        [Tooltip("Send product events to POST /api/v1/events (docs/METRICS.md §6). Off: nothing is queued, written or sent.")]
        [SerializeField] private bool sendEvents = true;
        [Tooltip("Seconds between regular sends.")]
        [SerializeField] private float flushIntervalSeconds = 30f;
        [Tooltip("Send as soon as this many events are waiting.")]
        [SerializeField] private int flushAtCount = 20;
        [Tooltip("Log every event and every send as a [BesideEvent] line.")]
        [SerializeField] private bool logEvents = true;

        public const string InstallIdPrefKey = "beside.installId";
        public const string Platform = "android";
        const int MaxStoredEvents = 500;
        const float BindRetrySeconds = 1f;
        static readonly float[] RetryDelaySeconds = { 5f, 10f, 20f };
        static readonly HashSet<string> KnownClips = new HashSet<string> { "Walk", "Sit", "TailWag", "HeadTilt", "Idle" };
        static readonly JsonSerializerSettings Json = new JsonSerializerSettings { NullValueHandling = NullValueHandling.Ignore };

        static ProductEvents instance;
        static bool quitting;
        static float arEnteredAt = -1f;
        static float centerPlacementAt = -100f;

        /// <summary>The screen of the last screen_view (lower-case DesignedUIController.Page name), null before the first.</summary>
        public static string CurrentScreen { get; private set; }

        /// <summary>One waiting event with the batch fields of its session (a file line can be from an earlier launch).
        /// A line of events.jsonl through Newtonsoft only (not Unity serialization).</summary>
        class Line
        {
            public string sessionId;
            public string appVersion;
            public ProductEvent ev;
        }

        readonly List<Line> queue = new List<Line>();
        string installId, sessionId, appVersion, filePath;
        int seq;
        bool sending;
        int failures;
        float nextFlushAt, nextBindAt;
        JobManager jobManager;
        ARGuidance guidance;
        PetAnimationPlayer petAnim;

        // =====================================================================
        // static API (one-line calls from the UI)
        // =====================================================================

        /// <summary>screen_view when the screen changes (the same screen twice in a row is one view). screen: lower case.</summary>
        public static void ScreenView(string screen)
        {
            if (string.IsNullOrEmpty(screen) || screen == CurrentScreen) return;
            CurrentScreen = screen;
            if (screen == "ar") arEnteredAt = Time.realtimeSinceStartup;
            Track("screen_view");
        }

        public static void Track(string name, params (string key, object value)[] props) => TrackJob(name, null, props);

        /// <summary>Queues one event on the current screen. Values: string (enum), int / long, bool only.</summary>
        public static void TrackJob(string name, string jobId, params (string key, object value)[] props)
        {
#if !BESIDE_NO_PRODUCT_EVENTS
            var host = Instance;
            if (host != null) host.Enqueue(name, jobId, props);
#endif
        }

        /// <summary>photos_selected: count 0 = the picker closed without a photo, 10 = ten or more.</summary>
        public static void PhotosSelected(string source, int count) =>
            Track("photos_selected", ("source", source), ("count", Mathf.Clamp(count, 0, 10)));

        /// <summary>job_cancel_tap with the stage and the job's elapsed seconds; call before JobManager.CancelByUser.</summary>
        public static void JobCancelTap(JobManager jobs)
        {
            if (jobs == null) return;
            string stage = jobs.CurrentState switch
            {
                JobManager.State.Uploading => "uploading",
                JobManager.State.Waiting => jobs.LastJobStatus != null && jobs.LastJobStatus.Status == JobStatus.Processing ? "processing" : "queued",
                JobManager.State.Downloading => "downloading",
                JobManager.State.Loading => "loading",
                _ => null
            };
            int elapsed = Mathf.Clamp(Mathf.FloorToInt(jobs.ElapsedSeconds), 0, 86400);
            if (stage == null) TrackJob("job_cancel_tap", jobs.CurrentJobId, ("elapsedSec", elapsed));
            else TrackJob("job_cancel_tap", jobs.CurrentJobId, ("stage", stage), ("elapsedSec", elapsed));
        }

        /// <summary>Call just before CenterPlacer.PlaceAtCenter: the model_placed that follows is method=center.</summary>
        public static void NoteCenterPlacement() => centerPlacementAt = Time.realtimeSinceStartup;

        static ProductEvents Instance
        {
            get
            {
                if (instance == null && !quitting && Application.isPlaying)
                {
                    instance = FindAnyObjectByType<ProductEvents>();
                    if (instance == null)
                    {
                        var go = new GameObject("BesideProductEvents");
                        DontDestroyOnLoad(go);
                        instance = go.AddComponent<ProductEvents>();
                    }
                }
                return instance;
            }
        }

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
        static void ResetStatics()   // Enter Play Mode without a domain reload keeps statics
        {
            instance = null; quitting = false; CurrentScreen = null; arEnteredAt = -1f; centerPlacementAt = -100f;
        }

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void AutoInstall()
        {
#if !BESIDE_NO_PRODUCT_EVENTS
            _ = Instance;   // also sends what an earlier launch left in events.jsonl
#endif
        }

        // =====================================================================
        // lifecycle
        // =====================================================================

        void Awake()
        {
            if (instance != null && instance != this) { Destroy(this); return; }
            instance = this;
            Init();
        }

        /// <summary>Also called by Enqueue: a component placed in the scene can be found and used by another object's
        /// OnEnable before its own Awake has run.</summary>
        void Init()
        {
            if (filePath != null) return;
            Application.quitting += OnQuitting;
            installId = LoadInstallId();
            sessionId = Guid.NewGuid().ToString("D");
            appVersion = SafeVersion(Application.version);
            filePath = Path.Combine(Application.persistentDataPath, "metrics", "events.jsonl");
            LoadFile();
            nextFlushAt = Time.unscaledTime + flushIntervalSeconds;
        }

        void OnDestroy()
        {
            if (filePath != null) Application.quitting -= OnQuitting;
            Unbind();
            if (instance == this) instance = null;
        }

        static void OnQuitting() => quitting = true;

        void OnApplicationPause(bool paused)
        {
            if (paused && sendEvents && !sending && queue.Count > 0) StartCoroutine(Flush());   // the file keeps them if it does not finish
        }

        void Update()
        {
            if (!sendEvents) return;
            float now = Time.unscaledTime;
            if (now >= nextBindAt) { nextBindAt = now + BindRetrySeconds; Bind(); }
            if (sending || queue.Count == 0) return;
            bool due = now >= nextFlushAt;
            bool full = queue.Count >= flushAtCount && failures == 0;   // not while backing off
            if (due || full) StartCoroutine(Flush());
        }

        // =====================================================================
        // subscriptions (JobManager, ARGuidance, PetAnimationPlayer)
        // =====================================================================

        void Bind()
        {
            if (jobManager == null && (jobManager = FindAnyObjectByType<JobManager>()) != null)
            {
                jobManager.JobFailed += OnJobFailed;
                jobManager.ModelReady += OnModelReady;
                jobManager.ModelPlaced += OnModelPlaced;
            }
            if (guidance == null && (guidance = FindAnyObjectByType<ARGuidance>()) != null)
                guidance.GuidanceChanged += OnGuidance;
            if (petAnim == null && (petAnim = FindAnyObjectByType<PetAnimationPlayer>()) != null)
                petAnim.ClipStarted += OnClipStarted;
        }

        void Unbind()
        {
            if (jobManager != null)
            {
                jobManager.JobFailed -= OnJobFailed;
                jobManager.ModelReady -= OnModelReady;
                jobManager.ModelPlaced -= OnModelPlaced;
            }
            if (guidance != null) guidance.GuidanceChanged -= OnGuidance;
            if (petAnim != null) petAnim.ClipStarted -= OnClipStarted;
        }

        void OnJobFailed(string code, string userText, bool retryable) =>
            TrackJob("job_failed_shown", jobManager != null ? jobManager.CurrentJobId : null, ("code", code), ("retryable", retryable));

        void OnModelReady(string jobId, string path, GameObject template)
        {
            var ready = jobManager != null ? jobManager.LastReady : default;
            TrackJob("model_ready", jobId, ("cached", ready.cached),
                ("loadMs", Clamp(ready.loadMs, 3_600_000)), ("waitMs", Clamp(ready.waitMs, 3_600_000)));
        }

        void OnModelPlaced(string jobId, GameObject placedModel)
        {
            float now = Time.realtimeSinceStartup;
            string method = now - centerPlacementAt < 1f ? "center" : "tap";
            if (CurrentScreen == "ar" && arEnteredAt >= 0f)
                TrackJob("model_placed", jobId, ("method", method), ("secSinceArEnter", Mathf.Clamp(Mathf.FloorToInt(now - arEnteredAt), 0, 86400)));
            else
                TrackJob("model_placed", jobId, ("method", method));
        }

        void OnGuidance(ARGuidance.Issue issue, string text, bool showSettings)
        {
            if (issue != ARGuidance.Issue.None) Track("ar_issue_shown", ("issue", issue.ToString()));
        }

        void OnClipStarted(string clip)
        {
            if (KnownClips.Contains(clip)) Track("animation_play", ("clip", clip));
            else Track("animation_play");
        }

        // =====================================================================
        // queue and file
        // =====================================================================

        void Enqueue(string name, string jobId, (string key, object value)[] props)
        {
            if (!sendEvents || string.IsNullOrEmpty(name)) return;
            Init();
            Dictionary<string, object> map = null;
            if (props != null && props.Length > 0)
            {
                map = new Dictionary<string, object>();
                foreach (var (key, value) in props)
                    if (!string.IsNullOrEmpty(key) && value != null) map[key] = value;
                if (map.Count == 0) map = null;
            }
            var line = new Line
            {
                sessionId = sessionId,
                appVersion = appVersion,
                ev = new ProductEvent
                {
                    name = name,
                    at = DateTime.UtcNow.ToString("o", CultureInfo.InvariantCulture),
                    seq = seq++,
                    jobId = string.IsNullOrEmpty(jobId) ? null : jobId,
                    screen = CurrentScreen,
                    props = map
                }
            };
            queue.Add(line);
            if (queue.Count > MaxStoredEvents) queue.RemoveAt(0);   // offline for long: the oldest go first
            AppendToFile(line);
            if (logEvents) Debug.Log($"[BesideEvent] {name} screen={CurrentScreen ?? "-"} {Describe(map)}");
        }

        void LoadFile()
        {
            try
            {
                if (!File.Exists(filePath)) return;
                foreach (string text in File.ReadAllLines(filePath))
                {
                    if (string.IsNullOrWhiteSpace(text)) continue;
                    try
                    {
                        var line = JsonConvert.DeserializeObject<Line>(text);
                        if (line?.ev != null && !string.IsNullOrEmpty(line.sessionId)) queue.Add(line);
                    }
                    catch (Exception) { /* a torn last line after a crash: skip it */ }
                }
                if (queue.Count > MaxStoredEvents) queue.RemoveRange(0, queue.Count - MaxStoredEvents);
                if (queue.Count > 0 && logEvents) Debug.Log($"[BesideEvent] {queue.Count} event(s) left from an earlier launch");
            }
            catch (Exception e) { Debug.LogWarning("[BesideEvent] could not read events.jsonl: " + e.Message); }
        }

        void AppendToFile(Line line)
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(filePath));
                File.AppendAllText(filePath, JsonConvert.SerializeObject(line, Json) + "\n", Encoding.UTF8);
            }
            catch (Exception e) { Debug.LogWarning("[BesideEvent] could not keep the event in events.jsonl: " + e.Message); }
        }

        void RewriteFile()
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(filePath));
                var lines = new List<string>(queue.Count);
                foreach (var line in queue) lines.Add(JsonConvert.SerializeObject(line, Json));
                File.WriteAllLines(filePath, lines, Encoding.UTF8);
            }
            catch (Exception e) { Debug.LogWarning("[BesideEvent] could not rewrite events.jsonl: " + e.Message); }
        }

        // =====================================================================
        // sending
        // =====================================================================

        IEnumerator Flush()
        {
            sending = true;
            try
            {
                // One session per batch (the body has one sessionId), the oldest first, at most 50 events.
                var batch = new List<Line>();
                string session = queue[0].sessionId, version = queue[0].appVersion;
                foreach (var line in queue)
                {
                    if (line.sessionId != session || line.appVersion != version) break;
                    batch.Add(line);
                    if (batch.Count >= ApiV1Routes.MaxEventsPerBatch) break;
                }
                var body = new ProductEventBatch
                {
                    installId = installId, sessionId = session, appVersion = version, platform = Platform,
                    events = batch.ConvertAll(line => line.ev)
                };
                string json = JsonConvert.SerializeObject(body, Json);

                if (jobManager == null) jobManager = FindAnyObjectByType<JobManager>();
                string baseUrl = jobManager != null ? jobManager.BaseUrl : null;
                if (string.IsNullOrEmpty(baseUrl)) { nextFlushAt = Time.unscaledTime + flushIntervalSeconds; yield break; }

                var api = new BesideApiClient(baseUrl) { TimeoutSeconds = 15 };
                ProductEventsAccepted accepted = null; ApiError error = null;
                yield return api.PostEvents(json, Guid.NewGuid().ToString("N"), a => accepted = a, e => error = e);

                bool refused = error != null && error.httpStatus >= 400 && error.httpStatus < 500;
                if (error == null || refused)
                {
                    var sent = new HashSet<Line>(batch);
                    queue.RemoveAll(sent.Contains);
                    RewriteFile();
                    failures = 0;
                    nextFlushAt = Time.unscaledTime + flushIntervalSeconds;
                    if (logEvents)
                    {
                        if (error == null) Debug.Log($"[BesideEvent] sent {batch.Count}: accepted {accepted.accepted}, dropped {accepted.dropped}");
                        else Debug.LogWarning($"[BesideEvent] batch of {batch.Count} refused and dropped: {error}");
                    }
                }
                else
                {
                    failures++;
                    float wait = failures <= RetryDelaySeconds.Length ? RetryDelaySeconds[failures - 1] : flushIntervalSeconds;
                    nextFlushAt = Time.unscaledTime + wait;
                    if (logEvents) Debug.Log($"[BesideEvent] send failed ({error}); {queue.Count} kept, next try in {wait:0} s");
                }
            }
            finally
            {
                sending = false;
            }
        }

        // =====================================================================
        // helpers
        // =====================================================================

        static string LoadInstallId()
        {
            string saved = PlayerPrefs.GetString(InstallIdPrefKey, "");
            if (Guid.TryParseExact(saved, "D", out _)) return saved;
            string made = Guid.NewGuid().ToString("D");
            PlayerPrefs.SetString(InstallIdPrefKey, made);
            PlayerPrefs.Save();
            return made;
        }

        /// <summary>The server takes [0-9A-Za-z._+-]{1,32}: anything else becomes '-'.</summary>
        static string SafeVersion(string version)
        {
            var sb = new StringBuilder();
            foreach (char c in version ?? "")
            {
                if (sb.Length >= 32) break;
                bool ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '.' || c == '_' || c == '+' || c == '-';
                sb.Append(ok ? c : '-');
            }
            return sb.Length > 0 ? sb.ToString() : "0";
        }

        static long Clamp(long value, long max) => value < 0 ? 0 : value > max ? max : value;

        static string Describe(Dictionary<string, object> props)
        {
            if (props == null) return "";
            var sb = new StringBuilder();
            foreach (var kv in props) sb.Append(kv.Key).Append('=').Append(Convert.ToString(kv.Value, CultureInfo.InvariantCulture)).Append(' ');
            return sb.ToString().TrimEnd();
        }
    }
}
