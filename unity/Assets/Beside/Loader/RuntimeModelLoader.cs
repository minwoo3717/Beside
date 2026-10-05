using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using GLTFast;
using UnityEngine;
using UnityEngine.Networking;
using UnityEngine.XR.Interaction.Toolkit.Samples.StarterAssets;

/// <summary>
/// Loads a GLB at runtime (URL or local file), makes it AR-ready and registers it with the ObjectSpawner:
///  - wraps it in an interactable shell prefab (move / rotate / scale)
///  - optional height normalization, feet placed on the floor (pivot at the bottom)
///  - BoxCollider generated from the mesh bounds
///  - optional shell fur from hair length / flow maps
/// After loading, the next tap on a detected plane spawns the new model.
/// </summary>
public class RuntimeModelLoader : MonoBehaviour
{
    [Header("References")]
    [Tooltip("ObjectSpawner in the scene. Found automatically if empty.")]
    public ObjectSpawner objectSpawner;

    [Tooltip("Copy of besideObj with the model, Visuals and Cube_COL removed (interaction setup only).")]
    public GameObject shellPrefab;

    [Tooltip("Spawner slot the loaded model replaces (0 = first menu button). -1 = add as a new slot.")]
    public int replaceSlot = 0;

    public enum NormalizeMode { None, Height, LongestAxis }

    [Header("Model Fitting")]
    [Tooltip("Height: scale so the model is Target Size tall. LongestAxis: longest side = Target Size (GLB_SPEC: generator normalizes to 1 m, app shows at 0.6). None: use GLB units as-is.")]
    public NormalizeMode normalizeMode = NormalizeMode.Height;
    [Tooltip("Target size in meters for the chosen Normalize Mode.")]
    public float targetSize = 0.4f;

    [Header("Materials")]
    [Tooltip("Optional URP/Lit material (e.g. Dog_Mat). If set, the GLB's own materials are replaced by copies of it " +
             "with the GLB texture. Avoids pink models in builds when glTFast shaders are stripped.")]
    public Material baseMaterialTemplate;

    [Header("Fur (optional)")]
    [Tooltip("Material with the Custom/ShellFur shader. Leave empty to skip fur.")]
    public Material furMaterialTemplate;
    [Range(4, 64)] public int furShellCount = 20;

    [Header("Test (loads on Start if set)")]
    [Tooltip("http(s) URL or absolute file path of a .glb")]
    public string testGlbUrl;
    public string testHairLengthUrl;
    public string testHairFlowUrl;

    /// <summary>Called when a model has been loaded and registered. Argument: the template object.</summary>
    public event Action<GameObject> ModelReady;

    Transform templateRoot;                               // inactive holder: templates never run or render
    readonly List<GltfImport> imports = new List<GltfImport>(); // keep alive: spawned copies share their meshes/textures

    void Awake()
    {
        var holder = new GameObject("RuntimeModelTemplates");
        holder.SetActive(false);
        templateRoot = holder.transform;

        if (objectSpawner == null)
            objectSpawner = FindFirstObjectByType<ObjectSpawner>();
    }

    async void Start()
    {
        if (!string.IsNullOrEmpty(testGlbUrl))
            await LoadAndRegister(testGlbUrl, testHairLengthUrl, testHairFlowUrl);
    }

    void OnDestroy()
    {
        foreach (var gltf in imports) gltf.Dispose();
        imports.Clear();
    }

    /// <summary>Load a GLB (and optional hair maps) from a URL or local path and make it the next object to spawn.</summary>
    public async Task<GameObject> LoadAndRegister(string glbUrl, string hairLengthUrl = null, string hairFlowUrl = null)
    {
        var gltf = new GltfImport();
        if (!await gltf.Load(glbUrl))
        {
            Debug.LogError($"[RuntimeModelLoader] Failed to load GLB: {glbUrl}");
            gltf.Dispose();
            return null;
        }
        Texture2D lengthMap = string.IsNullOrEmpty(hairLengthUrl) ? null : await DownloadLinearTexture(hairLengthUrl);
        Texture2D flowMap   = string.IsNullOrEmpty(hairFlowUrl)   ? null : await DownloadLinearTexture(hairFlowUrl);
        return await BuildAndRegister(gltf, System.IO.Path.GetFileNameWithoutExtension(glbUrl), lengthMap, flowMap);
    }

