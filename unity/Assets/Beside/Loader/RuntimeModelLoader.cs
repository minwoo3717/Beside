using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using GLTFast;
using UnityEngine;
using UnityEngine.Networking;
using Newtonsoft.Json.Linq;
using UnityEngine.XR.Interaction.Toolkit.Samples.StarterAssets;

/// <summary>
/// Loads a GLB at runtime (URL or local file), makes it AR-ready and registers it with the ObjectSpawner:
///  - wraps it in an interactable shell prefab (move / rotate / scale)
///  - optional height normalization, feet placed on the floor (pivot at the bottom)
///  - BoxCollider generated from the mesh bounds
///  - optional shell fur from hair length / flow maps
///  - optional shadow catcher quad under the pet (the AR floor planes are hidden after placement)
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
    public enum ModelFacing { PlusZ, MinusZ, PlusX, MinusX }

    [Header("Model Fitting")]
    [Tooltip("Height: scale so the model is Target Size tall. LongestAxis: longest side = Target Size (GLB_SPEC: generator normalizes to 1 m, app shows at 0.6). None: use GLB units as-is.")]
    public NormalizeMode normalizeMode = NormalizeMode.LongestAxis;
    [Tooltip("Target size in meters for the chosen Normalize Mode.")]
    public float targetSize = 0.6f;

    [Tooltip("Which local axis the model's head points to after import. The loader turns it so the head faces +Z, " +
             "which the ObjectSpawner points at the camera. Rigged GLBs (GLB_SPEC) are +Z already; the old OBJ-based sample was MinusX. " +
             "Default PlusZ.")]
    public ModelFacing modelFacing = ModelFacing.PlusZ;

    [Header("Materials")]
    [Tooltip("Optional URP/Lit material (e.g. Dog_Mat). If set, the GLB's own materials are replaced by copies of it " +
             "with the GLB texture. Avoids pink models in builds when glTFast shaders are stripped.")]
    public Material baseMaterialTemplate;

    [Header("Shadow")]
    [Tooltip("Material with the Custom/ShadowCatcher shader. A transparent quad under the pet shows its shadow, " +
             "because the AR floor planes that used to receive it are hidden after placement. Empty = no floor shadow.")]
    public Material shadowCatcherMaterial;
    [Tooltip("Shadow quad size relative to the model's longest side.")]
    public float shadowQuadScale = 1.6f;

    [Header("Fur (optional)")]
    [Tooltip("Material with the Custom/ShellFur shader. Leave empty to skip fur.")]
    public Material furMaterialTemplate;
    [Range(4, 64)] public int furShellCount = 20;

    [Tooltip("Hair maps used when the GLB carries none (e.g. the old sample). Leave empty to skip fur for such models.")]
    public Texture2D fallbackHairLength;
    public Texture2D fallbackHairFlow;

    [Header("Test (loads on Start if set)")]
    [Tooltip("http(s) URL or absolute file path of a .glb")]
    public string testGlbUrl;
    public string testHairLengthUrl;
    public string testHairFlowUrl;

    /// <summary>Called when a model has been loaded and registered. Argument: the template object.</summary>
    public event Action<GameObject> ModelReady;

    /// <summary>Name of the template child that holds the GLB scene (the ShadowCatcher quad is its sibling).</summary>
    public const string ModelRootName = "LoadedModel";

    Transform templateRoot;                               // inactive holder: templates never run or render
    readonly List<GltfImport> imports = new List<GltfImport>(); // keep alive: spawned copies share their meshes/textures

    /// <summary>What one loaded template owns: its import plus the materials and hair maps made for it.
    /// Freed when a newer model replaces it in the spawner slot.</summary>
    sealed class LoadedModel
    {
        public GltfImport gltf;
        public readonly List<UnityEngine.Object> owned = new List<UnityEngine.Object>();
    }
    readonly Dictionary<GameObject, LoadedModel> loaded = new Dictionary<GameObject, LoadedModel>();

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

    /// <summary>Same, from GLB bytes already downloaded (lets the caller measure download time and validate the file).
    /// stillWanted: checked before registering; false (job cancelled) drops the model and returns null.</summary>
    public async Task<GameObject> LoadAndRegister(byte[] glbBytes, string name, byte[] hairLengthPng = null, byte[] hairFlowPng = null,
        Func<bool> stillWanted = null)
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
        float furLength = -1f;
        if (hairLengthPng == null && TryReadEmbeddedHair(glbBytes, out var embLen, out var embFlow, out var embFur))
        {
            hairLengthPng = embLen; hairFlowPng = embFlow; furLength = embFur;
            Debug.Log($"[RuntimeModelLoader] fur maps found in GLB (fur length {embFur})");
        }
        Texture2D lengthMap = hairLengthPng == null ? fallbackHairLength : LinearTexture(hairLengthPng);
        Texture2D flowMap   = hairFlowPng   == null ? fallbackHairFlow   : LinearTexture(hairFlowPng);
        if (lengthMap == null) Debug.Log("[RuntimeModelLoader] no fur maps in GLB and no fallback set: fur skipped");
        return await BuildAndRegister(gltf, name, lengthMap, flowMap, furLength, stillWanted);
    }

    /// <summary>
    /// Reads the hair maps generation/tools/convert_asset.py embeds in the GLB (GLB_SPEC 4.1): extras.beside.{hairLengthImage, hairFlowImage, furLength}
    /// point at images whose bufferViews live in the binary chunk.
    /// </summary>
    public static bool TryReadEmbeddedHair(byte[] glb, out byte[] lengthPng, out byte[] flowPng, out float furLength)
    {
        lengthPng = flowPng = null; furLength = -1f;
        try
        {
            if (!IsGlb(glb)) return false;
            int jsonLen = BitConverter.ToInt32(glb, 12);
            var root = JObject.Parse(System.Text.Encoding.UTF8.GetString(glb, 20, jsonLen));
            var beside = root["extras"]?["beside"];
            if (beside == null) return false;

            int binStart = 20 + jsonLen + 8;                       // skip BIN chunk header
            byte[] Image(JToken idx)
            {
                if (idx == null) return null;
                var img = root["images"]?[(int)idx];
                var view = img?["bufferView"] != null ? root["bufferViews"]?[(int)img["bufferView"]] : null;
                if (view == null) return null;
                int off = (int?)view["byteOffset"] ?? 0, len = (int)view["byteLength"];
                var bytes = new byte[len];
                Buffer.BlockCopy(glb, binStart + off, bytes, 0, len);
                return bytes;
            }
            lengthPng = Image(beside["hairLengthImage"]);
            flowPng = Image(beside["hairFlowImage"]);
            furLength = (float?)beside["furLength"] ?? -1f;
            return lengthPng != null;
        }
        catch (Exception e)
        {
            Debug.LogWarning($"[RuntimeModelLoader] could not read embedded hair maps: {e.Message}");
            return false;
        }
    }

    /// <summary>True if the bytes start with the glTF binary magic "glTF".</summary>
    public static bool IsGlb(byte[] data) =>
        data != null && data.Length > 12 && data[0] == 0x67 && data[1] == 0x6C && data[2] == 0x54 && data[3] == 0x46;

    async Task<GameObject> BuildAndRegister(GltfImport gltf, string name, Texture2D lengthMap, Texture2D flowMap, float furLength = -1f,
        Func<bool> stillWanted = null)
    {
        var model = new LoadedModel { gltf = gltf };
        if (lengthMap != null && lengthMap != fallbackHairLength) model.owned.Add(lengthMap);   // made from bytes here
        if (flowMap != null && flowMap != fallbackHairFlow) model.owned.Add(flowMap);

        if (shellPrefab == null) { Debug.LogError("[RuntimeModelLoader] Shell Prefab is not set."); Free(null, model); return null; }
        imports.Add(gltf);

        GameObject template = Instantiate(shellPrefab, templateRoot);
        template.name = "Runtime_" + name;

        var modelRoot = new GameObject(ModelRootName).transform;
        modelRoot.SetParent(template.transform, false);

        if (!await gltf.InstantiateMainSceneAsync(modelRoot))
        {
            Debug.LogError("[RuntimeModelLoader] Failed to instantiate GLB scene.");
            Free(template, model);
            return null;
        }

        if (baseMaterialTemplate != null)
            ReplaceMaterials(modelRoot, gltf, model.owned);

        AddRestPose(modelRoot, model.owned);   // before anything plays: the template sits under the inactive holder

        modelRoot.localRotation = FacingToPlusZ(modelFacing);   // head -> +Z (faces the camera on spawn)

        if (!FitModel(template.transform, modelRoot))
        {
            Debug.LogError("[RuntimeModelLoader] GLB contains no meshes.");
            Free(template, model);
            return null;
        }

        if (furMaterialTemplate != null && lengthMap != null)
            AddFur(modelRoot, lengthMap, flowMap, furLength, model.owned);

        if (stillWanted != null && !stillWanted())
        {
            Debug.Log($"[RuntimeModelLoader] Load cancelled, model dropped: {template.name}");
            Free(template, model);
            return null;
        }

        AddShadowCatcher(template.transform, modelRoot);   // child of the template: Free() destroys it with the template
        loaded[template] = model;
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

        // Collider in the model root's local space: undo the facing rotation (bounds are unscaled)
        Quaternion inv = Quaternion.Inverse(model.localRotation);
        Vector3 size = inv * b.size;
        var col = model.gameObject.AddComponent<BoxCollider>();
        col.center = inv * b.center;
        col.size = new Vector3(Mathf.Abs(size.x), Mathf.Abs(size.y), Mathf.Abs(size.z));
        return true;
    }

    // ---------- shadow ----------

    void AddShadowCatcher(Transform shell, Transform model)
    {
        if (shadowCatcherMaterial == null) return;

        // Make sure the pet itself casts a shadow
        foreach (var r in model.GetComponentsInChildren<Renderer>(true))
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.On;

        var col = model.GetComponent<BoxCollider>();
        Vector3 s = col != null ? Vector3.Scale(col.size, model.localScale) : Vector3.one * targetSize;
        float size = Mathf.Max(s.x, s.y, s.z) * shadowQuadScale;

        var quad = GameObject.CreatePrimitive(PrimitiveType.Quad);
        quad.name = "ShadowCatcher";
        Destroy(quad.GetComponent<Collider>());            // must not block taps / grabbing
        quad.transform.SetParent(shell, false);
        quad.transform.localPosition = new Vector3(0f, 0.002f, 0f);   // just above the floor (feet are at y = 0)
        quad.transform.localRotation = Quaternion.Euler(90f, 0f, 0f); // face up
        quad.transform.localScale = new Vector3(size, size, 1f);
        var mr = quad.GetComponent<MeshRenderer>();
        mr.sharedMaterial = shadowCatcherMaterial;           // shared scene asset and built-in Quad mesh: not in model.owned
        mr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
        mr.receiveShadows = true;
    }

    static Quaternion FacingToPlusZ(ModelFacing f) => f switch
    {
        ModelFacing.MinusZ => Quaternion.Euler(0f, 180f, 0f),
        ModelFacing.PlusX  => Quaternion.Euler(0f, -90f, 0f),
        ModelFacing.MinusX => Quaternion.Euler(0f, 90f, 0f),
        _ => Quaternion.identity
    };

    // ---------- materials ----------

    void ReplaceMaterials(Transform model, GltfImport gltf, List<UnityEngine.Object> owned)
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
                owned.Add(m);
                m.SetTexture("_BaseMap", tex);
                m.SetColor("_BaseColor", Color.white);
                newMats[i] = m;
            }
            r.sharedMaterials = newMats;
        }
        Debug.Log("[RuntimeModelLoader] GLB materials replaced with URP/Lit copies");
    }

    // ---------- fur ----------

    void AddFur(Transform model, Texture2D lengthMap, Texture2D flowMap, float furLength, List<UnityEngine.Object> owned)
    {
        // static meshes (MeshFilter) and rigged meshes (SkinnedMeshRenderer) both get shells
        var targets = new List<Renderer>();
        foreach (var mf in model.GetComponentsInChildren<MeshFilter>(true)) { var r = mf.GetComponent<MeshRenderer>(); if (r != null) targets.Add(r); }
        targets.AddRange(model.GetComponentsInChildren<SkinnedMeshRenderer>(true));

        foreach (var baseRenderer in targets)
        {
            var mat = new Material(furMaterialTemplate);
            owned.Add(mat);
            if (furLength > 0f) mat.SetFloat("_FurLength", furLength);   // value from the converter, model units
            mat.SetTexture("_LengthMap", lengthMap);
            if (flowMap != null) mat.SetTexture("_FlowMap", flowMap);

            Texture baseTex = GetBaseTexture(baseRenderer.sharedMaterial);
            if (baseTex != null) mat.SetTexture("_BaseMap", baseTex);

            Mesh mesh = baseRenderer is SkinnedMeshRenderer smr ? smr.sharedMesh
                      : baseRenderer.GetComponent<MeshFilter>() is MeshFilter mf2 ? mf2.sharedMesh : null;
            WeldSeamFurLength(mesh, lengthMap, mat.HasProperty("_FlipHairMaps") && mat.GetFloat("_FlipHairMaps") > 0.5f);
            // comb (flow map) only with real tangents: a GLB without TANGENT (main converter) does not give the shader
            // zero tangents on the phone, which laid the hair in a different direction on every UV island
            bool hasTangents = mesh != null && mesh.HasVertexAttribute(UnityEngine.Rendering.VertexAttribute.Tangent);
            mat.SetFloat("_UseTangents", hasTangents ? 1f : 0f);
            Debug.Log($"[RuntimeModelLoader] Fur comb (flow map) {(hasTangents ? "on: mesh has tangents" : "off: mesh has no tangents")}.");

            var fur = baseRenderer.gameObject.AddComponent<ShellFurRenderer>();
            fur.furMaterial = mat;
            fur.shellCount = furShellCount;
        }
    }

    /// <summary>
    /// Fur length per vertex, equal for every copy of a vertex at a UV seam, stored in uv2 (x = length 0..1, y = 1 =
    /// "present") for ShellFur.shader. At a seam the copies share a position but read the length map at different UVs,
    /// and AnimalLift predicts each UV island on its own: on the spine and the head the two sides differed by up to half
    /// the map range (2026-11-24, 184 of 400 seam positions > 0.1), so the shells pushed the copies out by different
    /// amounts and split open along the seam, showing the bare mesh. Averaging the copies keeps the shells closed.
    /// Skipped (the shader then samples the map per vertex as before) when the mesh or the map is not readable or uv2
    /// is already used.
    /// </summary>
    static void WeldSeamFurLength(Mesh mesh, Texture2D lengthMap, bool flipV)
    {
        if (mesh == null || lengthMap == null) return;
        if (!mesh.isReadable || !lengthMap.isReadable)
        {
            Debug.LogWarning("[RuntimeModelLoader] Fur seams not welded: mesh or hair length map is not readable.");
            return;
        }
        if (mesh.HasVertexAttribute(UnityEngine.Rendering.VertexAttribute.TexCoord1))
        {
            Debug.LogWarning("[RuntimeModelLoader] Fur seams not welded: the mesh already uses uv2.");
            return;
        }
        Vector3[] verts = mesh.vertices;
        Vector2[] uvs = mesh.uv;
        if (uvs == null || uvs.Length != verts.Length) return;

        // the same sample the shader takes in the vertex stage (bilinear, mip 0, clamp)
        var len = new float[verts.Length];
        for (int i = 0; i < verts.Length; i++)
        {
            Vector2 uv = uvs[i];
            if (flipV) uv.y = 1f - uv.y;
            len[i] = lengthMap.GetPixelBilinear(uv.x, uv.y).r;
        }

        // copies of a seam vertex have bit-identical positions (same source vertex)
        static Vector3Int Key(Vector3 p) =>
            new Vector3Int(Mathf.RoundToInt(p.x * 1e5f), Mathf.RoundToInt(p.y * 1e5f), Mathf.RoundToInt(p.z * 1e5f));
        var sums = new Dictionary<Vector3Int, Vector2>(verts.Length);   // x = sum of lengths, y = copies
        for (int i = 0; i < verts.Length; i++)
        {
            var k = Key(verts[i]);
            sums.TryGetValue(k, out Vector2 s);
            sums[k] = new Vector2(s.x + len[i], s.y + 1f);
        }

        var welded = new List<Vector2>(verts.Length);
        int seamVerts = 0;
        for (int i = 0; i < verts.Length; i++)
        {
            Vector2 s = sums[Key(verts[i])];
            if (s.y > 1f) seamVerts++;
            welded.Add(new Vector2(s.x / s.y, 1f));
        }
        mesh.SetUVs(1, welded);
        Debug.Log($"[RuntimeModelLoader] Fur length welded across UV seams ({seamVerts} of {verts.Length} vertices).");
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
        if (!tex.LoadImage(png)) { Debug.LogError("[RuntimeModelLoader] Hair map is not a valid image."); Destroy(tex); return null; }
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
            GameObject previous = list[replaceSlot];
            list[replaceSlot] = template;   // the menu button for this slot now spawns the loaded model
            index = replaceSlot;
            if (previous != null && previous != template) FreeReplaced(previous);
        }
        else
        {
            list.Add(template);
            index = list.Count - 1;
        }
        objectSpawner.spawnOptionIndex = index; // next tap spawns this model
        Debug.Log($"[RuntimeModelLoader] Registered in spawner slot {index}");
    }

    // ---------- freeing ----------

    /// <summary>Frees a runtime template that a newer model pushed out of the spawner slot. Kept while a placed copy
    /// still uses its meshes and materials (the UI clears placed models before the next job, so this is rare).</summary>
    void FreeReplaced(GameObject previous)
    {
        if (!loaded.TryGetValue(previous, out var model)) return;   // scene prefab, not ours
        if (HasPlacedCopies(previous.name)) { Debug.Log($"[RuntimeModelLoader] {previous.name} still placed: kept in memory"); return; }
        loaded.Remove(previous);
        Free(previous, model);
        Debug.Log($"[RuntimeModelLoader] Freed {previous.name}");
    }

    /// <summary>Destroys the template, the materials and hair maps made for it, and disposes its import.</summary>
    void Free(GameObject template, LoadedModel model)
    {
        if (template != null) Destroy(template);
        foreach (var o in model.owned) if (o != null) Destroy(o);
        model.owned.Clear();
        imports.Remove(model.gltf);
        model.gltf.Dispose();
    }

    /// <summary>
    /// Adds a still clip holding every skin bone's rest pose (<see cref="Beside.Pet.PetAnimationPlayer.RestClip"/>).
    /// The GLB clips move only some bones (e.g. TailWag 13 of 36), and the legacy Animation leaves the bones a clip does
    /// not move where the last clip left them: a Sit cut short stayed half-sat under the next clip (2026-11-28).
    /// PetAnimationPlayer plays this clip on layer 0 under the real clips, so those bones fade back to rest.
    /// Read right after import, before any clip has played. Placed copies get it with the Animation component.
    /// </summary>
    static void AddRestPose(Transform modelRoot, List<UnityEngine.Object> owned)
    {
        var anim = modelRoot.GetComponentInChildren<Animation>(true);
        if (anim == null) return;
        var bones = new HashSet<Transform>();
        foreach (var smr in modelRoot.GetComponentsInChildren<SkinnedMeshRenderer>(true))
            foreach (var b in smr.bones) if (b != null) bones.Add(b);

        var clip = new AnimationClip { legacy = true, name = Beside.Pet.PetAnimationPlayer.RestClip, wrapMode = WrapMode.Loop };
        int count = 0;
        foreach (var b in bones)
        {
            string path = RelativePath(anim.transform, b);
            if (path == null) continue;
            Vector3 p = b.localPosition, s = b.localScale; Quaternion r = b.localRotation;
            Hold(clip, path, "localPosition.x", p.x); Hold(clip, path, "localPosition.y", p.y); Hold(clip, path, "localPosition.z", p.z);
            Hold(clip, path, "localRotation.x", r.x); Hold(clip, path, "localRotation.y", r.y);
            Hold(clip, path, "localRotation.z", r.z); Hold(clip, path, "localRotation.w", r.w);
            Hold(clip, path, "localScale.x", s.x); Hold(clip, path, "localScale.y", s.y); Hold(clip, path, "localScale.z", s.z);
            count++;
        }
        if (count == 0) { Destroy(clip); return; }
        anim.AddClip(clip, clip.name);
        owned.Add(clip);
        Debug.Log($"[RuntimeModelLoader] Rest pose clip: {count} bones");
    }

    static void Hold(AnimationClip clip, string path, string property, float value) =>
        clip.SetCurve(path, typeof(Transform), property, AnimationCurve.Constant(0f, 1f, value));

    /// <summary>Path of <paramref name="t"/> below <paramref name="root"/> ("" for root itself), or null if it is not below it.</summary>
    static string RelativePath(Transform root, Transform t)
    {
        if (t == root) return "";
        string path = t.name;
        for (var p = t.parent; p != null; p = p.parent)
        {
            if (p == root) return path;
            path = p.name + "/" + path;
        }
        return null;
    }

    /// <summary>Spawned copies are named "{template}(Clone)"; templates themselves sit under the inactive holder.</summary>
    static bool HasPlacedCopies(string templateName)
    {
        foreach (var t in FindObjectsByType<Transform>(FindObjectsInactive.Exclude))
            if (t.name.StartsWith(templateName, StringComparison.Ordinal)) return true;
        return false;
    }
}
