using System;
using System.Collections;
using System.Globalization;
using System.IO;
using UnityEngine;
using UnityEngine.Profiling;

namespace Beside.Metrics
{
    /// <summary>
    /// Writes one row per job to Application.persistentDataPath/metrics/metrics.csv
    /// with the fixed header from docs/METRICS.md:
    ///   jobId,uploadMs,waitMs,downloadMs,loadMs,e2eMs,avgFps,minFps,memMB,device
    /// FPS is sampled for 30 s after the model is placed, then the row is written.
    /// Every stage is also logged as one JSON line ([BesideMetric]) for logcat.
    /// </summary>
    public class MetricsRecorder : MonoBehaviour
    {
        public const string Header = "jobId,uploadMs,waitMs,downloadMs,loadMs,e2eMs,avgFps,minFps,memMB,device";

        [Tooltip("Seconds of FPS sampling after placement before the row is written.")]
        public float fpsSampleSeconds = 30f;

        public string CsvPath => Path.Combine(Application.persistentDataPath, "metrics", "metrics.csv");

        [Serializable]
        public class Row
        {
            public string jobId;
            public long uploadMs, waitMs, downloadMs, loadMs, e2eMs;
            public float avgFps, minFps;
            public long memMB;
            public string device;
        }

        /// <summary>Call when the model is ready/placed. Samples FPS, fills memMB and device, then appends the row.</summary>
        public void Complete(Row row)
        {
            StartCoroutine(SampleAndWrite(row));
        }

        IEnumerator SampleAndWrite(Row row)
        {
            float end = Time.realtimeSinceStartup + fpsSampleSeconds;
            int frames = 0;
            float minFps = float.MaxValue;
            while (Time.realtimeSinceStartup < end)
            {
                float dt = Time.unscaledDeltaTime;
                if (dt > 0f) minFps = Mathf.Min(minFps, 1f / dt);
                frames++;
                yield return null;
            }
            row.avgFps = frames / fpsSampleSeconds;
            row.minFps = minFps == float.MaxValue ? 0f : minFps;
            row.memMB = Profiler.GetTotalAllocatedMemoryLong() / (1024 * 1024);
            row.device = SystemInfo.deviceModel;
            Append(row);
        }

        public void Append(Row r)
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(CsvPath));
                bool writeHeader = !File.Exists(CsvPath) || new FileInfo(CsvPath).Length == 0;
                using var w = new StreamWriter(CsvPath, append: true);
                if (writeHeader) w.WriteLine(Header);
                w.WriteLine(string.Join(",",
                    r.jobId, r.uploadMs, r.waitMs, r.downloadMs, r.loadMs, r.e2eMs,
                    r.avgFps.ToString("0.0", CultureInfo.InvariantCulture),
                    r.minFps.ToString("0.0", CultureInfo.InvariantCulture),
                    r.memMB, Csv(r.device)));
                Debug.Log($"[BesideMetric] row written: {JsonUtility.ToJson(r)} -> {CsvPath}");
            }
            catch (Exception e)
            {
                Debug.LogError($"[BesideMetric] metrics.csv write failed: {e.Message}");
            }
        }

        static string Csv(string s) => string.IsNullOrEmpty(s) ? "" : "\"" + s.Replace("\"", "\"\"") + "\"";

        // ---------- per-stage JSON log (request_id / job_id / stage / duration_ms / error_code) ----------

        [Serializable]
        class LogLine
        {
            public string timestamp, level, request_id, job_id, stage, error_code, message, device;
            public long duration_ms, bytes;
        }

        public static void Log(string requestId, string jobId, string stage, long durationMs, string errorCode = null, string message = null, long bytes = 0)
        {
            var line = new LogLine
            {
                timestamp = DateTime.UtcNow.ToString("o"),
                level = errorCode == null ? "info" : "error",
                request_id = requestId, job_id = jobId, stage = stage,
                duration_ms = durationMs, error_code = errorCode, message = message, bytes = bytes,
                device = SystemInfo.deviceModel
            };
            string json = JsonUtility.ToJson(line);
            if (errorCode == null) Debug.Log("[BesideMetric] " + json);
            else Debug.LogError("[BesideMetric] " + json);
        }
    }
}
