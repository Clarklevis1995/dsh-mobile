package com.clarklevis.dsh.android.ui

import android.graphics.Paint
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas

/** 复用 Harness 原始流体几何，与 iOS Metal 使用相同计算和 Bot 配色。 */
@RequiresApi(33)
@Composable
internal fun HarnessLavenderFluid(time: () -> Float, modifier: Modifier, largeWindow: Boolean, night: Boolean) {
    val shader = remember { RuntimeShader(HARNESS_LAVENDER_SHADER) }
    val paint = remember(shader) { Paint().apply { this.shader = shader } }
    Canvas(modifier.fillMaxSize()) {
        shader.setFloatUniform("size", size.width / density, size.height / density)
        shader.setFloatUniform("pixelDensity", density)
        shader.setFloatUniform("time", time())
        shader.setFloatUniform("tabletStyle", if (largeWindow) 1f else 0f)
        shader.setFloatUniform("prefersOpenLightArcs", 0f)
        shader.setFloatUniform("lavenderPalette", if (night) 2f else 1f)
        drawContext.canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
    }
}

private const val HARNESS_LAVENDER_SHADER = """
uniform float2 size;
uniform float time;
uniform float tabletStyle;
uniform float prefersOpenLightArcs;
uniform float lavenderPalette;
uniform float pixelDensity;
float clamp01(float x) { return clamp(x, 0.0, 1.0); }
float harnessHash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float harnessNoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(harnessHash(i), harnessHash(i + float2(1.0, 0.0)), u.x),
        mix(harnessHash(i + float2(0.0, 1.0)), harnessHash(i + 1.0), u.x),
        u.y
    );
}

float harnessFBM(float2 p) {
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

float harnessPhoneRibbons(float2 uv, float aspect, float t, float2 q, float2 r) {
    float2 warpedUV = float2(uv.x * aspect, uv.y) + (q - 0.5) * 0.085 + (r - 0.5) * 0.045;
    float2 waveCenterA = float2(aspect * 0.58, 0.16);
    float2 waveCenterB = float2(aspect * -0.20, 0.13);
    float radiusA = length(warpedUV - waveCenterA);
    float radiusB = length(warpedUV - waveCenterB);
    float ribbonA = exp(-pow((radiusA - (0.35 + 0.025 * sin(t * 1.5))) / 0.042, 2.0));
    float ribbonB = exp(-pow((radiusB - (0.83 + 0.020 * cos(t * 1.15))) / 0.065, 2.0));
    return clamp01(ribbonA * 0.78 + ribbonB * 0.62);
}

float harnessPadRibbons(float2 uv, float2 safeSize, float t, float2 distortion) {
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
    float upperWidth = mix(0.070, 0.0875, smoothstep(0.02, 0.56, uv.y));
    float upperDistance = radius - waveRadius;
    float upperCore = exp(-pow(upperDistance / upperWidth, 2.0));
    float upperBloom = exp(-pow(upperDistance / (upperWidth * 2.0), 2.0));
    float ribbonA = (upperCore * 0.55 + upperBloom * 0.45) * arcMask;

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
    outerMask *= smoothstep(upperWidth * 2.0 + 0.02, upperWidth * 2.0 + 0.10,
        radius - waveRadius);
    float outerDistance = length(warpedUV - outerCenter) - outerRadius;
    float outerCore = exp(-pow(outerDistance / 0.105, 2.0));
    float outerBloom = exp(-pow(outerDistance / 0.189, 2.0));
    float ribbonB = (outerCore * 0.55 + outerBloom * 0.45) * outerMask;
    return clamp01(ribbonA * 0.72 + ribbonB * 0.86);
}

half4 main(float2 coordinate) {
    float2 position = coordinate / pixelDensity;
    float2 safeSize = max(size, float2(1.0));
    float2 uv = position / safeSize;
    float aspect = safeSize.x / safeSize.y;
    float t = time * 0.075;

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
        float tone = clamp01(luminance / 0.50);
        color = lavenderPalette > 1.5
            ? mix(float3(0.20, 0.17, 0.24), float3(0.34, 0.30, 0.37), tone)
            : mix(float3(0.79, 0.70, 0.89), float3(0.99, 0.97, 1.0), tone);
    }
    return half4(half3(max(color, 0.0)), 1.0);
}

"""
