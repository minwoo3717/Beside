using System.Collections.Generic;
using Beside.Api;
using Beside.AR;
using Beside.Job;
using UnityEngine;
using UnityEngine.UI;

namespace Beside.UI
{
    /// <summary>
    /// Binds the UI to JobManager / ARGuidance / ModelPreview. The whole UI is built from code (UiKit),
    /// so the scene only needs the components (JobManager, PhotoPicker, ModelPreview, ARGuidance, MetricsRecorder, this).
    /// Screens: bottom panel (stage indicator, status, actions), top guidance banner, full-screen preview.
    /// </summary>
    public class MainUIController : MonoBehaviour
    {
        [SerializeField] private JobManager jobManager;
        [SerializeField] private PhotoPicker photoPicker;
        [SerializeField] private ModelPreview preview;
        [SerializeField] private ARGuidance guidance;

        [Tooltip("Shows the test buttons (screenshot upload, forced failure).")]
        [SerializeField] private bool debugButtons = true;

        const string IdleText = "사진을 골라 반려동물을 3D로 만들어 보세요";

        Text healthText, statusText, guidanceText, stepText;
        RectTransform actionsRow, guidanceBanner, previewPanel;
        Button settingsButton;
        RawImage previewImage;
        GameObject readyTemplate;
        bool placed;

        void Awake()
        {
            if (jobManager == null) jobManager = FindFirstObjectByType<JobManager>();
            if (photoPicker == null) photoPicker = GetComponent<PhotoPicker>() ?? FindFirstObjectByType<PhotoPicker>();
            if (preview == null) preview = GetComponent<ModelPreview>() ?? FindFirstObjectByType<ModelPreview>();
            if (guidance == null) guidance = GetComponent<ARGuidance>() ?? FindFirstObjectByType<ARGuidance>();
            if (jobManager == null) { Debug.LogError("[MainUI] JobManager missing"); enabled = false; return; }
            Build();
        }

        void OnEnable()
        {
            if (jobManager == null) return;
            jobManager.StateChanged += OnStateChanged;
            jobManager.JobFailed += OnJobFailed;
            jobManager.ModelReady += OnModelReady;
            jobManager.ModelPlaced += OnModelPlaced;
            if (guidance != null) guidance.GuidanceChanged += OnGuidance;
        }

        void OnDisable()
        {
            if (jobManager == null) return;
            jobManager.StateChanged -= OnStateChanged;
            jobManager.JobFailed -= OnJobFailed;
            jobManager.ModelReady -= OnModelReady;
            jobManager.ModelPlaced -= OnModelPlaced;
            if (guidance != null) guidance.GuidanceChanged -= OnGuidance;
        }

        void Start()
        {
            jobManager.CheckHealth(
                h => healthText.text = $"서버 연결됨 · {h.profile}/{h.workerType}",
                e => { healthText.text = "서버 연결 안 됨 · " + e.code; healthText.color = UiKit.Danger; });
            RenderState(jobManager.CurrentState, jobManager.CurrentState == JobManager.State.Idle ? IdleText : "");
        }

        // ---------- build ----------

