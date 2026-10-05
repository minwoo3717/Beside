using System.Collections.Generic;
using UnityEngine;
using UnityEngine.XR.Interaction.Toolkit.Samples.StarterAssets;

namespace Beside.AR
{
    /// <summary>
    /// Keeps at most maxInstances spawned models in the scene.
    ///  Block (default): extra taps are ignored. The tap-to-spawn trigger is switched off while full, and any
    ///                   extra model that still appears is removed immediately (newest first).
    ///  Move:            the oldest model is removed so the new one stays at the tap.
    /// Counts models two ways so it works with any template version: the ObjectSpawner.objectSpawned event,
    /// and a per-frame scan of the spawner's children (the template spawns as children by default).
    /// </summary>
    [DefaultExecutionOrder(-50)]
    public class SpawnLimiter : MonoBehaviour
    {
        public enum Mode { Block, Move }

        [Tooltip("How many spawned models may exist at the same time.")]
        [Min(1)] public int maxInstances = 1;

        [Tooltip("Block: extra taps are ignored. Move: the oldest model is replaced by the new one.")]
        public Mode mode = Mode.Block;

        [Tooltip("Only allow models on upward-facing floors. Anything spawned on a wall or ceiling is removed at once.")]
        public bool floorOnly = true;

        [Tooltip("Log what the limiter does (found objects, blocked taps).")]
        public bool verbose = true;

        /// <summary>cos(25°): a surface counts as floor when its normal is within ~25° of straight up.</summary>
        public const float FloorMinDot = 0.9f;

        readonly List<GameObject> spawned = new List<GameObject>();   // oldest first
        ObjectSpawner spawner;
        Behaviour trigger;   // template's ARInteractorSpawnTrigger, found by type name (namespace differs by XRI version)

        public int Count { get { Refresh(); return spawned.Count; } }
        public bool IsFull => Count >= maxInstances;
        public bool CanSpawn => mode == Mode.Move || !IsFull;

        void Awake()
        {
            spawner = FindFirstObjectByType<ObjectSpawner>(FindObjectsInactive.Include);
            foreach (var b in FindObjectsByType<Behaviour>(FindObjectsInactive.Include, FindObjectsSortMode.None))
                if (b.GetType().Name == "ARInteractorSpawnTrigger") { trigger = b; break; }

            if (spawner != null) spawner.objectSpawned += OnSpawned;
            if (verbose) Debug.Log($"[SpawnLimiter] spawner={(spawner ? spawner.name : "NOT FOUND")}, trigger={(trigger ? trigger.name : "NOT FOUND")}, max={maxInstances}, mode={mode}");
        }

        void OnDestroy()
        {
            if (spawner != null) spawner.objectSpawned -= OnSpawned;
            if (trigger != null) trigger.enabled = true;
        }

        void OnSpawned(GameObject go)
        {
            if (go == null) return;
            // The spawner aligns the model's up axis with the surface normal, so a wall spawn has a sideways up.
            if (floorOnly && Vector3.Dot(go.transform.up, Vector3.up) < FloorMinDot)
            {
                go.SetActive(false);
                Destroy(go);
                if (verbose) Debug.Log("[SpawnLimiter] tap on a wall/ceiling ignored (floor only)");
                return;
            }
            if (!spawned.Contains(go)) spawned.Add(go);
            Enforce();
        }

        void LateUpdate()
        {
            Enforce();
            if (trigger != null && mode == Mode.Block)
            {
                bool allow = spawned.Count < maxInstances;
                if (trigger.enabled != allow) trigger.enabled = allow;
            }
        }

        void Enforce()
        {
            Refresh();
            while (spawned.Count > maxInstances)
            {
                GameObject victim = mode == Mode.Move ? spawned[0] : spawned[spawned.Count - 1];
                spawned.Remove(victim);
                if (victim != null)
                {
                    victim.SetActive(false);   // hide this frame, destroy at end of frame
                    Destroy(victim);
                }
                if (verbose) Debug.Log(mode == Mode.Block ? "[SpawnLimiter] tap ignored (limit reached)" : "[SpawnLimiter] moved: oldest removed");
            }
        }

        /// <summary>Drops deleted entries and adds spawner children the event did not report.</summary>
        void Refresh()
        {
            spawned.RemoveAll(o => o == null || !o.activeInHierarchy);
            if (spawner == null) return;
            foreach (Transform child in spawner.transform)
            {
                var go = child.gameObject;
                if (!go.activeInHierarchy || spawned.Contains(go)) continue;
                if (floorOnly && Vector3.Dot(go.transform.up, Vector3.up) < FloorMinDot) { go.SetActive(false); Destroy(go); continue; }
                spawned.Add(go);   // children are in creation order
            }
        }

        /// <summary>Removes every spawned model (used by "새로 만들기" / "다시 배치").</summary>
        public void ClearAll()
        {
            Refresh();
            foreach (var go in spawned) if (go != null) Destroy(go);
            spawned.Clear();
        }
    }
}
