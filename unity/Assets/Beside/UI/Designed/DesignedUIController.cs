using System.Collections;
using System.Collections.Generic;
using System.IO;
using Beside.Api;
using Beside.AR;
using Beside.Job;
using UnityEngine;
using UnityEngine.UI;

namespace Beside.UI.Designed
{
    /// <summary>
    /// The "Beside 앱 UI 시안" screens, built from code with DesignKit.
    /// Home → Confirm → Progress → Preview → AR (scan / place / placed) and Error.
    /// Uses the same JobManager / PhotoPicker / ModelPreview / ARGuidance as the basic UI, unchanged.
    /// Enable either this or MainUIController on the Beside object, not both.
    /// </summary>
    public class DesignedUIController : MonoBehaviour
    {
        [Header("References (auto-found when empty)")]
        [SerializeField] private JobManager jobManager;
        [SerializeField] private PhotoPicker photoPicker;
        [SerializeField] private ModelPreview preview;
        [SerializeField] private ARGuidance guidance;
        [SerializeField] private CenterPlacer centerPlacer;
        [SerializeField] private RuntimeModelLoader modelLoader;

        [Header("Look")]
        [Tooltip("Korean TTF such as Noto Sans KR. Empty = Unity default font (OS fallback).")]
        [SerializeField] private Font font;
        [Tooltip("Hero image on the home screen. Empty = soft color block.")]
        [SerializeField] private Sprite heroImage;

        [Header("AR messages")]
        [Tooltip("Show the instruction card at the top of the AR screen. The user can also close it with ×.")]
        [SerializeField] private bool showARInstructions = true;

        [Tooltip("After the user closes the card, show it again when the message changes (e.g. after placing).")]
        [SerializeField] private bool reshowOnNewMessage = false;

        [Header("Template UI")]
        [Tooltip("Template UI objects to hide so they don't overlap this UI. Coaching UI stays.")]
        [SerializeField] private string[] hideTemplateObjects = { "Create Button", "Delete Button", "Options Button", "Options Modal", "Object Menu Animator", "Greeting Prompt", "DebugMenu" };

        enum Screen { Home, Confirm, Progress, Preview, AR, Error }

        Canvas canvas;
        RectTransform home, confirm, progress, previewScreen, arOverlay, error;
        // confirm
        RectTransform thumbGrid; Text confirmCount; List<Photo> pendingPhotos = new List<Photo>();
        // progress
        Text progressTitle, progressBody, progressJob; (RectTransform row, Image circle, Text label, Text right)[] steps;
        // preview
        RawImage previewImage; Text statSize, statTris, statFile, furToggleLabel; GameObject readyTemplate; string readyPath; bool furOn = true;
        // AR
        Text arPill, arInstruction, arModelName; Image arPillDot; RectTransform arBeforePlace, arAfterPlace; Text arToast; bool placed;
        // error
        Text errorTitle, errorBody, errorCode, errorStage, errorRetry; Button errorRetryButton;
        SpawnLimiter limiter;
        RectTransform arInstructionCard;
        bool instructionDismissed;
        RectTransform bottomToggle; Text bottomToggleLabel;
        bool bottomHidden;
        UnityEngine.XR.Interaction.Toolkit.Samples.StarterAssets.ObjectSpawner spawner;

        void Awake()
        {
            if (jobManager == null) jobManager = FindFirstObjectByType<JobManager>();
            if (photoPicker == null) photoPicker = GetComponent<PhotoPicker>() ?? FindFirstObjectByType<PhotoPicker>();
            if (preview == null) preview = GetComponent<ModelPreview>() ?? FindFirstObjectByType<ModelPreview>();
            if (guidance == null) guidance = GetComponent<ARGuidance>() ?? FindFirstObjectByType<ARGuidance>();
            if (centerPlacer == null) centerPlacer = GetComponent<CenterPlacer>() ?? gameObject.AddComponent<CenterPlacer>();
            limiter = FindFirstObjectByType<SpawnLimiter>();
            spawner = FindFirstObjectByType<UnityEngine.XR.Interaction.Toolkit.Samples.StarterAssets.ObjectSpawner>();
            if (modelLoader == null) modelLoader = FindFirstObjectByType<RuntimeModelLoader>();
            if (jobManager == null) { Debug.LogError("[DesignedUI] JobManager missing"); enabled = false; return; }
        }

        void BuildOnce()
        {
            if (canvas != null) return;
            DesignKit.Font = font;
            Build();
            HideTemplateUI();
            Show(Screen.Home);
        }

