using System;
using System.Collections.Generic;
using UnityEngine;

namespace Beside.FurLab
{
    public enum FurMode { None, TextureOnly, Shell, Strands, ShellFins }
    public enum ShellVersion { V2, V1 }

    [Serializable]
    public class FurCondition
    {
        public string name = "shell16";
        [Tooltip("Which run buttons include it: comma list of 'method' (방식 비교), 'optim' (최적화 비교).")]
        public string sets = "method";
        public FurMode mode = FurMode.Shell;
        [Min(1)] public int shells = 16;
        [Min(1)] public int strands = 5000;
        [Range(1, 8)] public int segments = 4;
        [Tooltip("Shell shader: V2 (current) or V1 (before the 2026-11 rework, for the before/after comparison).")]
        public ShellVersion version = ShellVersion.V2;
        [Tooltip("Shell count drops when the pet fills the screen (what the app ships). Off = fixed count.")]
        public bool adaptive;
        [Tooltip("MSAA samples during this condition: -1 = keep the project setting, 1 = off, 2, 4.")]
        public int msaa = -1;

        public FurCondition() { }
        public FurCondition(string n, string set, FurMode m, int sh = 16, int st = 5000, int seg = 4,
                            ShellVersion v = ShellVersion.V2, bool adapt = false, int aa = -1)
        { name = n; sets = set; mode = m; shells = sh; strands = st; segments = seg; version = v; adaptive = adapt; msaa = aa; }

        public bool InSet(string set) =>
            string.IsNullOrEmpty(set) || set == "all" || ("," + sets.Replace(" ", "") + ",").Contains("," + set + ",");
    }

    /// <summary>Materials and hair maps the fur variants are built from.</summary>
    [Serializable]
    public class FurAssets
    {
        [Tooltip("Custom/ShellFur material (same one RuntimeModelLoader uses). Optional if the pet already has shell fur.")]
        public Material shellMaterial;
        [Tooltip("Optional: material with Custom/ShellFurV1 (the old shader) for the v1 vs v2 comparison. " +
                 "Empty = the v1 conditions are skipped.")]
        public Material shellV1Material;
        [Tooltip("Custom/StrandFur material")]
        public Material strandMaterial;
        [Tooltip("Custom/FinFur material")]
        public Material finMaterial;
        [Tooltip("Optional URP/Lit material WITH a Normal Map assigned (e.g. AnimalLift assets/Normal.png). " +
                 "Without it a generated normal map is used; it may render flat in builds if the _NORMALMAP variant was stripped.")]
        public Material normalMappedMaterial;

        [Tooltip("Used when the pet has no shell fur yet. Must have Read/Write enabled for strands.")]
        public Texture2D hairLength;
        public Texture2D hairFlow;
        public float furLength = 0.065f;
        public bool flipHairMaps;
    }

    /// <summary>
    /// Switches one placed pet between fur conditions (none / texture / shell N / strands N / shell+fins)
    /// and back to how it was. Used by FurBenchmark.
    /// </summary>
    public class FurVariants
    {
        readonly List<Renderer> targets = new List<Renderer>();
        readonly Dictionary<Renderer, Material[]> original = new Dictionary<Renderer, Material[]>();
        readonly Dictionary<Renderer, Material[]> textured = new Dictionary<Renderer, Material[]>();
        readonly Dictionary<Renderer, (bool enabled, int count)> originalShell = new Dictionary<Renderer, (bool, int)>();
        readonly Dictionary<Renderer, Material> v2Mat = new Dictionary<Renderer, Material>();
        readonly Dictionary<Renderer, Material> v1Mat = new Dictionary<Renderer, Material>();
        readonly FurAssets assets;
        Texture2D lengthMap, flowMap;
        float furLength;
        bool flip;
        static Texture2D generatedNormal;

        public bool HasHairMaps => lengthMap != null;
        public bool CanRun(FurCondition c)
        {
            bool maps = c.mode == FurMode.None || c.mode == FurMode.TextureOnly || lengthMap != null;
            bool v1 = c.version != ShellVersion.V1 || c.mode == FurMode.None || c.mode == FurMode.TextureOnly || assets.shellV1Material != null;
            return maps && v1;
        }

        /// <summary>Average shells drawn this frame over the pet's renderers (after the close-up reduction).</summary>
        public float ShellsDrawn()
        {
            int n = 0, sum = 0;
            foreach (var r in targets)
            {
                var sh = r != null ? r.GetComponent<ShellFurRenderer>() : null;
                if (sh != null && sh.enabled) { sum += sh.LastShells > 0 ? sh.LastShells : sh.shellCount; n++; }
            }
            return n > 0 ? (float)sum / n : 0f;
        }
        public string Warning { get; private set; }

