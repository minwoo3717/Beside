// Strand fur: each hair is a thin camera-facing ribbon with _Segments segments.
// No mesh: StrandFurRenderer draws strandCount * _Segments * 6 vertices and the vertex shader builds them.
// Each strand is glued to a triangle (3 vertex indices + barycentrics) and reads the CURRENT pose from the
// model's GPU vertex buffer, so strands follow the animation without per-strand CPU work.
// Needs compute-capable graphics (Vulkan on Android; some old Mali GLES drivers cannot read buffers in vertex shaders).
Shader "Custom/StrandFur"
{
    Properties
    {
        _BaseMap ("Base Map", 2D) = "white" {}
        [NoScaleOffset] _FlowMap ("Hair Flow Map (sRGB off)", 2D) = "gray" {}
        _Width ("Strand Width (object units)", Float) = 0.004
        _FlowStrength ("Flow Strength", Range(0, 1)) = 0.6
        _Gravity ("Gravity", Range(0, 1)) = 0.15
        _RootDarkness ("Root Darkness", Range(0, 1)) = 0.45
        [Toggle] _FlipHairMaps ("Flip Hair Maps Y", Float) = 0
    }

    SubShader
    {
        Tags { "RenderPipeline" = "UniversalPipeline" "RenderType" = "Opaque" "Queue" = "Geometry+10" }

        HLSLINCLUDE
        #pragma target 4.5
        #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Core.hlsl"
        #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Lighting.hlsl"

        struct Strand { uint3 v; float2 bary; float2 uv; float len; float seed; };

        StructuredBuffer<Strand> _Strands;
        ByteAddressBuffer _Verts;
        uint _Stride, _PosOff, _NrmOff;
        int _TanOff;
        int _Segments;
        float4x4 _FurL2W;
        float4 _FurDisplace;
        float _FurUnitScale;

        TEXTURE2D(_BaseMap); SAMPLER(sampler_BaseMap);
        TEXTURE2D(_FlowMap); SAMPLER(sampler_FlowMap);

        CBUFFER_START(UnityPerMaterial)
            float4 _BaseMap_ST;
            float _Width, _FlowStrength, _Gravity, _RootDarkness, _FlipHairMaps;
        CBUFFER_END

        float3 LoadF3(uint vi, uint off) { return asfloat(_Verts.Load3(vi * _Stride + off)); }

        struct Varyings
        {
            float4 positionCS : SV_POSITION;
            half3 color : COLOR;
            float3 normalWS : TEXCOORD0;
            float t : TEXCOORD1;
        };

        static const float2 kCorner[6] = { float2(0,-1), float2(0,1), float2(1,-1), float2(1,-1), float2(0,1), float2(1,1) };

        Varyings StrandVert(uint vid : SV_VertexID)
        {
            Varyings o;
            uint quad = vid / 6;
            float2 c = kCorner[vid % 6];
            Strand s = _Strands[quad / (uint)_Segments];
            float t = ((quad % (uint)_Segments) + c.x) / _Segments;

            float3 w = float3(s.bary, 1 - s.bary.x - s.bary.y);
            float3 root = LoadF3(s.v.x, _PosOff) * w.x + LoadF3(s.v.y, _PosOff) * w.y + LoadF3(s.v.z, _PosOff) * w.z;
            float3 n = normalize(LoadF3(s.v.x, _NrmOff) * w.x + LoadF3(s.v.y, _NrmOff) * w.y + LoadF3(s.v.z, _NrmOff) * w.z);
            float3 tg = _TanOff >= 0 ? LoadF3(s.v.x, (uint)_TanOff) : float3(0, 0, 0);
            float tw = _TanOff >= 0 ? asfloat(_Verts.Load(s.v.x * _Stride + (uint)_TanOff + 12)) : 1;
            if (dot(tg, tg) < 1e-6) tg = cross(n, abs(n.y) < 0.99 ? float3(0, 1, 0) : float3(1, 0, 0));
            tg = normalize(tg - n * dot(tg, n));
            float3 bt = cross(n, tg) * (tw < 0 ? -1 : 1);     // +v direction (Unity convention), matches the flow map

            float2 huv = float2(s.uv.x, lerp(s.uv.y, 1 - s.uv.y, _FlipHairMaps));
            float3 flow = SAMPLE_TEXTURE2D_LOD(_FlowMap, sampler_FlowMap, huv, 0).rgb * 2 - 1;
            float lift = lerp(1.0, max(flow.b, 0.3), _FlowStrength);
            float3 curl = (frac(s.seed * float3(12.9898, 78.233, 37.719)) - 0.5) * 0.3;   // per-strand variation
            float3 bend = (tg * flow.r + bt * flow.g) * _FlowStrength + curl
                        + normalize(mul(transpose((float3x3)_FurL2W), float3(0, -1, 0))) * _Gravity + _FurDisplace.xyz;

            s.len *= _FurUnitScale;
            float3 p   = root + n * (s.len * t * lift) + bend * (s.len * t * t);
            float3 dir = n * (s.len * lift) + bend * (s.len * 2 * t);

            float3 pWS = mul(_FurL2W, float4(p, 1)).xyz;
            float3 dWS = normalize(mul((float3x3)_FurL2W, dir));
            float3 v = normalize(GetCameraPositionWS() - pWS);
            float3 side = normalize(cross(dWS, v));
            float scale = length(_FurL2W._m00_m10_m20) * _FurUnitScale;
            pWS += side * (c.y * 0.5 * _Width * scale * (1 - 0.85 * t));

            o.positionCS = TransformWorldToHClip(pWS);
            o.normalWS = normalize(mul((float3x3)_FurL2W, n));
            o.color = SAMPLE_TEXTURE2D_LOD(_BaseMap, sampler_BaseMap, TRANSFORM_TEX(s.uv, _BaseMap), 0).rgb;
            o.t = t;
            return o;
        }
        ENDHLSL

        Pass
        {
            Name "StrandFur"
            Tags { "LightMode" = "UniversalForward" }
            Cull Off
            HLSLPROGRAM
            #pragma vertex StrandVert
            #pragma fragment Frag
            half4 Frag(Varyings i) : SV_Target
            {
                Light l = GetMainLight();
                float3 N = normalize(i.normalWS);
                half wrap = saturate(dot(N, l.direction) * 0.5 + 0.5);
                half3 rgb = i.color * (l.color * wrap + SampleSH(N)) * lerp(1 - _RootDarkness, 1, i.t);
                return half4(rgb, 1);
            }
            ENDHLSL
        }

        Pass
        {
            Name "DepthOnly"
            Tags { "LightMode" = "DepthOnly" }
            Cull Off
            ColorMask R
            HLSLPROGRAM
            #pragma vertex StrandVert
            #pragma fragment FragDepth
            half4 FragDepth(Varyings i) : SV_Target { return 0; }
            ENDHLSL
        }
    }
    FallBack Off
}
