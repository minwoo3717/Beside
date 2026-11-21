using UnityEngine;
using UnityEngine.Rendering;

/// <summary>
/// Draws the mesh on this GameObject several extra times with the ShellFur material,
/// each time a little further out along the normals, to create shell fur.
/// Put it on the object that has the MeshFilter, or the SkinnedMeshRenderer for rigged models.
/// Skinned: the animated pose is baked once per frame (after animation) and every shell reuses that one mesh.
/// </summary>
[ExecuteAlways]
public class ShellFurRenderer : MonoBehaviour
{
    [Tooltip("Material using the Custom/ShellFur shader")]
    public Material furMaterial;

    [Tooltip("More shells = denser fur but slower. 16-24 is good for phones.")]
    [Range(4, 64)] public int shellCount = 20;

    [Header("Close-up cost (fill rate)")]
    [Tooltip("Use fewer shells when the pet fills a large part of the screen. Shell cost = shells x covered pixels, " +
             "so a close-up costs several times more than the same pet further away.")]
    public bool adaptiveShells = true;
    [Range(4, 64)] public int minShells = 10;
    [Tooltip("Screen share (0-1, bounding box) where shell reduction starts / where Min Shells is reached.")]
    [Range(0f, 1f)] public float reduceFrom = 0.2f;
    [Range(0f, 1f)] public float reduceTo = 0.6f;

    /// <summary>Shells drawn in the last frame (after the close-up reduction).</summary>
    public int LastShells { get; private set; }

    [Tooltip("Extra margin for culling so fur at the edges is not cut off (world units).")]
    public float boundsPadding = 0.2f;

    [Header("Fur Physics (Play mode only)")]
    public bool enablePhysics = true;

    [Tooltip("How much the fur lags behind when the dog moves. Higher = more swing.")]
    public float inertia = 15f;

    [Tooltip("How quickly the fur springs back. Higher = stiffer, faster.")]
    public float stiffness = 60f;

    [Tooltip("How quickly the wobble calms down. Higher = less bouncing.")]
    public float damping = 6f;

    [Tooltip("Maximum bend, relative to fur length.")]
    [Range(0f, 2f)] public float maxBend = 0.8f;

    static readonly int ShellHId = Shader.PropertyToID("_ShellH");
    static readonly int DisplaceId = Shader.PropertyToID("_FurDisplace");
    static readonly int UnitScaleId = Shader.PropertyToID("_FurUnitScale");

    Vector3 lastPosition;
    bool hasLastPosition;
    Vector3 offset;      // current fur bend (world space, relative to fur length)
    Vector3 offsetVel;

    [Header("Rigged models")]
    [Tooltip("Turn on only if the fur appears at the wrong size on a scaled skinned model.")]
    public bool bakeUseScale = false;

    MeshFilter meshFilter;
    SkinnedMeshRenderer skinned;
    Mesh bakedMesh;
    int bakedFrame = -1;
    Renderer baseRenderer;
    MaterialPropertyBlock[] blocks;
    readonly System.Collections.Generic.Dictionary<int, MaterialPropertyBlock[]> blocksByCount =
        new System.Collections.Generic.Dictionary<int, MaterialPropertyBlock[]>();
    Vector3 offsetOS;

    /// <summary>The mesh to draw this frame: static mesh, or the skinned pose baked once per frame.</summary>
    Mesh CurrentMesh()
    {
        if (skinned == null) return meshFilter != null ? meshFilter.sharedMesh : null;
        // shared with fins/strands on the same model (Beside/FurLab): one bake per frame in total
        return Beside.FurLab.FurPose.GetMesh(skinned, bakeUseScale);
    }

    /// <summary>Triangles drawn per frame by the shells (for benchmark logs).</summary>
    public int FurTriangles
    {
        get
        {
            Mesh m = skinned != null ? skinned.sharedMesh : meshFilter != null ? meshFilter.sharedMesh : null;
            if (m == null) return 0;
            long idx = 0;
            for (int s = 0; s < m.subMeshCount; s++) idx += m.GetIndexCount(s);
            return (int)(idx / 3) * (LastShells > 0 ? LastShells : shellCount);
        }
    }

    void OnDestroy()
    {
        if (bakedMesh != null) { if (Application.isPlaying) Destroy(bakedMesh); else DestroyImmediate(bakedMesh); }
        // the pose bake now lives in FurPose: free it with this renderer instead of waiting for FurPose to prune
        // (> 16 entries). Play mode only: Release uses Object.Destroy and this class also runs in the editor.
        if (Application.isPlaying && skinned != null) Beside.FurLab.FurPose.Release(skinned);
    }

    void OnEnable()
    {
        meshFilter = GetComponent<MeshFilter>();
        skinned = GetComponent<SkinnedMeshRenderer>();
        baseRenderer = GetComponent<Renderer>();
        FurNoise.Assign(furMaterial);      // ShellFur v2 strand noise
        BuildBlocks();
        RenderPipelineManager.beginCameraRendering += OnBeginCameraRendering;
    }

