using System.Collections.Generic;
using System.Runtime.InteropServices;
using UnityEngine;
using UnityEngine.Rendering;

namespace Beside.FurLab
{
    /// <summary>
    /// Fin fur on the silhouette of the mesh. Meant to be combined with ShellFurRenderer (shell + fins).
    /// Put on the GameObject with the SkinnedMeshRenderer (or MeshRenderer).
    /// Every unique mesh edge gets one fin; the shader collapses fins that are not on the outline.
    /// </summary>
    public class FinFurRenderer : MonoBehaviour
    {
        public Material finMaterial;          // Custom/FinFur (needs _LengthMap, _BaseMap, _FurLength set)
        public bool enablePhysics = true;
        public float boundsPadding = 0.2f;

        [StructLayout(LayoutKind.Sequential)]
        struct Edge { public uint v0, v1; public Vector4 uv; }

        Renderer source;
        GraphicsBuffer edges;
        MaterialPropertyBlock props;
        readonly FurSpring spring = new FurSpring();
        Vector3 bendOS;

        public int EdgeCount { get; private set; }
        public int FurTriangles => EdgeCount * 2;

        void OnEnable()
        {
            source = GetComponent<SkinnedMeshRenderer>();
            if (source == null) source = GetComponent<MeshRenderer>();
            props = new MaterialPropertyBlock();
            RenderPipelineManager.beginCameraRendering += Draw;
        }

        void OnDisable() => RenderPipelineManager.beginCameraRendering -= Draw;

        void OnDestroy() { edges?.Dispose(); edges = null; }

        void LateUpdate() => bendOS = spring.Step(transform, enablePhysics);

        void Build()
        {
            Mesh mesh = FurPose.GetMesh(source);
            if (mesh == null || !mesh.isReadable) { Debug.LogWarning("[FinFur] mesh missing or not readable"); EdgeCount = -1; return; }
            var tris = mesh.triangles;
            var uvs = mesh.uv;
            bool hasUv = uvs != null && uvs.Length == mesh.vertexCount;

            var seen = new HashSet<long>();
            var list = new List<Edge>(tris.Length);
            for (int i = 0; i < tris.Length; i += 3)
                for (int k = 0; k < 3; k++)
                {
                    int a = tris[i + k], b = tris[i + (k + 1) % 3];
                    long key = a < b ? ((long)a << 32) | (uint)b : ((long)b << 32) | (uint)a;
                    if (!seen.Add(key)) continue;
                    Vector2 ua = hasUv ? uvs[a] : Vector2.zero, ub = hasUv ? uvs[b] : Vector2.zero;
                    list.Add(new Edge { v0 = (uint)a, v1 = (uint)b, uv = new Vector4(ua.x, ua.y, ub.x, ub.y) });
                }

            edges = new GraphicsBuffer(GraphicsBuffer.Target.Structured, list.Count, Marshal.SizeOf<Edge>());
            edges.SetData(list);
            EdgeCount = list.Count;
            Debug.Log($"[FinFur] {EdgeCount} edges");
        }

        void Draw(ScriptableRenderContext ctx, Camera cam)
        {
            if (finMaterial == null || source == null || EdgeCount < 0) return;
            if (edges == null) Build();
            if (edges == null) return;

            var vb = FurPose.GetVertexBuffer(source, false, out var layout);
            if (vb == null || !layout.valid) return;

            props.SetBuffer("_Edges", edges);
            props.SetBuffer("_Verts", vb);
            props.SetInteger("_Stride", layout.stride);
            props.SetInteger("_PosOff", layout.posOffset);
            props.SetInteger("_NrmOff", layout.normalOffset);
            props.SetMatrix("_FurL2W", FurPose.DrawMatrix(source, false, out float unitScale));
            props.SetFloat("_FurUnitScale", unitScale);
            props.SetVector("_FurDisplace", bendOS);

            Bounds b = source.bounds;
            b.Expand(boundsPadding * 2f);
            var rp = new RenderParams(finMaterial)
            {
                camera = cam, layer = gameObject.layer, worldBounds = b, matProps = props,
                shadowCastingMode = ShadowCastingMode.Off, receiveShadows = false,
            };
            for (int r = 0; r < Mathf.Max(1, FurLoad.Repeat); r++)
                Graphics.RenderPrimitives(rp, MeshTopology.Triangles, EdgeCount * 6);
        }
    }
}
