using UnityEngine;
using UnityEngine.Rendering;
using UnityEngine.Rendering.Universal;

namespace Beside.UI
{
    /// <summary>
    /// Shows the loaded model before AR placement: a copy of the spawner template is rendered by a
    /// dedicated camera (own layer, far from the AR scene) into a RenderTexture the UI displays.
    /// The user can drag to rotate; otherwise it turns slowly.
    /// </summary>
    public class ModelPreview : MonoBehaviour
    {
        [Tooltip("Layer used only by the preview camera and the preview copy (must not be used by anything else).")]
        public int previewLayer = 30;
        [Tooltip("Texture size when Auto Texture Size is off (and the smallest size when it is on).")]
        public int textureSize = 768;
        [Tooltip("Size the preview texture to the phone screen (about the width of the preview card), so the fur is not " +
                 "drawn small and then stretched. Off: always Texture Size.")]
        public bool autoTextureSize = true;
        [Tooltip("Upper limit for Auto Texture Size (memory: size^2 x MSAA x 8 bytes, 1280 at 4x = about 50 MB).")]
        public int maxTextureSize = 1280;
        [Tooltip("Part of the texture the turning model fills (side view, widest angle).")]
        [Range(0.5f, 0.95f)] public float fill = 0.85f;
        [Tooltip("Keep the full shell count on the preview copy (the AR view keeps lowering it for close-ups).")]
        public bool fullShellsInPreview = true;
        [Tooltip("Show the floor shadow (the loader's ShadowCatcher quad) under the preview copy. The AR pet keeps its shadow either way.")]
        public bool showShadow = false;
        public float autoRotateDegPerSec = 20f;
        public Color background = new Color(0.12f, 0.12f, 0.14f, 1f);

        public RenderTexture Texture { get; private set; }
        public bool IsShowing => copy != null;

        Camera cam;
        GameObject copy;
        Transform pivot;
        float dragVelocity;
        static readonly Vector3 FarAway = new Vector3(0f, -500f, 0f);

        void Awake()
        {
            // Shell fur v2 uses alpha-to-coverage, so it needs MSAA. For a camera with a target texture URP takes the
            // MSAA count from that texture, not from the URP asset: give the texture the asset's count (set before first use).
            int msaa = GraphicsSettings.currentRenderPipeline is UniversalRenderPipelineAsset urp ? Mathf.Max(1, urp.msaaSampleCount) : 4;
            int size = textureSize;
            if (autoTextureSize)   // the card is about 85% of the screen width; the square image is a bit smaller
                size = Mathf.Clamp(Mathf.RoundToInt(Mathf.Min(Screen.width, Screen.height) * 0.85f), textureSize, Mathf.Max(textureSize, maxTextureSize));
            Texture = new RenderTexture(size, size, 24) { name = "ModelPreviewRT", antiAliasing = msaa };

            var camGo = new GameObject("ModelPreviewCamera");
            camGo.transform.SetParent(transform, false);
            cam = camGo.AddComponent<Camera>();
            cam.cullingMask = 1 << previewLayer;
            cam.clearFlags = CameraClearFlags.SolidColor;
            cam.backgroundColor = background;
            cam.targetTexture = Texture;
            cam.nearClipPlane = 0.01f;
            cam.enabled = false;

            // Keep the AR camera from drawing the preview copy
            if (Camera.main != null) Camera.main.cullingMask &= ~(1 << previewLayer);

            pivot = new GameObject("ModelPreviewPivot").transform;
            pivot.SetParent(transform, false);
            pivot.position = FarAway;
        }

