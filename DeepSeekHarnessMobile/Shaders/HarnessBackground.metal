#include <metal_stdlib>
#include <SwiftUI/SwiftUI_Metal.h>
using namespace metal;

// 波前轮廓、折射和边缘高光与 Android 着色器保持一致。
[[ stitchable ]] half4 workspaceModeWave(float2 position, SwiftUI::Layer outgoing,
                                        float4 bounds, float2 origin, float progress, half4 tint) {
    float2 extent = bounds.zw;
    float2 center = clamp(origin, 0.0, extent);
    float2 delta = position - bounds.xy - center;
    float distance = length(delta);
    float width = clamp(min(extent.x, extent.y) * 0.09, 24.0, 52.0);
    float span = length(max(center, extent - center));
    float radius = mix(-0.8 * width, span + 1.85 * width, progress * (1.55 - 0.55 * progress));
    float radialFront = (distance - radius) / width;
    // 跳过波前影响范围外的像素，以免整屏计算细小的轮廓变化。
    if (radialFront < -1.75) return half4(0.0);
    if (radialFront > 2.14) return outgoing.sample(position);
    float angle = atan2(delta.y, delta.x + 0.0001);
    // 大小不同的凸缘以不同速度变化，起点先连成一片再沿弧线蔓延。
    float contour = 0.65 * sin(3.0 * angle + 0.8 + 2.35 * progress)
                  + 0.33 * sin(7.0 * angle - 1.4 - 3.1 * progress)
                  + 0.20 * sin(14.0 * angle + 2.1 + 4.1 * progress)
                  + 0.11 * sin(27.0 * angle - 0.6 - 6.0 * progress);
    float spread = smoothstep(0.0, 3.2 * width, radius);
    float front = radialFront - spread * contour;
    float visibility = smoothstep(-0.085, 0.115, front);
    float lens = 1.0 - smoothstep(0.0, 0.85, abs(front));
    float2 bend = delta / max(distance, 1.0) * (8.0 * lens * sin(3.14 * front));
    float2 pixel = clamp(position + bend, bounds.xy + 0.5, bounds.xy + extent - 0.5);
    half4 color = outgoing.sample(pixel) * half(visibility);
    float rim = 0.36 * (1.0 - smoothstep(0.015, 0.135, abs(front + 0.025)));
    float halo = (0.10 + 0.07 * smoothstep(-0.35, 0.55, contour))
               * (1.0 - smoothstep(0.06, 0.46, abs(front)));
    return color * half(1.0 - rim - halo) + tint * half(rim + halo);
}

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

// 手机参数与改动前的版本保持一致。
static float harnessPhoneRibbons(float2 uv, float aspect, float t, float2 q, float2 r) {
    float2 warpedUV = float2(uv.x * aspect, uv.y) + (q - 0.5) * 0.085 + (r - 0.5) * 0.045;
    float2 waveCenterA = float2(aspect * 0.58, 0.16);
    float2 waveCenterB = float2(aspect * -0.20, 0.13);
    float radiusA = length(warpedUV - waveCenterA);
    float radiusB = length(warpedUV - waveCenterB);
    float ribbonA = exp(-pow((radiusA - (0.35 + 0.025 * sin(t * 1.5))) / 0.042, 2.0));
    float ribbonB = exp(-pow((radiusB - (0.83 + 0.020 * cos(t * 1.15))) / 0.065, 2.0));
    return saturate(ribbonA * 0.78 + ribbonB * 0.62);
}

