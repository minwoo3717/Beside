// URP "shadow catcher": an invisible floor that only shows the shadows falling on it.
// Put on a quad under the pet (RuntimeModelLoader adds one automatically).
// Needed because the AR floor planes (which used to receive the shadow) are hidden after placement.
Shader "Custom/ShadowCatcher"
{
    Properties
    {
        _ShadowColor ("Shadow Color (alpha = strength)", Color) = (0, 0, 0, 0.55)
        _EdgeFade ("Edge Fade", Range(0.01, 1)) = 0.35
    }

    SubShader
    {
        Tags
        {
            "RenderPipeline" = "UniversalPipeline"
            "RenderType" = "Transparent"
            "Queue" = "Transparent-1"
        }

        Pass
        {
            Name "ShadowCatcher"
            Tags { "LightMode" = "UniversalForward" }
            Blend SrcAlpha OneMinusSrcAlpha
            ZWrite Off
            Cull Back

            HLSLPROGRAM
            #pragma vertex Vert
            #pragma fragment Frag
            #pragma multi_compile _ _MAIN_LIGHT_SHADOWS _MAIN_LIGHT_SHADOWS_CASCADE
            #pragma multi_compile_fragment _ _SHADOWS_SOFT _SHADOWS_SOFT_LOW _SHADOWS_SOFT_MEDIUM _SHADOWS_SOFT_HIGH

            #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Core.hlsl"
            #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Lighting.hlsl"

            CBUFFER_START(UnityPerMaterial)
                half4 _ShadowColor;
                half  _EdgeFade;
            CBUFFER_END

            struct Attributes { float4 positionOS : POSITION; float2 uv : TEXCOORD0; };
            struct Varyings   { float4 positionCS : SV_POSITION; float3 positionWS : TEXCOORD0; float2 uv : TEXCOORD1; };

            Varyings Vert(Attributes v)
            {
                Varyings o;
                o.positionWS = TransformObjectToWorld(v.positionOS.xyz);
                o.positionCS = TransformWorldToHClip(o.positionWS);
                o.uv = v.uv;
                return o;
            }

            half4 Frag(Varyings i) : SV_Target
            {
                float4 shadowCoord = TransformWorldToShadowCoord(i.positionWS);
                half lit = MainLightRealtimeShadow(shadowCoord);          // 1 = lit, 0 = in shadow
                half edge = smoothstep(0, _EdgeFade, 1 - length(i.uv - 0.5) * 2);  // fade out toward the quad edge
                return half4(_ShadowColor.rgb, (1 - lit) * _ShadowColor.a * edge);
            }
            ENDHLSL
        }
    }
    FallBack Off
}