        public void Show(GameObject template)
        {
            Hide();
            if (template == null) return;

            copy = Instantiate(template, pivot);
            copy.name = "PreviewCopy";
            copy.transform.localPosition = Vector3.zero;
            copy.transform.localRotation = Quaternion.identity;
            copy.SetActive(true);

            // Visual only: turn off interaction scripts (keep fur)
            foreach (var mb in copy.GetComponentsInChildren<MonoBehaviour>(true))
                if (!(mb is ShellFurRenderer)) mb.enabled = false;
            // The model fills much of the preview, which would make the fur drop to its close-up shell count.
            // One model in a small view is cheap, so keep every shell (denser, less grainy fur).
            if (fullShellsInPreview)
                foreach (var fur in copy.GetComponentsInChildren<ShellFurRenderer>(true)) fur.adaptiveShells = false;
            foreach (var col in copy.GetComponentsInChildren<Collider>(true)) col.enabled = false;
            if (!showShadow)   // the preview is for checking the model: no floor shadow
                foreach (var r in copy.GetComponentsInChildren<Renderer>(true))
                    if (r.name == "ShadowCatcher") r.enabled = false;
            SetLayer(copy.transform, previewLayer);

            // Frame the model: bounds from the loader's BoxCollider (or renderers). The model turns about the pivot,
            // so fit the widest horizontal reach from that axis (side view) and the height to the camera's field of
            // view, instead of a fixed distance from the box diagonal (a long dog seen from the front was tiny).
            Bounds b = ComputeBounds(copy);
            float reach = 0.05f;
            Vector3 axis = pivot.position;
            for (int sx = -1; sx <= 1; sx += 2)
                for (int sz = -1; sz <= 1; sz += 2)
                {
                    Vector3 c = b.center + Vector3.Scale(b.extents, new Vector3(sx, 0f, sz));
                    reach = Mathf.Max(reach, new Vector2(c.x - axis.x, c.z - axis.z).magnitude);
                }
            float halfHeight = Mathf.Max(b.extents.y, 0.05f);
            float tanHalf = Mathf.Tan(cam.fieldOfView * 0.5f * Mathf.Deg2Rad);   // square texture: same both ways
            // a point `reach` from the axis looks widest when it has turned toward the camera by acos(reach / distance):
            // its size on screen is then reach / sqrt(distance^2 - reach^2), which must stay within tanHalf * fill
            float tf = tanHalf * Mathf.Clamp(fill, 0.5f, 0.95f);
            float distance = Mathf.Max(reach * Mathf.Sqrt(1f + 1f / (tf * tf)), halfHeight / tf + reach * 0.5f);
            Vector3 look = new Vector3(axis.x, b.center.y, axis.z);
            Vector3 dir = new Vector3(0f, 0.35f, 2.6f).normalized;   // same slightly-from-above angle as before
            cam.transform.position = look + dir * distance;
            cam.transform.LookAt(look);
            cam.farClipPlane = (distance + reach + halfHeight) * 3f;
            cam.enabled = true;
            pivot.rotation = Quaternion.identity;
        }

        public void Hide()
        {
            if (copy != null) Destroy(copy);
            copy = null;
            cam.enabled = false;
        }

        /// <summary>Background of the preview texture (the designed UI matches its card color).</summary>
        public void SetBackground(Color color)
        {
            background = color;
            if (cam != null) cam.backgroundColor = color;
        }

        /// <summary>Call from the UI drag handler with the horizontal drag delta in pixels.</summary>
        public void Drag(float deltaX)
        {
            dragVelocity = -deltaX * 0.4f;
        }

        void Update()
        {
            if (copy == null) return;
            float speed = Mathf.Abs(dragVelocity) > 0.01f ? dragVelocity : autoRotateDegPerSec * Time.deltaTime;
            pivot.Rotate(0f, speed, 0f, Space.World);
            dragVelocity = Mathf.Lerp(dragVelocity, 0f, Time.deltaTime * 6f);
        }

        static Bounds ComputeBounds(GameObject go)
        {
            var box = go.GetComponentInChildren<BoxCollider>(true);
            if (box != null) return new Bounds(box.transform.TransformPoint(box.center), Vector3.Scale(box.size, box.transform.lossyScale));
            Bounds b = new Bounds(go.transform.position, Vector3.one * 0.3f);
            foreach (var r in go.GetComponentsInChildren<Renderer>(true)) b.Encapsulate(r.bounds);
            return b;
        }

        static void SetLayer(Transform t, int layer)
        {
            t.gameObject.layer = layer;
            foreach (Transform c in t) SetLayer(c, layer);
        }

        void OnDestroy()
        {
            if (Texture != null) Texture.Release();
        }
    }
}
