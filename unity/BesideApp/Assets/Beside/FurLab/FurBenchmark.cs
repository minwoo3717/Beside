using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using Beside.AR;
using Beside.Pet;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.Profiling;
using UnityEngine.Rendering;
using UnityEngine.Rendering.Universal;
using UnityEngine.XR.ARFoundation;
using UnityEngine.XR.Interaction.Toolkit.Samples.StarterAssets;

namespace Beside.FurLab
{
    /// <summary>
    /// Fur performance benchmark for the presentation.
    ///  Set "방식 비교" (method): what each fur method costs (none / texture / shell 8-16-24 / shell+fins / strands)
    ///  Set "최적화 비교" (optim): did our changes help (shell v1 vs v2, fixed vs adaptive shell count, MSAA off/2x/4x)
    /// Place the pet, hide the buttons, fix the phone, open the panel (four-finger tap / F9), pick the set and the
    /// distance label (가까이 / 멀리), then run. Every condition: warm-up, screenshot (round 1), measure; rounds go
    /// round-robin so heat drift spreads over all conditions. One CSV row per condition per round:
    ///   persistentDataPath/fur_bench/fur_bench_yyyyMMdd_HHmmss.csv   (+ "[BesideFurBench]" lines in logcat)
    /// Analyse with scripts/analyze_fur_bench.py (tables + charts for the slides).
    /// </summary>
    public class FurBenchmark : MonoBehaviour
    {
        public FurAssets assets = new FurAssets();

        public List<FurCondition> conditions = DefaultConditions();

        public static List<FurCondition> DefaultConditions() => new List<FurCondition>
        {
            // ① method: cost of each way to draw fur (fixed shell count, project MSAA)
            new FurCondition("none",          "method", FurMode.None),
            new FurCondition("texture",       "method", FurMode.TextureOnly),
            new FurCondition("shell8",        "method", FurMode.Shell, sh: 8),
            new FurCondition("shell16",       "method,optim", FurMode.Shell, sh: 16),
            new FurCondition("shell24",       "method", FurMode.Shell, sh: 24),
            new FurCondition("shell16+fins",  "method", FurMode.ShellFins, sh: 16),
            new FurCondition("strand5k",      "method", FurMode.Strands, st: 5000),
            // ② optim: before/after of our changes (shell16 above is the v2 baseline)
            new FurCondition("shell16_v1",    "optim", FurMode.Shell, sh: 16, v: ShellVersion.V1),
            new FurCondition("shell20_fixed", "optim", FurMode.Shell, sh: 20),
            new FurCondition("shell20_adapt", "optim", FurMode.Shell, sh: 20, adapt: true),
            new FurCondition("shell16_aa1",   "optim", FurMode.Shell, sh: 16, aa: 1),
            new FurCondition("shell16_aa2",   "optim", FurMode.Shell, sh: 16, aa: 2),
            new FurCondition("shell16_aa4",   "optim", FurMode.Shell, sh: 16, aa: 4),
        };

        [ContextMenu("Reset conditions to defaults")]
        void ResetConditions() => conditions = DefaultConditions();

        [Header("Full run (정식 측정)")]
        [Min(1)] public int rounds = 5;
        public float warmupSeconds = 5f;
        public float measureSeconds = 20f;

        [Header("GPU-bound measurement (부하 배수)")]
        [Tooltip("The pet (base mesh + fur) is drawn this many times per frame so the GPU is the bottleneck and runs at " +
                 "full clock; frame time / repeat = cost of one pet. 0 = calibrate automatically at the start of a run.")]
        public int loadRepeat = 0;
        [Tooltip("Per-condition calibration: smallest repeat (doubling) whose frame time reaches this x the refresh interval.")]
        public float boundFactor = 2f;
        [Tooltip("Upper limit for the per-condition load (light conditions like 'none' need a lot).")]
        public int loadRepeatLimit = 128;

