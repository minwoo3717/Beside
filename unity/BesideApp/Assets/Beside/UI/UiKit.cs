using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.UI;

namespace Beside.UI
{
    /// <summary>Builds uGUI widgets from code so the scene needs no manual Canvas work. Legacy Text uses the OS font fallback for Korean.</summary>
    public static class UiKit
    {
        public static readonly Color Panel = new Color(0.07f, 0.07f, 0.09f, 0.86f);
        public static readonly Color Accent = new Color(0.35f, 0.62f, 1f, 1f);
        public static readonly Color Muted = new Color(1f, 1f, 1f, 0.45f);
        public static readonly Color Danger = new Color(1f, 0.45f, 0.4f, 1f);
        public static readonly Color ButtonBg = new Color(1f, 1f, 1f, 0.12f);

        static Font font;
        public static Font Font => font != null ? font : (font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf"));

        public static Canvas Canvas(string name, int sortingOrder = 10)
        {
            var go = new GameObject(name, typeof(Canvas), typeof(CanvasScaler), typeof(GraphicRaycaster));
            var canvas = go.GetComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            canvas.sortingOrder = sortingOrder;
            var scaler = go.GetComponent<CanvasScaler>();
            scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
            scaler.referenceResolution = new Vector2(1080, 1920);
            scaler.matchWidthOrHeight = 0.5f;
            if (Object.FindFirstObjectByType<EventSystem>() == null)
                new GameObject("EventSystem", typeof(EventSystem), typeof(UnityEngine.InputSystem.UI.InputSystemUIInputModule));
            return canvas;
        }

        public static RectTransform Rect(string name, Transform parent, Vector2 anchorMin, Vector2 anchorMax, Vector2 offsetMin, Vector2 offsetMax)
        {
            var go = new GameObject(name, typeof(RectTransform));
            var rt = go.GetComponent<RectTransform>();
            rt.SetParent(parent, false);
            rt.anchorMin = anchorMin; rt.anchorMax = anchorMax;
            rt.offsetMin = offsetMin; rt.offsetMax = offsetMax;
            return rt;
        }

        public static Image Image(RectTransform rt, Color color)
        {
            var img = rt.gameObject.AddComponent<Image>();
            img.color = color;
            return img;
        }

        public static Text Text(string name, Transform parent, string text, int size, Color color, TextAnchor anchor = TextAnchor.MiddleCenter, FontStyle style = FontStyle.Normal)
        {
            var rt = Rect(name, parent, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            var t = rt.gameObject.AddComponent<Text>();
            t.font = Font; t.fontSize = size; t.color = color; t.text = text;
            t.alignment = anchor; t.fontStyle = style;
            t.horizontalOverflow = HorizontalWrapMode.Wrap; t.verticalOverflow = VerticalWrapMode.Truncate;
            t.raycastTarget = false;
            return t;
        }

        public static Button Button(string name, Transform parent, string label, Color bg, System.Action onClick, int fontSize = 40)
        {
            var rt = Rect(name, parent, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            var img = Image(rt, bg);
            var btn = rt.gameObject.AddComponent<Button>();
            btn.targetGraphic = img;
            var colors = btn.colors; colors.disabledColor = new Color(1f, 1f, 1f, 0.3f); btn.colors = colors;
            btn.onClick.AddListener(() => onClick());
            Text(name + "Label", rt, label, fontSize, Color.white, TextAnchor.MiddleCenter, FontStyle.Bold);
            var le = rt.gameObject.AddComponent<LayoutElement>();
            le.preferredHeight = 120; le.flexibleWidth = 1;
            return btn;
        }

        public static HorizontalLayoutGroup Row(RectTransform rt, float spacing = 20f)
        {
            var h = rt.gameObject.AddComponent<HorizontalLayoutGroup>();
            h.spacing = spacing; h.childForceExpandHeight = true; h.childForceExpandWidth = true;
            h.childControlHeight = true; h.childControlWidth = true;
            return h;
        }

        public static VerticalLayoutGroup Column(RectTransform rt, float spacing = 16f, int padding = 32)
        {
            var v = rt.gameObject.AddComponent<VerticalLayoutGroup>();
            v.spacing = spacing; v.padding = new RectOffset(padding, padding, padding, padding);
            v.childForceExpandHeight = false; v.childForceExpandWidth = true;
            v.childControlHeight = true; v.childControlWidth = true;
            return v;
        }

        public static LayoutElement Height(Component c, float height)
        {
            var le = c.gameObject.GetComponent<LayoutElement>() ?? c.gameObject.AddComponent<LayoutElement>();
            le.preferredHeight = height; le.minHeight = height;
            return le;
        }

        /// <summary>Forwards horizontal drags (pixels) to a callback; used for the preview rotation.</summary>
        public class DragForwarder : MonoBehaviour, IDragHandler
        {
            public System.Action<float> onDragX;
            public void OnDrag(PointerEventData e) => onDragX?.Invoke(e.delta.x);
        }
    }
}
