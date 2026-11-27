using System.Collections.Generic;
using UnityEngine;
using UnityEngine.UI;

namespace Beside.UI.Designed
{
    /// <summary>
    /// Tokens and widgets for the designed UI, following 시안 E of "Beside 앱 UI 개선 시안": light gray ground, white
    /// cards, indigo accent, Noto Sans KR, line icons. Builds on UiKit. Rounded rects, circles and rings are generated in
    /// code; fonts, icons and example photos load from Resources/BesideUI.
    /// Reference resolution is 1080×1920, so sizes here are roughly the 시안's 390-wide CSS px × 2.6.
    /// </summary>
    public static class DesignKit
    {
        public static readonly Color Ground = Hex("#F2F3F5");      // page, and gray tiles on white cards
        public static readonly Color CardBg = Color.white;
        public static readonly Color Ink = Hex("#1A1C20");
        public static readonly Color InkSoft = Hex("#62676F");
        public static readonly Color InkMuted = Hex("#9EA3AB");
        public static readonly Color Accent = Hex("#4C5BD4");
        public static readonly Color AccentSoft = Hex("#ECEEFC");
        public static readonly Color Line = Hex("#E1E3E7");
        public static readonly Color Divider = Hex("#D5D8DD");
        public static readonly Color Success = Hex("#12A150");
        public static readonly Color Warning = Hex("#F5A524");
        public static readonly Color Danger = Hex("#D93A2B");
        public static readonly Color DangerSoft = Hex("#FCEBE9");
        public static readonly Color Glass = new Color(1f, 1f, 1f, 0.94f);                 // AR hints over the camera
        public static readonly Color ToastBg = new Color(0.102f, 0.110f, 0.125f, 0.92f);
        public static readonly Color ShadowColor = new Color(0f, 0f, 0f, 0.14f);

        const string Root = "BesideUI/";

        public static Font Font;       // regular face; null = Unity default font (OS fallback)
        public static Font FontBold;   // real bold face; null = synthesized bold of Font

        static Sprite rounded, circle, ring;
        static readonly Dictionary<string, Sprite> icons = new Dictionary<string, Sprite>();
        static readonly Dictionary<string, Texture2D> photos = new Dictionary<string, Texture2D>();

        public static Color Hex(string hex) => ColorUtility.TryParseHtmlString(hex, out var c) ? c : Color.magenta;

        // ---------- fonts ----------

        /// <summary>Inspector font wins; otherwise Noto Sans KR Regular/Bold from Resources/BesideUI/Fonts.</summary>
        public static void LoadFonts(Font regularOverride, Font boldOverride)
        {
            if (regularOverride != null)
            {
                Font = regularOverride;
                FontBold = boldOverride;
                return;
            }
            Font = Resources.Load<Font>(Root + "Fonts/NotoSansKR-Regular");
            FontBold = boldOverride != null ? boldOverride : Resources.Load<Font>(Root + "Fonts/NotoSansKR-Bold");
            if (Font == null) Debug.LogWarning("[DesignKit] Noto Sans KR not found in Resources/BesideUI/Fonts, using the default font");
        }

        /// <summary>Sets the face for regular or bold text (the bold face when there is one, synthesized bold otherwise).</summary>
        public static void SetBold(Text t, bool bold)
        {
            if (bold && FontBold != null) { t.font = FontBold; t.fontStyle = FontStyle.Normal; return; }
            if (Font != null) t.font = Font;
            t.fontStyle = bold ? FontStyle.Bold : FontStyle.Normal;
        }

        // ---------- generated sprites ----------

        /// <summary>9-sliced rounded rectangle sprite (radius in reference px via pixelsPerUnitMultiplier).</summary>
        public static Sprite Rounded()
        {
            if (rounded != null) return rounded;
            const int size = 64, r = 28;
            var tex = new Texture2D(size, size, TextureFormat.RGBA32, false) { name = "DesignKitRounded" };
            var px = new Color32[size * size];
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                {
                    float dx = Mathf.Max(r - x, x - (size - 1 - r), 0);
                    float dy = Mathf.Max(r - y, y - (size - 1 - r), 0);
                    float d = Mathf.Sqrt(dx * dx + dy * dy);
                    float a = Mathf.Clamp01(r - d + 0.5f);
                    px[y * size + x] = new Color32(255, 255, 255, (byte)(a * 255));
                }
            tex.SetPixels32(px); tex.Apply();
            tex.wrapMode = TextureWrapMode.Clamp;
            rounded = Sprite.Create(tex, new Rect(0, 0, size, size), new Vector2(0.5f, 0.5f), 100f, 0, SpriteMeshType.FullRect, new Vector4(r, r, r, r));
            return rounded;
        }