        [Header("Heat")]
        [Tooltip("Before a condition, wait while the battery is hotter than this (C)... Under this load the phone " +
                 "settles around 41-43 C and stays there, so only real overheating is waited out.")]
        public float coolStartC = 45f;
        [Tooltip("...until it is back to this (C), at most Cool Max Seconds.")]
        public float coolStopC = 43f;
        public float coolMaxSeconds = 90f;
        [Tooltip("ARSession 'Match Frame Rate' locks rendering to the camera (30 FPS). Turn it off while measuring.")]
        public bool unlockArFrameRate = true;

        [Header("Quick run (빠른 측정)")]
        public float quickWarmupSeconds = 3f;
        public float quickMeasureSeconds = 12f;

        [Header("Fixed conditions")]
        [Tooltip("Play this clip during the run so skinning cost is the same for every condition.")]
        public string animationClip = "Idle";
        [Tooltip("Lift Unity's 30 FPS default cap to the display refresh rate while measuring. " +
                 "Without this every condition reads ~30 FPS and only gpuMs shows differences.")]
        public bool uncapFrameRate = true;
        [Tooltip("Flag a row when the pet's size on screen changed more than this (%) during the window (phone moved).")]
        public float maxCoverageSpreadPct = 30f;

        [Tooltip("Save one screenshot per condition (round 1, end of warm-up) next to the CSV, for the quality rating.")]
        public bool captureScreenshots = true;

        [Header("UI")]
        public bool showPanelOnStart = false;
        public bool fourFingerToggle = true;

        // ---------- state ----------
        static readonly string[] SetIds = { "method", "optim", "all" };
        static readonly string[] SetNames = { "방식 비교", "최적화 비교", "전체" };
        static readonly string[] DistIds = { "near", "far", "other" };
        static readonly string[] DistNames = { "가까이", "멀리", "기타" };
        int setIndex, distIndex;
        static readonly int[] LoadOptions = { 0, 4, 8, 16, 32 };
        static readonly string[] LoadNames = { "부하 자동", "x4", "x8", "x16", "x32" };
        int loadIndex;
        float liveCoverage = -1, nextCoverageCheck;

        bool panel, running, stopRequested, wasFourDown, panelHiddenForShot, hasResults;
        float nextResultCheck;
        string status = "";
        string csvPath;
        readonly List<string> summary = new List<string>();
        readonly List<string> shots = new List<string>();
        GUIStyle box, label, button, selected;
        readonly FrameTiming[] timing = new FrameTiming[1];

        public bool IsRunning => running;
        public string CsvPath => csvPath;

        const string Header = "runId,timestamp,device,gpu,api,screen,refreshHz,renderScale,set,distanceLabel," +
                              "condition,mode,shells,strands,segments,shellVersion,adaptive,msaa,shellsDrawn,furTris,loadRepeat,arFrameRateLocked,msPerPet,msPerPetSimple,frameMsA,frameMsB,gpuBound," +
                              "round,durationS,frames,avgFps,meanMs,p50Ms,p95Ms,p99Ms,low1Fps,cpuMs,gpuMs,memMB,tempC," +
                              "coveragePct,coverageSpreadPct,distanceM,clip,note";

        void Start()
        {
            panel = showPanelOnStart;
            loadIndex = System.Array.IndexOf(LoadOptions, loadRepeat);
            if (loadIndex < 0) loadIndex = 0;
            AddMissingDefaults();
        }

        /// <summary>
        /// A component saved with an older version keeps its old condition list (no "optim" set, empty 'sets').
        /// Add the default conditions that are missing (by name) and give old entries their default sets.
        /// </summary>
        void AddMissingDefaults()
        {
            var defaults = DefaultConditions();
            foreach (var d in defaults)
            {
                var have = conditions.Find(c => c.name == d.name);
                if (have == null) conditions.Add(d);
                else if (have.sets != d.sets && (string.IsNullOrEmpty(have.sets) || have.sets == "method")) have.sets = d.sets;
            }
        }

