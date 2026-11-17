using UnityEngine;
using UnityEngine.Rendering;

namespace Beside.FurLab
{
    /// <summary>
    /// Benchmark-only: draws the pet's own mesh (FurLoad.Repeat - 1) extra times with its own materials, so the
    /// base model is multiplied like the fur. Added by FurBenchmark during a run, removed afterwards.
    /// </summary>
    public class PetLoadRepeater : MonoBehaviour
    {
        Renderer source;

        void OnEnable()
        {
            source = GetComponent<SkinnedMeshRenderer>();
            if (source == null) source = GetComponent<MeshRenderer>();
            RenderPipelineManager.beginCameraRendering += Draw;
        }

        void OnDisable() => RenderPipelineManager.beginCameraRendering -= Draw;

        void Draw(ScriptableRenderContext ctx, Camera cam)
        {
            int extra = FurLoad.Repeat - 1;
            if (extra <= 0 || source == null || !source.enabled) return;
            Mesh mesh = FurPose.GetMesh(source);
            if (mesh == null) return;
            Matrix4x4 m = FurPose.DrawMatrix(source, false, out _);
            var mats = source.sharedMaterials;
            for (int sub = 0; sub < mesh.subMeshCount && sub < mats.Length; sub++)
            {
                if (mats[sub] == null) continue;
                var rp = new RenderParams(mats[sub])
                {
                    camera = cam, layer = gameObject.layer, worldBounds = source.bounds,
                    shadowCastingMode = ShadowCastingMode.Off, receiveShadows = true,
                };
                for (int k = 0; k < extra; k++) Graphics.RenderMesh(rp, mesh, sub, m);
            }
        }
    }
}