        const int DiscPx = 128;
        const float RingStrokePx = DiscPx / 22f * 3f;

        /// <summary>Filled circle (dots, badges, step marks).</summary>
        public static Sprite CircleSprite() => circle != null ? circle : (circle = Disc(DiscPx, 0f, "DesignKitCircle"));

        /// <summary>Ring with the 시안's stroke (22 px circle, 3 px line) for step marks and the spinner.</summary>
        public static Sprite RingSprite() => ring != null ? ring : (ring = Disc(DiscPx, RingStrokePx, "DesignKitRing"));

        static Sprite Disc(int size, float stroke, string name)
        {
            var tex = new Texture2D(size, size, TextureFormat.RGBA32, false) { name = name };
            var px = new Color32[size * size];
            float c = (size - 1) / 2f, outer = size / 2f - 1f;
            float mid = outer - stroke / 2f;
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                {
                    float d = Mathf.Sqrt((x - c) * (x - c) + (y - c) * (y - c));
                    float a = stroke <= 0f ? Mathf.Clamp01(outer - d + 0.5f) : Mathf.Clamp01(stroke / 2f - Mathf.Abs(d - mid) + 0.5f);
                    px[y * size + x] = new Color32(255, 255, 255, (byte)(a * 255));
                }
            tex.SetPixels32(px); tex.Apply();
            tex.wrapMode = TextureWrapMode.Clamp;
            return Sprite.Create(tex, new Rect(0, 0, size, size), new Vector2(0.5f, 0.5f), 100f);
        }

        // ---------- images ----------

        public static Image Box(RectTransform rt, Color color, float radius = 40f)
        {
            var img = rt.gameObject.GetComponent<Image>();
            if (img == null) img = rt.gameObject.AddComponent<Image>();
            img.sprite = Rounded(); img.type = Image.Type.Sliced; img.color = color;
            img.pixelsPerUnitMultiplier = 28f / Mathf.Max(radius, 1f);   // 28 px corner in the sprite -> radius ref px
            return img;
        }

        public static Image Circle(RectTransform rt, Color color)
        {
            var img = rt.gameObject.AddComponent<Image>();
            img.sprite = CircleSprite(); img.type = Image.Type.Simple; img.color = color; img.raycastTarget = false;
            return img;
        }

        public static Image Ring(RectTransform rt, Color color)
        {
            var img = rt.gameObject.AddComponent<Image>();
            img.sprite = RingSprite(); img.type = Image.Type.Simple; img.color = color; img.raycastTarget = false;
            return img;
        }

        /// <summary>White line icon from Resources/BesideUI/Icons, tinted by Image.color. Null when the file is missing.</summary>
        public static Sprite IconSprite(string name)
        {
            if (icons.TryGetValue(name, out var s)) return s;
            var tex = Resources.Load<Texture2D>(Root + "Icons/" + name);
            if (tex != null)
            {
                tex.wrapMode = TextureWrapMode.Clamp;
                s = Sprite.Create(tex, new Rect(0, 0, tex.width, tex.height), new Vector2(0.5f, 0.5f), 100f);
            }
            else Debug.LogWarning("[DesignKit] icon missing: " + name);
            icons[name] = s;
            return s;
        }

        /// <summary>Icon of a fixed size: centered in its parent, or sized by the parent's layout group.</summary>
        public static Image Icon(string name, Transform parent, float size, Color color)
        {
            var rt = UiKit.Rect("Icon_" + name, parent, new Vector2(0.5f, 0.5f), new Vector2(0.5f, 0.5f), new Vector2(-size / 2, -size / 2), new Vector2(size / 2, size / 2));
            var img = rt.gameObject.AddComponent<Image>();
            img.sprite = IconSprite(name); img.color = color; img.preserveAspect = true; img.raycastTarget = false;
            img.enabled = img.sprite != null;
            Fixed(rt, size, size);
            return img;
        }

