using System.Collections.Generic;
using System.Runtime.InteropServices;
using UnityEngine;
using UnityEngine.Rendering;

namespace Beside.FurLab
{
    /// <summary>
    /// Strand (guide-hair) fur: strandCount ribbons scattered over the mesh, length from the hair length map.
    /// Put on the GameObject with the SkinnedMeshRenderer (or MeshRenderer). Rebuilds when strandCount / segments change.
    ///
    /// For now strands are scattered evenly by surface area. Later the real AnimalLift strands (hair.npz) or a
    /// per-region mask can feed the same buffer — the rendering cost per strand stays the same, which is what
    /// the benchmark measures.
    /// </summary>
    public class StrandFurRenderer : MonoBehaviour
    {
        public Material strandMaterial;               // Custom/StrandFur
        public Texture2D lengthMap;                    // readable, same as the shell's _LengthMap
        public float furLength = 0.065f;               // object units, same meaning as ShellFur _FurLength
        public bool flipHairMaps;
        [Range(0f, 0.5f)] public float minLength = 0.05f;

        [Min(1)] public int strandCount = 5000;
        [Range(1, 8)] public int segments = 4;
        public int seed = 1234;
        public bool enablePhysics = true;
        public float boundsPadding = 0.2f;

        [Tooltip("Optional: only scatter where this mask (R channel, hair UV) is above 0.5. For per-region tests later.")]
        public Texture2D regionMask;

        [StructLayout(LayoutKind.Sequential)]
        struct Strand { public uint v0, v1, v2; public Vector2 bary; public Vector2 uv; public float len; public float seed; }

        Renderer source;
        GraphicsBuffer strands;
        int builtCount = -1, builtSegments = -1;
        MaterialPropertyBlock props;
        readonly FurSpring spring = new FurSpring();
        Vector3 bendOS;

        public int BuiltStrands { get; private set; }
        public int FurTriangles => BuiltStrands * segments * 2;

        void OnEnable()
        {
            source = GetComponent<SkinnedMeshRenderer>();
            if (source == null) source = GetComponent<MeshRenderer>();
            props = new MaterialPropertyBlock();
            RenderPipelineManager.beginCameraRendering += Draw;
        }

        void OnDisable()
        {
            RenderPipelineManager.beginCameraRendering -= Draw;
        }

        void OnDestroy()
        {
            strands?.Dispose();
            strands = null;
        }

        void LateUpdate() => bendOS = spring.Step(transform, enablePhysics);

        public void Rebuild() => builtCount = -1;

        void Build()
        {
            strands?.Dispose(); strands = null;
            BuiltStrands = 0;
            builtCount = strandCount; builtSegments = segments;

            Mesh mesh = FurPose.GetMesh(source);
            if (mesh == null || !mesh.isReadable) { Debug.LogWarning("[StrandFur] mesh missing or not readable"); return; }
            var verts = mesh.vertices;
            var uvs = mesh.uv;
            var tris = mesh.triangles;
            if (uvs == null || uvs.Length != verts.Length) { Debug.LogWarning("[StrandFur] mesh has no UVs"); return; }

            int triCount = tris.Length / 3;
            var cum = new float[triCount];
            float total = 0f;
            for (int i = 0; i < triCount; i++)
            {
                Vector3 a = verts[tris[3 * i]], b = verts[tris[3 * i + 1]], c = verts[tris[3 * i + 2]];
                total += Vector3.Cross(b - a, c - a).magnitude * 0.5f;
                cum[i] = total;
            }

            bool canReadLen = lengthMap != null && lengthMap.isReadable;
            bool canReadMask = regionMask != null && regionMask.isReadable;
            if (lengthMap != null && !canReadLen) Debug.LogWarning("[StrandFur] length map not readable (Read/Write off): uniform length used");

            var rnd = new System.Random(seed);
            var list = new List<Strand>(strandCount);
            int attempts = 0, maxAttempts = strandCount * 6;
            while (list.Count < strandCount && attempts++ < maxAttempts)
            {
                float pick = (float)rnd.NextDouble() * total;
                int t = System.Array.BinarySearch(cum, pick);
                if (t < 0) t = ~t;
                t = Mathf.Min(t, triCount - 1);

                float r1 = Mathf.Sqrt((float)rnd.NextDouble()), r2 = (float)rnd.NextDouble();
                float wa = 1 - r1, wb = r1 * (1 - r2), wc = r1 * r2;
                int ia = tris[3 * t], ib = tris[3 * t + 1], ic = tris[3 * t + 2];
                Vector2 uv = uvs[ia] * wa + uvs[ib] * wb + uvs[ic] * wc;
                Vector2 huv = new Vector2(uv.x, flipHairMaps ? 1 - uv.y : uv.y);

                float l = canReadLen ? lengthMap.GetPixelBilinear(huv.x, huv.y).r : 1f;
                if (l < minLength) continue;
                if (canReadMask && regionMask.GetPixelBilinear(huv.x, huv.y).r < 0.5f) continue;

                list.Add(new Strand
                {
                    v0 = (uint)ia, v1 = (uint)ib, v2 = (uint)ic,
                    bary = new Vector2(wa, wb), uv = uv,
                    len = l * furLength * Mathf.Lerp(0.7f, 1f, (float)rnd.NextDouble()),
                    seed = (float)rnd.NextDouble() * 100f,
                });
            }
            if (list.Count == 0) { Debug.LogWarning("[StrandFur] no strand passed the length/mask test"); return; }

            strands = new GraphicsBuffer(GraphicsBuffer.Target.Structured, list.Count, Marshal.SizeOf<Strand>());
            strands.SetData(list);
            BuiltStrands = list.Count;
            Debug.Log($"[StrandFur] built {BuiltStrands} strands x {segments} segments");
        }

        void Draw(ScriptableRenderContext ctx, Camera cam)
        {
            if (strandMaterial == null || source == null) return;
            if (builtCount != strandCount || builtSegments != segments) Build();
            if (strands == null || BuiltStrands == 0) return;

            var vb = FurPose.GetVertexBuffer(source, false, out var layout);
            if (vb == null || !layout.valid) return;

            props.SetBuffer("_Strands", strands);
            props.SetBuffer("_Verts", vb);
            props.SetInteger("_Stride", layout.stride);
            props.SetInteger("_PosOff", layout.posOffset);
            props.SetInteger("_NrmOff", layout.normalOffset);
            props.SetInteger("_TanOff", layout.tangentOffset);
            props.SetInteger("_Segments", segments);
            props.SetMatrix("_FurL2W", FurPose.DrawMatrix(source, false, out float unitScale));
            props.SetFloat("_FurUnitScale", unitScale);
            props.SetVector("_FurDisplace", bendOS);
            props.SetFloat("_FlipHairMaps", flipHairMaps ? 1 : 0);

            Bounds b = source.bounds;
            b.Expand(boundsPadding * 2f);
            var rp = new RenderParams(strandMaterial)
            {
                camera = cam, layer = gameObject.layer, worldBounds = b, matProps = props,
                shadowCastingMode = ShadowCastingMode.Off, receiveShadows = false,
            };
            for (int r = 0; r < Mathf.Max(1, FurLoad.Repeat); r++)
                Graphics.RenderPrimitives(rp, MeshTopology.Triangles, BuiltStrands * segments * 6);
        }
    }
}