        void Update()
        {
            if (Keyboard.current != null && Keyboard.current.f9Key.wasPressedThisFrame) panel = !panel;
            if (fourFingerToggle && Touchscreen.current != null)
            {
                int down = 0;
                foreach (var t in Touchscreen.current.touches) if (t.press.isPressed) down++;
                bool four = down >= 4;
                if (four && !wasFourDown) panel = !panel;
                wasFourDown = four;
            }
            if (panel && !running && Time.unscaledTime > nextCoverageCheck)       // live readout for placing the phone
            {
                nextCoverageCheck = Time.unscaledTime + 0.5f;
                var pet = FindPet();
                liveCoverage = pet != null && Coverage(pet, out float cov, out _) ? cov : -1;
            }
        }

        List<FurCondition> Selected() => conditions.FindAll(c => c.InSet(SetIds[setIndex]));

        public void StartRun(bool quick)
        {
            if (running) return;
            StartCoroutine(Run(quick, SetIds[setIndex], DistIds[distIndex]));
        }

        public void Stop() => stopRequested = true;

        // ---------- run ----------

        IEnumerator Run(bool quick, string set, string distLabel)
        {
            GameObject pet = FindPet();
            if (pet == null) { status = "먼저 펫을 바닥에 배치하세요."; yield break; }
            var list = conditions.FindAll(c => c.InSet(set));
            if (list.Count == 0) { status = "이 묶음에 조건이 없습니다."; yield break; }

            running = true; stopRequested = false; summary.Clear(); shots.Clear();
            int prevSleep = Screen.sleepTimeout;
            Screen.sleepTimeout = SleepTimeout.NeverSleep;     // a long run with no touches must not turn the screen off
            var variants = new FurVariants(pet, assets);
            if (!string.IsNullOrEmpty(variants.Warning)) Debug.LogWarning("[FurBenchmark] " + variants.Warning);

            int prevRate = Application.targetFrameRate, prevVsync = QualitySettings.vSyncCount;
            int refresh = Mathf.RoundToInt((float)Screen.currentResolution.refreshRateRatio.value);
            if (refresh <= 0) refresh = 60;
            if (uncapFrameRate) { QualitySettings.vSyncCount = 0; Application.targetFrameRate = refresh; }
            var urp = GraphicsSettings.currentRenderPipeline as UniversalRenderPipelineAsset;
            int prevMsaa = urp != null ? urp.msaaSampleCount : 1;
            var session = FindFirstObjectByType<ARSession>();
            bool prevMatch = session != null && session.matchFrameRateRequested;
            if (unlockArFrameRate && session != null && prevMatch)
            {
                session.matchFrameRateRequested = false;
                yield return null;
                if (uncapFrameRate) { QualitySettings.vSyncCount = 0; Application.targetFrameRate = refresh; }
            }
            bool arLocked = session != null && session.matchFrameRateRequested;
            var repeaters = AddRepeaters(pet);

            var anim = FindFirstObjectByType<PetAnimationPlayer>();
            if (anim != null && !string.IsNullOrEmpty(animationClip)) anim.Play(animationClip);

            string runId = DateTime.Now.ToString("yyyyMMdd_HHmmss");
            string dir = Path.Combine(Application.persistentDataPath, "fur_bench");
            Directory.CreateDirectory(dir);
            csvPath = Path.Combine(dir, $"fur_bench_{runId}_{set}_{distLabel}.csv");
            File.WriteAllText(csvPath, Header + "\n");

            int fixedLoad = LoadOptions[loadIndex];          // 0 = calibrate per condition
            var kCache = new Dictionary<string, int>();
            float capMs = 1000f / refresh;

            int nRounds = quick ? 1 : rounds;
            float warm = quick ? quickWarmupSeconds : warmupSeconds;
            float meas = quick ? quickMeasureSeconds : measureSeconds;
            int total = nRounds * list.Count, done = 0;
            var results = new Dictionary<string, List<(float ms, float gpu)>>();

            for (int round = 1; round <= nRounds && !stopRequested; round++)
            {
                for (int k = 0; k < list.Count && !stopRequested; k++)
                {
                    var c = list[(k + round - 1) % list.Count];   // rotate the start each round
                    if (!variants.CanRun(c))
                    {
                        Debug.LogWarning($"[FurBenchmark] skip {c.name}: missing hair maps or v1 material");
                        done++; continue;
                    }

                    // ---- cool down: a hot phone throttles and makes later conditions look slower
                    float temp0 = BatteryTempC();
                    if (!float.IsNaN(temp0) && temp0 > coolStartC && !stopRequested)
                    {
                        FurLoad.Repeat = 1;
                        variants.Apply(new FurCondition("none", "", FurMode.None));
                        for (float w = 0; w < coolMaxSeconds && !stopRequested; w += 1f)
                        {
                            float tNow = BatteryTempC();
                            status = $"[{done + 1}/{total}] 폰 식히는 중 {tNow:0.0}°C → {coolStopC:0}°C ({coolMaxSeconds - w:0}s)";
                            if (float.IsNaN(tNow) || tNow <= coolStopC) break;
                            yield return new WaitForSecondsRealtime(1f);
                        }
                    }

                    variants.Apply(c);
                    int msaa = urp != null ? (c.msaa > 0 ? c.msaa : prevMsaa) : 1;
                    if (urp != null && urp.msaaSampleCount != msaa) urp.msaaSampleCount = msaa;

                    for (float t = 0; t < warm && !stopRequested; t += Time.unscaledDeltaTime)
                    {
                        status = $"[{done + 1}/{total}] {c.name}  준비 {warm - t:0}s · 폰을 움직이지 마세요";
                        yield return null;
                    }

                    if (captureScreenshots && round == 1 && !stopRequested)
                    {
                        string shot = Path.Combine(dir, $"{runId}_{distLabel}_{c.name.Replace('+', '_')}.png");
                        panelHiddenForShot = true;
                        yield return null;                                   // one frame without the panel
                        ScreenCapture.CaptureScreenshot(Application.isMobilePlatform ? Path.GetRelativePath(Application.persistentDataPath, shot) : shot);
                        yield return null;
                        panelHiddenForShot = false;
                        shots.Add(shot);
                        for (int i = 0; i < 10; i++) yield return null;      // let the capture hitch pass
                    }

                    // ---- load: per-condition calibration (or the fixed value) so this condition is GPU-bound
                    int K = fixedLoad;
                    if (K <= 0)
                    {
                        K = kCache.TryGetValue(c.name, out var kc) ? kc : 1;
                        while (!stopRequested)
                        {
                            FurLoad.Repeat = K;
                            var probe = new List<float>();
                            yield return Sample(0.3f, null, null);                       // settle
                            yield return Sample(1.0f, probe, null);
                            status = $"[{done + 1}/{total}] {c.name}  부하 보정 x{K}: {Mean(probe):0.0} ms";
                            if (Mean(probe) >= capMs * boundFactor || K >= loadRepeatLimit) break;
                            K = Mathf.Min(K * 2, loadRepeatLimit);
                        }
                        kCache[c.name] = K;
                    }

                    // ---- measure at K, then at 2K: cost of one pet = (frame(2K) - frame(K)) / K
                    // (the slope removes everything drawn once per frame - AR camera image, UI - and the vsync offset)
                    var frames = new List<float>(Mathf.CeilToInt(meas * refresh) + 16);
                    var framesB = new List<float>(Mathf.CeilToInt(meas * refresh) + 16);
                    double cpuSum = 0, gpuSum = 0; int cpuN = 0, gpuN = 0;
                    float covSum = 0, covMin = float.MaxValue, covMax = 0, distSum = 0, shellSum = 0; int covN = 0, shellN = 0;
                    string phase = "";
                    Action<float> perFrame = left =>
                    {
                        FrameTimingManager.CaptureFrameTimings();
                        if (FrameTimingManager.GetLatestTimings(1, timing) > 0)
                        {
                            if (timing[0].cpuFrameTime > 0) { cpuSum += timing[0].cpuFrameTime; cpuN++; }
                            if (timing[0].gpuFrameTime > 0) { gpuSum += timing[0].gpuFrameTime; gpuN++; }
                        }
                        if (pet != null && Coverage(pet, out float cov, out float dist))
                        {
                            covSum += cov; covN++; distSum += dist;
                            covMin = Mathf.Min(covMin, cov); covMax = Mathf.Max(covMax, cov);
                        }
                        shellSum += variants.ShellsDrawn(); shellN++;
                        status = $"[{done + 1}/{total}] {c.name}  측정 {phase} {left:0}s · {1f / Mathf.Max(0.0001f, Time.unscaledDeltaTime):0} FPS";
                    };
                    FurLoad.Repeat = K; phase = $"x{K}";
                    yield return Sample(meas * 0.5f, frames, perFrame);
                    FurLoad.Repeat = K * 2; phase = $"x{K * 2}";
                    yield return Sample(0.5f, null, null);
                    yield return Sample(meas * 0.5f, framesB, perFrame);
                    FurLoad.Repeat = 1;
                    float elapsed = (Sum(frames) + Sum(framesB)) / 1000f;
                    if (pet == null) { status = "펫이 사라져서 중단했습니다."; stopRequested = true; break; }
                    if (stopRequested || frames.Count < 10 || framesB.Count < 5) break;
                    float meanA = Mean(frames), meanB = Mean(framesB);
                    float slope = Mathf.Max(0f, (meanB - meanA) / K);
                    float simple = meanB / (2f * K);
                    bool gpuBound = meanA >= capMs * 1.15f;     // clearly slower than the refresh cap = GPU-limited

                    var row = Stats(frames);
                    float covAvg = covN > 0 ? covSum / covN : 0;
                    float spread = covN > 0 && covAvg > 0 ? (covMax - covMin) / covAvg * 100f : 0;
                    float gpu = gpuN > 0 ? (float)(gpuSum / gpuN) : float.NaN;
                    string note = spread > maxCoverageSpreadPct ? "moved" : "";
                    if (gpuN == 0) note += (note.Length > 0 ? ";" : "") + "noGpuTiming";
                    if (!gpuBound) note += (note.Length > 0 ? ";" : "") + "notGpuBound";
                    bool shellMode = c.mode == FurMode.Shell || c.mode == FurMode.ShellFins;

                    string line = string.Join(",", new[]
                    {
                        runId, DateTime.Now.ToString("s"), Csv(SystemInfo.deviceModel), Csv(SystemInfo.graphicsDeviceName),
                        SystemInfo.graphicsDeviceType.ToString(), $"{Screen.width}x{Screen.height}", refresh.ToString(), F(RenderScale()),
                        set, distLabel,
                        c.name, c.mode.ToString(), Shells(c), Strands(c), Segs(c),
                        shellMode ? c.version.ToString() : "", shellMode ? (c.adaptive ? "1" : "0") : "", msaa.ToString(),
                        F(shellN > 0 ? shellSum / shellN : 0), variants.CountFurTriangles().ToString(),
                        K.ToString(), arLocked ? "1" : "0", F(slope), F(simple), F(meanA), F(meanB), gpuBound ? "1" : "0",
                        round.ToString(), F(elapsed), frames.Count.ToString(),
                        F(row.avgFps), F(row.mean), F(row.p50), F(row.p95), F(row.p99), F(row.low1),
                        cpuN > 0 ? F((float)(cpuSum / cpuN)) : "", F(gpu),
                        (Profiler.GetTotalAllocatedMemoryLong() / (1024 * 1024)).ToString(), F(BatteryTempC()),
                        F(covAvg), F(spread), covN > 0 ? F(distSum / covN) : "",
                        anim != null ? (anim.PlayingClip ?? "") : "", note,
                    });
                    File.AppendAllText(csvPath, line + "\n");
                    Debug.Log("[BesideFurBench] " + line);

                    if (!results.TryGetValue(c.name, out var l)) results[c.name] = l = new List<(float, float)>();
                    l.Add((slope, gpu));
                    done++;
                }
            }

            variants.Restore();
            Screen.sleepTimeout = prevSleep;
            FurLoad.Repeat = 1;
            foreach (var r in repeaters) if (r != null) Destroy(r);
            if (session != null && unlockArFrameRate) session.matchFrameRateRequested = prevMatch;
            if (urp != null) urp.msaaSampleCount = prevMsaa;
            Application.targetFrameRate = prevRate;
            QualitySettings.vSyncCount = prevVsync;

            foreach (var c in list)
                if (results.TryGetValue(c.name, out var l))
                {
                    var ms = l.ConvertAll(x => x.ms); ms.Sort();
                    var g = l.FindAll(x => !float.IsNaN(x.gpu)).ConvertAll(x => x.gpu); g.Sort();
                    float med = ms[ms.Count / 2];
                    summary.Add($"{c.name,-14} {med,6:0.00} ms/펫 (x{(kCache.TryGetValue(c.name, out var kk) ? kk : fixedLoad)})");
                }
            status = (stopRequested ? "중지됨. " : "완료. ") + $"AR 30fps 고정 {(arLocked ? "켜짐(!)" : "해제")}. CSV: {csvPath}";
            Debug.Log($"[FurBenchmark] saved {csvPath}");
            running = false;
        }