        /// <summary>Example photo from Resources/BesideUI/Examples (null when missing).</summary>
        public static Texture2D Photo(string name)
        {
            if (photos.TryGetValue(name, out var t)) return t;
            t = Resources.Load<Texture2D>(Root + "Examples/" + name);
            if (t != null) t.wrapMode = TextureWrapMode.Clamp;
            photos[name] = t;
            return t;
        }

        /// <summary>RawImage that covers its parent like CSS object-fit: cover (the parent clips it with a Mask/RectMask2D).</summary>
        public static RawImage CoverImage(Transform parent)
        {
            var rt = UiKit.Rect("Img", parent, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            var raw = rt.gameObject.AddComponent<RawImage>(); raw.raycastTarget = false;
            rt.gameObject.AddComponent<AspectRatioFitter>().aspectMode = AspectRatioFitter.AspectMode.EnvelopeParent;
            return raw;
        }

        public static void SetCover(RawImage raw, Texture tex)
        {
            raw.texture = tex;
            raw.enabled = tex != null;
            var fit = raw.GetComponent<AspectRatioFitter>();
            if (fit != null && tex != null && tex.height > 0) fit.aspectRatio = (float)tex.width / tex.height;
        }

        /// <summary>Rounded box that clips its children to its shape.</summary>
        public static Image MaskBox(RectTransform rt, Color color, float radius)
        {
            var img = Box(rt, color, radius);
            rt.gameObject.AddComponent<Mask>().showMaskGraphic = true;
            return img;
        }

        public static void AddShadow(Graphic g, float distance = 6f)
        {
            var s = g.gameObject.AddComponent<Shadow>();
            s.effectColor = ShadowColor; s.effectDistance = new Vector2(0, -distance);
        }

        // ---------- layout ----------

        public static RectTransform Screen(string name, Transform parent, Color bg)
        {
            var rt = UiKit.Rect(name, parent, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            var img = rt.gameObject.AddComponent<Image>(); img.color = bg;
            return rt;
        }

        public static RectTransform Column(string name, Transform parent, Vector2 anchorMin, Vector2 anchorMax, Vector2 offMin, Vector2 offMax, float spacing = 40f, int padding = 64)
        {
            var rt = UiKit.Rect(name, parent, anchorMin, anchorMax, offMin, offMax);
            UiKit.Column(rt, spacing, padding);
            return rt;
        }

        /// <summary>Vertical column inside a vertical ScrollRect (content sized to fit). Spacers have no effect inside it.
        /// Clamped: the page moves only when its content is taller than the screen (Elastic let a short page be dragged
        /// up and down and spring back, 2026-11-25).</summary>
        public static RectTransform ScrollColumn(string name, Transform parent, RectOffset padding, float spacing)
        {
            var rt = UiKit.Rect(name, parent, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            var sr = rt.gameObject.AddComponent<ScrollRect>();
            sr.horizontal = false; sr.vertical = true; sr.movementType = ScrollRect.MovementType.Clamped; sr.scrollSensitivity = 40f;
            var vp = UiKit.Rect("Viewport", rt, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            vp.gameObject.AddComponent<RectMask2D>();
            vp.gameObject.AddComponent<Image>().color = Color.clear;   // catches drags on empty space
            var content = UiKit.Rect("Content", vp, new Vector2(0, 1), new Vector2(1, 1), Vector2.zero, Vector2.zero);
            content.pivot = new Vector2(0.5f, 1f);
            var v = UiKit.Column(content, spacing, 0); v.padding = padding;
            content.gameObject.AddComponent<ContentSizeFitter>().verticalFit = ContentSizeFitter.FitMode.PreferredSize;
            sr.viewport = vp; sr.content = content;
            rt.gameObject.AddComponent<ScrollWhenOverflow>();
            return content;
        }

        /// <summary>Turns a page's scrolling off while its content is at most its bottom padding (plus a little) taller
        /// than the view: such a page moved a few px under the finger even with Clamped (2026-11-25). Only the empty
        /// bottom padding is cut then; a page that really overflows (small phone, long text) still scrolls.</summary>
        public class ScrollWhenOverflow : MonoBehaviour
        {
            public float extraSlack = 40f;
            ScrollRect sr; VerticalLayoutGroup v;

            void LateUpdate()
            {
                if (sr == null) { sr = GetComponent<ScrollRect>(); if (sr == null || sr.content == null) return; v = sr.content.GetComponent<VerticalLayoutGroup>(); }
                if (sr.content == null || sr.viewport == null) return;
                float slack = (v != null ? v.padding.bottom : 0) + extraSlack;
                bool scroll = sr.content.rect.height > sr.viewport.rect.height + slack;
                if (sr.vertical == scroll) return;
                sr.vertical = scroll;
                if (!scroll) { sr.StopMovement(); sr.verticalNormalizedPosition = 1f; }   // back to the top
            }
        }

        /// <summary>White rounded card stacking its children; its height follows the content.</summary>
        public static RectTransform Card(string name, Transform parent, float radius = 62f, int padH = 52, int padV = 52, float spacing = 12f, Color? color = null)
        {
            var rt = UiKit.Rect(name, parent, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            Box(rt, color ?? CardBg, radius);
            var v = UiKit.Column(rt, spacing, 0);
            v.padding = new RectOffset(padH, padH, padV, padV);
            return rt;
        }

        /// <summary>Horizontal row. expand = equal stretch (buttons side by side); otherwise children keep their own
        /// size, are centered vertically, and one child takes the rest with Flex. height &lt;= 0 = height of the content.</summary>
        public static RectTransform Row(string name, Transform parent, float height, float spacing = 28f, bool expand = true)
        {
            var rt = UiKit.Rect(name, parent, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            var h = UiKit.Row(rt, spacing);
            if (!expand)
            {
                h.childForceExpandWidth = false; h.childForceExpandHeight = false;
                h.childAlignment = TextAnchor.MiddleLeft;
            }
            if (height > 0) UiKit.Height(rt, height);
            return rt;
        }

        public static LayoutElement Fixed(Component c, float width, float height)
        {
            var le = c.gameObject.GetComponent<LayoutElement>();
            if (le == null) le = c.gameObject.AddComponent<LayoutElement>();
            le.minWidth = le.preferredWidth = width; le.minHeight = le.preferredHeight = height;
            le.flexibleWidth = 0; le.flexibleHeight = 0;
            return le;
        }

        /// <summary>Takes the remaining width of a row.</summary>
        public static LayoutElement Flex(Component c, float weight = 1f)
        {
            var le = c.gameObject.GetComponent<LayoutElement>();
            if (le == null) le = c.gameObject.AddComponent<LayoutElement>();
            le.flexibleWidth = weight;
            return le;
        }

        /// <summary>Same width as the siblings marked Equal, whatever the content.</summary>
        public static LayoutElement Equal(Component c)
        {
            var le = Flex(c);
            le.minWidth = 0; le.preferredWidth = 0;
            return le;
        }

        public static RectTransform Gap(Transform parent, float height)
        {
            var rt = UiKit.Rect("Gap", parent, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            UiKit.Height(rt, height);
            return rt;
        }

        public static RectTransform Spacer(Transform parent, float flexible = 1f)
        {
            var rt = UiKit.Rect("Spacer", parent, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            var le = rt.gameObject.AddComponent<LayoutElement>(); le.flexibleHeight = flexible; le.minHeight = 0;
            return rt;
        }

        // ---------- text ----------

        public static Text Text(string name, Transform parent, string text, int size, Color color, TextAnchor anchor = TextAnchor.MiddleLeft, bool bold = false)
        {
            var t = UiKit.Text(name, parent, text, size, color, anchor);
            SetBold(t, bold);
            return t;
        }

        /// <summary>Text for a column. height &lt;= 0 = as tall as the wrapped text.</summary>
        public static Text Label(string name, Transform parent, string text, int size, Color color, float height, TextAnchor anchor = TextAnchor.MiddleLeft, bool bold = false)
        {
            var t = Text(name, parent, text, size, color, anchor, bold);
            t.verticalOverflow = VerticalWrapMode.Overflow;
            if (height > 0) UiKit.Height(t, height);
            return t;
        }

        // ---------- buttons ----------

        /// <summary>Makes a rect with an Image clickable (pressed tint, dim when disabled).</summary>
        public static Button MakeButton(RectTransform rt, Image img, System.Action onClick)
        {
            var btn = rt.gameObject.AddComponent<Button>();
            btn.targetGraphic = img;
            var colors = btn.colors; colors.disabledColor = new Color(1, 1, 1, 0.45f); colors.pressedColor = new Color(0.86f, 0.86f, 0.88f); btn.colors = colors;
            btn.onClick.AddListener(() => onClick?.Invoke());
            return btn;
        }

        public static Button RoundedButton(string name, Transform parent, string label, Color bg, Color fg, System.Action onClick, float height, int fontSize, bool bold, float radius = 47f)
        {
            var rt = UiKit.Rect(name, parent, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            var btn = MakeButton(rt, Box(rt, bg, radius), onClick);
            Text(name + "Label", rt, label, fontSize, fg, TextAnchor.MiddleCenter, bold);
            UiKit.Height(rt, height);
            return btn;
        }

        public static Button PrimaryButton(string name, Transform parent, string label, System.Action onClick)
            => RoundedButton(name, parent, label, Accent, Color.white, onClick, 150f, 42, true);

        /// <summary>White on the gray page; pass Ground for a gray button on a white card.</summary>
        public static Button SecondaryButton(string name, Transform parent, string label, System.Action onClick, Color? bg = null, float height = 150f)
            => RoundedButton(name, parent, label, bg ?? CardBg, Ink, onClick, height, 40, false);

        public static Button GhostButton(string name, Transform parent, string label, System.Action onClick)
            => RoundedButton(name, parent, label, Color.clear, InkSoft, onClick, 120f, 38, false);

        /// <summary>Square or round icon button of a fixed size. radius &lt; 0 = circle.</summary>
        public static Button IconButton(string name, Transform parent, string icon, float size, float iconSize, Color bg, Color fg, System.Action onClick, float radius = -1f)
        {
            var rt = UiKit.Rect(name, parent, new Vector2(0.5f, 0.5f), new Vector2(0.5f, 0.5f), new Vector2(-size / 2, -size / 2), new Vector2(size / 2, size / 2));
            var btn = MakeButton(rt, Box(rt, bg, radius < 0 ? size / 2f : radius), onClick);
            Icon(icon, rt, iconSize, fg);
            Fixed(rt, size, size);
            return btn;
        }

        /// <summary>Big tile of the 시안: icon top-left and label bottom-left (vertical = icon above a centered label).</summary>
        public static Button Tile(string name, Transform parent, string icon, string label, Color bg, Color fg, System.Action onClick, bool vertical = false, bool bold = true)
        {
            var rt = UiKit.Rect(name, parent, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            var btn = MakeButton(rt, Box(rt, bg, 42f), onClick);
            var ic = Icon(icon, rt, vertical ? 57f : 62f, fg).rectTransform;
            Text t;
            if (vertical)
            {
                ic.anchoredPosition = new Vector2(0, 26);
                t = Text("Label", rt, label, 34, fg, TextAnchor.MiddleCenter, bold);
                t.rectTransform.anchorMin = new Vector2(0, 0.5f); t.rectTransform.anchorMax = new Vector2(1, 0.5f);
                t.rectTransform.offsetMin = new Vector2(8, -82); t.rectTransform.offsetMax = new Vector2(-8, -18);
            }
            else
            {
                ic.anchorMin = ic.anchorMax = new Vector2(0, 1); ic.pivot = new Vector2(0, 1);
                ic.anchoredPosition = new Vector2(42, -40);
                t = Text("Label", rt, label, 39, fg, TextAnchor.LowerLeft, bold);
                t.rectTransform.offsetMin = new Vector2(42, 38); t.rectTransform.offsetMax = new Vector2(-24, -110);
            }
            t.horizontalOverflow = HorizontalWrapMode.Overflow;
            Equal(rt);
            return btn;
        }

        /// <summary>Plain text button (e.g. "취소" in a top bar).</summary>
        public static Button TextButton(string name, Transform parent, string label, int fontSize, Color fg, System.Action onClick, float width)
        {
            var rt = UiKit.Rect(name, parent, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            var btn = MakeButton(rt, Box(rt, Color.clear, 30f), onClick);
            Text(name + "Label", rt, label, fontSize, fg, TextAnchor.MiddleCenter);
            Fixed(rt, width, 110f);
            return btn;
        }

        /// <summary>Rounded pill whose width follows its content (optional status dot in front). Place it with
        /// anchors/pivot/anchoredPosition; it must not sit under a layout group.</summary>
        public static RectTransform Pill(string name, Transform parent, Color bg, Color fg, int fontSize, bool bold, float height, out Text label, out Image dot, bool withDot = false)
        {
            var rt = UiKit.Rect(name, parent, new Vector2(0.5f, 0.5f), new Vector2(0.5f, 0.5f), Vector2.zero, Vector2.zero);
            rt.sizeDelta = new Vector2(0, height);
            Box(rt, bg, height / 2f);
            var h = UiKit.Row(rt, 16f);
            h.padding = new RectOffset(34, 34, 0, 0);
            h.childForceExpandWidth = false; h.childForceExpandHeight = false; h.childAlignment = TextAnchor.MiddleCenter;
            rt.gameObject.AddComponent<ContentSizeFitter>().horizontalFit = ContentSizeFitter.FitMode.PreferredSize;
            dot = null;
            if (withDot)
            {
                var d = UiKit.Rect("Dot", rt, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
                dot = Circle(d, Success);
                Fixed(d, 18, 18);
            }
            label = Text("Text", rt, "", fontSize, fg, TextAnchor.MiddleCenter, bold);
            label.horizontalOverflow = HorizontalWrapMode.Overflow;
            return rt;
        }

        // ---------- behaviours ----------

        /// <summary>Keeps a GridLayoutGroup's square cells filling its width (the canvas width changes with the phone's aspect).</summary>
        public class GridAutoCell : MonoBehaviour
        {
            public int columns = 4;
            GridLayoutGroup grid; RectTransform rt; float lastWidth = -1f;

            void Update()
            {
                if (grid == null) { grid = GetComponent<GridLayoutGroup>(); rt = (RectTransform)transform; }
                float w = rt.rect.width;
                if (grid == null || w <= 0f || Mathf.Abs(w - lastWidth) < 0.5f) return;
                lastWidth = w;
                float cell = Mathf.Floor((w - grid.padding.horizontal - grid.spacing.x * (columns - 1)) / columns);
                grid.cellSize = new Vector2(cell, cell);
            }
        }

        /// <summary>Quarter arc in the ring's stroke with round ends, turning at a steady speed (시안: 0.9 s a turn).
        /// The angle comes from the clock, so a step that becomes active picks up the same phase.
        /// Same motion as unity/docs/ui-preview/style.css (.spin).</summary>
        public class Spinner : MonoBehaviour
        {
            const float SecondsPerTurn = 0.9f, Arc = 0.25f;
            Image arc; RectTransform head, tail;

            public static void Build(RectTransform rt, Color color)
            {
                var s = rt.gameObject.AddComponent<Spinner>();
                s.arc = Ring(rt, color);
                s.arc.type = Image.Type.Filled; s.arc.fillMethod = Image.FillMethod.Radial360;
                s.arc.fillOrigin = (int)Image.Origin360.Top; s.arc.fillClockwise = true; s.arc.fillAmount = Arc;
                s.tail = Cap(rt, color);
                s.head = Cap(rt, color);
            }

            static RectTransform Cap(RectTransform parent, Color color)
            {
                var cap = UiKit.Rect("Cap", parent, new Vector2(0.5f, 0.5f), new Vector2(0.5f, 0.5f), Vector2.zero, Vector2.zero);
                Circle(cap, color);
                return cap;
            }

            void Update()
            {
                var rt = (RectTransform)transform;
                rt.localEulerAngles = new Vector3(0f, 0f, -Mathf.Repeat(Time.unscaledTime / SecondsPerTurn, 1f) * 360f);

                // round ends: two dots of the stroke's width on the ring's centre line (rect size is known only after layout)
                float px = Mathf.Min(rt.rect.width, rt.rect.height) / DiscPx;
                float mid = (DiscPx / 2f - 1f - RingStrokePx / 2f) * px;
                float capSize = RingStrokePx * px * DiscPx / (DiscPx - 2f);   // the circle sprite leaves a 1 px margin
                float a = Arc * 2f * Mathf.PI;
                tail.sizeDelta = head.sizeDelta = new Vector2(capSize, capSize);
                tail.anchoredPosition = new Vector2(0f, mid);
                head.anchoredPosition = new Vector2(Mathf.Sin(a) * mid, Mathf.Cos(a) * mid);
            }
        }
    }
}