        void OnEnable()
        {
            if (jobManager == null) return;
            BuildOnce();                    // Awake runs even when unchecked, so build here
            canvas.gameObject.SetActive(true);
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
            if (canvas != null) canvas.gameObject.SetActive(false);
        }

        void Update()
        {
            if (arOverlay == null || !arOverlay.gameObject.activeSelf) return;

            // The buttons follow what is actually on the floor, however it got there (tap, center button, reset)
            bool hasModel = HasPlacedModel();
            if (hasModel != placed) SetPlaced(hasModel);

            if (!placed && guidance != null)
            {
                bool plane = guidance.PlaneDetected;
                arPill.text = plane ? "바닥 인식됨" : "바닥 인식 중";
                arPillDot.color = plane ? DesignKit.Mint : DesignKit.Peach;
                arInstruction.text = plane ? "흰 점이 보이는 바닥을 터치하면 그 자리에 놓여요" : "폰을 좌우로 천천히 움직여 바닥을 비춰 주세요";
            }
        }

        void ToggleBottomPanels()
        {
            bottomHidden = !bottomHidden;
            ApplyBottomPanels();
        }

        /// <summary>Shows the right bottom panel for the placement state, unless the user hid the buttons.</summary>
        void ApplyBottomPanels()
        {
            arBeforePlace.gameObject.SetActive(!bottomHidden && !placed);
            arAfterPlace.gameObject.SetActive(!bottomHidden && placed);
            bottomToggleLabel.text = bottomHidden ? "버튼 보기" : "버튼 숨기기";
            // sits just above the panel when shown, drops to the bottom edge when hidden
            bottomToggle.anchoredPosition = new Vector2(-64, bottomHidden ? 90 : 440);
        }

        void DismissInstruction()
        {
            instructionDismissed = true;
            arInstructionCard.gameObject.SetActive(false);
        }

        void RefreshInstructionCard()
        {
            if (arInstructionCard != null) arInstructionCard.gameObject.SetActive(showARInstructions && !instructionDismissed);
        }

        void SetPlaced(bool value)
        {
            if (value != placed && reshowOnNewMessage) instructionDismissed = false;
            placed = value;
            RefreshInstructionCard();
            ApplyBottomPanels();
            if (value)
            {
                arPill.text = "배치됨";
                arPillDot.color = DesignKit.Mint;
                arInstruction.text = "배치 완료! 한 손가락으로 옮기고, 두 손가락으로 돌리거나 키울 수 있어요.";
            }
        }

        bool HasPlacedModel()
        {
            if (limiter != null) return limiter.Count > 0;
            if (spawner == null) return placed;
            foreach (Transform c in spawner.transform) if (c.gameObject.activeInHierarchy) return true;
            return false;
        }

        void ClearPlacedModels()
        {
            if (limiter != null) { limiter.ClearAll(); return; }
            if (spawner != null) foreach (Transform c in spawner.transform) Destroy(c.gameObject);
        }

        // =====================================================================
        // build
        // =====================================================================

        void Build()
        {
            canvas = UiKit.Canvas("BesideDesignedUI", 20);
            var root = canvas.transform;
            BuildHome(root);
            BuildConfirm(root);
            BuildProgress(root);
            BuildPreview(root);
            BuildAR(root);
            BuildError(root);
        }

