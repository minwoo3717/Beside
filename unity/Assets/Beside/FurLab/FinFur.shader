// Fin fur: a short quad sticks out of every mesh edge; only edges on the silhouette are kept
// (others collapse to zero size in the vertex shader, so they cost almost no pixels).
// Used together with shell fur: shells look thin at the outline, fins fill the outline with strands.
Shader "Custom/FinFur"
{
    Properties
    {
        _BaseMap ("Base Map", 2D) = "white" {}
        [NoScaleOffset] _LengthMap ("Hair Length Map (sRGB off)", 2D) = "white" {}
        _FurLength ("Fur Length (object units)", Float) = 0.065
        _Density ("Strands per object unit along an edge", Float) = 600
        _Thickness ("Strand Thickness", Range(0.1, 1.5)) = 0.7
        _Silhouette ("Silhouette Width", Range(0.05, 1)) = 0.45
        _Gravity ("Gravity", Range(0, 1)) = 0.15
        _RootDarkness ("Root Darkness", Range(0, 1)) = 0.45
        [Toggle] _FlipHairMaps ("Flip Hair Maps Y", Float) = 0
    }

    SubShader
    {
        Tags { "RenderPipeline" = "UniversalPipeline" "RenderType" = "TransparentCutout" "Queue" = "AlphaTest+10" }

        HLSLINCLUDE
        #pragma target 4.5
        #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Core.hlsl"
        #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Lighting.hlsl"

        struct Edge { uint2 v; float4 uv; };   // uv.xy = uv of v.x, uv.zw = uv of v.y

        StructuredBuffer<Edge> _Edges;
        ByteAddressBuffer _Verts;
        uint _Stride, _PosOff, _NrmOff;
        float4x4 _FurL2W;
        float4 _FurDisplace;
        float _FurUnitScale;

        TEXTURE2D(_BaseMap);   SAMPLER(sampler_BaseMap);
        TEXTURE2D(_LengthMap); SAMPLER(sampler_LengthMap);

        CBUFFER_START(UnityPerMaterial)
            float4 _BaseMap_ST;
            float _FurLength, _Density, _Thickness, _Silhouette, _Gravity, _RootDarkness, _FlipHairMaps;
        CBUFFER_END

        float3 LoadF3(uint vi, uint off) { return asfloat(_Verts.Load3(vi * _Stride + off)); }

        struct Varyings
        {
            float4 positionCS : SV_POSITION;
            float2 uv : TEXCOORD0;
            float2 fin : TEXCOORD1;      // x = strand coordinate along the edge, y = height 0..1
            float3 normalWS : TEXCOORD2;
            float edgeId : TEXCOORD3;
        };

        static const float2 kCorner[6] = { float2(0,0), float2(1,0), float2(0,1), float2(0,1), float2(1,0), float2(1,1) };

        float Hash12(float2 p)
        {
            float3 p3 = frac(float3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return frac((p3.x + p3.y) * p3.z);
        }

        Varyings FinVert(uint vid : SV_VertexID)
        {
            Varyings o;
            uint e = vid / 6;
            float2 c = kCorner[vid % 6];
            Edge ed = _Edges[e];

            float3 p0 = LoadF3(ed.v.x, _PosOff), p1 = LoadF3(ed.v.y, _PosOff);
            float3 n0 = LoadF3(ed.v.x, _NrmOff), n1 = LoadF3(ed.v.y, _NrmOff);
            float3 p = lerp(p0, p1, c.x);
            float3 n = normalize(lerp(n0, n1, c.x));
            float2 uv = lerp(ed.uv.xy, ed.uv.zw, c.x);
            float2 huv = float2(uv.x, lerp(uv.y, 1 - uv.y, _FlipHairMaps));
            float len = SAMPLE_TEXTURE2D_LOD(_LengthMap, sampler_LengthMap, huv, 0).r * _FurLength * _FurUnitScale;

            // silhouette test with the edge's mid normal
            float3 nMidWS = normalize(mul((float3x3)_FurL2W, normalize(n0 + n1)));
            float3 midWS = mul(_FurL2W, float4((p0 + p1) * 0.5, 1)).xyz;
            float facing = abs(dot(nMidWS, normalize(GetCameraPositionWS() - midWS)));
            float keep = facing < _Silhouette ? 1 : 0;

            float3 down = normalize(mul(transpose((float3x3)_FurL2W), float3(0, -1, 0)));
            p += (n * c.y + (down * _Gravity + _FurDisplace.xyz) * c.y * c.y) * len * keep;

            o.positionCS = TransformWorldToHClip(mul(_FurL2W, float4(p, 1)).xyz);
            o.uv = uv;
            o.fin = float2(c.x * length(p1 - p0) / _FurUnitScale * _Density, c.y);
            o.normalWS = nMidWS;
            o.edgeId = e;
            return o;
        }

        half4 FinMask(Varyings i)
        {
            float h = i.fin.y;
            float cell = floor(i.fin.x);
            float f = frac(i.fin.x) - 0.5;
            float strandH = lerp(0.55, 1.0, Hash12(float2(cell, i.edgeId)));
            clip(strandH - h);
            clip(_Thickness * 0.5 * (1 - h / strandH) - abs(f));
            return SAMPLE_TEXTURE2D(_BaseMap, sampler_BaseMap, TRANSFORM_TEX(i.uv, _BaseMap));
        }
        ENDHLSL

        Pass
        {
            Name "FinFur"
            Tags { "LightMode" = "UniversalForward" }
            Cull Off
            HLSLPROGRAM
            #pragma vertex FinVert
            #pragma fragment Frag
            half4 Frag(Varyings i) : SV_Target
            {
                half4 col = FinMask(i);
                Light l = GetMainLight();
                float3 N = normalize(i.normalWS);
                half wrap = saturate(dot(N, l.direction) * 0.5 + 0.5);
                return half4(col.rgb * (l.color * wrap + SampleSH(N)) * lerp(1 - _RootDarkness, 1, i.fin.y), 1);
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
            #pragma vertex FinVert
            #pragma fragment FragDepth
            half4 FragDepth(Varyings i) : SV_Target { FinMask(i); return 0; }
            ENDHLSL
        }
    }
    FallBack Off
}
