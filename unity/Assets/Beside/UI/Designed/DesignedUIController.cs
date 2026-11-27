using System.Collections;
using System.Collections.Generic;
using System.IO;
using Beside.Api;
using Beside.AR;
using Beside.Job;
using Beside.Library;
using Beside.Metrics;
using Beside.Pet;
using UnityEngine;
using UnityEngine.UI;

namespace Beside.UI.Designed
{
    /// <summary>
    /// The designed screens (시안 E of "Beside 앱 UI 개선 시안": light gray page, white cards, indigo accent), built from
    /// code with DesignKit. Home → Confirm → Progress → Preview → AR (scan / place / placed), Error and Settings.
    /// Uses the same JobManager / PhotoPicker / ModelPreview / ARGuidance as the basic UI.
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
        [Tooltip("Korean regular face. Empty = Noto Sans KR Regular from Resources/BesideUI/Fonts.")]
        [SerializeField] private Font font;
        [Tooltip("Bold face paired with Font. Empty = Noto Sans KR Bold (synthesized bold when only Font is set).")]
        [SerializeField] private Font fontBold;

        [Header("Home")]
        [Tooltip("Offer the saved job on the home screen (\"만들던 모델이 있어요\") instead of continuing it as soon as the app starts.")]
        [SerializeField] private bool offerResumeOnHome = true;

        [Header("AR messages")]
        [Tooltip("Show the hint under the status at the top of the AR screen. The user can also close it with ×.")]
        [SerializeField] private bool showARInstructions = true;

        [Tooltip("After the user closes the hint, show it again when the message changes (e.g. after placing).")]
        [SerializeField] private bool reshowOnNewMessage = false;

        [Header("AR scanning")]
        [Tooltip("Find floors only after \"AR로 배치하기\". Other screens pause plane detection so random surfaces seen earlier don't get in the way.")]
        [SerializeField] private bool scanOnlyInAR = true;

        [Header("Template UI")]
        [Tooltip("Template UI objects to hide so they don't overlap this UI. Coaching UI stays.")]
        [SerializeField] private string[] hideTemplateObjects = { "Create Button", "Delete Button", "Options Button", "Options Modal", "Object Menu Animator", "Greeting Prompt", "DebugMenu" };

        enum Page { Home, Confirm, Progress, Preview, AR, Error, Settings, Library, Camera }

        // 시안 px × 2.6 in the 1080×1920 reference
        const int Margin = 42;        // 16 px page margin
        const int TopInset = 135;     // 52 px: below the status bar
        const int BarHeight = 114;    // 44 px top bar
        const int BottomInset = 90;
        const float HintPadX = 36f, HintCloseW = 88f;

        Canvas canvas;
        RectTransform home, confirm, progress, previewScreen, arOverlay, error, settings;
        // home
        RectTransform resumeCard; RawImage resumeThumb; Text resumeSub;
        Image serverDot; Text serverStatus; float healthCheckedAt; bool healthBusy, healthKnown, healthWanted;
        int healthGen;   // bumped when the server address changes; health answers of an older generation are dropped
        // settings
        InputField urlInput; Text settingsStatus;
        // confirm
        RectTransform thumbGrid; Text confirmCount; List<Photo> pendingPhotos = new List<Photo>();
        Button confirmGoButton; Text confirmGoLabel; Text confirmAgainLabel; bool confirmFromCamera;
        // camera ("지금 촬영하기"): live camera with a shutter button
        RectTransform cameraPage; bool capturing;
        readonly List<Renderer> hiddenPetRenderers = new List<Renderer>();
        readonly List<Behaviour> hiddenPetFur = new List<Behaviour>();
        const string GoButtonText = "이 사진으로 만들기";
        readonly List<Texture2D> thumbTextures = new List<Texture2D>();   // destroyed with the thumbnails (not freed by Destroy(gameObject))
        // the job's first photo, shown on the home card, the progress card and the AR sheet
        Texture2D pendingHero, heroTex; string heroJobId; bool heroUnsaved;
        // progress
        struct StepView { public GameObject done, active, pending; public Text label, right; }
        RawImage progressPhoto; Text progressPill; StepView[] steps; int activeStep; string elapsedText = "";
        // preview
        RawImage previewImage; Text statSize, statTris, statFile, furToggleLabel; GameObject readyTemplate; bool furOn = true;
        // AR
        Text arPill, arHint, arModelSize; Image arPillDot; RectTransform arHintCard, arSheet, arShowButton, arToast, arAnimRow;
        RawImage arThumb; GameObject arPlaceButton, arTiles; Text arToastText; float toastUntil; bool placed, hintDirty;
        Text arName; Texture2D arThumbTex; string currentEntryId;   // the saved model on screen (null = not saved)
        // saved models ("저장한 강아지", ModelLibrary)
        RectTransform library, libList, libFullNotice, homeLibCard; Text libSub, homeLibSub;
        readonly List<Texture2D> libThumbs = new List<Texture2D>();
        Page libReturnPage = Page.Home; bool libDeleteMode, libLoading;
        string pendingSaveJobId, pendingSavePath; byte[] pendingSavePhoto; int pendingSaveAutoCm;   // a new model waiting for a free slot
        // a toast for the pages other than AR, and a dialog over everything
        RectTransform pageToast, dialogRoot, dialogCard; Text pageToastText; float pageToastUntil;
        // error
        Text errorTitle, errorBody, errorCode, errorStage, errorRetry, errorRetryLabel; Button errorRetryButton;
        const string RetryButtonText = "같은 사진으로 다시 시도";