        void BuildHome(Transform root)
        {
            home = DesignKit.Screen("Home", root, DesignKit.Ground);
            var col = DesignKit.Column("Col", home, Vector2.zero, Vector2.one, new Vector2(64, 90), new Vector2(-64, -170), 60, 0);

            var brand = DesignKit.Row("Brand", col, 70, 24);
            brand.GetComponent<HorizontalLayoutGroup>().childForceExpandWidth = false;
            DesignKit.Label("BrandText", brand, "Beside", 60, DesignKit.Ink, 70, TextAnchor.MiddleLeft, FontStyle.Bold);

            DesignKit.Spacer(col);

            var hero = UiKit.Rect("Hero", col, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            var heroImg = DesignKit.Box(hero, DesignKit.AccentSoft, 76f);
            if (heroImage != null) { heroImg.sprite = heroImage; heroImg.type = Image.Type.Simple; heroImg.preserveAspect = true; heroImg.color = Color.white; }
            UiKit.Height(hero, 760);

            DesignKit.Label("Title", col, "사진 한 장으로\n우리 아이를 3D로 만나요", 74, DesignKit.Ink, 200, TextAnchor.MiddleLeft, FontStyle.Bold);
            DesignKit.Label("Body", col, "얼굴과 몸 전체가 잘 보이는 사진을 고르면 2~3분 뒤 AR로 볼 수 있어요.", 40, DesignKit.InkSoft, 130);

            DesignKit.Spacer(col);

            DesignKit.PrimaryButton("Pick", col, photoPicker != null && photoPicker.GalleryAvailable ? "갤러리에서 사진 선택" : "사진으로 만들기", PickFromGallery);
            DesignKit.SecondaryButton("Shoot", col, "지금 촬영하기", () => StartCoroutine(CaptureThenConfirm()));
        }

        void BuildConfirm(Transform root)
        {
            confirm = DesignKit.Screen("Confirm", root, DesignKit.Ground);
            var col = DesignKit.Column("Col", confirm, Vector2.zero, Vector2.one, new Vector2(64, 90), new Vector2(-64, -150), 48, 0);

            var header = DesignKit.Row("Header", col, 120, 32);
            header.GetComponent<HorizontalLayoutGroup>().childForceExpandWidth = false;
            var back = DesignKit.SecondaryButton("Back", header, "‹", () => Show(Screen.Home));
            var ble = back.GetComponent<LayoutElement>(); ble.preferredWidth = 120; ble.preferredHeight = 120;
            DesignKit.Label("Title", header, "사진 확인", 56, DesignKit.Ink, 120, TextAnchor.MiddleLeft, FontStyle.Bold);

            confirmCount = DesignKit.Label("Count", col, "", 40, DesignKit.InkSoft, 50);

            thumbGrid = UiKit.Rect("Thumbs", col, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            var grid = thumbGrid.gameObject.AddComponent<GridLayoutGroup>();
            grid.cellSize = new Vector2(300, 300); grid.spacing = new Vector2(26, 26); grid.constraint = GridLayoutGroup.Constraint.FixedColumnCount; grid.constraintCount = 3;
            UiKit.Height(thumbGrid, 626);

            var tips = DesignKit.Card("Tips", col, DesignKit.CardBg, 420, 56f, 12, 48);
            DesignKit.Label("TipsTitle", tips, "좋은 결과를 위한 사진", 40, DesignKit.Ink, 60, TextAnchor.MiddleLeft, FontStyle.Bold);
            DesignKit.Label("Tip1", tips, "✓  얼굴과 몸 전체가 한 장에 들어와요", 36, DesignKit.Ink, 70);
            DesignKit.Label("Tip2", tips, "✓  밝은 곳에서 찍은 선명한 사진이 좋아요", 36, DesignKit.Ink, 70);
            DesignKit.Label("Tip3", tips, "✕  여러 마리가 함께 있거나 가려진 사진은 피해주세요", 36, DesignKit.Danger, 70);

            DesignKit.Spacer(col);
            DesignKit.Label("Limits", col, "JPG · PNG · WEBP, 장당 5MB 이하", 32, DesignKit.InkSoft, 40, TextAnchor.MiddleCenter);
            DesignKit.PrimaryButton("Go", col, "이 사진으로 만들기", () => { if (pendingPhotos.Count > 0) jobManager.StartJob(pendingPhotos); });
            DesignKit.GhostButton("Again", col, "다시 고르기", PickFromGallery);
        }

        void BuildProgress(Transform root)
        {
            progress = DesignKit.Screen("Progress", root, DesignKit.Ground);
            var col = DesignKit.Column("Col", progress, Vector2.zero, Vector2.one, new Vector2(64, 90), new Vector2(-64, -150), 56, 0);

            var header = DesignKit.Row("Header", col, 90, 24);
            DesignKit.Label("Title", header, "만드는 중", 56, DesignKit.Ink, 90, TextAnchor.MiddleLeft, FontStyle.Bold);
            progressJob = DesignKit.Label("Job", header, "", 32, DesignKit.InkSoft, 90, TextAnchor.MiddleRight);

            var hero = UiKit.Rect("Hero", col, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            DesignKit.Box(hero, DesignKit.AccentSoft, 76f);
            UiKit.Height(hero, 560);
            var spin = UiKit.Rect("Spinner", hero, new Vector2(0.5f, 0.5f), new Vector2(0.5f, 0.5f), new Vector2(-180, -180), new Vector2(180, 180));
            var spinImg = DesignKit.Box(spin, DesignKit.Accent, 180f);
            spinImg.type = Image.Type.Filled; spinImg.fillMethod = Image.FillMethod.Radial360; spinImg.fillAmount = 0.3f;
            spin.gameObject.AddComponent<Spinner>().target = spin;
            var spinInner = UiKit.Rect("Inner", hero, new Vector2(0.5f, 0.5f), new Vector2(0.5f, 0.5f), new Vector2(-160, -160), new Vector2(160, 160));
            DesignKit.Box(spinInner, DesignKit.AccentSoft, 160f);

            progressTitle = DesignKit.Label("PTitle", col, "", 66, DesignKit.Ink, 100, TextAnchor.MiddleLeft, FontStyle.Bold);
            progressBody = DesignKit.Label("PBody", col, "보통 2~3분 정도 걸려요. 앱을 닫아도 작업은 계속되고, 다시 열면 이어서 볼 수 있어요.", 38, DesignKit.InkSoft, 130);

            var card = DesignKit.Card("Steps", col, DesignKit.CardBg, 470, 56f, 0, 40);
            string[] names = { "사진 업로드", "3D 모델 생성", "모델 다운로드", "AR 준비" };
            steps = new (RectTransform, Image, Text, Text)[4];
            for (int i = 0; i < 4; i++) { steps[i] = DesignKit.StepRow("Step" + i, card); steps[i].label.text = names[i]; }

            DesignKit.Spacer(col);
            DesignKit.GhostButton("Cancel", col, "취소", () => { jobManager.ClearSavedJob(); Show(Screen.Home); });
        }

        void BuildPreview(Transform root)
        {
            previewScreen = DesignKit.Screen("PreviewScreen", root, DesignKit.DarkGround);
            var col = DesignKit.Column("Col", previewScreen, Vector2.zero, Vector2.one, new Vector2(64, 90), new Vector2(-64, -150), 44, 0);

            var header = DesignKit.Row("Header", col, 90, 24);
            DesignKit.Label("Title", header, "미리보기", 56, DesignKit.OnDark, 90, TextAnchor.MiddleLeft, FontStyle.Bold);
            var furBtn = DesignKit.RoundedButton("Fur", header, "털 표현 켜짐", new Color(1, 1, 1, 0.1f), DesignKit.OnDark, ToggleFur, 80, 30, FontStyle.Normal);
            furToggleLabel = furBtn.GetComponentInChildren<Text>();
            var fle = furBtn.GetComponent<LayoutElement>(); fle.preferredWidth = 300; fle.flexibleWidth = 0;

            var view = UiKit.Rect("View", col, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            DesignKit.Box(view, DesignKit.DarkCard, 76f);
            var vle = view.gameObject.AddComponent<LayoutElement>(); vle.flexibleHeight = 1; vle.preferredHeight = 1000;
            var imgRt = UiKit.Rect("Image", view, Vector2.zero, Vector2.one, new Vector2(8, 8), new Vector2(-8, -8));
            previewImage = imgRt.gameObject.AddComponent<RawImage>();
            previewImage.texture = preview != null ? preview.Texture : null;
            imgRt.gameObject.AddComponent<UiKit.DragForwarder>().onDragX = dx => preview?.Drag(dx);
            var hint = UiKit.Text("Hint", view, "드래그해서 돌려보세요", 32, DesignKit.OnDarkSoft, TextAnchor.LowerCenter);
            hint.rectTransform.offsetMin = new Vector2(0, 40);
            if (font != null) hint.font = font;

            var stats = DesignKit.Row("Stats", col, 170, 28);
            statSize = Stat(stats, "크기"); statTris = Stat(stats, "폴리곤"); statFile = Stat(stats, "파일");

            DesignKit.PrimaryButton("ToAR", col, "AR로 배치하기", EnterAR, DesignKit.Mint, DesignKit.DarkGround);
            DesignKit.GhostButton("Remake", col, "다른 사진으로 다시 만들기", StartOver, dark: true);
        }

        Text Stat(Transform row, string title)
        {
            var card = DesignKit.Card(title, row, DesignKit.DarkCard, 170, 44f, 4, 36);
            DesignKit.Label("T", card, title, 28, DesignKit.InkMuted, 40);
            return DesignKit.Label("V", card, "–", 40, DesignKit.OnDark, 56, TextAnchor.MiddleLeft, FontStyle.Bold);
        }

        void BuildAR(Transform root)
        {
            arOverlay = UiKit.Rect("AR", root, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero); // transparent: camera shows through

            // top: pill
            var pill = DesignKit.Pill("Pill", arOverlay, "바닥 인식 중", DesignKit.DarkScrim, DesignKit.OnDark, out arPill, 380);
            pill.anchorMin = pill.anchorMax = new Vector2(1, 1); pill.pivot = new Vector2(1, 1);
            pill.anchoredPosition = new Vector2(-64, -110); pill.sizeDelta = new Vector2(380, 84);
            var dot = UiKit.Rect("Dot", pill, new Vector2(0, 0.5f), new Vector2(0, 0.5f), new Vector2(28, -12), new Vector2(52, 12));
            arPillDot = DesignKit.Box(dot, DesignKit.Peach, 12f);
            arPill.alignment = TextAnchor.MiddleCenter; arPill.rectTransform.offsetMin = new Vector2(40, 0);

            // instruction card under the pill
            var card = UiKit.Rect("Instruction", arOverlay, new Vector2(0, 1), new Vector2(1, 1), new Vector2(64, -350), new Vector2(-64, -230));
            DesignKit.Box(card, DesignKit.DarkScrim, 50f);
            arInstructionCard = card;
            arInstruction = UiKit.Text("Text", card, "", 36, DesignKit.OnDark, TextAnchor.MiddleLeft);
            arInstruction.rectTransform.offsetMin = new Vector2(44, 0); arInstruction.rectTransform.offsetMax = new Vector2(-130, 0);
            if (font != null) arInstruction.font = font;

            // × close button on the right of the card
            var close = DesignKit.RoundedButton("Close", card, "×", new Color(1, 1, 1, 0.08f), DesignKit.OnDarkSoft, DismissInstruction, 96, 56, FontStyle.Normal);
            var crt = close.GetComponent<RectTransform>();
            crt.anchorMin = crt.anchorMax = new Vector2(1, 0.5f); crt.pivot = new Vector2(1, 0.5f);
            crt.anchoredPosition = new Vector2(-14, 0); crt.sizeDelta = new Vector2(96, 96);
            Destroy(close.GetComponent<LayoutElement>());

            // bottom: before placement
            arBeforePlace = DesignKit.Column("Before", arOverlay, new Vector2(0, 0), new Vector2(1, 0), new Vector2(64, 90), new Vector2(-64, 420), 32, 0);
            var modelCard = DesignKit.Card("ModelCard", arBeforePlace, DesignKit.DarkScrim, 150, 50f, 4, 40);
            arModelName = DesignKit.Label("Name", modelCard, "우리 아이", 38, DesignKit.OnDark, 50, TextAnchor.MiddleLeft, FontStyle.Bold);
            DesignKit.Label("Sub", modelCard, "배치 후 손가락으로 옮기고 돌릴 수 있어요", 30, DesignKit.OnDarkSoft, 40);
            DesignKit.PrimaryButton("Center", arBeforePlace, "화면 가운데에 바로 놓기", PlaceAtCenter, DesignKit.Mint, DesignKit.DarkGround);

            // bottom: after placement
            arAfterPlace = DesignKit.Column("After", arOverlay, new Vector2(0, 0), new Vector2(1, 0), new Vector2(64, 90), new Vector2(-64, 420), 32, 0);
            var row = DesignKit.Row("Row", arAfterPlace, 150, 28);
            DesignKit.SecondaryButton("Replace", row, "다시 배치", ResetPlacement, dark: true);
            DesignKit.SecondaryButton("Snap", row, "사진 찍기", () => StartCoroutine(SavePhoto()), dark: true);
            DesignKit.PrimaryButton("New", arAfterPlace, "새로 만들기", StartOver, DesignKit.Mint, DesignKit.DarkGround);

            var toastRt = DesignKit.Pill("Toast", arOverlay, "", DesignKit.DarkScrim, DesignKit.OnDark, out arToast, 520);
            toastRt.anchorMin = toastRt.anchorMax = new Vector2(0.5f, 0); toastRt.anchoredPosition = new Vector2(0, 560);
            toastRt.gameObject.SetActive(false);

            // show / hide the bottom buttons
            var tb = DesignKit.RoundedButton("BottomToggle", arOverlay, "버튼 숨기기", DesignKit.DarkScrim, DesignKit.OnDark, ToggleBottomPanels, 84, 30, FontStyle.Normal);
            bottomToggle = tb.GetComponent<RectTransform>();
            bottomToggle.anchorMin = bottomToggle.anchorMax = new Vector2(1, 0); bottomToggle.pivot = new Vector2(1, 0);
            bottomToggle.sizeDelta = new Vector2(260, 84);
            bottomToggleLabel = tb.GetComponentInChildren<Text>();
            Destroy(tb.GetComponent<LayoutElement>());

            // back to preview
            var back = DesignKit.RoundedButton("Back", arOverlay, "‹", DesignKit.DarkScrim, DesignKit.OnDark, () => Show(Screen.Preview), 120, 56, FontStyle.Normal);
            var brt = back.GetComponent<RectTransform>();
            brt.anchorMin = brt.anchorMax = new Vector2(0, 1); brt.pivot = new Vector2(0, 1);
            brt.anchoredPosition = new Vector2(64, -92); brt.sizeDelta = new Vector2(120, 120);
            Destroy(back.GetComponent<LayoutElement>());
        }

        void BuildError(Transform root)
        {
            error = DesignKit.Screen("Error", root, DesignKit.Ground);
            var col = DesignKit.Column("Col", error, Vector2.zero, Vector2.one, new Vector2(64, 90), new Vector2(-64, -150), 56, 0);

            var header = DesignKit.Row("Header", col, 90, 24);
            DesignKit.Label("Title", header, "만들지 못했어요", 56, DesignKit.Ink, 90, TextAnchor.MiddleLeft, FontStyle.Bold);
            errorStage = DesignKit.Label("Job", header, "", 32, DesignKit.InkSoft, 90, TextAnchor.MiddleRight);

            var hero = UiKit.Rect("Hero", col, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            DesignKit.Box(hero, DesignKit.DangerSoft, 76f);
            UiKit.Height(hero, 460);
            var mark = UiKit.Text("Mark", hero, "!", 160, DesignKit.Danger, TextAnchor.MiddleCenter, FontStyle.Bold);

            errorTitle = DesignKit.Label("ETitle", col, "", 62, DesignKit.Ink, 170, TextAnchor.MiddleLeft, FontStyle.Bold);
            errorBody = DesignKit.Label("EBody", col, "", 38, DesignKit.InkSoft, 170);

            var card = DesignKit.Card("Info", col, DesignKit.CardBg, 260, 56f, 20, 48);
            errorCode = InfoRow(card, "오류 코드");
            errorRetry = InfoRow(card, "다시 시도");

            DesignKit.Spacer(col);
            errorRetryButton = DesignKit.PrimaryButton("Retry", col, "같은 사진으로 다시 시도", () => jobManager.Retry());
            DesignKit.SecondaryButton("Other", col, "다른 사진 고르기", StartOver);
        }

        Text InfoRow(Transform card, string key)
        {
            var row = DesignKit.Row(key, card, 56, 16);
            DesignKit.Label("K", row, key, 36, DesignKit.InkSoft, 56);
            return DesignKit.Label("V", row, "", 36, DesignKit.Ink, 56, TextAnchor.MiddleRight);
        }

        // =====================================================================
        // screens
        // =====================================================================

        void Show(Screen s)
        {
            home.gameObject.SetActive(s == Screen.Home);
            confirm.gameObject.SetActive(s == Screen.Confirm);
            progress.gameObject.SetActive(s == Screen.Progress);
            previewScreen.gameObject.SetActive(s == Screen.Preview);
            arOverlay.gameObject.SetActive(s == Screen.AR);
            error.gameObject.SetActive(s == Screen.Error);

            if (s == Screen.Preview && preview != null && readyTemplate != null) preview.Show(readyTemplate);
            else if (preview != null) preview.Hide();

            if (s == Screen.AR) SetPlaced(HasPlacedModel());
        }

        // ---------- events ----------

        void OnStateChanged(JobManager.State s, string text)
        {
            switch (s)
            {
                case JobManager.State.Uploading:
                case JobManager.State.Waiting:
                case JobManager.State.Downloading:
                case JobManager.State.Loading:
                    UpdateProgress(s, text);
                    if (!progress.gameObject.activeSelf) Show(Screen.Progress);
                    break;
                case JobManager.State.Idle:
                    if (!home.gameObject.activeSelf && !confirm.gameObject.activeSelf) Show(Screen.Home);
                    break;
            }
        }

        void UpdateProgress(JobManager.State s, string text)
        {
            int active = s switch { JobManager.State.Uploading => 0, JobManager.State.Waiting => 1, JobManager.State.Downloading => 2, _ => 3 };
            progressTitle.text = string.IsNullOrEmpty(text) ? "만드는 중이에요" : text;
            progressJob.text = string.IsNullOrEmpty(jobManager.CurrentJobId) ? "" : "작업 번호 " + Short(jobManager.CurrentJobId);
            for (int i = 0; i < steps.Length; i++)
            {
                bool done = i < active, now = i == active;
                steps[i].circle.color = done || now ? DesignKit.Accent : DesignKit.Line;
                steps[i].label.color = done || now ? DesignKit.Ink : DesignKit.InkMuted;
                steps[i].label.fontStyle = now ? FontStyle.Bold : FontStyle.Normal;
                steps[i].right.text = done ? "완료" : now ? "진행 중" : "";
                steps[i].right.color = now ? DesignKit.Accent : DesignKit.InkSoft;
            }
        }

        void OnJobFailed(string code, string userText, bool retryable)
        {
            errorTitle.text = userText.Split('.')[0] + (userText.Contains(".") ? "." : "");
            errorBody.text = userText;
            errorCode.text = code;
            errorStage.text = string.IsNullOrEmpty(jobManager.CurrentJobId) ? "" : "작업 번호 " + Short(jobManager.CurrentJobId);
            errorRetry.text = retryable ? "가능" : "불가";
            errorRetry.color = retryable ? DesignKit.Accent : DesignKit.Danger;
            errorRetryButton.gameObject.SetActive(retryable);
            Show(Screen.Error);
        }

        void OnModelReady(string jobId, string path, GameObject template)
        {
            readyTemplate = template; readyPath = path; placed = false;
            FillStats(template, path);
            SetFur(furOn);
            Show(Screen.Preview);
        }

        void OnModelPlaced(string jobId, GameObject instance)
        {
            placed = true;
            if (arOverlay.gameObject.activeSelf) ApplyBottomPanels();
            arInstruction.text = "배치 완료! 한 손가락으로 옮기고, 두 손가락으로 돌리거나 키울 수 있어요.";
        }

        void OnGuidance(ARGuidance.Issue issue, string text, bool showSettings)
        {
            if (issue == ARGuidance.Issue.None || issue == ARGuidance.Issue.NoPlane) return;
            StartCoroutine(Toast(text, 6f));
        }

        // ---------- actions ----------

        void PickFromGallery()
        {
            if (photoPicker == null) return;
            photoPicker.Pick(photos =>
            {
                if (photos == null || photos.Count == 0)
                {
                    if (!photoPicker.GalleryAvailable) StartCoroutine(CaptureThenConfirm()); // editor / no plugin
                    return;
                }
                ShowConfirm(photos);
            });
        }

        IEnumerator CaptureThenConfirm()
        {
            canvas.gameObject.SetActive(false);                      // the camera frame must not include our UI
            yield return new WaitForEndOfFrame();
            Photo? captured = null;
            photoPicker.CaptureScreen("camera.jpg", p => captured = p);
            while (captured == null) yield return null;
            canvas.gameObject.SetActive(true);
            ShowConfirm(new List<Photo> { captured.Value });
        }

        void ShowConfirm(List<Photo> photos)
        {
            pendingPhotos = photos;
            foreach (Transform c in thumbGrid) Destroy(c.gameObject);
            for (int i = 0; i < photos.Count && i < 6; i++)
            {
                var cell = UiKit.Rect("Thumb" + i, thumbGrid, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
                var img = DesignKit.Box(cell, DesignKit.AccentSoft2, 50f);
                var tex = new Texture2D(2, 2);
                if (tex.LoadImage(photos[i].bytes))
                {
                    var raw = UiKit.Rect("Img", cell, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero).gameObject.AddComponent<RawImage>();
                    raw.texture = tex;
                    float a = (float)tex.width / tex.height;                                   // cover crop
                    raw.uvRect = a > 1 ? new Rect((1 - 1 / a) / 2, 0, 1 / a, 1) : new Rect(0, (1 - a) / 2, 1, a);
                    var mask = cell.gameObject.AddComponent<Mask>(); mask.showMaskGraphic = true;
                }
                if (i == 0)
                {
                    var tag = DesignKit.Pill("Tag", cell, "대표", DesignKit.Accent, Color.white, out var tagText, 110);
                    tag.anchorMin = tag.anchorMax = new Vector2(0, 1); tag.pivot = new Vector2(0, 1);
                    tag.anchoredPosition = new Vector2(16, -16); tag.sizeDelta = new Vector2(110, 52); tagText.fontSize = 26;
                }
            }
            confirmCount.text = $"선택한 사진 {photos.Count}장 · 최대 {ApiV1Routes.MaxPhotos}장";
            Show(Screen.Confirm);
        }

        void EnterAR()
        {
            instructionDismissed = false;   // a new AR session starts with the hint visible
            bottomHidden = false;           // ...and with the buttons visible
            Show(Screen.AR);
            SetPlaced(HasPlacedModel());
        }

        void PlaceAtCenter()
        {
            if (centerPlacer == null || !centerPlacer.PlaceAtCenter())
                StartCoroutine(Toast("화면 가운데에 바닥이 없어요. 폰을 조금 움직여 보세요.", 3f));
        }

        void ResetPlacement()
        {
            // remove the placed model, then wait for a new tap
            ClearPlacedModels();
            SetPlaced(false);
        }

        IEnumerator SavePhoto()
        {
            canvas.gameObject.SetActive(false);
            yield return new WaitForEndOfFrame();
            var shot = ScreenCapture.CaptureScreenshotAsTexture();
            canvas.gameObject.SetActive(true);
            byte[] png = shot.EncodeToPNG(); Destroy(shot);
            string name = $"Beside_{System.DateTime.Now:yyyyMMdd_HHmmss}.png";
#if BESIDE_NATIVEGALLERY && (UNITY_ANDROID || UNITY_IOS) && !UNITY_EDITOR
            NativeGallery.SaveImageToGallery(png, "Beside", name);
            StartCoroutine(Toast("갤러리에 저장했어요", 2.5f));
#else
            string dir = Path.Combine(Application.persistentDataPath, "photos"); Directory.CreateDirectory(dir);
            File.WriteAllBytes(Path.Combine(dir, name), png);
            StartCoroutine(Toast("사진을 저장했어요", 2.5f));
#endif
        }

        void ToggleFur() { furOn = !furOn; SetFur(furOn); }

        void SetFur(bool on)
        {
            furToggleLabel.text = on ? "털 표현 켜짐" : "털 표현 꺼짐";
            foreach (var fur in FindObjectsByType<ShellFurRenderer>(FindObjectsInactive.Include, FindObjectsSortMode.None)) fur.enabled = on;
        }

        void StartOver()
        {
            ClearPlacedModels();
            readyTemplate = null; placed = false; pendingPhotos.Clear();
            jobManager.ClearSavedJob();
            Show(Screen.Home);
        }

        IEnumerator Toast(string text, float seconds)
        {
            arToast.text = text;
            var go = arToast.transform.parent.gameObject;
            go.SetActive(true);
            yield return new WaitForSeconds(seconds);
            go.SetActive(false);
        }

        // ---------- helpers ----------

        void FillStats(GameObject template, string path)
        {
            long tris = 0;
            foreach (var mf in template.GetComponentsInChildren<MeshFilter>(true))
                if (mf.sharedMesh != null) for (int s = 0; s < mf.sharedMesh.subMeshCount; s++) tris += (long)mf.sharedMesh.GetIndexCount(s) / 3;
            var box = template.GetComponentInChildren<BoxCollider>(true);
            float height = box != null ? box.size.y * box.transform.lossyScale.y : 0f;
            statSize.text = height > 0 ? $"높이 {Mathf.RoundToInt(height * 100)}cm" : "–";
            statTris.text = tris.ToString("N0");
            try { statFile.text = $"{new FileInfo(path).Length / 1024f / 1024f:0.0} MB"; } catch { statFile.text = "–"; }
            arModelName.text = statSize.text == "–" ? "우리 아이" : "우리 아이 · " + statSize.text;
        }

        static string Short(string id) => id.Length > 8 ? id.Substring(0, 8) : id;

        void HideTemplateUI()
        {
            var names = new HashSet<string>(hideTemplateObjects);
            foreach (var t in FindObjectsByType<Transform>(FindObjectsInactive.Include, FindObjectsSortMode.None))
                if (names.Contains(t.name) && t.gameObject.scene.IsValid()) t.gameObject.SetActive(false);
        }

        /// <summary>Rotates the progress ring.</summary>
        class Spinner : MonoBehaviour
        {
            public RectTransform target;
            void Update() { if (target != null) target.Rotate(0, 0, -180f * Time.deltaTime); }
        }
    }
}