        public FurVariants(GameObject pet, FurAssets a)
        {
            assets = a;
            foreach (var r in pet.GetComponentsInChildren<Renderer>(true))
            {
                if (!(r is SkinnedMeshRenderer) && !(r is MeshRenderer)) continue;
                if (r.name == "ShadowCatcher") continue;
                targets.Add(r);
                original[r] = r.sharedMaterials;
                var shell = r.GetComponent<ShellFurRenderer>();
                if (shell != null)
                {
                    originalShell[r] = (shell.enabled, shell.shellCount);
                    if (lengthMap == null && shell.furMaterial != null)       // take the maps the loader used
                    {
                        var m = shell.furMaterial;
                        lengthMap = m.GetTexture("_LengthMap") as Texture2D;
                        flowMap = m.GetTexture("_FlowMap") as Texture2D;
                        furLength = m.GetFloat("_FurLength");
                        flip = m.GetFloat("_FlipHairMaps") > 0.5f;
                    }
                }
            }
            if (lengthMap == null) { lengthMap = a.hairLength; flowMap = a.hairFlow; furLength = a.furLength; flip = a.flipHairMaps; }
            if (lengthMap == null) Warning = "털 맵 없음: shell/strand/fin 조건은 건너뜀 (FurBenchmark에 Hair Length 지정)";
            else if (!lengthMap.isReadable) Warning = "Hair Length 텍스처 Read/Write 꺼짐: strand 길이가 균일하게 들어감";
        }

        /// <summary>Applies the condition. Returns the extra fur triangles drawn per frame (estimate).</summary>
        public int Apply(FurCondition c)
        {
            bool tex = c.mode == FurMode.TextureOnly;
            bool shell = c.mode == FurMode.Shell || c.mode == FurMode.ShellFins;
            bool strands = c.mode == FurMode.Strands;
            bool fins = c.mode == FurMode.ShellFins;
            int tris = 0;

            foreach (var r in targets)
            {
                r.sharedMaterials = tex ? Textured(r) : original[r];

                var sh = r.GetComponent<ShellFurRenderer>();
                if (shell && lengthMap != null)
                {
                    if (sh == null) sh = AddShell(r);
                    if (sh != null)
                    {
                        if (!v2Mat.ContainsKey(r)) v2Mat[r] = sh.furMaterial;
                        sh.furMaterial = c.version == ShellVersion.V1 ? V1(r, v2Mat[r]) : v2Mat[r];
                        sh.shellCount = c.shells; sh.adaptiveShells = c.adaptive; sh.enabled = true; tris += sh.FurTriangles;
                    }
                }
                else if (sh != null) sh.enabled = false;

                var st = r.GetComponent<StrandFurRenderer>();
                if (strands && lengthMap != null && assets.strandMaterial != null)
                {
                    if (st == null) st = AddStrands(r);
                    st.strandCount = c.strands; st.segments = c.segments; st.enabled = true;
                    tris += c.strands * c.segments * 2;     // exact count is known after the first frame (BuiltStrands)
                }
                else if (st != null) UnityEngine.Object.Destroy(st);   // free its buffer for fair memory numbers

                var fn = r.GetComponent<FinFurRenderer>();
                if (fins && lengthMap != null && assets.finMaterial != null)
                {
                    if (fn == null) fn = AddFins(r);
                    fn.enabled = true;
                    tris += Mathf.Max(0, fn.FurTriangles);
                }
                else if (fn != null) UnityEngine.Object.Destroy(fn);
            }
            return tris;
        }

        /// <summary>Exact fur triangles after the renderers have built their buffers.</summary>
        public int CountFurTriangles()
        {
            int t = 0;
            foreach (var r in targets)
            {
                var sh = r.GetComponent<ShellFurRenderer>(); if (sh != null && sh.enabled) t += sh.FurTriangles;
                var st = r.GetComponent<StrandFurRenderer>(); if (st != null && st.enabled) t += st.FurTriangles;
                var fn = r.GetComponent<FinFurRenderer>(); if (fn != null && fn.enabled) t += Mathf.Max(0, fn.FurTriangles);
            }
            return t;
        }

        public void Restore()
        {
            foreach (var r in targets)
            {
                if (r == null) continue;
                r.sharedMaterials = original[r];
                var sh = r.GetComponent<ShellFurRenderer>();
                if (sh != null)
                {
                    if (v2Mat.TryGetValue(r, out var m2)) sh.furMaterial = m2;
                    if (originalShell.TryGetValue(r, out var s)) { sh.enabled = s.enabled; sh.shellCount = s.count; sh.adaptiveShells = true; }
                    else UnityEngine.Object.Destroy(sh);
                }
                var st = r.GetComponent<StrandFurRenderer>(); if (st != null) UnityEngine.Object.Destroy(st);
                var fn = r.GetComponent<FinFurRenderer>(); if (fn != null) UnityEngine.Object.Destroy(fn);
            }
        }

        // ---------- builders ----------

        static Texture BaseTex(Material m)
        {
            if (m == null) return null;
            foreach (var p in new[] { "_BaseMap", "baseColorTexture", "_MainTex" })
                if (m.HasProperty(p) && m.GetTexture(p) != null) return m.GetTexture(p);
            return null;
        }

