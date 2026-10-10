using System.Collections.Generic;
using UnityEngine;
using UnityEngine.XR.ARFoundation;
using UnityEngine.XR.Interaction.Toolkit.Samples.StarterAssets;

namespace Beside.AR
{
    /// <summary>
    /// Hides the white-dot floor visuals while a model is placed, and shows them again when it is removed.
    /// Only the renderers are switched off: plane detection keeps running, so the placed model can still be
    /// dragged along the floor and "다시 배치" works right away.
    /// Enforced every frame because the template's plane visualizer re-enables its renderer on tracking updates.
    /// </summary>
    public class PlaneVisibility : MonoBehaviour
    {
        [Tooltip("Hide the floor dots once a model is on the floor.")]
        public bool hideWhenPlaced = true;

        ARPlaneManager planeManager;
        SpawnLimiter limiter;
        ObjectSpawner spawner;
        readonly Dictionary<ARPlane, Renderer[]> cache = new Dictionary<ARPlane, Renderer[]>();
        bool lastVisible = true;

        void Awake()
        {
            planeManager = FindFirstObjectByType<ARPlaneManager>();
            limiter = FindFirstObjectByType<SpawnLimiter>();
            spawner = FindFirstObjectByType<ObjectSpawner>();
            if (planeManager == null) Debug.LogWarning("[PlaneVisibility] ARPlaneManager not found");
        }

        void LateUpdate()
        {
            if (planeManager == null) return;
            bool visible = !(hideWhenPlaced && HasPlacedModel());

            foreach (var plane in planeManager.trackables)
            {
                if (!cache.TryGetValue(plane, out var renderers) || renderers == null)
                {
                    renderers = plane.GetComponentsInChildren<Renderer>(true);
                    cache[plane] = renderers;
                }
                foreach (var r in renderers)
                {
                    if (r == null) continue;
                    // when showing, leave it to the visualizer (it hides untracked/subsumed planes itself)
                    if (!visible && r.enabled) r.enabled = false;
                    else if (visible && !lastVisible) r.enabled = true;
                }
            }
            lastVisible = visible;

            if (cache.Count > 64) cache.Clear();   // drop entries for planes that were removed
        }

        /// <summary>Show or hide the floor visuals from other scripts (e.g. a "show floor" toggle).</summary>
        public void ForceVisible(bool show) => hideWhenPlaced = !show;

        bool HasPlacedModel()
        {
            if (limiter != null) return limiter.Count > 0;
            if (spawner == null) return false;
            foreach (Transform c in spawner.transform) if (c.gameObject.activeInHierarchy) return true;
            return false;
        }
    }
}