        static float Median(List<float> v) { var s = new List<float>(v); s.Sort(); return s[s.Count / 2]; }
        static float Sum(List<float> v) { float t = 0; foreach (var x in v) t += x; return t; }
        static float Mean(List<float> v) => v.Count == 0 ? 0 : Sum(v) / v.Count;

        /// <summary>Runs for 'seconds', adding each frame time (ms) to 'into' and calling perFrame(secondsLeft).</summary>
        IEnumerator Sample(float seconds, List<float> into, Action<float> perFrame)
        {
            float t = 0;
            while (t < seconds && !stopRequested)
            {
                yield return null;
                float dt = Time.unscaledDeltaTime;
                t += dt;
                into?.Add(dt * 1000f);
                perFrame?.Invoke(seconds - t);
            }
        }

        List<PetLoadRepeater> AddRepeaters(GameObject pet)
        {
            var list = new List<PetLoadRepeater>();
            foreach (var r in pet.GetComponentsInChildren<Renderer>(true))
            {
                if (!(r is SkinnedMeshRenderer) && !(r is MeshRenderer)) continue;
                if (r.name == "ShadowCatcher" || r.GetComponent<PetLoadRepeater>() != null) continue;
                list.Add(r.gameObject.AddComponent<PetLoadRepeater>());
            }
            return list;
        }

