using UnityEngine;

/// <summary>
/// The strand noise used by Custom/ShellFur v2: 256x256, one random value per texel = one strand with a random length.
/// Mip-mapped + bilinear, so far-away fur turns into soft fuzz instead of shimmering. Built once, shared by all fur.
/// </summary>
public static class FurNoise
{
    public const int Size = 256;      // must match noiseSize in ShellFur.shader
    static Texture2D tex;

    public static Texture2D Texture
    {
        get
        {
            if (tex != null) return tex;
            var rnd = new System.Random(7);
            var px = new byte[Size * Size];
            for (int i = 0; i < px.Length; i++)
            {
                double r = rnd.NextDouble();
                px[i] = (byte)(r * r * 255);   // more short strands than long ones, like an undercoat under guard hairs
            }
            tex = new Texture2D(Size, Size, TextureFormat.R8, true, true)
            {
                name = "FurNoise", wrapMode = TextureWrapMode.Repeat, filterMode = FilterMode.Trilinear, anisoLevel = 4,
            };
            tex.SetPixelData(px, 0);
            tex.Apply(true, true);
            return tex;
        }
    }

    /// <summary>Gives the material the noise if it uses ShellFur v2 and has none yet.</summary>
    public static void Assign(Material m)
    {
        if (m == null || !m.HasProperty("_NoiseMap")) return;
        var cur = m.GetTexture("_NoiseMap");
        if (cur == null || cur.width <= 4)            // unset (null or Unity's 4x4 default grey)
            m.SetTexture("_NoiseMap", Texture);
    }
}