    void OnDisable()
    {
        RenderPipelineManager.beginCameraRendering -= OnBeginCameraRendering;
    }

    void OnValidate()
    {
        blocks = null; // rebuild with the new shell count
        blocksByCount.Clear();
    }

    void LateUpdate()
    {
        float dt = Time.deltaTime;
        if (!Application.isPlaying || !enablePhysics || dt <= 0f)
        {
            offset = offsetVel = Vector3.zero;
            hasLastPosition = false;
        }
        else
        {
            Vector3 pos = transform.position;
            if (!hasLastPosition) { lastPosition = pos; hasLastPosition = true; }
            Vector3 delta = pos - lastPosition;
            lastPosition = pos;

            // Ignore teleports (spawning, big tracking jumps)
            if (delta.magnitude < 0.5f)
                offset -= delta * inertia;              // fur is left behind by the movement

            // Damped spring pulling the fur back to rest
            offsetVel += -offset * stiffness * dt;
            offsetVel *= 1f / (1f + damping * dt);
            offset += offsetVel * dt;
            offset = Vector3.ClampMagnitude(offset, maxBend);
        }

        offsetOS = transform.InverseTransformDirection(offset);
    }

    void BuildBlocks() => blocks = Blocks(shellCount);

    /// <summary>One property block per shell for a given shell count (heights spread evenly over 0..1).</summary>
    MaterialPropertyBlock[] Blocks(int count)
    {
        if (blocksByCount.TryGetValue(count, out var b)) return b;
        b = new MaterialPropertyBlock[count];
        for (int i = 0; i < count; i++)
        {
            b[i] = new MaterialPropertyBlock();
            b[i].SetFloat(ShellHId, (i + 1f) / count);
        }
        blocksByCount[count] = b;
        return b;
    }

    /// <summary>Shell count for this camera: full count far away, fewer when the pet fills the screen.</summary>
    int ShellsFor(Camera cam, Bounds b)
    {
        if (!adaptiveShells || !Application.isPlaying || cam == null) return shellCount;
        float x0 = 1, y0 = 1, x1 = 0, y1 = 0;
        for (int i = 0; i < 8; i++)
        {
            Vector3 p = b.center + Vector3.Scale(b.extents, new Vector3((i & 1) == 0 ? -1 : 1, (i & 2) == 0 ? -1 : 1, (i & 4) == 0 ? -1 : 1));
            Vector3 v = cam.WorldToViewportPoint(p);
            if (v.z <= 0) { x0 = y0 = 0; x1 = y1 = 1; break; }      // camera inside / very close: treat as full screen
            x0 = Mathf.Min(x0, v.x); y0 = Mathf.Min(y0, v.y); x1 = Mathf.Max(x1, v.x); y1 = Mathf.Max(y1, v.y);
        }
        float cover = Mathf.Max(0, Mathf.Min(x1, 1) - Mathf.Max(x0, 0)) * Mathf.Max(0, Mathf.Min(y1, 1) - Mathf.Max(y0, 0));
        float t = Mathf.InverseLerp(reduceFrom, Mathf.Max(reduceFrom + 0.01f, reduceTo), cover);
        return Mathf.Max(1, Mathf.RoundToInt(Mathf.Lerp(shellCount, Mathf.Min(minShells, shellCount), t)));
    }

    void OnBeginCameraRendering(ScriptableRenderContext context, Camera cam)
    {
        if (furMaterial == null) return;
        FurNoise.Assign(furMaterial);      // material may have been set after OnEnable
        Mesh mesh = CurrentMesh();
        if (mesh == null) return;

        Bounds bounds = baseRenderer != null
            ? baseRenderer.bounds
            : new Bounds(transform.position, Vector3.one);
        bounds.Expand(boundsPadding * 2f);

        var rp = new RenderParams(furMaterial)
        {
            camera = cam,
            layer = gameObject.layer,
            shadowCastingMode = ShadowCastingMode.Off,
            receiveShadows = false,
            worldBounds = bounds
        };

        // skinned: the baked mesh is already world-size, so draw it without the transform's scale
        float unitScale = 1f;
        Matrix4x4 matrix = transform.localToWorldMatrix;
        if (skinned != null) matrix = Beside.FurLab.FurPose.DrawMatrix(skinned, bakeUseScale, out unitScale);
        int count = ShellsFor(cam, baseRenderer != null ? baseRenderer.bounds : bounds);
        var shells = Blocks(count);
        LastShells = count;
        // outermost shell first: its strands write depth, so hidden pixels of the inner shells can be skipped
        int repeat = Mathf.Max(1, Beside.FurLab.FurLoad.Repeat);   // >1 only while benchmarking
        for (int r = 0; r < repeat; r++)
            for (int i = count - 1; i >= 0; i--)
            {
                shells[i].SetFloat(UnitScaleId, unitScale);
                shells[i].SetVector(DisplaceId, offsetOS);
                rp.matProps = shells[i];
                for (int sub = 0; sub < mesh.subMeshCount; sub++)
                    Graphics.RenderMesh(rp, mesh, sub, matrix);
            }
    }
}
