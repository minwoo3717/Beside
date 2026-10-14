using System;
using System.Collections.Generic;
using Beside.AR;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.XR.Interaction.Toolkit.Samples.StarterAssets;

namespace Beside.Pet
{
    /// <summary>
    /// Plays the rigged GLB clips (GLB_SPEC: Idle, Walk, TailWag, HeadTilt, Sit) on the placed pet(s).
    /// glTFast imports clips as a legacy Animation component on the "pet" node; this finds it on every placed copy.
    ///  - loop clips cross-fade and keep playing
    ///  - one-shot clips play once, then fade back to Idle
    /// Editor hotkeys 1–5 for quick checks.
    /// </summary>
    public class PetAnimationPlayer : MonoBehaviour
    {
        public string idleClip = "Idle";
        public string[] loopClips = { "Idle", "Walk", "TailWag" };
        public string[] oneShotClips = { "Sit", "HeadTilt" };
        [Range(0f, 1f)] public float fadeSeconds = 0.25f;

        [Tooltip("Keys 1–5 play Idle / Walk / Sit / TailWag / HeadTilt (editor and devices with a keyboard).")]
        public bool debugHotkeys = true;

        /// <summary>Buttons shown by the UI, in order: (clip name, Korean label).</summary>
        public static readonly (string clip, string label)[] Actions =
        {
            ("Walk", "걷기"), ("Sit", "앉기"), ("TailWag", "꼬리"), ("HeadTilt", "갸웃"), ("Idle", "기본"),
        };

        public string Current { get; private set; }
        public event Action<string> ClipStarted;

        SpawnLimiter limiter;
        ObjectSpawner spawner;
        readonly List<Animation> buffer = new List<Animation>();

        void Awake()
        {
            limiter = FindFirstObjectByType<SpawnLimiter>();
            spawner = FindFirstObjectByType<ObjectSpawner>();
        }

        // ---------- queries ----------

        /// <summary>Animation components of the pets currently on the floor.</summary>
        public List<Animation> PlacedAnimations()
        {
            buffer.Clear();
            foreach (var go in PlacedModels())
            {
                var a = go != null ? go.GetComponentInChildren<Animation>(true) : null;
                if (a != null) buffer.Add(a);
            }
            return buffer;
        }

        public bool HasAnyAnimation => PlacedAnimations().Count > 0;

        public bool HasClip(string clip)
        {
            foreach (var a in PlacedAnimations()) if (a.GetClip(clip) != null) return true;
            return false;
        }

        IEnumerable<GameObject> PlacedModels()
        {
            if (limiter != null) { foreach (var g in limiter.PlacedModels) yield return g; yield break; }
            if (spawner == null) yield break;
            foreach (Transform c in spawner.transform) if (c.gameObject.activeInHierarchy) yield return c.gameObject;
        }

        /// <summary>
        /// The clip that is actually driving the first placed pet right now (highest weight), e.g. Idle on a pet
        /// that was just placed again. Queued clones ("Idle - Queued Clone") report their source name.
        /// </summary>
        public string PlayingClip
        {
            get
            {
                var list = PlacedAnimations();
                if (list.Count == 0) return null;
                string best = null; float bestWeight = 0f;
                foreach (AnimationState st in list[0])
                    if (st.enabled && st.weight > bestWeight) { bestWeight = st.weight; best = st.name; }
                if (best == null) return null;
                int q = best.IndexOf(" - Queued Clone", StringComparison.Ordinal);
                return q >= 0 ? best.Substring(0, q) : best;
            }
        }

        /// <summary>Forget the last requested clip (a newly placed pet starts in Idle).</summary>
        public void ResetToIdle() => Current = idleClip;

        // ---------- playback ----------

        public void Play(string clip)
        {
            bool any = false;
            foreach (var a in PlacedAnimations()) any |= PlayOn(a, clip);
            if (!any) { Debug.LogWarning($"[PetAnimation] no placed pet has clip '{clip}'"); return; }
            Current = clip;
            ClipStarted?.Invoke(clip);
        }

        public bool PlayOn(Animation anim, string clip)
        {
            AnimationState state = anim[clip];
            if (state == null) return false;

            bool oneShot = Array.IndexOf(oneShotClips, clip) >= 0;
            state.wrapMode = oneShot ? WrapMode.Once : WrapMode.Loop;
            anim.CrossFade(clip, fadeSeconds);

            if (oneShot && anim[idleClip] != null)
            {
                anim[idleClip].wrapMode = WrapMode.Loop;
                anim.CrossFadeQueued(idleClip, fadeSeconds, QueueMode.CompleteOthers);   // back to Idle when done
            }
            return true;
        }

        void Update()
        {
            if (!debugHotkeys) return;
            var kb = Keyboard.current;
            if (kb == null) return;
            if (kb.digit1Key.wasPressedThisFrame) Play("Idle");
            if (kb.digit2Key.wasPressedThisFrame) Play("Walk");
            if (kb.digit3Key.wasPressedThisFrame) Play("Sit");
            if (kb.digit4Key.wasPressedThisFrame) Play("TailWag");
            if (kb.digit5Key.wasPressedThisFrame) Play("HeadTilt");
        }
    }
}