        void Build()
        {
            var canvas = UiKit.Canvas("BesideUI").transform;

            // top guidance banner (AR blockers)
            guidanceBanner = UiKit.Rect("Guidance", canvas, new Vector2(0, 1), new Vector2(1, 1), new Vector2(32, -300), new Vector2(-32, -80));
            UiKit.Image(guidanceBanner, UiKit.Panel);
            UiKit.Column(guidanceBanner, 12, 24);
            guidanceText = UiKit.Text("GuidanceText", guidanceBanner, "", 36, Color.white, TextAnchor.MiddleLeft);
            UiKit.Height(guidanceText, 120);
            settingsButton = UiKit.Button("Settings", guidanceBanner, "설정 열기", UiKit.Accent, () => guidance?.RequestCameraOrOpenSettings(), 34);
            UiKit.Height(settingsButton, 90);
            guidanceBanner.gameObject.SetActive(false);

            // bottom panel
            var panel = UiKit.Rect("Panel", canvas, new Vector2(0, 0), new Vector2(1, 0), new Vector2(32, 48), new Vector2(-32, 520));
            UiKit.Image(panel, UiKit.Panel);
            UiKit.Column(panel, 14, 32);
            healthText = UiKit.Text("Health", panel, "서버 확인 중…", 28, UiKit.Muted, TextAnchor.MiddleLeft);
            UiKit.Height(healthText, 40);
            stepText = UiKit.Text("Steps", panel, "", 30, UiKit.Muted, TextAnchor.MiddleLeft);
            UiKit.Height(stepText, 44);
            statusText = UiKit.Text("Status", panel, "", 40, Color.white, TextAnchor.MiddleLeft, FontStyle.Bold);
            UiKit.Height(statusText, 130);
            actionsRow = UiKit.Rect("Actions", panel, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            UiKit.Row(actionsRow);
            UiKit.Height(actionsRow, 120);

            // full-screen preview
            previewPanel = UiKit.Rect("Preview", canvas, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            UiKit.Image(previewPanel, new Color(0.05f, 0.05f, 0.06f, 0.97f));
            UiKit.Column(previewPanel, 24, 48);
            var title = UiKit.Text("PreviewTitle", previewPanel, "미리보기 · 드래그해서 돌려보세요", 40, Color.white, TextAnchor.MiddleCenter, FontStyle.Bold);
            UiKit.Height(title, 90);
            var imgRt = UiKit.Rect("PreviewImage", previewPanel, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            previewImage = imgRt.gameObject.AddComponent<RawImage>();
            previewImage.texture = preview != null ? preview.Texture : null;
            imgRt.gameObject.AddComponent<UiKit.DragForwarder>().onDragX = dx => preview?.Drag(dx);
            var le = imgRt.gameObject.AddComponent<LayoutElement>(); le.flexibleHeight = 1; le.preferredHeight = 1000;
            var row = UiKit.Rect("PreviewActions", previewPanel, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            UiKit.Row(row); UiKit.Height(row, 120);
            UiKit.Button("Place", row, "AR에 배치하기", UiKit.Accent, ClosePreview);
            UiKit.Button("Remake", row, "다시 만들기", UiKit.ButtonBg, () => { ClosePreview(); StartOver(); });
            previewPanel.gameObject.SetActive(false);
        }

        // ---------- events ----------

        void OnStateChanged(JobManager.State s, string text) => RenderState(s, text);

        void OnJobFailed(string code, string userText, bool retryable) =>
            RenderState(JobManager.State.Failed, retryable ? userText : userText + "\n(다시 시도할 수 없어요. 새 사진으로 시작해 주세요.)");

        void OnModelReady(string jobId, string path, GameObject template)
        {
            readyTemplate = template;
            placed = false;
            if (preview != null) { preview.Show(template); previewPanel.gameObject.SetActive(true); }
        }

        void OnModelPlaced(string jobId, GameObject instance)
        {
            placed = true;
            RenderState(JobManager.State.Ready, "배치 완료! 손가락으로 옮기거나 두 손가락으로 돌리고 키울 수 있어요");
        }

        void OnGuidance(ARGuidance.Issue issue, string text, bool showSettings)
        {
            guidanceBanner.gameObject.SetActive(issue != ARGuidance.Issue.None);
            guidanceText.text = text;
            settingsButton.gameObject.SetActive(showSettings);
        }

        // ---------- rendering ----------

        void RenderState(JobManager.State s, string text)
        {
            if (!string.IsNullOrEmpty(text)) statusText.text = text;
            statusText.color = s == JobManager.State.Failed ? UiKit.Danger : Color.white;
            stepText.text = Steps(s);

            foreach (Transform c in actionsRow) Destroy(c.gameObject);
            switch (s)
            {
                case JobManager.State.Idle:
                    UiKit.Button("Pick", actionsRow, photoPicker != null && photoPicker.GalleryAvailable ? "사진 선택" : "사진으로 만들기", UiKit.Accent, PickAndStart);
                    if (debugButtons)
                    {
                        UiKit.Button("Shot", actionsRow, "캡처로 테스트", UiKit.ButtonBg, () => CaptureAndStart("camera.jpg"), 32);
                        UiKit.Button("FailTest", actionsRow, "실패 테스트", UiKit.ButtonBg, () => CaptureAndStart("fail.jpg"), 32);
                    }
                    break;
                case JobManager.State.Uploading:
                case JobManager.State.Waiting:
                case JobManager.State.Downloading:
                case JobManager.State.Loading:
                    UiKit.Button("Cancel", actionsRow, "취소", UiKit.ButtonBg, () => { jobManager.CancelJob(); RenderState(JobManager.State.Idle, IdleText); });
                    break;
                case JobManager.State.Failed:
                    if (jobManager.LastErrorRetryable) UiKit.Button("Retry", actionsRow, "다시 시도", UiKit.Accent, jobManager.Retry);
                    UiKit.Button("Reset", actionsRow, "처음부터", UiKit.ButtonBg, StartOver);
                    break;
                case JobManager.State.Ready:
                    if (!placed && readyTemplate != null)
                        UiKit.Button("Preview", actionsRow, "미리보기", UiKit.ButtonBg, () => { preview?.Show(readyTemplate); previewPanel.gameObject.SetActive(true); });
                    UiKit.Button("New", actionsRow, "새로 만들기", UiKit.Accent, StartOver);
                    break;
            }
        }

        static string Steps(JobManager.State s)
        {
            string[] names = { "업로드", "생성", "다운로드", "준비" };
            int active = s switch
            {
                JobManager.State.Uploading => 0,
                JobManager.State.Waiting => 1,
                JobManager.State.Downloading => 2,
                JobManager.State.Loading => 3,
                JobManager.State.Ready => 4,
                _ => -1
            };
            if (active < 0) return "";
            var parts = new List<string>();
            for (int i = 0; i < names.Length; i++)
                parts.Add((i < active ? "✓ " : i == active ? "● " : "○ ") + names[i]);
            return string.Join("   ", parts);
        }

        // ---------- actions ----------

        void PickAndStart()
        {
            if (photoPicker == null) { Debug.LogError("[MainUI] PhotoPicker missing"); return; }
            photoPicker.Pick(photos =>
            {
                if (photos == null || photos.Count == 0)
                {
                    if (!photoPicker.GalleryAvailable) CaptureAndStart("camera.jpg"); // editor without test paths / no plugin
                    return;                                                           // gallery: user cancelled
                }
                jobManager.StartJob(photos);
            });
        }

        void CaptureAndStart(string fileName)
        {
            if (photoPicker == null) return;
            photoPicker.CaptureScreen(fileName, photo => jobManager.StartJob(new List<Photo> { photo }));
        }

        void ClosePreview()
        {
            previewPanel.gameObject.SetActive(false);
            preview?.Hide();
            if (!placed) RenderState(JobManager.State.Ready, "바닥을 터치해서 배치하세요");
        }

        void StartOver()
        {
            readyTemplate = null;
            placed = false;
            jobManager.ClearSavedJob();
            RenderState(JobManager.State.Idle, IdleText);
        }
    }
}