        Material[] Textured(Renderer r)
        {
            if (textured.TryGetValue(r, out var mats)) return mats;
            var src = original[r];
            mats = new Material[src.Length];
            for (int i = 0; i < src.Length; i++)
            {
                Material m;
                if (assets.normalMappedMaterial != null)
                {
                    m = new Material(assets.normalMappedMaterial);
                    m.SetTexture("_BaseMap", BaseTex(src[i]));
                }
                else
                {
                    m = new Material(src[i]);
                    m.SetTexture("_BumpMap", GeneratedNormal());
                    m.SetFloat("_BumpScale", 1f);
                    m.EnableKeyword("_NORMALMAP");
                }
                if (m.HasProperty("_Smoothness")) m.SetFloat("_Smoothness", 0.25f);   // fur is not shiny
                mats[i] = m;
            }
            textured[r] = mats;
            return mats;
        }

        ShellFurRenderer AddShell(Renderer r)
        {
            if (assets.shellMaterial == null) return null;
            var m = new Material(assets.shellMaterial);
            m.SetTexture("_LengthMap", lengthMap);
            if (flowMap != null) m.SetTexture("_FlowMap", flowMap);
            m.SetFloat("_FurLength", furLength);
            m.SetFloat("_FlipHairMaps", flip ? 1 : 0);
            var bt = BaseTex(original[r].Length > 0 ? original[r][0] : null);
            if (bt != null) m.SetTexture("_BaseMap", bt);
            var sh = r.gameObject.AddComponent<ShellFurRenderer>();
            sh.furMaterial = m;
            return sh;
        }

        /// <summary>The old (v1) shell material with this pet's maps, for the before/after comparison.</summary>
        Material V1(Renderer r, Material v2)
        {
            if (v1Mat.TryGetValue(r, out var m)) return m;
            m = new Material(assets.shellV1Material);
            if (v2 != null)
            {
                foreach (var tex in new[] { "_BaseMap", "_LengthMap", "_FlowMap" })
                    if (v2.HasProperty(tex) && m.HasProperty(tex)) m.SetTexture(tex, v2.GetTexture(tex));
                foreach (var f in new[] { "_FurLength", "_FlipHairMaps", "_KeyOutGreen", "_MinLength" })
                    if (v2.HasProperty(f) && m.HasProperty(f)) m.SetFloat(f, v2.GetFloat(f));
            }
            v1Mat[r] = m;
            return m;
        }

        StrandFurRenderer AddStrands(Renderer r)
        {
            var m = new Material(assets.strandMaterial);
            if (flowMap != null) m.SetTexture("_FlowMap", flowMap);
            var bt = BaseTex(original[r].Length > 0 ? original[r][0] : null);
            if (bt != null) m.SetTexture("_BaseMap", bt);
            var st = r.gameObject.AddComponent<StrandFurRenderer>();
            st.strandMaterial = m;
            st.lengthMap = lengthMap;
            st.furLength = furLength;
            st.flipHairMaps = flip;
            return st;
        }

        FinFurRenderer AddFins(Renderer r)
        {
            var m = new Material(assets.finMaterial);
            m.SetTexture("_LengthMap", lengthMap);
            m.SetFloat("_FurLength", furLength);
            m.SetFloat("_FlipHairMaps", flip ? 1 : 0);
            var bt = BaseTex(original[r].Length > 0 ? original[r][0] : null);
            if (bt != null) m.SetTexture("_BaseMap", bt);
            var fn = r.gameObject.AddComponent<FinFurRenderer>();
            fn.finMaterial = m;
            return fn;
        }

        /// <summary>1024² fine-stripe normal map (fur-like grain). Alpha = 1 so it decodes on both RGB and AG layouts.</summary>
        static Texture2D GeneratedNormal()
        {
            if (generatedNormal != null) return generatedNormal;
            const int N = 1024;
            var h = new float[N * N];
            for (int y = 0; y < N; y++)
                for (int x = 0; x < N; x++)
                {
                    int sx = x / 2, sy = y / 12;                       // thin strands running along V
                    uint k = (uint)(sx * 73856093) ^ (uint)(sy * 19349663);
                    k ^= k >> 13; k *= 0x5bd1e995; k ^= k >> 15;
                    h[y * N + x] = (k & 1023) / 1023f;
                }
            var px = new Color32[N * N];
            for (int y = 0; y < N; y++)
                for (int x = 0; x < N; x++)
                {
                    float dx = h[y * N + (x + 1) % N] - h[y * N + (x + N - 1) % N];
                    float dy = h[((y + 1) % N) * N + x] - h[((y + N - 1) % N) * N + x];
                    var n = new Vector3(-dx * 1.5f, -dy * 0.3f, 1f).normalized;
                    px[y * N + x] = new Color32((byte)((n.x * 0.5f + 0.5f) * 255), (byte)((n.y * 0.5f + 0.5f) * 255), (byte)((n.z * 0.5f + 0.5f) * 255), 255);
                }
            generatedNormal = new Texture2D(N, N, TextureFormat.RGBA32, true, true) { name = "GeneratedFurNormal", wrapMode = TextureWrapMode.Repeat };
            generatedNormal.SetPixels32(px);
            generatedNormal.Apply(true, true);
            return generatedNormal;
        }
    }
}