    /// <summary>Same, from GLB bytes already downloaded (lets the caller measure download time and validate the file).</summary>
    public async Task<GameObject> LoadAndRegister(byte[] glbBytes, string name, byte[] hairLengthPng = null, byte[] hairFlowPng = null)
    {
        if (!IsGlb(glbBytes))
        {
            Debug.LogError("[RuntimeModelLoader] Data is not a GLB file (bad magic header).");
            return null;
        }
        var gltf = new GltfImport();
        if (!await gltf.LoadGltfBinary(glbBytes))
        {
            Debug.LogError("[RuntimeModelLoader] Failed to parse GLB bytes.");
            gltf.Dispose();
            return null;
        }
        Texture2D lengthMap = hairLengthPng == null ? null : LinearTexture(hairLengthPng);
        Texture2D flowMap   = hairFlowPng   == null ? null : LinearTexture(hairFlowPng);
        return await BuildAndRegister(gltf, name, lengthMap, flowMap);
    }

    /// <summary>True if the bytes start with the glTF binary magic "glTF".</summary>
    public static bool IsGlb(byte[] data) =>
        data != null && data.Length > 12 && data[0] == 0x67 && data[1] == 0x6C && data[2] == 0x54 && data[3] == 0x46;

    async Task<GameObject> BuildAndRegister(GltfImport gltf, string name, Texture2D lengthMap, Texture2D flowMap)
    {
        if (shellPrefab == null) { Debug.LogError("[RuntimeModelLoader] Shell Prefab is not set."); gltf.Dispose(); return null; }
        imports.Add(gltf);

        GameObject template = Instantiate(shellPrefab, templateRoot);
        template.name = "Runtime_" + name;

        var modelRoot = new GameObject("LoadedModel").transform;
        modelRoot.SetParent(template.transform, false);

        if (!await gltf.InstantiateMainSceneAsync(modelRoot))
        {
            Debug.LogError("[RuntimeModelLoader] Failed to instantiate GLB scene.");
            Destroy(template);
            return null;
        }

        if (baseMaterialTemplate != null)
            ReplaceMaterials(modelRoot, gltf);

        if (!FitModel(template.transform, modelRoot))
        {
            Debug.LogError("[RuntimeModelLoader] GLB contains no meshes.");
            Destroy(template);
            return null;
        }

        if (furMaterialTemplate != null && lengthMap != null)
            AddFur(modelRoot, lengthMap, flowMap);

        Register(template);
        Debug.Log($"[RuntimeModelLoader] Ready: {template.name}");
        ModelReady?.Invoke(template);
        return template;
    }

    // ---------- fitting ----------

    bool FitModel(Transform shell, Transform model)
    {
        // Mesh bounds in shell space (Renderer.bounds is empty for inactive objects, so use mesh data)
        bool any = false;
        Bounds b = default;
        Matrix4x4 toShell = shell.worldToLocalMatrix;

        void Add(Mesh mesh, Transform t)
        {
            if (mesh == null) return;
            Matrix4x4 m = toShell * t.localToWorldMatrix;
            Bounds mb = mesh.bounds;
            for (int i = 0; i < 8; i++)
            {
                Vector3 corner = mb.center + Vector3.Scale(mb.extents,
                    new Vector3((i & 1) == 0 ? -1 : 1, (i & 2) == 0 ? -1 : 1, (i & 4) == 0 ? -1 : 1));
                Vector3 p = m.MultiplyPoint3x4(corner);
                if (!any) { b = new Bounds(p, Vector3.zero); any = true; }
                else b.Encapsulate(p);
            }
        }

        foreach (var mf in model.GetComponentsInChildren<MeshFilter>(true)) Add(mf.sharedMesh, mf.transform);
        foreach (var smr in model.GetComponentsInChildren<SkinnedMeshRenderer>(true)) Add(smr.sharedMesh, smr.transform);
        if (!any) return false;

        float scale = 1f;
        if (normalizeMode == NormalizeMode.Height && b.size.y > 1e-5f) scale = targetSize / b.size.y;
        else if (normalizeMode == NormalizeMode.LongestAxis)
        {
            float longest = Mathf.Max(b.size.x, b.size.y, b.size.z);
            if (longest > 1e-5f) scale = targetSize / longest;
        }

        // Scale around the shell origin, then move so the bottom-center sits at the origin (feet on the floor)
        model.localScale = Vector3.one * scale;
        model.localPosition = new Vector3(-b.center.x, -b.min.y, -b.center.z) * scale;

        // Collider in the model root's local space (= unscaled bounds)
        var col = model.gameObject.AddComponent<BoxCollider>();
        col.center = b.center;
        col.size = b.size;
        return true;
    }

