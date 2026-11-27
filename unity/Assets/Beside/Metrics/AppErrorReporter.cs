using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using Beside.Job;
using UnityEngine;

namespace Beside.Metrics
{
    /// <summary>
    /// App errors as the product event app_error (docs/METRICS.md §6.1, openapi ProductEvent): kind = exception | error |
    /// assert and sig = a hash of what went wrong and where. The message text never leaves the phone.
    ///  - Installs itself before the first scene loads (RuntimeInitializeOnLoadMethod, the scene is not changed) and
    ///    listens to Application.logMessageReceivedThreaded until the app quits or the object is destroyed.
    ///    BESIDE_NO_PRODUCT_EVENTS turns it off, like ProductEvents.
    ///  - Exception, Error and Assert only. Lines starting with [BesideMetric] or [BesideEvent] are skipped: a failed job
    ///    is counted by job_failed_shown (MetricsRecorder logs failed stages with LogError).
    ///  - sig = FNV-1a 32 of the UTF-8 text, &amp; 0x7fffffff. Exceptions: "{exception type}|{frame}". Others:
    ///    "{first message line without quoted parts, hex runs and digits}|{frame}" (IL2CPP release builds give Error logs
    ///    no stack, so the message keeps different errors apart). frame = the first stack line that is not Debug / Logger /
    ///    Assert itself, cut before " (at …)", " [0x…]" and " in &lt;…&gt;" (file, IL offset and build id change between builds).
    ///  - At most <see cref="MaxPerSession"/> per launch and <see cref="MaxPerSig"/> per sig. The callback may run on any
    ///    thread, so it only computes and queues, inside try/catch: no Unity API and no Debug.* (a log from there would
    ///    come back to it). Update sends the queue on the main thread with ProductEvents.TrackJob (the JobManager's current
    ///    job, if any) and writes "[BesideEvent] app_error sig=… kind=… type=… frame=…" to logcat: the cause of a sig seen on
    ///    the server is found through that line.
    /// </summary>
    public class AppErrorReporter : MonoBehaviour
    {
        public const int MaxPerSession = 20;
        public const int MaxPerSig = 3;
        const int MaxScanChars = 1000, MaxMessageChars = 200;
        const string MetricPrefix = "[BesideMetric]", EventPrefix = "[BesideEvent]";
        static readonly string[] FrameCuts = { " (at ", " [0x", " in <" };
        static readonly string[] LoggingFrames = { "UnityEngine.Debug", "UnityEngine.Logger", "UnityEngine.DebugLogHandler", "UnityEngine.Assertions." };
        // "…" and '…' (an apostrophe inside a word, as in can't, does not open a quote)
        static readonly Regex Quoted = new Regex("\"[^\"]*\"|(?<![A-Za-z])'[^']*'", RegexOptions.CultureInvariant);
        // 0x… and runs of 6+ hex digits / dashes that hold a digit (ids, hashes, GUIDs). Not \b: '_' is a word character, so
        // an id right after one (Runtime_job_5d1c2e3f…) would keep its letters and give each job its own sig.
        static readonly Regex HexRun = new Regex(@"(?<![0-9A-Za-z])0[xX][0-9A-Fa-f]+|(?<![0-9A-Za-z])(?=[0-9A-Fa-f-]*[0-9])[0-9A-Fa-f][0-9A-Fa-f-]{5,}(?![0-9A-Za-z])", RegexOptions.CultureInvariant);
        static readonly Regex Digits = new Regex("[0-9]+", RegexOptions.CultureInvariant);

        struct Report { public string kind, type, frame; public int sig; }

        static AppErrorReporter instance;
        static readonly ConcurrentQueue<Report> pending = new ConcurrentQueue<Report>();
        static readonly object gate = new object();
        static readonly Dictionary<int, int> perSig = new Dictionary<int, int>();   // under gate
        static int reported;                                                       // under gate (read without it first)
        JobManager jobManager;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
        static void ResetStatics()   // Enter Play Mode without a domain reload keeps statics
        {
            Application.logMessageReceivedThreaded -= OnLog;
            instance = null;
            while (pending.TryDequeue(out _)) { }
            lock (gate) { perSig.Clear(); reported = 0; }
        }

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void Install()
        {
#if !BESIDE_NO_PRODUCT_EVENTS
            if (instance != null) return;
            var go = new GameObject("BesideAppErrorReporter");
            DontDestroyOnLoad(go);
            go.AddComponent<AppErrorReporter>();
#endif
        }

        void Awake()
        {
#if BESIDE_NO_PRODUCT_EVENTS
            Destroy(this);
#else
            if (instance != null && instance != this) { Destroy(this); return; }
            instance = this;
            Application.logMessageReceivedThreaded -= OnLog;   // never twice
            Application.logMessageReceivedThreaded += OnLog;
            Application.quitting += OnQuitting;
#endif
        }

