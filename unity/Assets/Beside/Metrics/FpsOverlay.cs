using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.Profiling;

namespace Beside.Metrics
{
    /// <summary>
    /// Small FPS readout in the top-left corner for checking performance on the phone.
    ///   current FPS (0.5 s average) · lowest FPS in the last 5 s · frame time · memory · Unity's frame-rate cap
    /// Toggle: the Inspector checkbox, or a three-finger tap on the phone. Off by default in builds you hand out.
    /// The numbers for reports still come from metrics.csv (MetricsRecorder: avgFps/minFps over 30 s after placement).
    /// </summary>
    public class FpsOverlay : MonoBehaviour
    {
        public bool show = true;
        [Tooltip("Allow a three-finger tap to show/hide the overlay.")]
        public bool threeFingerToggle = true;

        const float AvgWindow = 0.5f, MinWindow = 5f;
        float accTime; int accFrames; float fps;
        float minFps = float.MaxValue, minTimer; float shownMin;
        float frameMs;
        GUIStyle style;
        bool wasThreeDown;

        void Update()
        {
            float dt = Time.unscaledDeltaTime;
            if (dt <= 0f) return;
            frameMs = dt * 1000f;

            accTime += dt; accFrames++;
            if (accTime >= AvgWindow) { fps = accFrames / accTime; accTime = 0f; accFrames = 0; }

            minFps = Mathf.Min(minFps, 1f / dt);
            minTimer += dt;
            if (minTimer >= MinWindow) { shownMin = minFps; minFps = float.MaxValue; minTimer = 0f; }

            if (threeFingerToggle && Touchscreen.current != null)
            {
                int down = 0;
                foreach (var t in Touchscreen.current.touches) if (t.press.isPressed) down++;
                bool three = down >= 3;
                if (three && !wasThreeDown) show = !show;
                wasThreeDown = three;
            }
        }

        void OnGUI()
        {
            if (!show) return;
            if (style == null)
            {
                style = new GUIStyle(GUI.skin.box) { alignment = TextAnchor.UpperLeft, richText = true };
                style.normal.textColor = Color.white;
            }
            float s = Mathf.Max(1f, Screen.dpi / 160f);
            style.fontSize = Mathf.RoundToInt(13 * s);

            string color = fps >= 28f ? "#9FD3B4" : fps >= 20f ? "#F2B98B" : "#FF7A70";
            long memMB = Profiler.GetTotalAllocatedMemoryLong() / (1024 * 1024);
            int cap = Application.targetFrameRate;
            string text =
                $"<b><color={color}>{fps:0} FPS</color></b>   {frameMs:0.0} ms\n" +
                $"min(5s) {(shownMin > 0 ? shownMin.ToString("0") : "-")}   mem {memMB} MB\n" +
                $"cap {(cap > 0 ? cap.ToString() : "default")}";
            var safe = Screen.safeArea;
            GUI.Box(new Rect(safe.xMin + 8 * s, Screen.height - safe.yMax + 8 * s, 190 * s, 58 * s), text, style);
        }
    }
}