    // ---------- materials ----------

    void ReplaceMaterials(Transform model, GltfImport gltf)
    {
        // Fallback texture straight from the GLB, in case the glTFast material could not be created
        Texture fallback = gltf.TextureCount > 0 ? gltf.GetTexture(0) : null;

        foreach (var r in model.GetComponentsInChildren<Renderer>(true))
        {
            var oldMats = r.sharedMaterials;
            var newMats = new Material[Mathf.Max(1, oldMats.Length)];
            for (int i = 0; i < newMats.Length; i++)
            {
                Material old = i < oldMats.Length ? oldMats[i] : null;
                Texture tex = GetBaseTexture(old) ?? fallback;
                var m = new Material(baseMaterialTemplate);
                m.SetTexture("_BaseMap", tex);
                m.SetColor("_BaseColor", Color.white);
                newMats[i] = m;
            }
            r.sharedMaterials = newMats;
        }
        Debug.Log("[RuntimeModelLoader] GLB materials replaced with URP/Lit copies");
    }

    // ---------- fur ----------

    void AddFur(Transform model, Texture2D lengthMap, Texture2D flowMap)
    {
        foreach (var mf in model.GetComponentsInChildren<MeshFilter>(true))
        {
            var mat = new Material(furMaterialTemplate);
            mat.SetTexture("_LengthMap", lengthMap);
            if (flowMap != null) mat.SetTexture("_FlowMap", flowMap);

            var baseRenderer = mf.GetComponent<Renderer>();
            Texture baseTex = baseRenderer != null ? GetBaseTexture(baseRenderer.sharedMaterial) : null;
            if (baseTex != null) mat.SetTexture("_BaseMap", baseTex);

            var fur = mf.gameObject.AddComponent<ShellFurRenderer>();
            fur.furMaterial = mat;
            fur.shellCount = furShellCount;
        }
    }

    static Texture GetBaseTexture(Material m)
    {
        if (m == null) return null;
        foreach (var prop in new[] { "baseColorTexture", "_BaseMap", "_MainTex" })
            if (m.HasProperty(prop) && m.GetTexture(prop) != null) return m.GetTexture(prop);
        return null;
    }

    static async Task<Texture2D> DownloadLinearTexture(string url)
    {
        byte[] data = await Download(url);
        return data == null ? null : LinearTexture(data);
    }

    static Texture2D LinearTexture(byte[] png)
    {
        var tex = new Texture2D(2, 2, TextureFormat.RGBA32, false, true); // linear: data, not color
        if (!tex.LoadImage(png)) { Debug.LogError("[RuntimeModelLoader] Hair map is not a valid image."); return null; }
        tex.wrapMode = TextureWrapMode.Clamp;
        return tex;
    }

    static async Task<byte[]> Download(string url)
    {
        if (!url.Contains("://")) url = "file://" + url; // local path
        using var req = UnityWebRequest.Get(url);
        var op = req.SendWebRequest();
        while (!op.isDone) await Task.Yield();
        if (req.result != UnityWebRequest.Result.Success)
        {
            Debug.LogError($"[RuntimeModelLoader] Download failed: {url} ({req.error})");
            return null;
        }
        return req.downloadHandler.data;
    }

    // ---------- spawner ----------

    void Register(GameObject template)
    {
        if (objectSpawner == null) { Debug.LogError("[RuntimeModelLoader] No ObjectSpawner found."); return; }
        var list = objectSpawner.objectPrefabs;
        int index;
        if (replaceSlot >= 0 && replaceSlot < list.Count)
        {
            list[replaceSlot] = template;   // the menu button for this slot now spawns the loaded model
            index = replaceSlot;
        }
        else
        {
            list.Add(template);
            index = list.Count - 1;
        }
        objectSpawner.spawnOptionIndex = index; // next tap spawns this model
        Debug.Log($"[RuntimeModelLoader] Registered in spawner slot {index}");
    }
}
