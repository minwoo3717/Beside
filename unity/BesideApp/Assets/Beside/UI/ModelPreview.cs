using UnityEngine;

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
        public int textureSize = 768;
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
            Texture = new RenderTexture(textureSize, textureSize, 24) { name = "ModelPreviewRT" };

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
            foreach (var col in copy.GetComponentsInChildren<Collider>(true)) col.enabled = false;
            SetLayer(copy.transform, previewLayer);

            // Frame the model: bounds from the loader's BoxCollider (or renderers)
            Bounds b = ComputeBounds(copy);
            float radius = Mathf.Max(b.extents.magnitude, 0.05f);
            cam.transform.position = b.center + new Vector3(0f, radius * 0.35f, radius * 2.6f);
            cam.transform.LookAt(b.center);
            cam.farClipPlane = radius * 10f;
            cam.enabled = true;
            pivot.rotation = Quaternion.identity;
        }

        public void Hide()
        {
            if (copy != null) Destroy(copy);
            copy = null;
            cam.enabled = false;
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
