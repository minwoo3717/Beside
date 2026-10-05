// URP Shell Fur shader
// Drawn several times by ShellFurRenderer.cs, once per shell (_ShellH = 0..1).
// The base model keeps its normal URP/Lit material; this shader only draws the fur layers.
Shader "Custom/ShellFur"
{
    Properties
    {
        _BaseMap ("Base Map (Color UV Texture)", 2D) = "white" {}
        _BaseColor ("Base Color", Color) = (1,1,1,1)

        [NoScaleOffset] _LengthMap ("Hair Length Map (sRGB off)", 2D) = "white" {}
        [NoScaleOffset] _FlowMap ("Hair Flow Map (sRGB off)", 2D) = "gray" {}

        _FurLength ("Fur Length (object units)", Float) = 0.065
        _Density ("Strand Density", Float) = 400
        _Thickness ("Strand Thickness", Range(0.1, 1.5)) = 0.8
        _FlowStrength ("Flow Strength (comb direction)", Range(0, 1)) = 0.6
        _Gravity ("Gravity", Range(0, 1)) = 0.15
        _RootDarkness ("Root Darkness", Range(0, 1)) = 0.45
        _MinLength ("Min Length Cutoff", Range(0, 0.5)) = 0.05

        [Toggle] _FlipHairMaps ("Flip Hair Maps Y", Float) = 0
        [Toggle] _KeyOutGreen ("Hide Green Background", Float) = 1

        [HideInInspector] _ShellH ("Shell Height", Float) = 0
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

        CBUFFER_START(UnityPerMaterial)
            float4 _BaseMap_ST;
            half4  _BaseColor;
            float  _FurLength;
            float  _Density;
            float  _Thickness;
            float  _FlowStrength;
            float  _Gravity;
            float  _RootDarkness;
            float  _MinLength;
            float  _FlipHairMaps;
            float  _KeyOutGreen;
            float  _ShellH;
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
            float3 normalWS   : TEXCOORD2;
        };

        // Dave Hoskins hash (stable on mobile GPUs)
        float Hash12(float2 p)
        {
            float3 p3 = frac(float3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return frac((p3.x + p3.y) * p3.z);
        }

        float2 GetHairUV(float2 uv)
        {
            uv.y = lerp(uv.y, 1.0 - uv.y, _FlipHairMaps);
            return uv;
        }

        Varyings FurVert(Attributes v)
        {
            Varyings o;
            float2 hairUV = GetHairUV(v.uv);

            float  len  = SAMPLE_TEXTURE2D_LOD(_LengthMap, sampler_LengthMap, hairUV, 0).r * _FurLength;
            float3 flow = SAMPLE_TEXTURE2D_LOD(_FlowMap, sampler_FlowMap, hairUV, 0).rgb * 2.0 - 1.0;

            float3 n = normalize(v.normalOS);
            float3 t = v.tangentOS.xyz;
            if (dot(t, t) < 1e-6) // mesh without tangents: build a fallback
                t = cross(n, abs(n.y) < 0.99 ? float3(0, 1, 0) : float3(1, 0, 0));
            t = normalize(t);
            float3 b = cross(n, t) * (v.tangentOS.w < 0 ? -1.0 : 1.0);

            float h = _ShellH;
            float lift = lerp(1.0, max(flow.b, 0.3), _FlowStrength);       // how upright the hair stands
            float3 comb = (t * flow.r + b * flow.g) * _FlowStrength;         // which way the hair lies
            float3 down = TransformWorldToObjectDir(float3(0, -1, 0)) * _Gravity;
            float3 physics = _FurDisplace.xyz;                               // set by ShellFurRenderer (spring physics)

            float3 pos = v.positionOS.xyz
                       + n * (len * h * lift)
                       + (comb + down + physics) * (len * h * h);

            o.positionCS = TransformObjectToHClip(pos);
            o.normalWS   = TransformObjectToWorldNormal(n);
            o.uv         = v.uv;
            o.hairUV     = hairUV;
            return o;
        }

        // Discards pixels that are not part of a strand on this shell. Returns base color.
        half4 FurMask(Varyings i)
        {
            float lenMap = SAMPLE_TEXTURE2D(_LengthMap, sampler_LengthMap, i.hairUV).r;
            clip(lenMap - _MinLength);

            float h = _ShellH;
            float2 cellUV = i.uv * _Density;
            float2 cell = floor(cellUV);
            float2 jitter = float2(Hash12(cell), Hash12(cell + 17.31)) - 0.5;
            float2 f = frac(cellUV) - 0.5 - jitter * 0.4;

            float strandH = lerp(0.55, 1.0, Hash12(cell + 91.7));            // each strand has its own length
            clip(strandH - h);
            float radius = _Thickness * 0.5 * (1.0 - h / strandH);           // taper toward the tip
            clip(radius - length(f));

            half4 col = SAMPLE_TEXTURE2D(_BaseMap, sampler_BaseMap, TRANSFORM_TEX(i.uv, _BaseMap)) * _BaseColor;
            if (_KeyOutGreen > 0.5)
                clip(0.25 - (col.g - max(col.r, col.b)));                    // skip the green UV background
            return col;
        }
        ENDHLSL

        Pass
        {
            Name "ShellFur"
            Tags { "LightMode" = "UniversalForward" }
            Cull Back
            ZWrite On

            HLSLPROGRAM
            #pragma vertex FurVert
            #pragma fragment Frag

            half4 Frag(Varyings i) : SV_Target
            {
                half4 col = FurMask(i);
                float3 N = normalize(i.normalWS);
                Light mainLight = GetMainLight();
                half wrap = saturate(dot(N, mainLight.direction) * 0.5 + 0.5);   // soft lighting for fur
                half3 lighting = mainLight.color * wrap + SampleSH(N);
                half3 rgb = col.rgb * lighting * lerp(1.0 - _RootDarkness, 1.0, _ShellH);
                return half4(rgb, 1);
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