        // ---------- measurements ----------

        struct Row { public float avgFps, mean, p50, p95, p99, low1; }

        static Row Stats(List<float> ms)
        {
            var s = new List<float>(ms); s.Sort();
            float sum = 0; foreach (var v in s) sum += v;
            int worst = Mathf.Max(1, s.Count / 100);
            float worstSum = 0; for (int i = s.Count - worst; i < s.Count; i++) worstSum += s[i];
            float P(float q) => s[Mathf.Clamp(Mathf.RoundToInt(q * (s.Count - 1)), 0, s.Count - 1)];
            return new Row
            {
                mean = sum / s.Count, avgFps = s.Count / (sum / 1000f),
                p50 = P(0.5f), p95 = P(0.95f), p99 = P(0.99f), low1 = 1000f / (worstSum / worst),
            };
        }

        /// <summary>How much of the screen the pet covers (bounding box, %) and its distance to the camera.</summary>
        static bool Coverage(GameObject pet, out float pct, out float dist)
        {
            pct = 0; dist = 0;
            var cam = Camera.main;
            if (cam == null) return false;
            bool any = false; Bounds b = default;
            foreach (var r in pet.GetComponentsInChildren<Renderer>())
            {
                if (r.name == "ShadowCatcher") continue;
                if (!any) { b = r.bounds; any = true; } else b.Encapsulate(r.bounds);
            }
            if (!any) return false;
            float x0 = 1, y0 = 1, x1 = 0, y1 = 0;
            for (int i = 0; i < 8; i++)
            {
                Vector3 p = b.center + Vector3.Scale(b.extents, new Vector3((i & 1) == 0 ? -1 : 1, (i & 2) == 0 ? -1 : 1, (i & 4) == 0 ? -1 : 1));
                Vector3 v = cam.WorldToViewportPoint(p);
                if (v.z <= 0) continue;
                x0 = Mathf.Min(x0, v.x); y0 = Mathf.Min(y0, v.y); x1 = Mathf.Max(x1, v.x); y1 = Mathf.Max(y1, v.y);
            }
            x0 = Mathf.Clamp01(x0); y0 = Mathf.Clamp01(y0); x1 = Mathf.Clamp01(x1); y1 = Mathf.Clamp01(y1);
            pct = Mathf.Max(0, x1 - x0) * Mathf.Max(0, y1 - y0) * 100f;
            dist = Vector3.Distance(cam.transform.position, b.center);
            return true;
        }

