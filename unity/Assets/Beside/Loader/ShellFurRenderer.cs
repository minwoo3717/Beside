using UnityEngine;
using UnityEngine.Rendering;

/// <summary>
/// Draws the mesh on this GameObject several extra times with the ShellFur material,
/// each time a little further out along the normals, to create shell fur.
/// Put it on the object that has the MeshFilter (for the dog model: "default").
/// </summary>
[ExecuteAlways]
[RequireComponent(typeof(MeshFilter))]
public class ShellFurRenderer : MonoBehaviour
{
    [Tooltip("Material using the Custom/ShellFur shader")]
    public Material furMaterial;

    [Tooltip("More shells = denser fur but slower. 16-24 is good for phones.")]
    [Range(4, 64)] public int shellCount = 20;

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

    Vector3 lastPosition;
    bool hasLastPosition;
    Vector3 offset;      // current fur bend (world space, relative to fur length)
    Vector3 offsetVel;

    MeshFilter meshFilter;
    Renderer baseRenderer;
    MaterialPropertyBlock[] blocks;

    void OnEnable()
    {
        meshFilter = GetComponent<MeshFilter>();
        baseRenderer = GetComponent<Renderer>();
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

        if (blocks == null || blocks.Length != shellCount) BuildBlocks();
        Vector3 offsetOS = transform.InverseTransformDirection(offset);
        for (int i = 0; i < blocks.Length; i++)
            blocks[i].SetVector(DisplaceId, offsetOS);
    }

    void BuildBlocks()
    {
        blocks = new MaterialPropertyBlock[shellCount];
        for (int i = 0; i < shellCount; i++)
        {
            blocks[i] = new MaterialPropertyBlock();
            blocks[i].SetFloat(ShellHId, (i + 1f) / shellCount);
        }
    }

    void OnBeginCameraRendering(ScriptableRenderContext context, Camera cam)
    {
        if (furMaterial == null || meshFilter == null) return;
        Mesh mesh = meshFilter.sharedMesh;
        if (mesh == null) return;
        if (blocks == null || blocks.Length != shellCount) BuildBlocks();

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

        Matrix4x4 matrix = transform.localToWorldMatrix;
        for (int i = 0; i < shellCount; i++)
        {
            rp.matProps = blocks[i];
            for (int sub = 0; sub < mesh.subMeshCount; sub++)
                Graphics.RenderMesh(rp, mesh, sub, matrix);
        }
    }
}
