#include <metal_stdlib>
using namespace metal;

static float harnessHash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

static float harnessNoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(harnessHash(i), harnessHash(i + float2(1.0, 0.0)), u.x),
        mix(harnessHash(i + float2(0.0, 1.0)), harnessHash(i + 1.0), u.x),
        u.y
    );
}

static float harnessFBM(float2 p) {
    float value = 0.0;
    float amplitude = 0.56;
    float2x2 rotation = float2x2(0.80, -0.60, 0.60, 0.80);
    for (int octave = 0; octave < 4; ++octave) {
        value += amplitude * harnessNoise(p);
        p = rotation * p * 2.03 + 17.17;
        amplitude *= 0.46;
    }
    return value;
}

[[ stitchable ]] half4 harnessFluid(
    float2 position,
    half4 currentColor,
    float2 size,
    float time
) {
    float2 safeSize = max(size, float2(1.0));
    float2 uv = position / safeSize;
    float aspect = safeSize.x / safeSize.y;
    float t = time * 0.075;

    float2 p = float2(uv.x * aspect, uv.y) * 2.05 + float2(-0.30, -0.08);
    float2 q = float2(
        harnessFBM(p * 0.62 + float2(0.0, t)),
        harnessFBM(p * 0.62 + float2(5.2, 1.3 - t))
    );
    float2 r = float2(
        harnessFBM(p * 0.72 + q * 0.62 + float2(1.7, 3.1 + t * 0.7)),
        harnessFBM(p * 0.72 + q * 0.62 + float2(9.2, 5.7 - t * 0.65))
    );
    float fluid = harnessFBM((p + q * 0.76 + r * 0.52) * 0.72 + t * 0.25);
    float swirl = harnessNoise(p * 1.25 + r * 2.1 - t * 0.42);

    float3 black = float3(0.002, 0.008, 0.018);
    float3 deepBlue = float3(0.102, 0.220, 0.439);
    float3 oceanBlue = float3(0.125, 0.290, 0.494);
    float3 mistBlue = float3(0.325, 0.553, 0.790);
    float3 warmLight = float3(0.933, 0.847, 0.667);

    float3 color = mix(black, deepBlue, smoothstep(0.14, 0.63, fluid));
    color = mix(color, oceanBlue, smoothstep(0.42, 0.82, fluid + swirl * 0.18));
    color = mix(color, mistBlue, smoothstep(0.74, 0.98, swirl) * 0.18);

    // Two broad, softly distorted wave fronts reproduce the luminous liquid
    // ribbons crossing the website hero without simulating expensive fluid.
    float2 warpedUV = float2(uv.x * aspect, uv.y) + (q - 0.5) * 0.085 + (r - 0.5) * 0.045;
    float2 waveCenterA = float2(aspect * 0.58, 0.16);
    float2 waveCenterB = float2(aspect * -0.20, 0.13);
    float radiusA = length(warpedUV - waveCenterA);
    float radiusB = length(warpedUV - waveCenterB);
    float ribbonA = exp(-pow((radiusA - (0.35 + 0.025 * sin(t * 1.5))) / 0.042, 2.0));
    float ribbonB = exp(-pow((radiusB - (0.83 + 0.020 * cos(t * 1.15))) / 0.065, 2.0));
    float ribbon = saturate(ribbonA * 0.78 + ribbonB * 0.62);
    color = mix(color, warmLight, ribbon * (0.48 + 0.24 * swirl));

    float glow = smoothstep(0.90, 0.18, length((uv - float2(0.58, 0.32)) * float2(0.82, 1.0)));
    color += deepBlue * glow * 0.22;

    float vignette = smoothstep(0.95, 0.23, length((uv - 0.5) * float2(0.86, 1.0)));
    color *= 0.38 + 0.72 * vignette;
    color += (harnessHash(position + floor(time * 12.0)) - 0.5) * 0.010;

    return half4(half3(max(color, 0.0)), currentColor.a);
}