        static float RenderScale() =>
            GraphicsSettings.currentRenderPipeline is UniversalRenderPipelineAsset u ? u.renderScale : 1f;

        static float BatteryTempC()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                var activity = UnityEngine.Android.AndroidApplication.currentActivity;   // shared object: do not dispose
                using var filter = new AndroidJavaObject("android.content.IntentFilter", "android.intent.action.BATTERY_CHANGED");
                using var intent = activity.Call<AndroidJavaObject>("registerReceiver", null, filter);
                return intent.Call<int>("getIntExtra", "temperature", 0) / 10f;
            }
            catch (Exception e) { Debug.LogWarning("[FurBenchmark] battery temp: " + e.Message); }
#endif
            return float.NaN;
        }

        GameObject FindPet()
        {
            var limiter = FindFirstObjectByType<SpawnLimiter>();
            if (limiter != null && limiter.PlacedModels.Count > 0) return limiter.PlacedModels[0];
            var spawner = FindFirstObjectByType<ObjectSpawner>();
            if (spawner != null)
                foreach (Transform c in spawner.transform) if (c.gameObject.activeInHierarchy) return c.gameObject;
            return null;
        }

        /// <summary>Copies this run's screenshots into the phone gallery (album "Beside"), for the quality rating.</summary>
        string SaveShotsToGallery()
        {
#if BESIDE_NATIVEGALLERY
            int n = 0;
            foreach (var p in shots)
                if (File.Exists(p)) { NativeGallery.SaveImageToGallery(p, "Beside", Path.GetFileName(p)); n++; }
            return n > 0 ? $"스크린샷 {n}장을 갤러리(Beside 앨범)에 저장했습니다." : "저장할 스크린샷이 없습니다.";
#else
            return "NativeGallery가 없어 갤러리 저장을 할 수 없습니다 (BESIDE_NATIVEGALLERY).";
#endif
        }

        static string Shells(FurCondition c) => c.mode == FurMode.Shell || c.mode == FurMode.ShellFins ? c.shells.ToString() : "0";
        static string Strands(FurCondition c) => c.mode == FurMode.Strands ? c.strands.ToString() : "0";
        static string Segs(FurCondition c) => c.mode == FurMode.Strands ? c.segments.ToString() : "0";
        static string F(float v) => float.IsNaN(v) ? "" : v.ToString("0.###", CultureInfo.InvariantCulture);
        static string Csv(string s) => s == null ? "" : "\"" + s.Replace("\"", "'") + "\"";

        string SummaryText() =>
            summary.Count == 0 ? "" : $"{SystemInfo.deviceModel} · 조건별 중앙값\n" + string.Join("\n", summary);

        // ---------- panel ----------

        void OnGUI()
        {
            if ((!panel && !running) || panelHiddenForShot) return;
            float s = Mathf.Max(1f, Screen.dpi / 160f);
            if (box == null)
            {
                box = new GUIStyle(GUI.skin.box) { alignment = TextAnchor.UpperLeft, richText = true, wordWrap = true };
                label = new GUIStyle(GUI.skin.label) { wordWrap = true, richText = true };
                button = new GUIStyle(GUI.skin.button);
                selected = new GUIStyle(GUI.skin.button) { fontStyle = FontStyle.Bold };
                selected.normal.textColor = new Color(0.62f, 0.83f, 0.71f);
            }
            box.fontSize = label.fontSize = button.fontSize = selected.fontSize = Mathf.RoundToInt(14 * s);
            label.normal.textColor = Color.white;
            float bh = 38 * s;

            if (Event.current.type == EventType.Layout && Time.unscaledTime > nextResultCheck)
            { hasResults = FurResultShare.HasResults; nextResultCheck = Time.unscaledTime + 2f; }

            var safe = Screen.safeArea;
            float w = Mathf.Min(Screen.width - 32 * s, 440 * s);
            float rows = running ? 1 : 4.6f + (hasResults ? 1 : 0);
            float h = (70 + rows * 44 + summary.Count * 18) * s;
            var rect = new Rect((Screen.width - w) / 2, Screen.height - safe.yMax + 80 * s, w, h);
            GUI.Box(rect, "");
            GUILayout.BeginArea(new Rect(rect.x + 10 * s, rect.y + 8 * s, rect.width - 20 * s, rect.height - 16 * s));
            GUILayout.Label("<b>털 성능 측정</b>", label);
            GUILayout.Label(string.IsNullOrEmpty(status) ? "펫 배치 → 버튼 숨기기 → 폰 고정 → 묶음·거리 선택 → 측정" : status, label);

            if (running)
            {
                if (GUILayout.Button("중지", button, GUILayout.Height(bh))) Stop();
            }
            else
            {
                GUILayout.BeginHorizontal();
                for (int i = 0; i < SetNames.Length; i++)
                    if (GUILayout.Button(SetNames[i], i == setIndex ? selected : button, GUILayout.Height(bh))) setIndex = i;
                GUILayout.EndHorizontal();
                GUILayout.BeginHorizontal();
                for (int i = 0; i < DistNames.Length; i++)
                    if (GUILayout.Button(DistNames[i], i == distIndex ? selected : button, GUILayout.Height(bh))) distIndex = i;
                GUILayout.EndHorizontal();
                GUILayout.BeginHorizontal();
                for (int i = 0; i < LoadNames.Length; i++)
                    if (GUILayout.Button(LoadNames[i], i == loadIndex ? selected : button, GUILayout.Height(bh))) loadIndex = i;
                GUILayout.EndHorizontal();
                GUILayout.Label(liveCoverage < 0 ? "화면 차지: 펫 없음"
                    : $"화면 차지 {liveCoverage:0}%  (목표: 가까이 80~90%, 멀리 20~30%)", label);

                int n = Selected().Count;
                GUILayout.BeginHorizontal();
                if (GUILayout.Button($"빠른 ({n}개 ~{Mathf.CeilToInt(n * (quickWarmupSeconds + quickMeasureSeconds + 1) / 60f)}분)", button, GUILayout.Height(bh))) StartRun(true);
                if (GUILayout.Button($"정식 (~{Mathf.CeilToInt(rounds * n * (warmupSeconds + measureSeconds + 1) / 60f)}분)", button, GUILayout.Height(bh))) StartRun(false);
                if (GUILayout.Button("닫기", button, GUILayout.Height(bh))) panel = false;
                GUILayout.EndHorizontal();

                if (hasResults)
                {
                    GUILayout.BeginHorizontal();
                    if (GUILayout.Button("최근 공유", button, GUILayout.Height(bh)))
                        status = FurResultShare.Share(FurResultShare.Latest(), SummaryText());
                    if (GUILayout.Button("전체 공유", button, GUILayout.Height(bh)))
                        status = FurResultShare.Share(FurResultShare.MergeAll(), SummaryText());
                    if (GUILayout.Button("스크린샷→갤러리", button, GUILayout.Height(bh)))
                        status = SaveShotsToGallery();
                    GUILayout.EndHorizontal();
                }
            }
            foreach (var l in summary) GUILayout.Label($"<size={Mathf.RoundToInt(12 * s)}>{l}</size>", label);
            GUILayout.EndArea();
        }
    }
}