        SpawnLimiter limiter;
        PlaneScanGate scanGate;
        bool instructionDismissed;
        string highlightedClip; float chipSyncHoldUntil;
        PetAnimationPlayer petAnim; readonly Dictionary<string, (Image bg, Text label)> animChips = new Dictionary<string, (Image, Text)>();
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
            scanGate = GetComponent<PlaneScanGate>() ?? gameObject.AddComponent<PlaneScanGate>();
            petAnim = FindFirstObjectByType<PetAnimationPlayer>() ?? gameObject.AddComponent<PetAnimationPlayer>();
            spawner = FindFirstObjectByType<UnityEngine.XR.Interaction.Toolkit.Samples.StarterAssets.ObjectSpawner>();
            if (modelLoader == null) modelLoader = FindFirstObjectByType<RuntimeModelLoader>();
            if (jobManager == null) { Debug.LogError("[DesignedUI] JobManager missing"); enabled = false; return; }
        }

        void BuildOnce()
        {
            if (canvas != null) return;
            DesignKit.LoadFonts(font, fontBold);
            if (preview != null) preview.SetBackground(DesignKit.CardBg);   // the 3D view sits on a white card
            Build();
            HideTemplateUI();
            Show(Page.Home);
        }

        void OnEnable()
        {
            if (jobManager == null) return;
            if (offerResumeOnHome) jobManager.ResumeOnStart = false;   // before JobManager.Start: the home card offers it
            BuildOnce();                    // Awake runs even when unchecked, so build here
            canvas.gameObject.SetActive(true);
            if (scanOnlyInAR && scanGate != null && !arOverlay.gameObject.activeSelf) scanGate.StopScan();
            jobManager.StateChanged += OnStateChanged;
            jobManager.JobFailed += OnJobFailed;
            jobManager.ModelReady += OnModelReady;
            jobManager.ModelPlaced += OnModelPlaced;
            if (guidance != null) guidance.GuidanceChanged += OnGuidance;
            if (modelLoader != null) modelLoader.ModelReady += OnLoaderReady;
        }

        void OnDisable()
        {
            if (jobManager == null) return;
            jobManager.StateChanged -= OnStateChanged;
            jobManager.JobFailed -= OnJobFailed;
            jobManager.ModelReady -= OnModelReady;
            jobManager.ModelPlaced -= OnModelPlaced;
            if (guidance != null) guidance.GuidanceChanged -= OnGuidance;
            if (modelLoader != null) modelLoader.ModelReady -= OnLoaderReady;
            if (canvas != null) canvas.gameObject.SetActive(false);
            if (scanOnlyInAR && scanGate != null) scanGate.BeginScan(false);   // hand normal scanning back (e.g. basic UI)
        }

        void Update()
        {
            // the health check waits for Update: JobManager may not have run Awake when the home screen is first built
            if (healthWanted && home.gameObject.activeSelf) { healthWanted = false; RefreshServerStatus(false); }

            // real generation takes minutes and has no progress %, so the photo pill shows elapsed time
            if (progress.gameObject.activeSelf && jobManager.CurrentState == JobManager.State.Waiting)
            {
                int sec = Mathf.FloorToInt(jobManager.ElapsedSeconds);
                string t = sec < 60 ? $"{sec}초" : $"{sec / 60}분 {sec % 60:00}초";
                if (t != elapsedText) { elapsedText = t; RefreshProgressPill(); }
            }

            if (error.gameObject.activeSelf) SyncRetryWait();
            if (confirm.gameObject.activeSelf) SyncGoWait();
            if (arToast.gameObject.activeSelf && Time.unscaledTime > toastUntil) arToast.gameObject.SetActive(false);
            if (pageToast.gameObject.activeSelf && Time.unscaledTime > pageToastUntil) pageToast.gameObject.SetActive(false);

            if (!arOverlay.gameObject.activeSelf) return;

            // The buttons follow what is actually on the floor, however it got there (tap, center button, reset)
            bool hasModel = HasPlacedModel();
            if (hasModel != placed) SetPlaced(hasModel);
            SyncChipWithPet();

            if (!placed && guidance != null)
            {
                bool plane = guidance.PlaneDetected;
                SetStatus(plane ? "바닥 인식됨" : "바닥 찾는 중", plane ? DesignKit.Success : DesignKit.Warning);
                SetHint(plane ? "흰 점이 보이는 바닥을 터치하면 그 자리에 놓여요" : "폰을 좌우로 천천히 움직여 바닥을 비춰 주세요");
            }
            if (hintDirty) FitHint();
        }

        // =====================================================================
        // AR state
        // =====================================================================

        void PlayAction(string clip)
        {
            if (petAnim == null) return;
            petAnim.Play(clip);
            HighlightChip(clip);
            chipSyncHoldUntil = Time.unscaledTime + petAnim.fadeSeconds + 0.15f;   // let the cross-fade finish before syncing
        }

        /// <summary>Keeps the highlighted chip equal to what the pet is really doing (new placement, one-shot ending...).</summary>
        void SyncChipWithPet()
        {
            if (petAnim == null || !placed || !arAnimRow.gameObject.activeInHierarchy) return;
            if (Time.unscaledTime < chipSyncHoldUntil) return;
            string playing = petAnim.PlayingClip;
            if (playing != null && playing != highlightedClip) HighlightChip(playing);
        }

        void HighlightChip(string clip)
        {
            highlightedClip = clip ?? "Idle";
            foreach (var kv in animChips)
            {
                bool on = kv.Key == highlightedClip;
                kv.Value.bg.color = on ? DesignKit.CardBg : Color.clear;
                kv.Value.label.color = on ? DesignKit.Ink : DesignKit.InkSoft;
                DesignKit.SetBold(kv.Value.label, on);
            }
        }

        void ToggleBottomPanels()
        {
            bottomHidden = !bottomHidden;
            ApplyBottomPanels();
        }

        /// <summary>Shows the bottom sheet for the placement state, unless the user hid it.</summary>
        void ApplyBottomPanels()
        {
            arSheet.gameObject.SetActive(!bottomHidden);
            arShowButton.gameObject.SetActive(bottomHidden);
            arPlaceButton.SetActive(!placed);
            arTiles.SetActive(placed);
            arAnimRow.gameObject.SetActive(placed && petAnim != null && petAnim.HasAnyAnimation);
            HighlightChip(petAnim != null ? petAnim.Current : null);
        }

        void DismissInstruction()
        {
            instructionDismissed = true;
            arHintCard.gameObject.SetActive(false);
        }

        void RefreshInstructionCard() => arHintCard.gameObject.SetActive(showARInstructions && !instructionDismissed);

        void SetStatus(string text, Color dot)
        {
            if (arPill.text != text) arPill.text = text;
            arPillDot.color = dot;
        }

        void SetHint(string text)
        {
            if (arHint.text == text) return;
            arHint.text = text;
            hintDirty = true;
        }

        /// <summary>The hint is as wide as its text, up to the screen width, then wraps.</summary>
        void FitHint()
        {
            float maxW = arOverlay.rect.width - 2 * Margin;
            if (maxW <= 0f) return;
            hintDirty = false;
            float textW = Mathf.Min(arHint.preferredWidth + 2f, maxW - HintPadX - HintCloseW);
            var settings = arHint.GetGenerationSettings(new Vector2(textW, 0f));
            float textH = arHint.cachedTextGeneratorForLayout.GetPreferredHeight(arHint.text, settings) / arHint.pixelsPerUnit;
            arHintCard.sizeDelta = new Vector2(textW + HintPadX + HintCloseW, Mathf.Max(96f, textH + 46f));
        }

        void SetPlaced(bool value)
        {
            if (value != placed && reshowOnNewMessage) instructionDismissed = false;
            if (value != placed && petAnim != null) { petAnim.ResetToIdle(); chipSyncHoldUntil = 0f; }
            placed = value;
            RefreshInstructionCard();
            ApplyBottomPanels();
            if (value)
            {
                SetStatus("배치됨", DesignKit.Success);
                SetHint("한 손가락으로 옮기고, 두 손가락으로 돌리거나 크기를 바꿀 수 있어요");
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
            BuildCamera(root);
            BuildError(root);
            BuildSettings(root);
            BuildLibrary(root);
            BuildPageToast(root);
            BuildDialog(root);   // last: over every page
        }

        static RectTransform R(string name, Transform parent) => UiKit.Rect(name, parent, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);

        static void Fill(Transform row) => DesignKit.Flex(R("Fill", row));

        /// <summary>Gray page: a scrolling body, and a bottom area of fixed height for the main buttons (0 = none).</summary>
        static RectTransform BuildPage(string name, Transform root, float bottomHeight, out RectTransform body, out RectTransform bottom)
        {
            var screen = DesignKit.Screen(name, root, DesignKit.Ground);
            var holder = UiKit.Rect("Body", screen, Vector2.zero, Vector2.one, new Vector2(0, bottomHeight), Vector2.zero);
            body = DesignKit.ScrollColumn("Scroll", holder, new RectOffset(Margin, Margin, TopInset, 40), 36f);
            bottom = null;
            if (bottomHeight > 0)
            {
                bottom = DesignKit.Column("Bottom", screen, Vector2.zero, new Vector2(1, 0), Vector2.zero, new Vector2(0, bottomHeight), 16f, 0);
                var v = bottom.GetComponent<VerticalLayoutGroup>();
                v.padding = new RectOffset(Margin, Margin, 20, BottomInset);
                v.childAlignment = TextAnchor.LowerCenter;
            }
            return screen;
        }

        /// <summary>Big page title and the gray line under it. Returns the gray line.</summary>
        static Text TitleBlock(Transform body, string title, string sub)
        {
            var block = R("TitleBlock", body);
            var v = UiKit.Column(block, 12f, 0);
            v.padding = new RectOffset(20, 20, 0, 18);
            DesignKit.Label("Title", block, title, 83, DesignKit.Ink, 0, TextAnchor.MiddleLeft, true);
            return DesignKit.Label("Sub", block, sub, 39, DesignKit.InkSoft, 0);
        }

        static Button BackButton(Transform bar, System.Action onClick)
            => DesignKit.IconButton("Back", bar, "chevron_left", 104, 57, DesignKit.CardBg, DesignKit.Ink, onClick);

        void BuildHome(Transform root)
        {
            home = BuildPage("Home", root, 0, out var body, out _);
            body.GetComponent<VerticalLayoutGroup>().padding = new RectOffset(Margin, Margin, 240, BottomInset);
            TitleBlock(body, "Beside", "사진으로 만드는 우리 아이 3D");

            var card = DesignKit.Card("New", body, 62f, 52, 52, 10f);
            DesignKit.Label("Title", card, "새로 만들기", 47, DesignKit.Ink, 0, TextAnchor.MiddleLeft, true);
            DesignKit.Label("Sub", card, "얼굴과 몸이 잘 보이는 사진을 골라 주세요", 36, DesignKit.InkSoft, 0);
            DesignKit.Gap(card, 22);
            var tiles = DesignKit.Row("Tiles", card, 240, 26);
            DesignKit.Tile("Gallery", tiles, "gallery", "갤러리에서 고르기", DesignKit.Accent, Color.white, PickFromGallery);
            DesignKit.Tile("Shoot", tiles, "camera", "지금 촬영하기", DesignKit.Ground, DesignKit.Ink, OpenCamera);

            BuildResumeCard(body);
            BuildLibraryCard(body);

            var ex = DesignKit.Card("Examples", body, 62f, 52, 52, 0f);
            DesignKit.Label("Title", ex, "이런 사진이 잘 나와요", 42, DesignKit.Ink, 0, TextAnchor.MiddleLeft, true);
            DesignKit.Gap(ex, 31);
            var photos = DesignKit.Row("Photos", ex, 0, 21);
            Example(photos, "example_1", "몸 전체가 보여요", true);
            Example(photos, "example_2", "밝고 선명해요", true);
            Example(photos, "example_3", "몸이 잘렸어요", false);

            DesignKit.Gap(body, 4);
            BuildServerRow(body);
        }

        /// <summary>"만들던 모델이 있어요": the job saved from an earlier run, continued with a tap.</summary>
        void BuildResumeCard(Transform body)
        {
            resumeCard = R("Resume", body);
            DesignKit.MakeButton(resumeCard, DesignKit.Box(resumeCard, DesignKit.CardBg, 62f), ResumeSaved);
            var h = UiKit.Row(resumeCard, 36f);
            h.padding = new RectOffset(52, 42, 42, 42);
            h.childForceExpandWidth = false; h.childForceExpandHeight = false; h.childAlignment = TextAnchor.MiddleLeft;

            var thumb = R("Thumb", resumeCard);
            DesignKit.MaskBox(thumb, DesignKit.AccentSoft, 36f);
            DesignKit.Fixed(thumb, 146, 146);
            DesignKit.Icon("cube", thumb, 68, DesignKit.Accent);          // shows when there is no photo
            resumeThumb = DesignKit.CoverImage(thumb);
            resumeThumb.enabled = false;

            var texts = R("Texts", resumeCard);
            UiKit.Column(texts, 8f, 0).childAlignment = TextAnchor.MiddleLeft;
            DesignKit.Flex(texts);
            DesignKit.Label("Title", texts, "만들던 모델이 있어요", 42, DesignKit.Ink, 0, TextAnchor.MiddleLeft, true);
            resumeSub = DesignKit.Label("Sub", texts, "", 34, DesignKit.InkSoft, 0);

            DesignKit.Icon("chevron_right", resumeCard, 52, DesignKit.InkMuted);
        }

        /// <summary>"저장한 강아지 n/5": opens the saved models.</summary>
        void BuildLibraryCard(Transform body)
        {
            homeLibCard = R("Library", body);
            DesignKit.MakeButton(homeLibCard, DesignKit.Box(homeLibCard, DesignKit.CardBg, 62f), () => OpenLibrary(Page.Home));
            var h = UiKit.Row(homeLibCard, 36f);
            h.padding = new RectOffset(52, 42, 42, 42);
            h.childForceExpandWidth = false; h.childForceExpandHeight = false; h.childAlignment = TextAnchor.MiddleLeft;

            var icon = R("Icon", homeLibCard);
            DesignKit.Box(icon, DesignKit.AccentSoft, 36f);
            DesignKit.Fixed(icon, 146, 146);
            DesignKit.Icon("cube", icon, 68, DesignKit.Accent);

            var texts = R("Texts", homeLibCard);
            UiKit.Column(texts, 8f, 0).childAlignment = TextAnchor.MiddleLeft;
            DesignKit.Flex(texts);
            DesignKit.Label("Title", texts, "저장한 강아지", 42, DesignKit.Ink, 0, TextAnchor.MiddleLeft, true);
            homeLibSub = DesignKit.Label("Sub", texts, "", 34, DesignKit.InkSoft, 0);

            DesignKit.Icon("chevron_right", homeLibCard, 52, DesignKit.InkMuted);
        }

        static void Example(Transform row, string photo, string caption, bool good)
        {
            var fig = R("Example_" + photo, row);
            UiKit.Column(fig, 16f, 0);
            DesignKit.Equal(fig);
            var pic = R("Pic", fig);
            DesignKit.MaskBox(pic, DesignKit.Ground, 31f);
            UiKit.Height(pic, 260);
            DesignKit.SetCover(DesignKit.CoverImage(pic), DesignKit.Photo(photo));
            var badge = UiKit.Rect("Badge", pic, Vector2.one, Vector2.one, new Vector2(-68, -68), new Vector2(-16, -16));
            DesignKit.Circle(badge, good ? DesignKit.Success : DesignKit.Danger);
            DesignKit.Icon(good ? "check" : "close", badge, good ? 31 : 29, Color.white);
            DesignKit.Label("Caption", fig, caption, 31, DesignKit.InkSoft, 0);
        }

        void BuildServerRow(Transform body)
        {
            var rt = R("Server", body);
            DesignKit.MakeButton(rt, DesignKit.Box(rt, DesignKit.CardBg, 47f), OpenSettings);
            UiKit.Height(rt, 146);
            var h = UiKit.Row(rt, 31f);
            h.padding = new RectOffset(52, 42, 0, 0);
            h.childForceExpandWidth = false; h.childForceExpandHeight = false; h.childAlignment = TextAnchor.MiddleLeft;
            DesignKit.Icon("server", rt, 52, DesignKit.InkSoft);
            DesignKit.Flex(DesignKit.Text("Label", rt, "서버 설정", 39, DesignKit.Ink));
            var state = DesignKit.Row("State", rt, 0, 16, false);
            var dot = R("Dot", state);
            serverDot = DesignKit.Circle(dot, DesignKit.InkMuted);
            DesignKit.Fixed(dot, 18, 18);
            serverStatus = DesignKit.Text("Status", state, "", 34, DesignKit.InkSoft);
            serverStatus.horizontalOverflow = HorizontalWrapMode.Overflow;
            DesignKit.Icon("chevron_right", rt, 47, DesignKit.InkMuted);
        }

        void BuildConfirm(Transform root)
        {
            confirm = BuildPage("Confirm", root, 436, out var body, out var bottom);
            var bar = DesignKit.Row("TopBar", body, BarHeight, 16, false);
            BackButton(bar, () => Show(Page.Home));
            confirmCount = TitleBlock(body, "사진 확인", "");

            var card = DesignKit.Card("Photos", body, 62f, 42, 42, 0f);
            thumbGrid = R("Thumbs", card);
            var grid = thumbGrid.gameObject.AddComponent<GridLayoutGroup>();
            grid.cellSize = new Vector2(240, 240); grid.spacing = new Vector2(21, 21);
            grid.constraint = GridLayoutGroup.Constraint.FixedColumnCount; grid.constraintCount = 3;
            thumbGrid.gameObject.AddComponent<DesignKit.GridAutoCell>().columns = 3;

            var tips = DesignKit.Card("Tips", body, 62f, 52, 46, 24f);
            DesignKit.Label("Title", tips, "좋은 결과를 위한 사진", 42, DesignKit.Ink, 0, TextAnchor.MiddleLeft, true);
            Tip(tips, true, "얼굴과 몸 전체가 한 장에 들어와요");
            Tip(tips, true, "밝은 곳에서 찍은 선명한 사진이 좋아요");
            Tip(tips, false, "여러 마리가 함께 있거나 가려진 사진은 피해 주세요");

            DesignKit.Label("Limits", bottom, "JPG · PNG · WEBP, 장당 5MB 이하", 31, DesignKit.InkMuted, 44, TextAnchor.MiddleCenter);
            confirmGoButton = DesignKit.PrimaryButton("Go", bottom, GoButtonText, StartWithPending);
            confirmGoLabel = confirmGoButton.GetComponentInChildren<Text>();
            confirmAgainLabel = DesignKit.GhostButton("Again", bottom, "다시 고르기",
                () => { if (confirmFromCamera) OpenCamera(); else PickFromGallery(); }).GetComponentInChildren<Text>();
        }

        static void Tip(Transform card, bool good, string text)
        {
            var row = DesignKit.Row("Tip", card, 0, 26, false);
            var mark = R("Mark", row);
            DesignKit.Circle(mark, good ? DesignKit.Success : DesignKit.Danger);
            DesignKit.Fixed(mark, 52, 52);
            DesignKit.Icon(good ? "check" : "close", mark, good ? 31 : 29, Color.white);
            DesignKit.Flex(DesignKit.Text("Text", row, text, 36, DesignKit.Ink));
        }

        void BuildProgress(Transform root)
        {
            progress = BuildPage("Progress", root, 210, out var body, out var bottom);
            var bar = DesignKit.Row("TopBar", body, BarHeight, 0, false);
            Fill(bar);
            DesignKit.TextButton("Cancel", bar, "취소", 39, DesignKit.InkSoft, CancelProgress, 150);
            TitleBlock(body, "만드는 중", "보통 2~3분 걸려요");

            var card = DesignKit.Card("Job", body, 62f, 0, 0, 0f);
            card.gameObject.AddComponent<Mask>().showMaskGraphic = true;   // the photo follows the card's round corners
            var photo = R("Photo", card);
            UiKit.Height(photo, 510);
            photo.gameObject.AddComponent<Image>().color = DesignKit.AccentSoft;
            photo.gameObject.AddComponent<RectMask2D>();                    // the covering image must not spill onto the steps
            DesignKit.Icon("cube", photo, 130, DesignKit.Accent);
            progressPhoto = DesignKit.CoverImage(photo);
            progressPhoto.enabled = false;
            var pill = DesignKit.Pill("Pill", photo, DesignKit.CardBg, DesignKit.Ink, 34, true, 78, out progressPill, out _);
            pill.anchorMin = pill.anchorMax = Vector2.zero; pill.pivot = Vector2.zero;
            pill.anchoredPosition = new Vector2(36, 36);

            var list = R("Steps", card);
            UiKit.Column(list, 0f, 0).padding = new RectOffset(52, 52, 21, 31);
            string[] names = { "사진 올리기", "3D 모델 만들기", "모델 받기", "AR 준비" };
            steps = new StepView[names.Length];
            for (int i = 0; i < names.Length; i++) steps[i] = Step(list, names[i]);

            var tip = DesignKit.Card("Tip", body, 62f, 52, 47, 16f);
            DesignKit.Label("Title", tip, "기다리는 동안", 34, DesignKit.Accent, 0, TextAnchor.MiddleLeft, true);
            DesignKit.Label("Body", tip, "AR은 무늬가 적고 밝은 바닥에서 잘 인식돼요. 배치한 뒤 두 손가락으로 크기를 바꿀 수 있어요.", 36, DesignKit.Ink, 0).lineSpacing = 1.1f;

            DesignKit.Label("Note", bottom, "앱을 닫아도 계속 만들어요", 34, DesignKit.InkSoft, 60, TextAnchor.MiddleCenter);
        }

        static StepView Step(Transform list, string name)
        {
            var row = DesignKit.Row("Step", list, 125, 31, false);
            var mark = R("Mark", row);
            DesignKit.Fixed(mark, 57, 57);
            var s = new StepView();

            var done = R("Done", mark);
            DesignKit.Circle(done, DesignKit.AccentSoft);
            DesignKit.Icon("check", done, 34, DesignKit.Accent);
            s.done = done.gameObject;

            var active = R("Active", mark);                                 // light ring + growing, turning arc
            DesignKit.Ring(active, DesignKit.AccentSoft);
            DesignKit.Spinner.Build(R("Arc", active), DesignKit.Accent);
            s.active = active.gameObject;

            var pending = R("Pending", mark);
            DesignKit.Ring(pending, DesignKit.Line);
            s.pending = pending.gameObject;

            s.label = DesignKit.Text("Label", row, name, 39, DesignKit.Ink);
            DesignKit.Flex(s.label);
            s.right = DesignKit.Text("Right", row, "", 34, DesignKit.InkSoft, TextAnchor.MiddleRight);
            s.right.horizontalOverflow = HorizontalWrapMode.Overflow;
            return s;
        }

        void BuildPreview(Transform root)
        {
            previewScreen = DesignKit.Screen("PreviewScreen", root, DesignKit.Ground);
            var col = DesignKit.Column("Col", previewScreen, Vector2.zero, Vector2.one, new Vector2(Margin, BottomInset), new Vector2(-Margin, -TopInset), 36f, 0);

            var bar = DesignKit.Row("TopBar", col, BarHeight, 0, false);
            Fill(bar);
            var fur = DesignKit.RoundedButton("Fur", bar, "털 표현 켜짐", DesignKit.CardBg, DesignKit.InkSoft, ToggleFur, 92, 34, false, 46f);
            DesignKit.Fixed(fur, 300, 92);
            furToggleLabel = fur.GetComponentInChildren<Text>();
            TitleBlock(col, "미리보기", "드래그해서 돌려 보세요");

            var view = R("View", col);
            DesignKit.Box(view, DesignKit.CardBg, 62f);
            var vle = view.gameObject.AddComponent<LayoutElement>(); vle.flexibleHeight = 1; vle.minHeight = 400; vle.preferredHeight = 400;
            view.gameObject.AddComponent<UiKit.DragForwarder>().onDragX = dx => { if (preview != null) preview.Drag(dx); };
            var inner = UiKit.Rect("Inner", view, Vector2.zero, Vector2.one, new Vector2(24, 24), new Vector2(-24, -24));
            var imgRt = R("Image", inner);
            previewImage = imgRt.gameObject.AddComponent<RawImage>();
            previewImage.raycastTarget = false;
            previewImage.texture = preview != null ? preview.Texture : null;
            var fit = imgRt.gameObject.AddComponent<AspectRatioFitter>();
            fit.aspectMode = AspectRatioFitter.AspectMode.FitInParent; fit.aspectRatio = 1f;   // the preview texture is square

            var stats = DesignKit.Row("Stats", col, 0, 26);
            statSize = Stat(stats, "높이"); statTris = Stat(stats, "폴리곤"); statFile = Stat(stats, "파일");

            DesignKit.PrimaryButton("ToAR", col, "AR로 배치하기", EnterAR);
            DesignKit.GhostButton("Remake", col, "다른 사진으로 다시 만들기", StartOver);
        }

        static Text Stat(Transform row, string title)
        {
            var card = DesignKit.Card(title, row, 42f, 36, 31, 4f);
            DesignKit.Equal(card);
            DesignKit.Label("T", card, title, 31, DesignKit.InkSoft, 0);
            return DesignKit.Label("V", card, "–", 40, DesignKit.Ink, 0, TextAnchor.MiddleLeft, true);
        }

        void BuildAR(Transform root)
        {
            arOverlay = R("AR", root);   // transparent: camera shows through
            float barY = -TopInset - BarHeight / 2f;

            var back = BackButton(arOverlay, () => Show(Page.Preview));
            var brt = (RectTransform)back.transform;
            brt.anchorMin = brt.anchorMax = new Vector2(0, 1); brt.pivot = new Vector2(0, 0.5f);
            brt.anchoredPosition = new Vector2(Margin, barY);
            DesignKit.AddShadow(back.targetGraphic, 4f);

            var pill = DesignKit.Pill("Status", arOverlay, DesignKit.CardBg, DesignKit.Ink, 34, true, 83, out arPill, out arPillDot, withDot: true);
            pill.anchorMin = pill.anchorMax = new Vector2(0.5f, 1); pill.pivot = new Vector2(0.5f, 0.5f);
            pill.anchoredPosition = new Vector2(0, barY);
            DesignKit.AddShadow(pill.GetComponent<Image>(), 4f);

            // hint under the status, as wide as its text (FitHint)
            arHintCard = UiKit.Rect("Hint", arOverlay, new Vector2(0.5f, 1), new Vector2(0.5f, 1), Vector2.zero, Vector2.zero);
            arHintCard.pivot = new Vector2(0.5f, 1); arHintCard.anchoredPosition = new Vector2(0, -276); arHintCard.sizeDelta = new Vector2(800, 96);
            DesignKit.AddShadow(DesignKit.Box(arHintCard, DesignKit.Glass, 31f), 3f);
            arHint = DesignKit.Text("Text", arHintCard, "", 34, DesignKit.Ink);
            arHint.rectTransform.offsetMin = new Vector2(HintPadX, 0); arHint.rectTransform.offsetMax = new Vector2(-HintCloseW, 0);
            var close = DesignKit.IconButton("Close", arHintCard, "close_thin", 72, 36, Color.clear, DesignKit.InkSoft, DismissInstruction, 36f);
            var crt = (RectTransform)close.transform;
            crt.anchorMin = crt.anchorMax = new Vector2(1, 0.5f); crt.pivot = new Vector2(1, 0.5f); crt.anchoredPosition = new Vector2(-8, 0);

            // bottom sheet: model row, then "가운데에 놓기" before placement, or actions + buttons after
            arSheet = DesignKit.Card("Sheet", arOverlay, 73f, 42, 42, 34f);
            arSheet.anchorMin = Vector2.zero; arSheet.anchorMax = new Vector2(1, 0); arSheet.pivot = new Vector2(0.5f, 0);
            arSheet.offsetMin = new Vector2(31, 80); arSheet.offsetMax = new Vector2(-31, 80);
            arSheet.gameObject.AddComponent<ContentSizeFitter>().verticalFit = ContentSizeFitter.FitMode.PreferredSize;
            DesignKit.AddShadow(arSheet.GetComponent<Image>(), 5f);

            var head = DesignKit.Row("Head", arSheet, 104, 26, false);
            var thumb = R("Thumb", head);
            DesignKit.MaskBox(thumb, DesignKit.AccentSoft, 26f);
            DesignKit.Fixed(thumb, 94, 94);
            DesignKit.Icon("cube", thumb, 52, DesignKit.Accent);
            arThumb = DesignKit.CoverImage(thumb);
            arThumb.enabled = false;
            arName = DesignKit.Text("Name", head, "우리 아이", 42, DesignKit.Ink, TextAnchor.MiddleLeft, true);
            arName.horizontalOverflow = HorizontalWrapMode.Overflow;
            arModelSize = DesignKit.Text("Size", head, "", 34, DesignKit.InkSoft);
            DesignKit.Flex(arModelSize);
            DesignKit.IconButton("Hide", head, "chevron_down", 104, 47, DesignKit.Ground, DesignKit.InkSoft, ToggleBottomPanels, 31f);

            arPlaceButton = DesignKit.PrimaryButton("Center", arSheet, "화면 가운데에 바로 놓기", PlaceAtCenter).gameObject;

            // pet actions (only when the placed model has clips)
            arAnimRow = DesignKit.Row("Actions", arSheet, 114, 5);
            DesignKit.Box(arAnimRow, DesignKit.Ground, 36f);
            arAnimRow.GetComponent<HorizontalLayoutGroup>().padding = new RectOffset(10, 10, 10, 10);
            foreach (var (clip, label) in PetAnimationPlayer.Actions)
            {
                string c = clip;
                var chip = DesignKit.RoundedButton("Act_" + c, arAnimRow, label, Color.clear, DesignKit.InkSoft, () => PlayAction(c), 94, 36, false, 26f);
                DesignKit.Equal(chip);
                animChips[c] = (chip.GetComponent<Image>(), chip.GetComponentInChildren<Text>());
            }

            var tiles = DesignKit.Row("Tiles", arSheet, 187, 21);
            DesignKit.Tile("Replace", tiles, "refresh", "다시 배치", DesignKit.Ground, DesignKit.Ink, ResetPlacement, vertical: true);
            DesignKit.Tile("Load", tiles, "cube", "불러오기", DesignKit.Accent, Color.white, () => OpenLibrary(Page.AR), vertical: true);
            DesignKit.Tile("New", tiles, "plus", "새로 만들기", DesignKit.Ground, DesignKit.Ink, StartOver, vertical: true);
            arTiles = tiles.gameObject;

            // "버튼 보기": alone at the bottom while the sheet is hidden
            arShowButton = UiKit.Rect("ShowButtons", arOverlay, new Vector2(0.5f, 0), new Vector2(0.5f, 0), Vector2.zero, Vector2.zero);
            arShowButton.pivot = new Vector2(0.5f, 0); arShowButton.anchoredPosition = new Vector2(0, 80); arShowButton.sizeDelta = new Vector2(0, 110);
            var showImg = DesignKit.Box(arShowButton, DesignKit.CardBg, 55f);
            DesignKit.MakeButton(arShowButton, showImg, ToggleBottomPanels);
            DesignKit.AddShadow(showImg, 4f);
            var sh = UiKit.Row(arShowButton, 16f);
            sh.padding = new RectOffset(40, 46, 0, 0);
            sh.childForceExpandWidth = false; sh.childForceExpandHeight = false; sh.childAlignment = TextAnchor.MiddleCenter;
            arShowButton.gameObject.AddComponent<ContentSizeFitter>().horizontalFit = ContentSizeFitter.FitMode.PreferredSize;
            DesignKit.Icon("chevron_up", arShowButton, 47, DesignKit.Ink);
            DesignKit.Text("Label", arShowButton, "버튼 보기", 36, DesignKit.Ink, TextAnchor.MiddleCenter, true).horizontalOverflow = HorizontalWrapMode.Overflow;

            // toast above the sheet
            arToast = UiKit.Rect("Toast", arOverlay, Vector2.zero, new Vector2(1, 0), new Vector2(62, 700), new Vector2(-62, 700));
            arToast.pivot = new Vector2(0.5f, 0);
            DesignKit.Box(arToast, DesignKit.ToastBg, 40f).raycastTarget = false;
            UiKit.Column(arToast, 0f, 0).padding = new RectOffset(44, 44, 30, 30);
            arToast.gameObject.AddComponent<ContentSizeFitter>().verticalFit = ContentSizeFitter.FitMode.PreferredSize;
            arToastText = DesignKit.Label("Text", arToast, "", 36, Color.white, 0, TextAnchor.MiddleCenter);
            arToast.gameObject.SetActive(false);

            SetStatus("바닥 찾는 중", DesignKit.Warning);
            SetHint("폰을 좌우로 천천히 움직여 바닥을 비춰 주세요");
        }

        void BuildSettings(Transform root)
        {
            settings = BuildPage("Settings", root, 400, out var body, out var bottom);
            var bar = DesignKit.Row("TopBar", body, BarHeight, 16, false);
            BackButton(bar, () => Show(Page.Home));
            TitleBlock(body, "서버 설정", "생성 서버 주소를 넣어 주세요. 터널(Cloudflare, ngrok)이나 클라우드 서버 주소를 넣으면 다른 네트워크에서도 쓸 수 있어요.");

            var card = DesignKit.Card("Address", body, 62f, 52, 52, 24f);
            DesignKit.Label("Label", card, "서버 주소", 34, DesignKit.InkSoft, 0, TextAnchor.MiddleLeft, true);

            var field = R("UrlField", card);
            var bg = DesignKit.Box(field, DesignKit.Ground, 42f);
            UiKit.Height(field, 140);
            var text = DesignKit.Text("Text", field, "", 36, DesignKit.Ink);
            text.rectTransform.offsetMin = new Vector2(40, 0); text.rectTransform.offsetMax = new Vector2(-40, 0);
            text.supportRichText = false; text.horizontalOverflow = HorizontalWrapMode.Overflow;
            var placeholder = DesignKit.Text("Placeholder", field, "https://example.trycloudflare.com", 36, DesignKit.InkMuted);
            placeholder.rectTransform.offsetMin = new Vector2(40, 0); placeholder.rectTransform.offsetMax = new Vector2(-40, 0);
            urlInput = field.gameObject.AddComponent<InputField>();
            urlInput.targetGraphic = bg; urlInput.textComponent = text; urlInput.placeholder = placeholder;
            urlInput.lineType = InputField.LineType.SingleLine;
            urlInput.keyboardType = TouchScreenKeyboardType.URL;
            urlInput.inputType = InputField.InputType.Standard;   // no autocorrect / suggestions rewriting the URL
            urlInput.shouldHideMobileInput = false;               // type in the keyboard's own text box (stable caret on Android)

            // typing a long URL on a phone keyboard is error-prone: paste it instead (copy on PC → messenger → phone)
            var editRow = DesignKit.Row("EditRow", card, 120, 21);
            DesignKit.SecondaryButton("Paste", editRow, "붙여넣기", PasteServerUrl, DesignKit.Ground, 120);
            DesignKit.SecondaryButton("Clear", editRow, "지우기", () => { urlInput.text = ""; settingsStatus.text = ""; }, DesignKit.Ground, 120);

            settingsStatus = DesignKit.Label("Status", card, "", 34, DesignKit.InkSoft, 0);

            var row = DesignKit.Row("Row", bottom, 150, 21);
            DesignKit.SecondaryButton("Test", row, "연결 확인", TestServer);
            DesignKit.PrimaryButton("Save", row, "저장", SaveServer);
            DesignKit.GhostButton("Default", bottom, "기본 주소로 되돌리기", () => { urlInput.text = jobManager.DefaultBaseUrl; SetSettingsStatus("기본 주소를 넣었어요. 저장을 누르면 적용돼요.", DesignKit.InkSoft); });
        }

        void SetSettingsStatus(string text, Color color) { settingsStatus.text = text; settingsStatus.color = color; }

        void PasteServerUrl()
        {
            string clip = (GUIUtility.systemCopyBuffer ?? "").Trim();
            if (string.IsNullOrEmpty(clip)) { SetSettingsStatus("클립보드가 비어 있어요. 주소를 먼저 복사해 주세요.", DesignKit.Danger); return; }
            // keep only the server part if a full healthz URL was copied
            int api = clip.IndexOf("/api/", System.StringComparison.OrdinalIgnoreCase);
            if (api > 0) clip = clip.Substring(0, api);
            urlInput.text = clip.Split(' ', '\n', '\r', '\t')[0];
            SetSettingsStatus("붙여넣었어요. \"연결 확인\"을 눌러 보세요.", DesignKit.InkSoft);
        }

        void OpenSettings()
        {
            urlInput.text = jobManager.BaseUrl;
            SetSettingsStatus("지금 주소로 연결되는지 보려면 \"연결 확인\"을 눌러 주세요.", DesignKit.InkSoft);
            Show(Page.Settings);
        }

        void TestServer()
        {
            string url = JobManager.NormalizeUrl(urlInput.text);
            if (string.IsNullOrEmpty(url)) { SetSettingsStatus("주소를 입력해 주세요.", DesignKit.Danger); return; }
            SetSettingsStatus("확인하는 중…", DesignKit.InkSoft);
            jobManager.TestBaseUrl(url,
                h => SetSettingsStatus($"연결됐어요 · {h.profile}/{h.workerType}", DesignKit.Success),
                e => SetSettingsStatus("연결되지 않아요 · " + e.code + (e.httpStatus > 0 ? $" (HTTP {e.httpStatus})" : ""), DesignKit.Danger));
        }

        void SaveServer()
        {
            if (jobManager.IsBusy) { SetSettingsStatus("만드는 중이라 지금은 바꿀 수 없어요.", DesignKit.Danger); return; }
            string before = jobManager.BaseUrl;
            jobManager.SetBaseUrl(urlInput.text);
            urlInput.text = jobManager.BaseUrl;
            // Only a new address drops the saved job (SetBaseUrl keeps it for the same one), so only then its photo goes:
            // saving the same address used to delete the photo of a job that stayed (review finding, 2026-11-25).
            if (jobManager.BaseUrl != before)
            {
                healthGen++;           // an answer still coming from the old address is dropped
                healthBusy = false;
                healthKnown = false;   // the home row checks the new address
                DeleteHeroFiles();     // SetBaseUrl dropped the saved job (another server's job)
                SetHero(null, null);
            }
            SetSettingsStatus("저장했어요 · " + jobManager.BaseUrl, DesignKit.Success);
        }

        void BuildError(Transform root)
        {
            error = BuildPage("Error", root, 430, out var body, out var bottom);
            DesignKit.Gap(body, BarHeight);   // no top bar: the title sits where the other pages have theirs
            errorStage = TitleBlock(body, "만들지 못했어요", "");

            var card = DesignKit.Card("Reason", body, 62f, 52, 52, 20f);
            var mark = R("Mark", card);
            UiKit.Height(mark, 130);
            var circle = UiKit.Rect("Circle", mark, new Vector2(0, 0.5f), new Vector2(0, 0.5f), new Vector2(0, -65), new Vector2(130, 65));
            DesignKit.Circle(circle, DesignKit.DangerSoft);
            DesignKit.Icon("alert", circle, 68, DesignKit.Danger);
            errorTitle = DesignKit.Label("Title", card, "", 47, DesignKit.Ink, 0, TextAnchor.MiddleLeft, true);
            errorBody = DesignKit.Label("Body", card, "", 39, DesignKit.InkSoft, 0);

            var info = DesignKit.Card("Info", body, 62f, 52, 30, 0f);
            errorCode = InfoRow(info, "오류 코드");
            errorRetry = InfoRow(info, "다시 시도");

            errorRetryButton = DesignKit.PrimaryButton("Retry", bottom, RetryButtonText,
                () => { ProductEvents.TrackJob("generate_tap", jobManager.CurrentJobId, ("mode", "retry")); jobManager.Retry(); });
            errorRetryLabel = errorRetryButton.GetComponentInChildren<Text>();
            DesignKit.SecondaryButton("Other", bottom, "다른 사진 고르기", StartOver);
        }

        static Text InfoRow(Transform card, string key)
        {
            var row = DesignKit.Row(key, card, 104, 16, false);
            DesignKit.Flex(DesignKit.Text("K", row, key, 36, DesignKit.InkSoft));
            var v = DesignKit.Text("V", row, "", 36, DesignKit.Ink, TextAnchor.MiddleRight, true);
            v.horizontalOverflow = HorizontalWrapMode.Overflow;
            return v;
        }

        // =====================================================================
        // screens
        // =====================================================================

        void Show(Page p)
        {
            ProductEvents.ScreenView(p.ToString().ToLowerInvariant());   // screen_view (METRICS §6.1): home … settings
            home.gameObject.SetActive(p == Page.Home);
            confirm.gameObject.SetActive(p == Page.Confirm);
            progress.gameObject.SetActive(p == Page.Progress);
            previewScreen.gameObject.SetActive(p == Page.Preview);
            arOverlay.gameObject.SetActive(p == Page.AR);
            error.gameObject.SetActive(p == Page.Error);
            settings.gameObject.SetActive(p == Page.Settings);
            library.gameObject.SetActive(p == Page.Library);
            cameraPage.gameObject.SetActive(p == Page.Camera);
            SetPetsHidden(p == Page.Camera);   // a pet placed in AR must not end up in the photo
            CloseDialog();

            if (scanOnlyInAR && scanGate != null)
            {
                if (p == Page.AR) scanGate.BeginScan(freshStart: !HasPlacedModel());   // start finding the floor now
                else scanGate.StopScan();
            }

            if (p == Page.Preview && preview != null && readyTemplate != null)
            {
                preview.Show(readyTemplate);
                previewImage.texture = preview.Texture;
            }
            else if (preview != null) preview.Hide();

            if (p == Page.Home) RefreshHome();
            if (p == Page.Library) RefreshLibrary();
            if (p == Page.AR) { hintDirty = true; SetPlaced(HasPlacedModel()); }
        }

        /// <summary>The 이어보기 card follows the saved job; the server row is checked at most every 30 s.</summary>
        void RefreshHome()
        {
            string id = jobManager.SavedJobId;
            bool show = !string.IsNullOrEmpty(id) && !jobManager.IsBusy;
            resumeCard.gameObject.SetActive(show);
            if (show)
            {
                if (heroJobId != id) LoadHero(id);
                resumeSub.text = File.Exists(JobManager.CachePath(id)) ? "생성 완료 · 이어서 AR로 보기" : "만들던 중이에요 · 이어서 보기";
            }
            int saved = ModelLibrary.Count;
            homeLibSub.text = saved == 0 ? "만든 강아지가 여기에 저장돼요" : $"{saved}/{ModelLibrary.MaxItems} · 눌러서 불러오기";
            healthWanted = true;
        }

        void RefreshServerStatus(bool force)
        {
            if (healthBusy) return;
            if (!force && healthKnown && Time.unscaledTime - healthCheckedAt < 30f) return;
            healthBusy = true;
            int gen = healthGen;   // SaveServer bumps it: a late answer from the old address must not paint the new one
            if (!healthKnown) SetServerStatus("확인 중", DesignKit.InkMuted);
            jobManager.CheckHealth(
                h => { if (gen != healthGen) return; OnHealth(); SetServerStatus("연결됨", DesignKit.Success); },
                e => { if (gen != healthGen) return; OnHealth(); SetServerStatus("연결 안 됨", DesignKit.Danger); });
        }

        void OnHealth() { healthBusy = false; healthKnown = true; healthCheckedAt = Time.unscaledTime; }

        void SetServerStatus(string text, Color dot) { serverStatus.text = text; serverDot.color = dot; }

        // ---------- events ----------

        void OnStateChanged(JobManager.State s, string text)
        {
            switch (s)
            {
                case JobManager.State.Uploading:
                case JobManager.State.Waiting:
                case JobManager.State.Downloading:
                case JobManager.State.Loading:
                    if (s == JobManager.State.Waiting) SaveHeroForJob();   // the upload was accepted: the job has its id
                    UpdateProgress(s);
                    if (!progress.gameObject.activeSelf) Show(Page.Progress);
                    break;
                case JobManager.State.Idle:
                    // Not from the settings page: saving a new address drops the saved job (Idle) while the page still
                    // has to show "저장했어요"; its back button goes home.
                    if (!home.gameObject.activeSelf && !confirm.gameObject.activeSelf && !settings.gameObject.activeSelf)
                        Show(Page.Home);
                    break;
            }
        }

        void UpdateProgress(JobManager.State s)
        {
            activeStep = s switch { JobManager.State.Uploading => 0, JobManager.State.Waiting => 1, JobManager.State.Downloading => 2, _ => 3 };
            if (s == JobManager.State.Uploading) elapsedText = "";
            bool queued = s == JobManager.State.Waiting && jobManager.LastJobStatus != null && jobManager.LastJobStatus.Status == JobStatus.Pending;
            for (int i = 0; i < steps.Length; i++)
            {
                bool done = i < activeStep, now = i == activeStep;
                steps[i].done.SetActive(done);
                steps[i].active.SetActive(now);
                steps[i].pending.SetActive(!done && !now);
                steps[i].label.color = done || now ? DesignKit.Ink : DesignKit.InkSoft;
                DesignKit.SetBold(steps[i].label, now);
                steps[i].right.text = done ? "완료" : now ? (queued ? "순서 기다리는 중" : "진행 중") : "";
                steps[i].right.color = now ? DesignKit.Accent : DesignKit.InkSoft;
                DesignKit.SetBold(steps[i].right, now);
            }
            RefreshProgressPill();
        }

        void RefreshProgressPill()
        {
            string accent = ColorUtility.ToHtmlStringRGB(DesignKit.Accent), divider = ColorUtility.ToHtmlStringRGB(DesignKit.Divider);
            string text = $"<color=#{accent}>{activeStep + 1} / {steps.Length}</color>";
            if (!string.IsNullOrEmpty(elapsedText)) text += $"   <color=#{divider}>|</color>   {elapsedText}";
            progressPill.text = text;
        }

        void OnJobFailed(string code, string userText, bool retryable)
        {
            // first sentence as the title, the rest under it
            int cut = userText.IndexOf(". ", System.StringComparison.Ordinal);
            errorTitle.text = cut > 0 ? userText.Substring(0, cut + 1) : userText;
            errorBody.text = cut > 0 ? userText.Substring(cut + 2).Trim() : "";
            errorBody.gameObject.SetActive(errorBody.text.Length > 0);
            errorCode.text = code;
            errorStage.text = string.IsNullOrEmpty(jobManager.CurrentJobId) ? "사진을 보내지 못했어요" : "작업 번호 " + Short(jobManager.CurrentJobId);
            errorRetry.text = retryable ? "가능" : "불가";
            errorRetry.color = retryable ? DesignKit.Accent : DesignKit.Danger;
            errorRetryButton.gameObject.SetActive(retryable);
            Show(Page.Error);
            SyncRetryWait();   // first frame already locked; Update keeps it in step
        }

        /// <summary>QUEUE_FULL: the retry button stays non-interactable until RetryAvailableAt (Retry-After) and shows
        /// the time left. Nothing is re-sent automatically.</summary>
        void SyncRetryWait()
        {
            if (errorRetryButton == null || !errorRetryButton.gameObject.activeSelf) return;
            bool ready = SyncSendButton(errorRetryButton, errorRetryLabel, RetryButtonText, "다시 시도할 수 있어요");
            string when = ready ? "가능" : jobManager.RetryAvailableAt.Value.ToLocalTime().ToString("HH:mm:ss") + " 이후";
            if (errorRetry.text != when) { errorRetry.text = when; errorRetry.color = ready ? DesignKit.Accent : DesignKit.InkSoft; }
        }

        /// <summary>QUEUE_FULL wait kept across "다른 사진 고르기": photos can still be picked, but "이 사진으로 만들기" stays
        /// non-interactable until RetryAvailableAt (JobManager.StartJob also refuses before then), like the web Mock UI.</summary>
        void SyncGoWait()
        {
            if (confirmGoButton != null) SyncSendButton(confirmGoButton, confirmGoLabel, GoButtonText, "만들 수 있어요");
        }

        /// <summary>Locks a button that sends until RetryAvailableAt and counts down on its label. True when it may send.</summary>
        bool SyncSendButton(Button button, Text label, string readyText, string waitingText)
        {
            float wait = jobManager.RetryWaitSeconds;
            bool ready = wait <= 0f;
            if (button.interactable != ready) button.interactable = ready;
            string text = ready ? readyText : $"{Mathf.CeilToInt(wait)}초 뒤 {waitingText}";
            if (label != null && label.text != text) label.text = text;
            return ready;
        }

        void OnModelReady(string jobId, string path, GameObject template)
        {
            readyTemplate = template; placed = false;
            instructionDismissed = false;   // the AR hint shows again for a new model; × keeps it closed for this one
            var entry = ModelLibrary.Find(jobId);
            if (entry != null) ApplyHeight(template, entry.heightCm);   // e.g. the saved job continued after a restart
            FillStats(template, path);
            SetFur(furOn);
            SetModelIdentity(entry);

            // a finished job goes into the saved models automatically (max ModelLibrary.MaxItems)
            if (entry == null && !string.IsNullOrEmpty(jobId) && !string.IsNullOrEmpty(path))
            {
                byte[] photo = EncodeThumb(heroTex);
                int autoCm = Mathf.RoundToInt(ModelHeightM(template) * 100f);
                if (ModelLibrary.IsFull)
                {
                    // full: the user frees a slot (or skips saving) before the preview
                    pendingSaveJobId = jobId; pendingSavePath = path; pendingSavePhoto = photo; pendingSaveAutoCm = autoCm;
                    libDeleteMode = true; libReturnPage = Page.Preview;
                    Show(Page.Library);
                    return;
                }
                entry = ModelLibrary.Add(jobId, path, photo, NextDefaultName(), autoCm);
                if (entry != null)
                {
                    SetModelIdentity(entry);
                    ShowPageToast($"강아지를 저장했어요 · 저장한 강아지 {ModelLibrary.Count}/{ModelLibrary.MaxItems}", 3f);
                }
            }
            Show(Page.Preview);
        }

        /// <summary>Models loaded outside a job (RuntimeModelLoader "Test Glb Url") go straight to the preview.</summary>
        void OnLoaderReady(GameObject template)
        {
            if (jobManager.IsBusy) return;          // part of a job: JobManager.ModelReady handles it
            if (libLoading) return;                 // a saved model: LoadFromLibraryRoutine handles it
            OnModelReady(null, null, template);
        }

        void OnModelPlaced(string jobId, GameObject instance)
        {
            if (arOverlay.gameObject.activeSelf) SetPlaced(true);
            else placed = true;
        }

        void OnGuidance(ARGuidance.Issue issue, string text, bool showSettings)
        {
            if (issue == ARGuidance.Issue.None || issue == ARGuidance.Issue.NoPlane) return;
            ShowToast(text, 6f);
        }

        // ---------- actions ----------

        void PickFromGallery()
        {
            if (photoPicker == null) return;
            photoPicker.Pick(photos =>
            {
                if (photoPicker.GalleryAvailable) ProductEvents.PhotosSelected("gallery", photos?.Count ?? 0);
                if (photos == null || photos.Count == 0)
                {
                    if (!photoPicker.GalleryAvailable) OpenCamera(); // editor / no plugin
                    return;
                }
                confirmFromCamera = false;
                ShowConfirm(photos);
            });
        }

        // ---------- camera ("지금 촬영하기") ----------

        /// <summary>Live camera (the AR camera feed shows through) with a hint and a round shutter button.</summary>
        void BuildCamera(Transform root)
        {
            cameraPage = R("Camera", root);   // transparent: the camera shows through
            float barY = -TopInset - BarHeight / 2f;
            var back = BackButton(cameraPage, () => Show(Page.Home));
            var brt = (RectTransform)back.transform;
            brt.anchorMin = brt.anchorMax = new Vector2(0, 1); brt.pivot = new Vector2(0, 0.5f);
            brt.anchoredPosition = new Vector2(Margin, barY);
            DesignKit.AddShadow(back.targetGraphic, 4f);

            var hint = UiKit.Rect("Hint", cameraPage, new Vector2(0, 1), new Vector2(1, 1), new Vector2(Margin, 0), new Vector2(-Margin, 0));
            hint.pivot = new Vector2(0.5f, 1); hint.anchoredPosition = new Vector2(0, -276);
            DesignKit.AddShadow(DesignKit.Box(hint, DesignKit.Glass, 31f), 3f);
            UiKit.Column(hint, 0f, 0).padding = new RectOffset(40, 40, 28, 28);
            hint.gameObject.AddComponent<ContentSizeFitter>().verticalFit = ContentSizeFitter.FitMode.PreferredSize;
            DesignKit.Label("Text", hint, "강아지 몸 전체가 화면에 들어오게 찍어 주세요", 36, DesignKit.Ink, 0, TextAnchor.MiddleCenter);

            // shutter: white ring around a white disc
            var shutter = UiKit.Rect("Shutter", cameraPage, new Vector2(0.5f, 0), new Vector2(0.5f, 0), new Vector2(-110, 160), new Vector2(110, 380));
            DesignKit.Ring(shutter, Color.white);
            var disc = UiKit.Rect("Disc", shutter, Vector2.zero, Vector2.one, new Vector2(42, 42), new Vector2(-42, -42));
            var discImg = disc.gameObject.AddComponent<Image>();
            discImg.sprite = DesignKit.CircleSprite(); discImg.color = Color.white;
            DesignKit.AddShadow(discImg, 4f);
            DesignKit.MakeButton(disc, discImg, () => { if (!capturing) StartCoroutine(TakePhoto()); });
        }

        void OpenCamera()
        {
            if (photoPicker == null) return;
            Show(Page.Camera);
        }

        IEnumerator TakePhoto()
        {
            capturing = true;
            canvas.gameObject.SetActive(false);                      // the photo must not include our UI
            yield return new WaitForEndOfFrame();
            Photo? captured = null;
            photoPicker.CaptureScreen("camera.jpg", p => captured = p);
            while (captured == null) yield return null;
            canvas.gameObject.SetActive(true);
            capturing = false;
            confirmFromCamera = true;
            ProductEvents.PhotosSelected("camera", 1);
            ShowConfirm(new List<Photo> { captured.Value });
        }

        /// <summary>Hides the pets placed in AR while the camera page is open (their renderers and fur, not the
        /// objects: SpawnLimiter forgets inactive objects), and shows them again afterwards.</summary>
        void SetPetsHidden(bool hide)
        {
            if (!hide)
            {
                foreach (var r in hiddenPetRenderers) if (r != null) r.enabled = true;
                foreach (var f in hiddenPetFur) if (f != null) f.enabled = true;
                hiddenPetRenderers.Clear(); hiddenPetFur.Clear();
                return;
            }
            if (hiddenPetRenderers.Count > 0 || hiddenPetFur.Count > 0) return;   // already hidden
            var pets = new List<GameObject>();
            if (limiter != null) pets.AddRange(limiter.PlacedModels);
            else if (spawner != null) foreach (Transform c in spawner.transform) pets.Add(c.gameObject);
            foreach (var go in pets)
            {
                if (go == null) continue;
                foreach (var r in go.GetComponentsInChildren<Renderer>()) if (r.enabled) { r.enabled = false; hiddenPetRenderers.Add(r); }
                foreach (var f in go.GetComponentsInChildren<ShellFurRenderer>()) if (f.enabled) { f.enabled = false; hiddenPetFur.Add(f); }
            }
        }

        void ShowConfirm(List<Photo> photos)
        {
            pendingPhotos = photos;
            ClearThumbs();
            SetPendingHero(null);
            int shown = Mathf.Min(photos.Count, ApiV1Routes.MaxPhotos);
            for (int i = 0; i < shown; i++)
            {
                var cell = R("Thumb" + i, thumbGrid);
                DesignKit.MaskBox(cell, DesignKit.Ground, 31f);
                var decoded = new Texture2D(2, 2);
                if (decoded.LoadImage(photos[i].bytes, true))   // true: no CPU-side copy kept
                {
                    var small = Shrink(decoded, 360);            // a phone photo is 12 MP: keep only what the cell shows
                    thumbTextures.Add(small);
                    DesignKit.SetCover(DesignKit.CoverImage(cell), small);
                    if (i == 0) SetPendingHero(Shrink(decoded, 720));
                }
                Destroy(decoded);
                if (i == 0)
                {
                    var tag = DesignKit.Pill("Tag", cell, DesignKit.Accent, Color.white, 28, true, 60, out var tagText, out _);
                    tag.anchorMin = tag.anchorMax = new Vector2(0, 1); tag.pivot = new Vector2(0, 1);
                    tag.anchoredPosition = new Vector2(18, -18);
                    tagText.text = "대표";
                }
            }
            confirmCount.text = $"선택한 사진 {photos.Count}장 · 최대 {ApiV1Routes.MaxPhotos}장";
            confirmAgainLabel.text = confirmFromCamera ? "다시 찍기" : "다시 고르기";
            Show(Page.Confirm);
            SyncGoWait();   // first frame already locked; Update keeps it in step
        }

        void StartWithPending()
        {
            if (pendingPhotos.Count == 0) return;
            ProductEvents.Track("generate_tap", ("mode", "new"), ("photoCount", Mathf.Min(pendingPhotos.Count, 10)));
            if (pendingHero != null) { SetHero(pendingHero, null); heroUnsaved = true; }
            jobManager.StartJob(pendingPhotos);
        }

        void ResumeSaved()
        {
            string id = jobManager.SavedJobId;
            if (string.IsNullOrEmpty(id) || jobManager.IsBusy) return;
            ProductEvents.TrackJob("generate_tap", id, ("mode", "resume"));
            elapsedText = "";
            jobManager.ResumeJob(id);   // StateChanged opens the progress screen
        }

        void CancelProgress()
        {
            ProductEvents.JobCancelTap(jobManager);   // before the cancel: it reads the stage and the elapsed time
            jobManager.CancelByUser(forget: true);   // also drops a job still waiting in the server's queue (POST /cancel)
            DeleteHeroFiles();
            SetHero(null, null);
            Show(Page.Home);
        }

        /// <summary>Removes the confirm thumbnails and their photo textures.</summary>
        void ClearThumbs()
        {
            if (thumbGrid != null) foreach (Transform c in thumbGrid) Destroy(c.gameObject);
            foreach (var t in thumbTextures) if (t != null) Destroy(t);
            thumbTextures.Clear();
        }

        void OnDestroy()
        {
            ClearThumbs();
            SetPendingHero(null);
            SetHero(null, null);
            if (arThumbTex != null) Destroy(arThumbTex);
            foreach (var t in libThumbs) if (t != null) Destroy(t);
        }

        void EnterAR()
        {
            bottomHidden = false;           // a new AR session starts with the buttons visible
            Show(Page.AR);                  // the hint is reset per model (OnModelReady), not per visit
        }

        void PlaceAtCenter()
        {
            ProductEvents.NoteCenterPlacement();   // a model_placed right after this is method=center
            if (centerPlacer == null || !centerPlacer.PlaceAtCenter())
                ShowToast("화면 가운데에 바닥이 없어요. 폰을 조금 움직여 보세요.", 3f);
        }

        void ResetPlacement()
        {
            // remove the placed model, then wait for a new tap
            ClearPlacedModels();
            SetPlaced(false);
        }

        void ToggleFur() { furOn = !furOn; SetFur(furOn); ProductEvents.Track("fur_toggle", ("on", furOn)); }

        void SetFur(bool on)
        {
            furToggleLabel.text = on ? "털 표현 켜짐" : "털 표현 꺼짐";
            foreach (var fur in FindObjectsByType<ShellFurRenderer>(FindObjectsInactive.Include, FindObjectsSortMode.None)) fur.enabled = on;
        }

        void StartOver()
        {
            ProductEvents.Track("start_over", ("from", ProductEvents.CurrentScreen));   // preview, ar or error
            ClearPlacedModels();
            readyTemplate = null; placed = false; pendingPhotos.Clear();
            ClearThumbs();
            jobManager.ClearSavedJob();
            DeleteHeroFiles();
            SetPendingHero(null);
            SetHero(null, null);
            SetModelIdentity(null);
            Show(Page.Home);
        }

        void ShowToast(string text, float seconds)
        {
            arToastText.text = text;
            arToast.gameObject.SetActive(true);
            toastUntil = Time.unscaledTime + seconds;
        }

        // ---------- the job's photo ----------

        static string HeroDir => Path.Combine(Application.persistentDataPath, "ui");

        static string HeroPath(string jobId)
        {
            foreach (char c in Path.GetInvalidFileNameChars()) jobId = jobId.Replace(c, '_');
            return Path.Combine(HeroDir, "photo_" + jobId + ".jpg");
        }

        /// <summary>Keeps the first photo next to the saved job so the home card and progress show it after a restart.</summary>
        void SaveHeroForJob()
        {
            string id = jobManager.CurrentJobId;
            if (!heroUnsaved || heroTex == null || string.IsNullOrEmpty(id)) return;
            heroUnsaved = false; heroJobId = id;
            try
            {
                DeleteHeroFiles();
                Directory.CreateDirectory(HeroDir);
                File.WriteAllBytes(HeroPath(id), heroTex.EncodeToJPG(85));
            }
            catch (System.Exception e) { Debug.LogWarning("[DesignedUI] could not keep the job photo: " + e.Message); }
        }

        void LoadHero(string jobId)
        {
            Texture2D tex = null;
            try
            {
                string path = HeroPath(jobId);
                if (File.Exists(path))
                {
                    tex = new Texture2D(2, 2, TextureFormat.RGBA32, false);
                    if (!tex.LoadImage(File.ReadAllBytes(path))) { Destroy(tex); tex = null; }
                }
            }
            catch (System.Exception e) { Debug.LogWarning("[DesignedUI] could not read the job photo: " + e.Message); }
            heroUnsaved = false;
            SetHero(tex, jobId);
        }

        static void DeleteHeroFiles()
        {
            try
            {
                if (Directory.Exists(HeroDir)) foreach (var f in Directory.GetFiles(HeroDir, "photo_*.jpg")) File.Delete(f);
            }
            catch (System.Exception e) { Debug.LogWarning("[DesignedUI] could not remove the job photo: " + e.Message); }
        }

        void SetHero(Texture2D tex, string jobId)
        {
            if (heroTex != null && heroTex != tex && heroTex != pendingHero) Destroy(heroTex);
            heroTex = tex; heroJobId = jobId;
            if (tex == null) heroUnsaved = false;
            if (resumeThumb != null) DesignKit.SetCover(resumeThumb, tex);
            if (progressPhoto != null) DesignKit.SetCover(progressPhoto, tex);
            RefreshArThumb();
        }

        void SetPendingHero(Texture2D tex)
        {
            if (pendingHero != null && pendingHero != tex && pendingHero != heroTex) Destroy(pendingHero);
            pendingHero = tex;
        }

        /// <summary>Downscaled, readable copy of a texture (longest side maxSide).</summary>
        static Texture2D Shrink(Texture src, int maxSide)
        {
            float k = Mathf.Min(1f, (float)maxSide / Mathf.Max(src.width, src.height));
            int w = Mathf.Max(1, Mathf.RoundToInt(src.width * k)), h = Mathf.Max(1, Mathf.RoundToInt(src.height * k));
            var rt = RenderTexture.GetTemporary(w, h, 0, RenderTextureFormat.ARGB32);
            var prev = RenderTexture.active;
            Graphics.Blit(src, rt);
            RenderTexture.active = rt;
            var tex = new Texture2D(w, h, TextureFormat.RGB24, false);
            tex.ReadPixels(new Rect(0, 0, w, h), 0, 0);
            tex.Apply();
            RenderTexture.active = prev;
            RenderTexture.ReleaseTemporary(rt);
            tex.wrapMode = TextureWrapMode.Clamp;
            return tex;
        }

        // ---------- saved models (ModelLibrary) ----------

        void BuildLibrary(Transform root)
        {
            library = BuildPage("Library", root, 0, out var body, out _);
            var bar = DesignKit.Row("TopBar", body, BarHeight, 16, false);
            BackButton(bar, CloseLibrary);
            libSub = TitleBlock(body, "저장한 강아지", "");

            // shown when a new model is waiting for a free slot
            libFullNotice = DesignKit.Card("Full", body, 62f, 52, 46, 20f, DesignKit.DangerSoft);
            DesignKit.Label("Title", libFullNotice, "저장 공간이 가득 찼어요", 42, DesignKit.Danger, 0, TextAnchor.MiddleLeft, true);
            DesignKit.Label("Body", libFullNotice, $"강아지는 {ModelLibrary.MaxItems}마리까지 저장할 수 있어요. 하나를 지우면 방금 만든 강아지가 저장돼요.", 36, DesignKit.Ink, 0);
            DesignKit.SecondaryButton("Skip", libFullNotice, "저장하지 않고 계속", SkipPendingSave, DesignKit.CardBg, 130f);

            libList = R("List", body);
            UiKit.Column(libList, 26f, 0);
        }

        void OpenLibrary(Page from)
        {
            if (libLoading) return;
            libReturnPage = from;
            libDeleteMode = false;
            Show(Page.Library);
        }

        void CloseLibrary()
        {
            if (libDeleteMode) { SkipPendingSave(); return; }
            Show(libReturnPage);
        }

        void RefreshLibrary()
        {
            foreach (Transform c in libList) { c.gameObject.SetActive(false); Destroy(c.gameObject); }
            foreach (var t in libThumbs) if (t != null) Destroy(t);
            libThumbs.Clear();

            var items = ModelLibrary.Items;
            libFullNotice.gameObject.SetActive(libDeleteMode);
            libSub.text = libDeleteMode ? "지울 강아지를 골라 주세요"
                : items.Count == 0 ? "만든 강아지가 자동으로 여기에 저장돼요"
                : $"{items.Count}/{ModelLibrary.MaxItems} · 눌러서 불러오기";
            if (items.Count == 0)
            {
                var empty = DesignKit.Card("Empty", libList, 62f, 52, 60, 16f);
                DesignKit.Label("Text", empty, "아직 저장한 강아지가 없어요", 40, DesignKit.Ink, 0, TextAnchor.MiddleCenter, true);
                DesignKit.Label("Sub", empty, "사진으로 강아지를 만들면 자동으로 저장돼요", 34, DesignKit.InkSoft, 0, TextAnchor.MiddleCenter);
                return;
            }
            for (int i = items.Count - 1; i >= 0; i--) LibraryRow(items[i]);   // newest first
        }

        void LibraryRow(ModelLibrary.Entry e)
        {
            var row = R("Item", libList);
            var bg = DesignKit.Box(row, DesignKit.CardBg, 62f);
            if (!libDeleteMode) DesignKit.MakeButton(row, bg, () => LoadFromLibrary(e));
            var h = UiKit.Row(row, 31f);
            h.padding = new RectOffset(42, 36, 36, 36);
            h.childForceExpandWidth = false; h.childForceExpandHeight = false; h.childAlignment = TextAnchor.MiddleLeft;

            var thumb = R("Thumb", row);
            DesignKit.MaskBox(thumb, DesignKit.AccentSoft, 36f);
            DesignKit.Fixed(thumb, 166, 166);
            DesignKit.Icon("cube", thumb, 68, DesignKit.Accent);           // shows when there is no photo
            var photo = ModelLibrary.LoadPhoto(e);
            if (photo != null) { libThumbs.Add(photo); DesignKit.SetCover(DesignKit.CoverImage(thumb), photo); }

            var texts = R("Texts", row);
            UiKit.Column(texts, 6f, 0).childAlignment = TextAnchor.MiddleLeft;
            DesignKit.Flex(texts);
            DesignKit.Label("Name", texts, e.name, 40, DesignKit.Ink, 0, TextAnchor.MiddleLeft, true);
            string info = $"키 {e.ShownHeightCm}cm" + (e.heightCm > 0 ? "" : " (자동)") + $" · {e.CreatedLocal:M월 d일}";
            if (e.id == currentEntryId && readyTemplate != null) info += " · 보는 중";
            DesignKit.Label("Info", texts, info, 32, DesignKit.InkSoft, 0);
            if (!string.IsNullOrEmpty(e.memo)) DesignKit.Label("Memo", texts, e.memo, 32, DesignKit.InkMuted, 0);

            var buttons = R("Buttons", row);
            UiKit.Column(buttons, 14f, 0);
            var le = buttons.gameObject.AddComponent<LayoutElement>();
            le.minWidth = le.preferredWidth = 170f;
            if (!libDeleteMode) SmallButton(buttons, "수정", DesignKit.Ground, DesignKit.Ink, () => EditEntry(e));
            SmallButton(buttons, "삭제", DesignKit.DangerSoft, DesignKit.Danger, () => ConfirmDelete(e));
        }

        static Button SmallButton(Transform parent, string label, Color bg, Color fg, System.Action onClick)
            => DesignKit.RoundedButton(label, parent, label, bg, fg, onClick, 96f, 34, true, 30f);

        void LoadFromLibrary(ModelLibrary.Entry e)
        {
            if (libLoading || e == null) return;
            if (jobManager.IsBusy) { ShowPageToast("강아지를 만드는 중에는 불러올 수 없어요", 3f); return; }
            if (modelLoader == null) { ShowPageToast("불러올 수 없어요", 3f); return; }
            StartCoroutine(LoadFromLibraryRoutine(e));
        }

        IEnumerator LoadFromLibraryRoutine(ModelLibrary.Entry e)
        {
            string path = ModelLibrary.GlbPath(e);
            byte[] bytes = null;
            try { bytes = File.ReadAllBytes(path); }
            catch (System.Exception ex) { Debug.LogWarning("[DesignedUI] could not read the saved model: " + ex.Message); }
            if (bytes == null) { ShowPageToast("저장한 강아지 파일을 읽지 못했어요", 3f); yield break; }

            libLoading = true;
            ShowPageToast($"'{e.name}' 불러오는 중…", 60f);
            ClearPlacedModels();   // the spawner slot frees the previous model only when no copy of it is placed
            readyTemplate = null; placed = false;
            var task = modelLoader.LoadAndRegister(bytes, "lib_" + e.id);
            yield return new WaitUntil(() => task.IsCompleted);
            libLoading = false;
            pageToast.gameObject.SetActive(false);

            GameObject template = task.IsCompletedSuccessfully ? task.Result : null;
            if (template == null) { ShowPageToast("강아지를 열지 못했어요", 3f); yield break; }
            readyTemplate = template; placed = false;
            instructionDismissed = false;   // another model: the AR hint shows again
            ApplyHeight(template, e.heightCm);
            FillStats(template, path);
            SetFur(furOn);
            SetModelIdentity(e);
            Show(Page.Preview);
        }

        void ConfirmDelete(ModelLibrary.Entry e)
        {
            ShowDialog("강아지를 지울까요?", $"'{e.name}'을(를) 지우면 되돌릴 수 없어요.", "지우기", DesignKit.Danger, () =>
            {
                if (e.id == currentEntryId) currentEntryId = null;   // the model on screen stays until another one is loaded
                ModelLibrary.Remove(e);
                if (libDeleteMode) { TrySavePending(); return; }
                RefreshLibrary();
                ShowPageToast("지웠어요", 2f);
            });
        }

        void EditEntry(ModelLibrary.Entry e)
        {
            ClearDialog();
            DesignKit.Label("Title", dialogCard, "강아지 정보", 47, DesignKit.Ink, 0, TextAnchor.MiddleLeft, true);
            var name = Field(dialogCard, "이름", e.name, "예: 초코", InputField.ContentType.Standard, 20);
            var height = Field(dialogCard, "키 (cm)", e.heightCm > 0 ? e.heightCm.ToString() : "",
                $"비우면 자동 ({e.autoHeightCm}cm)", InputField.ContentType.IntegerNumber, 3);
            var memo = Field(dialogCard, "메모", e.memo, "견종, 나이 등", InputField.ContentType.Standard, 60);
            var status = DesignKit.Label("Status", dialogCard, "", 32, DesignKit.Danger, 0);
            var row = DesignKit.Row("Buttons", dialogCard, 130, 21);
            DesignKit.SecondaryButton("Cancel", row, "취소", CloseDialog, DesignKit.Ground, 130f);
            DesignKit.RoundedButton("Ok", row, "저장", DesignKit.Accent, Color.white,
                () => SaveEntryEdit(e, name.text, height.text, memo.text, status), 130f, 40, true);
            OpenDialog();
        }

        /// <summary>Label and a gray one-line input under it (the settings URL field's style).</summary>
        InputField Field(Transform parent, string label, string value, string placeholder, InputField.ContentType type, int limit)
        {
            DesignKit.Label(label, parent, label, 32, DesignKit.InkSoft, 0, TextAnchor.MiddleLeft, true);
            var field = R(label + "Field", parent);
            var bg = DesignKit.Box(field, DesignKit.Ground, 36f);
            field.gameObject.AddComponent<RectMask2D>();   // long text stays inside the box
            UiKit.Height(field, 120);
            var text = DesignKit.Text("Text", field, "", 38, DesignKit.Ink);
            text.rectTransform.offsetMin = new Vector2(36, 0); text.rectTransform.offsetMax = new Vector2(-36, 0);
            text.supportRichText = false; text.horizontalOverflow = HorizontalWrapMode.Overflow;
            var ph = DesignKit.Text("Placeholder", field, placeholder, 38, DesignKit.InkMuted);
            ph.rectTransform.offsetMin = new Vector2(36, 0); ph.rectTransform.offsetMax = new Vector2(-36, 0);
            var input = field.gameObject.AddComponent<InputField>();
            input.targetGraphic = bg; input.textComponent = text; input.placeholder = ph;
            input.contentType = type;                         // IntegerNumber also opens the number pad
            input.lineType = InputField.LineType.SingleLine;
            input.characterLimit = limit;
            input.shouldHideMobileInput = false;              // type in the keyboard's own text box (stable on Android)
            input.text = value ?? "";
            return input;
        }

        void SaveEntryEdit(ModelLibrary.Entry e, string name, string height, string memo, Text status)
        {
            name = (name ?? "").Trim();
            if (name.Length == 0) { status.text = "이름을 입력해 주세요"; return; }
            int cm = 0;
            height = (height ?? "").Trim();
            if (height.Length > 0 && (!int.TryParse(height, out cm) || cm < 5 || cm > 200))
            {
                status.text = "키는 5~200 사이 숫자(cm)로 넣어 주세요. 비우면 자동 크기예요.";
                return;
            }
            e.name = name; e.heightCm = cm; e.memo = (memo ?? "").Trim();
            ModelLibrary.SaveChanges();
            if (e.id == currentEntryId && readyTemplate != null)   // the model on screen: new size and name now
            {
                ApplyHeight(readyTemplate, cm);
                FillStats(readyTemplate, ModelLibrary.GlbPath(e));
                SetModelIdentity(e);
            }
            CloseDialog();
            RefreshLibrary();
            ShowPageToast("저장했어요", 2f);
        }

        /// <summary>After a slot was freed in the "full" library: saves the model that was waiting, then shows it.</summary>
        void TrySavePending()
        {
            ModelLibrary.Entry entry = null;
            if (!string.IsNullOrEmpty(pendingSaveJobId) && !ModelLibrary.IsFull)
                entry = ModelLibrary.Add(pendingSaveJobId, pendingSavePath, pendingSavePhoto, NextDefaultName(), pendingSaveAutoCm);
            ClearPendingSave();
            libDeleteMode = false;
            if (entry != null)
            {
                if (readyTemplate != null) SetModelIdentity(entry);   // the waiting model is the one on screen
                ShowPageToast($"방금 만든 강아지를 저장했어요 · {ModelLibrary.Count}/{ModelLibrary.MaxItems}", 3f);
            }
            else ShowPageToast("강아지를 저장하지 못했어요", 3f);
            Show(readyTemplate != null ? Page.Preview : Page.Home);
        }

        void SkipPendingSave()
        {
            ClearPendingSave();
            libDeleteMode = false;
            Show(readyTemplate != null ? Page.Preview : Page.Home);
        }

        void ClearPendingSave()
        {
            pendingSaveJobId = pendingSavePath = null;
            pendingSavePhoto = null;
            pendingSaveAutoCm = 0;
        }

        /// <summary>"우리 아이 n" with the smallest n no saved model uses.</summary>
        static string NextDefaultName()
        {
            var used = new HashSet<string>();
            foreach (var e in ModelLibrary.Items) used.Add(e.name);
            for (int n = 1; ; n++)
            {
                string s = "우리 아이 " + n;
                if (!used.Contains(s)) return s;
            }
        }

        /// <summary>Name and photo of the model on screen in the AR sheet (null = not a saved model).</summary>
        void SetModelIdentity(ModelLibrary.Entry e)
        {
            currentEntryId = e?.id;
            if (arName != null) arName.text = e != null ? Ellipsize(e.name, 10) : "우리 아이";
            if (arThumbTex != null) Destroy(arThumbTex);
            arThumbTex = e != null ? ModelLibrary.LoadPhoto(e) : null;
            RefreshArThumb();
        }

        /// <summary>AR sheet photo: the saved model's photo, else the job's photo.</summary>
        void RefreshArThumb()
        {
            if (arThumb != null) DesignKit.SetCover(arThumb, arThumbTex != null ? arThumbTex : heroTex);
        }

        static string Ellipsize(string s, int max) => string.IsNullOrEmpty(s) || s.Length <= max ? s : s.Substring(0, max) + "…";

        /// <summary>Model height in meters as it is now (the loaded GLB's BoxCollider, with the template's scale).</summary>
        static float ModelHeightM(GameObject template)
        {
            var box = template != null ? template.GetComponentInChildren<BoxCollider>(true) : null;
            return box != null ? box.size.y * box.transform.lossyScale.y : 0f;
        }

        /// <summary>Scales the model to cm tall (0 = the size the loader fitted). Copies already on the floor follow by
        /// the same ratio, so a size the user pinched stays relative.</summary>
        void ApplyHeight(GameObject template, int cm)
        {
            if (template == null) return;
            float oldK = template.transform.localScale.x;
            template.transform.localScale = Vector3.one;
            float autoM = ModelHeightM(template);
            float k = cm > 0 && autoM > 1e-4f ? cm / 100f / autoM : 1f;
            template.transform.localScale = Vector3.one * k;
            if (limiter == null || oldK <= 1e-6f || Mathf.Approximately(k, oldK)) return;
            foreach (var go in limiter.PlacedModels)
                if (go != null && go.name.StartsWith(template.name, System.StringComparison.Ordinal))
                    go.transform.localScale *= k / oldK;
        }

        /// <summary>Small JPG of the job's photo for the saved model (null without a photo).</summary>
        static byte[] EncodeThumb(Texture2D tex)
        {
            if (tex == null) return null;
            var small = Shrink(tex, 360);
            byte[] jpg = small.EncodeToJPG(85);
            Destroy(small);
            return jpg;
        }

        // ---------- page toast and dialog ----------

        void BuildPageToast(Transform root)
        {
            pageToast = UiKit.Rect("PageToast", root, Vector2.zero, new Vector2(1, 0), new Vector2(62, 330), new Vector2(-62, 330));
            pageToast.pivot = new Vector2(0.5f, 0);
            DesignKit.Box(pageToast, DesignKit.ToastBg, 40f).raycastTarget = false;
            UiKit.Column(pageToast, 0f, 0).padding = new RectOffset(44, 44, 30, 30);
            pageToast.gameObject.AddComponent<ContentSizeFitter>().verticalFit = ContentSizeFitter.FitMode.PreferredSize;
            pageToastText = DesignKit.Label("Text", pageToast, "", 36, Color.white, 0, TextAnchor.MiddleCenter);
            pageToast.gameObject.SetActive(false);
        }

        void ShowPageToast(string text, float seconds)
        {
            pageToastText.text = text;
            pageToast.gameObject.SetActive(true);
            pageToast.SetAsLastSibling();
            pageToastUntil = Time.unscaledTime + seconds;
        }

        void BuildDialog(Transform root)
        {
            dialogRoot = R("Dialog", root);
            dialogRoot.gameObject.AddComponent<Image>().color = new Color(0f, 0f, 0f, 0.45f);   // dims and blocks the page
            dialogCard = DesignKit.Card("Card", dialogRoot, 62f, 52, 52, 22f);
            // near the top, so the keyboard does not cover the fields
            dialogCard.anchorMin = new Vector2(0, 1); dialogCard.anchorMax = new Vector2(1, 1); dialogCard.pivot = new Vector2(0.5f, 1);
            dialogCard.offsetMin = new Vector2(Margin, 0); dialogCard.offsetMax = new Vector2(-Margin, 0);
            dialogCard.anchoredPosition = new Vector2(0, -TopInset - 40);
            dialogCard.gameObject.AddComponent<ContentSizeFitter>().verticalFit = ContentSizeFitter.FitMode.PreferredSize;
            dialogRoot.gameObject.SetActive(false);
        }

        void ShowDialog(string title, string body, string okLabel, Color okColor, System.Action onOk)
        {
            ClearDialog();
            DesignKit.Label("Title", dialogCard, title, 47, DesignKit.Ink, 0, TextAnchor.MiddleLeft, true);
            if (!string.IsNullOrEmpty(body)) DesignKit.Label("Body", dialogCard, body, 36, DesignKit.InkSoft, 0);
            var row = DesignKit.Row("Buttons", dialogCard, 130, 21);
            DesignKit.SecondaryButton("Cancel", row, "취소", CloseDialog, DesignKit.Ground, 130f);
            DesignKit.RoundedButton("Ok", row, okLabel, okColor, Color.white, () => { CloseDialog(); onOk?.Invoke(); }, 130f, 40, true);
            OpenDialog();
        }

        void OpenDialog()
        {
            dialogRoot.gameObject.SetActive(true);
            dialogRoot.SetAsLastSibling();
        }

        void ClearDialog()
        {
            if (dialogCard == null) return;
            foreach (Transform c in dialogCard) { c.gameObject.SetActive(false); Destroy(c.gameObject); }
        }

        void CloseDialog()
        {
            if (dialogRoot == null) return;
            ClearDialog();
            dialogRoot.gameObject.SetActive(false);
        }

        // ---------- helpers ----------

        void FillStats(GameObject template, string path)
        {
            // Count the GLB only (not the loader's ShadowCatcher quad); rigged models have SkinnedMeshRenderers, no MeshFilter
            Transform model = template.transform.Find(RuntimeModelLoader.ModelRootName);
            if (model == null) model = template.transform;
            long tris = 0;
            void Count(Mesh m) { if (m != null) for (int s = 0; s < m.subMeshCount; s++) tris += (long)m.GetIndexCount(s) / 3; }
            foreach (var mf in model.GetComponentsInChildren<MeshFilter>(true)) Count(mf.sharedMesh);
            foreach (var smr in model.GetComponentsInChildren<SkinnedMeshRenderer>(true)) Count(smr.sharedMesh);
            var box = template.GetComponentInChildren<BoxCollider>(true);
            float height = box != null ? box.size.y * box.transform.lossyScale.y : 0f;
            int cm = Mathf.RoundToInt(height * 100);
            statSize.text = height > 0 ? $"{cm}cm" : "–";
            statTris.text = tris.ToString("N0");
            try { statFile.text = string.IsNullOrEmpty(path) ? "–" : $"{new FileInfo(path).Length / 1024f / 1024f:0.0} MB"; } catch { statFile.text = "–"; }
            arModelSize.text = height > 0 ? $"높이 {cm}cm" : "";
        }

        static string Short(string id) => id.Length > 8 ? id.Substring(0, 8) : id;

        void HideTemplateUI()
        {
            var names = new HashSet<string>(hideTemplateObjects);
            foreach (var t in FindObjectsByType<Transform>(FindObjectsInactive.Include, FindObjectsSortMode.None))
                if (names.Contains(t.name) && t.gameObject.scene.IsValid()) t.gameObject.SetActive(false);
        }
    }
}
