"""将本目录的六个 SVG 母版转换为 Android VectorDrawable（不栅格化）。"""

from pathlib import Path
import re
import xml.etree.ElementTree as ET

ANDROID = "http://schemas.android.com/apk/res/android"
AAPT = "http://schemas.android.com/aapt"
ET.register_namespace("android", ANDROID)
ET.register_namespace("aapt", AAPT)
ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT.parents[1] / "androidApp/src/main/res/drawable"


def attr(name):
    return f"{{{ANDROID}}}{name}"


def color(value, opacity="1"):
    value = {"white": "#FFFFFF", "black": "#000000", "none": "#00000000"}.get(value, value)
    if len(value) == 9:
        return value
    assert re.fullmatch(r"#[0-9a-fA-F]{6}", value), value
    return f"#{round(float(opacity) * 255):02X}{value[1:]}"


def convert(source):
    svg = ET.parse(source).getroot()
    definitions = {node.attrib["id"]: node for node in svg.iter() if "id" in node.attrib}
    vector = ET.Element("vector", {
        attr("width"): "38dp", attr("height"): "38dp",
        attr("viewportWidth"): "256", attr("viewportHeight"): "256",
    })

    def paint(path, node, kind):
        value = node.get(kind, "none")
        match = re.fullmatch(r"url\(#(.+)\)", value)
        if not match:
            path.set(attr(kind + "Color"), color(value))
        else:
            gradient = definitions[match[1]]
            assert gradient.tag.endswith("linearGradient")
            item = ET.SubElement(path, f"{{{AAPT}}}attr", {"name": "android:" + kind + "Color"})
            output = ET.SubElement(item, "gradient", {attr("type"): "linear", **{
                attr(target): gradient.get(key, "0")
                for key, target in [("x1", "startX"), ("y1", "startY"), ("x2", "endX"), ("y2", "endY")]
            }})
            for stop in gradient:
                ET.SubElement(output, "item", {
                    attr("offset"): stop.get("offset", "0"),
                    attr("color"): color(stop.get("stop-color"), stop.get("stop-opacity", "1")),
                })
        if kind + "-opacity" in node.attrib:
            path.set(attr(kind + "Alpha"), node.get(kind + "-opacity"))

    def visit(node, parent):
        tag = node.tag.split("}")[-1]
        if tag in {"defs", "mask"}:
            return
        if tag in {"svg", "g"}:
            group = ET.SubElement(parent, "group") if tag == "g" else parent
            if "mask" in node.attrib:
                mask = definitions[re.fullmatch(r"url\(#(.+)\)", node.get("mask"))[1]]
                paths = [child for child in mask if child.tag.endswith("path")]
                assert len(paths) == 1
                ET.SubElement(group, "clip-path", {attr("pathData"): paths[0].get("d")})
            for child in node:
                visit(child, group)
        elif tag == "path":
            if "transform" in node.attrib:
                group = ET.SubElement(parent, "group")
                for operation, arguments in re.findall(r"(\w+)\(([^)]+)\)", node.get("transform")):
                    numbers = re.split(r"[ ,]+", arguments)
                    if operation == "translate":
                        group.set(attr("translateX"), numbers[0])
                        group.set(attr("translateY"), numbers[1])
                    elif operation == "rotate":
                        assert len(numbers) == 1
                        group.set(attr("rotation"), numbers[0])
                    else:
                        raise ValueError(operation)
                parent = group
            path = ET.SubElement(parent, "path", {attr("pathData"): node.get("d")})
            paint(path, node, "fill")
            if "stroke" in node.attrib:
                paint(path, node, "stroke")
                for key, target, default in [("stroke-width", "strokeWidth", "1"), ("stroke-linecap", "strokeLineCap", "butt"), ("stroke-linejoin", "strokeLineJoin", "miter")]:
                    path.set(attr(target), node.get(key, default))
        else:
            raise ValueError(f"Unsupported SVG element: {tag}")

    visit(svg, vector)
    ET.indent(vector)
    target = OUTPUT / f"connector_app_{source.stem}.xml"
    ET.ElementTree(vector).write(target, encoding="utf-8", xml_declaration=True)
    print(target.relative_to(ROOT.parents[1]))


if __name__ == "__main__":
    for name in ("calendar", "reminders", "location", "photos", "music", "alarms"):
        convert(ROOT / f"{name}.svg")
