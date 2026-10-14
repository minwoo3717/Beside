using UnityEngine;
using UnityEngine.UI;

namespace Beside.UI.Designed
{
    /// <summary>
    /// Tokens and rounded widgets for the designed UI (Beside 앱 UI 시안 canvas).
    /// Builds on UiKit; adds a procedurally generated rounded-rect sprite so no image assets are needed.
    /// Reference resolution is 1080×1920, so sizes here are roughly the canvas's 390-wide values × 2.77.
    /// </summary>
    public static class DesignKit
    {
        // Light theme (home, confirm, progress, error)
        public static readonly Color Ground = Hex("#F7F6F2");
        public static readonly Color Ink = Hex("#1F2421");
        public static readonly Color InkSoft = Hex("#5B6360");
        public static readonly Color InkMuted = Hex("#8A918E");
        public static readonly Color Accent = Hex("#2F6B4F");
        public static readonly Color AccentSoft = Hex("#E6EEE8");
        public static readonly Color AccentSoft2 = Hex("#DCE5DF");
        public static readonly Color CardBg = Color.white;
        public static readonly Color Line = Hex("#D9DED9");
        public static readonly Color Danger = Hex("#B4553A");
        public static readonly Color DangerSoft = Hex("#F3E3DC");
        // Dark theme (preview, AR overlays)
        public static readonly Color DarkGround = Hex("#1B211E");
        public static readonly Color DarkCard = Hex("#242C28");
        public static readonly Color DarkScrim = new Color(0.106f, 0.129f, 0.118f, 0.82f);
        public static readonly Color Mint = Hex("#9FD3B4");
        public static readonly Color Peach = Hex("#F2B98B");
        public static readonly Color OnDark = Hex("#F2F4F1");
        public static readonly Color OnDarkSoft = Hex("#B9C3BC");

        public static Font Font;   // optional: set to a Korean TTF (e.g. Noto Sans KR) before building; null = Unity default

        static Sprite rounded;

        public static Color Hex(string hex) => ColorUtility.TryParseHtmlString(hex, out var c) ? c : Color.magenta;

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

        public static Image Box(RectTransform rt, Color color, float radius = 40f)
        {
            var img = rt.gameObject.GetComponent<Image>() ?? rt.gameObject.AddComponent<Image>();
            img.sprite = Rounded(); img.type = Image.Type.Sliced; img.color = color;
            img.pixelsPerUnitMultiplier = 28f / Mathf.Max(radius, 1f) * 100f / 100f;  // 28 px corner in the sprite -> radius ref px
            return img;
        }

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

        public static RectTransform Card(string name, Transform parent, Color color, float height, float radius = 56f, float spacing = 24f, int padding = 48)
        {
            var rt = UiKit.Rect(name, parent, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            Box(rt, color, radius);
            UiKit.Column(rt, spacing, padding);
            UiKit.Height(rt, height);
            return rt;
        }

        public static RectTransform Row(string name, Transform parent, float height, float spacing = 28f)
        {
            var rt = UiKit.Rect(name, parent, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            UiKit.Row(rt, spacing);
            UiKit.Height(rt, height);
            return rt;
        }

        public static Text Label(string name, Transform parent, string text, int size, Color color, float height, TextAnchor anchor = TextAnchor.MiddleLeft, FontStyle style = FontStyle.Normal)
        {
            var t = UiKit.Text(name, parent, text, size, color, anchor, style);
            if (Font != null) t.font = Font;
            t.verticalOverflow = VerticalWrapMode.Overflow;
            UiKit.Height(t, height);
            return t;
        }

        public static Button PrimaryButton(string name, Transform parent, string label, System.Action onClick, Color? bg = null, Color? fg = null)
            => RoundedButton(name, parent, label, bg ?? Accent, fg ?? Color.white, onClick, 150f, 44, FontStyle.Bold);

        public static Button SecondaryButton(string name, Transform parent, string label, System.Action onClick, bool dark = false)
            => RoundedButton(name, parent, label, dark ? DarkScrim : CardBg, dark ? OnDark : Ink, onClick, 150f, 42, FontStyle.Normal, dark ? new Color(1, 1, 1, 0.14f) : Line);

        public static Button GhostButton(string name, Transform parent, string label, System.Action onClick, bool dark = false)
            => RoundedButton(name, parent, label, Color.clear, dark ? OnDarkSoft : InkSoft, onClick, 130f, 40, FontStyle.Normal);

        public static Button RoundedButton(string name, Transform parent, string label, Color bg, Color fg, System.Action onClick, float height, int fontSize, FontStyle style, Color? outline = null)
        {
            var rt = UiKit.Rect(name, parent, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            var img = Box(rt, bg, 44f);
            if (outline.HasValue)
            {
                var ol = rt.gameObject.AddComponent<Outline>();
                ol.effectColor = outline.Value; ol.effectDistance = new Vector2(2, -2);
            }
            var btn = rt.gameObject.AddComponent<Button>();
            btn.targetGraphic = img;
            var colors = btn.colors; colors.disabledColor = new Color(1, 1, 1, 0.4f); colors.pressedColor = new Color(0.85f, 0.85f, 0.85f); btn.colors = colors;
            btn.onClick.AddListener(() => onClick?.Invoke());
            var t = UiKit.Text(name + "Label", rt, label, fontSize, fg, TextAnchor.MiddleCenter, style);
            if (Font != null) t.font = Font;
            UiKit.Height(rt, height);
            return btn;
        }

        public static RectTransform Pill(string name, Transform parent, string text, Color bg, Color fg, out Text label, float width = 420f)
        {
            var rt = UiKit.Rect(name, parent, new Vector2(0.5f, 0.5f), new Vector2(0.5f, 0.5f), new Vector2(-width / 2, -42), new Vector2(width / 2, 42));
            Box(rt, bg, 84f);
            label = UiKit.Text(name + "Text", rt, text, 32, fg, TextAnchor.MiddleCenter);
            if (Font != null) label.font = Font;
            return rt;
        }

        public static RectTransform Spacer(Transform parent, float flexible = 1f)
        {
            var rt = UiKit.Rect("Spacer", parent, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            var le = rt.gameObject.AddComponent<LayoutElement>(); le.flexibleHeight = flexible; le.minHeight = 0;
            return rt;
        }

        /// <summary>Step row for the progress list: circle + label + right text.</summary>
        public static (RectTransform row, Image circle, Text label, Text right) StepRow(string name, Transform parent)
        {
            var row = Row(name, parent, 110f, 36f);
            var h = row.GetComponent<HorizontalLayoutGroup>(); h.childForceExpandWidth = false; h.childAlignment = TextAnchor.MiddleLeft;
            var c = UiKit.Rect("Circle", row, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            var circle = Box(c, Line, 40f);
            var cle = c.gameObject.AddComponent<LayoutElement>(); cle.preferredWidth = 76; cle.preferredHeight = 76; cle.minWidth = 76;
            var label = UiKit.Text("Label", row, "", 40, InkMuted, TextAnchor.MiddleLeft);
            if (Font != null) label.font = Font;
            var lle = label.gameObject.AddComponent<LayoutElement>(); lle.flexibleWidth = 1;
            var right = UiKit.Text("Right", row, "", 34, InkSoft, TextAnchor.MiddleRight);
            if (Font != null) right.font = Font;
            var rle = right.gameObject.AddComponent<LayoutElement>(); rle.preferredWidth = 240;
            return (row, circle, label, right);
        }
    }
}
