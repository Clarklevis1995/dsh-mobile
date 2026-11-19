#!/usr/bin/env python3
"""由 iOS 的原始 Metal 几何生成 Android AGSL，避免两端流体背景逐渐偏离。"""
import argparse
import re
from pathlib import Path

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--check", action="store_true", help="只检查生成内容是否同步")
args = parser.parse_args()
metal = (root / "DeepSeekHarnessMobile/Shaders/HarnessBackground.metal").read_text()
shader = metal[metal.index("static float harnessHash"):]
entry = """[[ stitchable ]] half4 harnessFluid(
    float2 position,
    half4 currentColor,
    float2 size,
    float time,
    float tabletStyle,
    float prefersOpenLightArcs,
    float lavenderPalette
)"""
assert entry in shader, "Metal 入口改变后需要校准 AGSL 转换"
shader = shader.replace("static float", "float").replace(entry, "half4 main(float2 coordinate)")
shader = shader.replace("    float2 safeSize = max(size, float2(1.0));",
                        "    float2 position = coordinate / pixelDensity;\n    float2 safeSize = max(size, float2(1.0));")
shader = shader.replace("currentColor.a", "1.0").replace("saturate(", "clamp01(")
# 注释留在 Metal 源码，避免嵌入 Kotlin 的着色器被当作界面文案。
shader = re.sub(r"(?m)^[ \t]*//[^\n]*\n", "", shader)
shader = """uniform float2 size;
uniform float time;
uniform float tabletStyle;
uniform float prefersOpenLightArcs;
uniform float lavenderPalette;
uniform float pixelDensity;
float clamp01(float x) { return clamp(x, 0.0, 1.0); }
""" + shader
path = root / "androidApp/src/main/kotlin/com/clarklevis/dsh/android/ui/HarnessLavenderFluid.kt"
source = path.read_text()
prefix = source.split('private const val HARNESS_LAVENDER_SHADER = """')[0]
expected = prefix + 'private const val HARNESS_LAVENDER_SHADER = """\n' + shader + '\n"""\n'
if args.check:
    if source != expected:
        raise SystemExit("Android AGSL 未同步，请运行 scripts/sync-harness-lavender-shader.py")
    print("Harness Metal / AGSL 已同步")
else:
    path.write_text(expected)
