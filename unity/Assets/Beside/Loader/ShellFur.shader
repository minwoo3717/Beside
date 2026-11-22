// URP Shell Fur shader — v2
// Drawn several times by ShellFurRenderer.cs, once per shell (_ShellH = 0..1).
// The base model keeps its normal URP/Lit material; this shader only draws the fur layers.
//
// v2 (vs v1):
//  * hair lies down along the per-dog flow map (AnimalLift comb direction) instead of standing up along the normal
//    ("carpet" look). This needs mesh TANGENT: main's converter (convert_asset.py / rig_transfer.py) writes none, so on
//    server GLBs comb is off (hasTangent) and hair stands along the normal (the flow map then has no effect), until
//    the TANGENT / flow G-sign decision
//  * strands come from a mip-mapped noise texture (FurNoise.cs) instead of a hash grid: no knitted pattern,
//    no shimmer at a distance; soft strand edges with alpha-to-coverage when MSAA is on
//  * Kajiya-Kay highlight along the hair direction + depth darkening + soft rim: reads as hair, not felt
//  * lighting per vertex, shells drawn outside-in (ShellFurRenderer) and fewer shells up close: cheaper fill
Shader "Custom/ShellFur"
{
    Properties
    {
        _BaseMap ("Base Map (Color UV Texture)", 2D) = "white" {}
        _BaseColor ("Base Color", Color) = (1,1,1,1)

        [NoScaleOffset] _LengthMap ("Hair Length Map (sRGB off)", 2D) = "white" {}
        [NoScaleOffset] _FlowMap ("Hair Flow Map (sRGB off)", 2D) = "gray" {}
        [NoScaleOffset] _NoiseMap ("Strand Noise (set by FurNoise.cs)", 2D) = "gray" {}

        _FurLength ("Fur Length (object units)", Float) = 0.065
        _Density ("Strands per UV unit", Float) = 700
        _Thickness ("Strand Thickness", Range(0.1, 1.5)) = 0.8
        _Cutoff ("Strand Cutoff", Range(0.05, 0.9)) = 0.35

        [Header(Direction)]
        _FlowStrength ("Follow Flow Map", Range(0, 1)) = 0.8
        _Lie ("Lie Flat", Range(0, 1)) = 0.65
        _Gravity ("Gravity", Range(0, 1)) = 0.15

        [Header(Shading)]
        _RootDarkness ("Root Darkness", Range(0, 1)) = 0.5
        _HairSpecColor ("Highlight Color", Color) = (1, 0.95, 0.9, 1)
        _SpecStrength ("Highlight Strength", Range(0, 2)) = 0.35
        _SpecPower ("Highlight Sharpness", Range(4, 256)) = 48
        _SpecShift ("Highlight Shift", Range(-1, 1)) = 0.1
        _RimStrength ("Rim Light", Range(0, 1)) = 0.25
        _MinLength ("Min Length Cutoff", Range(0, 0.5)) = 0.05

        [Toggle] _FlipHairMaps ("Flip Hair Maps Y", Float) = 0
        [Toggle] _KeyOutGreen ("Hide Green Background", Float) = 1

        [HideInInspector] _ShellH ("Shell Height", Float) = 0
        [HideInInspector] _FurUnitScale ("Object -> mesh units", Float) = 1
        [HideInInspector] _FurDisplace ("Physics Displacement (object space)", Vector) = (0,0,0,0)
    }

    SubShader
    {
        Tags
        {
            "RenderPipeline" = "UniversalPipeline"
            "RenderType" = "TransparentCutout"
            "Queue" = "AlphaTest"
        }

        HLSLINCLUDE
        #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Core.hlsl"
        #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Lighting.hlsl"

        TEXTURE2D(_BaseMap);   SAMPLER(sampler_BaseMap);
        TEXTURE2D(_LengthMap); SAMPLER(sampler_LengthMap);
        TEXTURE2D(_FlowMap);   SAMPLER(sampler_FlowMap);
        TEXTURE2D(_NoiseMap);  SAMPLER(sampler_NoiseMap);

        CBUFFER_START(UnityPerMaterial)
            float4 _BaseMap_ST;
            half4  _BaseColor;
            float  _FurLength;
            float  _Density;
            float  _Thickness;
            float  _Cutoff;
            float  _FlowStrength;
            float  _Lie;
            float  _Gravity;
            float  _RootDarkness;
            half4  _HairSpecColor;
            float  _SpecStrength;
            float  _SpecPower;
            float  _SpecShift;
            float  _RimStrength;
            float  _MinLength;
            float  _FlipHairMaps;
            float  _KeyOutGreen;
            float  _ShellH;
            float  _FurUnitScale;
            float4 _FurDisplace;
        CBUFFER_END

        struct Attributes
        {
            float4 positionOS : POSITION;
            float3 normalOS   : NORMAL;
            float4 tangentOS  : TANGENT;
            float2 uv         : TEXCOORD0;
        };

        struct Varyings
        {
            float4 positionCS : SV_POSITION;
            float2 uv         : TEXCOORD0;
            float2 hairUV     : TEXCOORD1;
            half3  diffuse    : TEXCOORD2;     // lighting computed per vertex (cheap: the fragment runs once per shell)
            half3  spec       : TEXCOORD3;     // Kajiya-Kay highlight + rim, already colored
            half   rim        : TEXCOORD4;
        };

        float2 GetHairUV(float2 uv)
        {
            uv.y = lerp(uv.y, 1.0 - uv.y, _FlipHairMaps);
            return uv;
        }

        Varyings FurVert(Attributes v)
        {
            Varyings o;
            float2 hairUV = GetHairUV(v.uv);

            float  len  = SAMPLE_TEXTURE2D_LOD(_LengthMap, sampler_LengthMap, hairUV, 0).r * _FurLength * _FurUnitScale;
            float3 flow = SAMPLE_TEXTURE2D_LOD(_FlowMap, sampler_FlowMap, hairUV, 0).rgb * 2.0 - 1.0;

            float3 n = normalize(v.normalOS);
            float3 t = v.tangentOS.xyz;
            float hasTangent = dot(t, t) > 1e-6 ? 1.0 : 0.0;
            if (hasTangent < 0.5) t = cross(n, abs(n.y) < 0.99 ? float3(0, 1, 0) : float3(1, 0, 0));
            t = normalize(t - n * dot(t, n));
            float3 b = cross(n, t) * (v.tangentOS.w < 0 ? -1.0 : 1.0);    // +v (Unity convention) = flow.g axis

            // comb direction in the surface plane (from AnimalLift); without tangents it is meaningless -> no comb
            float3 comb = (t * flow.r + b * flow.g) * hasTangent;
            float combLen = length(comb);
            float3 combDir = combLen > 1e-3 ? comb / combLen : float3(0, 0, 0);
            float stand = lerp(1.0, saturate(flow.b), _FlowStrength);          // how upright AnimalLift says it is
            float up = max(stand * (1.0 - _Lie), 0.18);                          // keep shells above the skin
            float3 dir = normalize(n * up + combDir * _FlowStrength * saturate(combLen * 2.0));

            float h = _ShellH;
            float3 down = TransformWorldToObjectDir(float3(0, -1, 0)) * _Gravity;
            float3 pos = v.positionOS.xyz + dir * (len * h) + (down + _FurDisplace.xyz) * (len * h * h);

            float3 posWS = TransformObjectToWorld(pos);
            o.positionCS = TransformWorldToHClip(posWS);

            // lighting per vertex
            float3 N = TransformObjectToWorldNormal(n);
            float3 T = normalize(TransformObjectToWorldDir(dir));
            float3 V = normalize(GetCameraPositionWS() - posWS);
            Light mainLight = GetMainLight();
            float3 L = mainLight.direction;
            half wrap = saturate(dot(N, L) * 0.5 + 0.5);                        // soft wrap: hair is lit from all around
            float3 Ts = normalize(T + N * _SpecShift);                           // Kajiya-Kay, shifted toward the tip
            float th = dot(Ts, normalize(L + V));
            half kk = pow(saturate(sqrt(saturate(1.0 - th * th))), _SpecPower) * saturate(dot(N, L) + 0.3);
            half ao = lerp(1.0 - _RootDarkness, 1.0, h);                         // deep layers get less light
            o.diffuse = (mainLight.color * wrap + SampleSH(N)) * ao;
            o.spec = _HairSpecColor.rgb * mainLight.color * (kk * _SpecStrength * h);
            o.rim = pow(1.0 - saturate(dot(N, V)), 3.0) * _RimStrength * h;     // fuzz catching light on the outline
            o.uv         = v.uv;
            o.hairUV     = hairUV;
            return o;
        }

        // Strand coverage on this shell (0..1). Discards empty pixels. Returns base color in rgb, coverage in a.
        half4 FurMask(Varyings i)
        {
            float lenMap = SAMPLE_TEXTURE2D(_LengthMap, sampler_LengthMap, i.hairUV).r;
            clip(lenMap - _MinLength);

            float h = _ShellH;
            const float noiseSize = 256.0;   // FurNoise texture size
            float noise = SAMPLE_TEXTURE2D(_NoiseMap, sampler_NoiseMap, i.uv * (_Density / noiseSize)).r;
            // each noise texel is one strand with a random length; bilinear filtering tapers it,
            // mip-maps blend distant strands into soft fuzz instead of shimmering
            float strand = noise + (_Thickness - 1.0) * 0.4 - h * h * 0.9 - h * 0.1;
            half coverage = saturate(strand * 6.0 + 0.5);
            clip(coverage - _Cutoff);

            half4 col = SAMPLE_TEXTURE2D(_BaseMap, sampler_BaseMap, TRANSFORM_TEX(i.uv, _BaseMap)) * _BaseColor;
            if (_KeyOutGreen > 0.5)
                clip(0.25 - (col.g - max(col.r, col.b)));                    // skip the green UV background
            return half4(col.rgb, coverage);
        }
        ENDHLSL

        Pass
        {
            Name "ShellFur"
            Tags { "LightMode" = "UniversalForward" }
            Cull Back
            ZWrite On
            AlphaToMask On      // soft strand edges when MSAA is on (URP asset: Anti Aliasing 4x)

            HLSLPROGRAM
            #pragma vertex FurVert
            #pragma fragment Frag

            half4 Frag(Varyings i) : SV_Target
            {
                half4 col = FurMask(i);
                return half4(col.rgb * (i.diffuse + i.rim) + i.spec, col.a);
            }
            ENDHLSL
        }

        Pass
        {
            Name "DepthOnly"
            Tags { "LightMode" = "DepthOnly" }
            Cull Back
            ZWrite On
            ColorMask R

            HLSLPROGRAM
            #pragma vertex FurVert
            #pragma fragment FragDepth

            half4 FragDepth(Varyings i) : SV_Target
            {
                FurMask(i);
                return 0;
            }
            ENDHLSL
        }
    }
    FallBack Off
}