        void OnDestroy()
        {
            if (instance != this) return;
            Application.logMessageReceivedThreaded -= OnLog;
            Application.quitting -= OnQuitting;
            instance = null;
        }

        static void OnQuitting() => Application.logMessageReceivedThreaded -= OnLog;

        void Update()
        {
            while (pending.TryDequeue(out var r))
            {
                try
                {
                    Debug.Log($"{EventPrefix} app_error sig={r.sig} kind={r.kind} type={r.type} frame={(string.IsNullOrEmpty(r.frame) ? "-" : r.frame)}");
                    if (jobManager == null) jobManager = FindAnyObjectByType<JobManager>();
                    ProductEvents.TrackJob("app_error", jobManager != null ? jobManager.CurrentJobId : null,
                        ("kind", r.kind), ("sig", r.sig));
                }
                catch (Exception) { /* sending a report must not turn into more reports */ }
            }
        }

        /// <summary>Application.logMessageReceivedThreaded: any thread. Only computing and queueing — never log here.</summary>
        static void OnLog(string condition, string stackTrace, LogType type)
        {
            try
            {
                string kind = type == LogType.Exception ? "exception" : type == LogType.Error ? "error" : type == LogType.Assert ? "assert" : null;
                if (kind == null || Volatile.Read(ref reported) >= MaxPerSession) return;
                condition = condition ?? "";
                if (condition.StartsWith(MetricPrefix, StringComparison.Ordinal) || condition.StartsWith(EventPrefix, StringComparison.Ordinal)) return;

                int sig = Signature(type, condition, stackTrace, out string typeName, out string frame);
                lock (gate)
                {
                    if (reported >= MaxPerSession) return;
                    perSig.TryGetValue(sig, out int seen);
                    if (seen >= MaxPerSig) return;
                    perSig[sig] = seen + 1;
                    reported++;
                }
                pending.Enqueue(new Report { kind = kind, type = typeName, frame = frame, sig = sig });
            }
            catch (Exception) { /* nothing may be logged from a log callback */ }
        }

        /// <summary>sig of one log entry (see the class summary), 0..2147483647. typeName: the exception type (Exception when
        /// the message does not start with one), else Error / Assert. frame: the stack line the sig used, "" when none.</summary>
        public static int Signature(LogType type, string condition, string stackTrace, out string typeName, out string frame)
        {
            frame = FirstFrame(stackTrace);
            string message = FirstLine(condition ?? "");
            string basis;
            if (type == LogType.Exception && TryExceptionType(message, out typeName))
                basis = typeName + "|" + frame;
            else
            {
                typeName = type == LogType.Exception ? "Exception" : type.ToString();
                basis = Normalize(message) + "|" + frame;
            }
            return (int)(Fnv1a32(Encoding.UTF8.GetBytes(basis)) & 0x7fffffff);
        }

        static string FirstLine(string text)
        {
            int end = text.IndexOf('\n');
            if (end >= 0) text = text.Substring(0, end);
            if (text.Length > MaxScanChars) text = text.Substring(0, MaxScanChars);
            return text.TrimEnd('\r');
        }

        /// <summary>"NullReferenceException: Object reference …" → NullReferenceException. False when the text before the
        /// first ':' is not a type name.</summary>
        static bool TryExceptionType(string message, out string typeName)
        {
            typeName = null;
            int colon = message.IndexOf(':');
            if (colon <= 0 || colon > 128) return false;
            string name = message.Substring(0, colon).Trim();
            if (name.Length == 0) return false;
            foreach (char c in name)
                if (!(char.IsLetterOrDigit(c) || c == '.' || c == '_' || c == '`' || c == '+')) return false;
            typeName = name;
            return true;
        }

        static string FirstFrame(string stackTrace)
        {
            if (string.IsNullOrEmpty(stackTrace)) return "";
            foreach (string raw in stackTrace.Split('\n'))
            {
                string line = raw.Trim();
                if (line.Length == 0 || IsLoggingFrame(line)) continue;
                foreach (string cut in FrameCuts)
                {
                    int at = line.IndexOf(cut, StringComparison.Ordinal);
                    if (at >= 0) line = line.Substring(0, at);
                }
                return line.Trim();
            }
            return "";
        }

        static bool IsLoggingFrame(string line)
        {
            foreach (string prefix in LoggingFrames)
                if (line.StartsWith(prefix, StringComparison.Ordinal)) return true;
            return false;
        }

        static string Normalize(string message)
        {
            message = Quoted.Replace(message, "");
            message = HexRun.Replace(message, "");
            message = Digits.Replace(message, "");
            return message.Length > MaxMessageChars ? message.Substring(0, MaxMessageChars) : message;
        }

        static uint Fnv1a32(byte[] data)
        {
            unchecked
            {
                uint hash = 2166136261;
                foreach (byte b in data) { hash ^= b; hash *= 16777619; }
                return hash;
            }
        }
    }
}