static float harnessPadRibbons(float2 uv, float2 safeSize, float t, float2 distortion) {
    // 截取圆环左下侧：从左上进入画面，向下弯曲后从右侧离开。
    // 按入场、出场位置计算屏幕外的圆心，横竖屏均保留这一段圆弧。
    float2 waveExtent = safeSize / min(safeSize.x, safeSize.y);
    float2 warpedUV = uv * waveExtent + distortion;
    float2 arcEntry = float2(waveExtent.x * 0.04, 0.0);
    float2 arcExit = waveExtent * float2(1.0, 0.56);
    float centerX = max(waveExtent.x * 1.10,
        waveExtent.x * 0.52 + 0.14 * waveExtent.y * waveExtent.y / waveExtent.x);
    float entryDX = centerX - arcEntry.x;
    float exitDX = centerX - arcExit.x;
    float centerY = (arcExit.y * arcExit.y - entryDX * entryDX + exitDX * exitDX)
        / (2.0 * arcExit.y);
    float2 waveCenter = float2(centerX, centerY);
    float waveRadius = length(waveCenter - arcEntry) + 0.020 * sin(t * 1.5);
    float radius = length(warpedUV - waveCenter);
    float arcMask = 1.0 - smoothstep(0.65, 0.90, uv.y);
    // 保留约 25% 的渐粗变化，叠加宽幅漫射，让光晕边缘柔和、轮廓更淡。
    float upperWidth = mix(0.070, 0.0875, smoothstep(0.02, 0.56, uv.y));
    float upperDistance = radius - waveRadius;
    float upperCore = exp(-pow(upperDistance / upperWidth, 2.0));
    float upperBloom = exp(-pow(upperDistance / (upperWidth * 2.0), 2.0));
    float ribbonA = (upperCore * 0.55 + upperBloom * 0.45) * arcMask;

    // 下方保留另一层更宽、更淡的光晕，从左下方延伸至右侧。
    // 圆心置于左侧窗口外，并仅显示下半弧，避免额外露出上半圆。
    float2 outerEntry = waveExtent * float2(-0.05, 1.02);
    float2 outerExit = waveExtent * float2(1.02, 0.80);
    float outerCenterX = -waveExtent.x * 0.18;
    float outerEntryDX = outerEntry.x - outerCenterX;
    float outerExitDX = outerExit.x - outerCenterX;
    float outerCenterY = (outerExitDX * outerExitDX - outerEntryDX * outerEntryDX
        + outerExit.y * outerExit.y - outerEntry.y * outerEntry.y)
        / (2.0 * (outerExit.y - outerEntry.y));
    float2 outerCenter = float2(outerCenterX, outerCenterY);
    float outerRadius = length(outerEntry - outerCenter) + 0.018 * cos(t * 1.15);
    float outerMask = smoothstep(0.38, 0.66, uv.y)
        * smoothstep(0.0, 0.12, warpedUV.y - outerCenterY);
    // 除几何错位外，按主光弧的有符号距离保留间隔，流动时也不会交叉。
    outerMask *= smoothstep(upperWidth * 2.0 + 0.02, upperWidth * 2.0 + 0.10,
        radius - waveRadius);
    float outerDistance = length(warpedUV - outerCenter) - outerRadius;
    float outerCore = exp(-pow(outerDistance / 0.105, 2.0));
    float outerBloom = exp(-pow(outerDistance / 0.189, 2.0));
    float ribbonB = (outerCore * 0.55 + outerBloom * 0.45) * outerMask;
    return saturate(ribbonA * 0.72 + ribbonB * 0.86);
}

[[ stitchable ]] half4 harnessFluid(
    float2 position,
    half4 currentColor,
    float2 size,
    float time,
    float tabletStyle,
    float prefersOpenLightArcs,
    float lavenderPalette
) {
    float2 safeSize = max(size, float2(1.0));
    float2 uv = position / safeSize;
    float aspect = safeSize.x / safeSize.y;
    float t = time * 0.075;

    // 宽屏扩展视野，同时按短边放大光带，保持圆弧形状与流动尺度。
    float sceneScale = mix(1.0, 1.6, smoothstep(0.65, 1.6, aspect));
    float2 fieldUV = tabletStyle > 0.5
        ? (uv - 0.5) * float2(aspect, 1.0) / sceneScale + float2(0.30, 0.5)
        : float2(uv.x * aspect, uv.y);
    float2 p = fieldUV * 2.05 + float2(-0.30, -0.08);
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

    float2 distortion = (q - 0.5) * 0.085 + (r - 0.5) * 0.045;
    // Duo 的侧边工具栏布局在闭合后仍保留开放光弧，点阵和流体配色继续按尺寸适配。
    float ribbon = tabletStyle > 0.5 || prefersOpenLightArcs > 0.5
        ? harnessPadRibbons(uv, safeSize, t, distortion)
        : harnessPhoneRibbons(uv, aspect, t, q, r);
    color = mix(color, warmLight, ribbon * (0.48 + 0.24 * swirl));

    float glow = smoothstep(0.90, 0.18, length((uv - float2(0.58, 0.32)) * float2(0.82, 1.0)));
    color += deepBlue * glow * 0.22;

    float vignette = smoothstep(0.95, 0.23, length((uv - 0.5) * float2(0.86, 1.0)));
    color *= 0.38 + 0.72 * vignette;
    color += (harnessHash(position + floor(time * 12.0)) - 0.5) * 0.010;

    if (lavenderPalette > 0.5) {
        float luminance = dot(max(color, 0.0), float3(0.2126, 0.7152, 0.0722));
        float tone = saturate(luminance / 0.50);
        color = lavenderPalette > 1.5
            ? mix(float3(0.20, 0.17, 0.24), float3(0.34, 0.30, 0.37), tone)
            : mix(float3(0.79, 0.70, 0.89), float3(0.99, 0.97, 1.0), tone);
    }
    return half4(half3(max(color, 0.0)), currentColor.a);
}
