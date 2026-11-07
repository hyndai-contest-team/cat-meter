#!/usr/bin/env python3
"""
art/cat/*.svg → app/src/main/res/drawable/cat_*.xml (Android VectorDrawable) 변환기.

고양이 일러스트 원본은 SVG 로 관리하고, 고친 뒤 이 스크립트를 다시 돌린다.
  python3 tools/svg2vd.py

지원하는 SVG (Figma·일러스트레이터에서 내보낼 때 이 범위로 맞춘다)
- 요소: path, ellipse, circle, rect(rx 포함), g
- g 의 transform: translate(x y), rotate(deg cx cy) — 이 순서로 한 번씩만
- 속성: fill, stroke, stroke-width, stroke-linecap, stroke-linejoin, opacity (g 의 opacity 는 자식에 곱한다)
- 털 색 자리는 약속된 임시 색으로 칠한다 (앱이 실행 중에 바꿔 칠한다, ui/CatArt.kt)
    #FF00FF 털 기본 / #00FF00 털 그늘 / #00FFFF 털 밝은 부분 / #FFFF00 무늬(무늬 없는 털이면 투명)
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "art" / "cat"
DST = ROOT / "app" / "src" / "main" / "res" / "drawable"
SVG_NS = "{http://www.w3.org/2000/svg}"
A = "android:"


def num(v, default=0.0):
    return float(v) if v is not None else default


def ellipse_path(cx, cy, rx, ry):
    return (f"M{cx - rx:g},{cy:g} A{rx:g},{ry:g} 0 1,0 {cx + rx:g},{cy:g} "
            f"A{rx:g},{ry:g} 0 1,0 {cx - rx:g},{cy:g} Z")


def rect_path(x, y, w, h, rx):
    if rx <= 0:
        return f"M{x:g},{y:g} H{x + w:g} V{y + h:g} H{x:g} Z"
    r = min(rx, w / 2, h / 2)
    return (f"M{x + r:g},{y:g} H{x + w - r:g} A{r:g},{r:g} 0 0,1 {x + w:g},{y + r:g} "
            f"V{y + h - r:g} A{r:g},{r:g} 0 0,1 {x + w - r:g},{y + h:g} "
            f"H{x + r:g} A{r:g},{r:g} 0 0,1 {x:g},{y + h - r:g} "
            f"V{y + r:g} A{r:g},{r:g} 0 0,1 {x + r:g},{y:g} Z")


def color(v):
    if v is None or v == "none":
        return None
    v = v.strip()
    if v.lower() in ("#fff", "white"):
        return "#FFFFFF"
    if re.fullmatch(r"#[0-9a-fA-F]{3}", v):
        return "#" + "".join(c * 2 for c in v[1:]).upper()
    if re.fullmatch(r"#[0-9a-fA-F]{6}", v):
        return v.upper()
    raise ValueError(f"지원하지 않는 색: {v}")


def shape_path(el):
    tag = el.tag.replace(SVG_NS, "")
    if tag == "path":
        return el.get("d")
    if tag == "ellipse":
        return ellipse_path(num(el.get("cx")), num(el.get("cy")), num(el.get("rx")), num(el.get("ry")))
    if tag == "circle":
        r = num(el.get("r"))
        return ellipse_path(num(el.get("cx")), num(el.get("cy")), r, r)
    if tag == "rect":
        return rect_path(num(el.get("x")), num(el.get("y")), num(el.get("width")), num(el.get("height")), num(el.get("rx")))
    return None


def convert_node(el, out, indent, alpha):
    tag = el.tag.replace(SVG_NS, "")
    pad = "    " * indent
    if tag == "g":
        a = alpha * num(el.get("opacity"), 1.0)
        attrs = []
        if el.get("id"):
            attrs.append(f'{A}name="{el.get("id")}"')
        t = el.get("transform", "")
        m = re.search(r"translate\(\s*([-\d.]+)[ ,]+([-\d.]+)\s*\)", t)
        if m:
            attrs += [f'{A}translateX="{m.group(1)}"', f'{A}translateY="{m.group(2)}"']
        m = re.search(r"rotate\(\s*([-\d.]+)[ ,]+([-\d.]+)[ ,]+([-\d.]+)\s*\)", t)
        if m:
            attrs += [f'{A}rotation="{m.group(1)}"', f'{A}pivotX="{m.group(2)}"', f'{A}pivotY="{m.group(3)}"']
        out.append(f"{pad}<group {' '.join(attrs)}>" if attrs else f"{pad}<group>")
        for c in el:
            convert_node(c, out, indent + 1, a)
        out.append(f"{pad}</group>")
        return
    d = shape_path(el)
    if d is None:
        return
    a = alpha * num(el.get("opacity"), 1.0)
    attrs = [f'{A}pathData="{" ".join(d.split())}"']
    fill = color(el.get("fill", "#000000"))
    if fill:
        attrs.append(f'{A}fillColor="{fill}"')
        if a < 1:
            attrs.append(f'{A}fillAlpha="{a:g}"')
    stroke = color(el.get("stroke"))
    if stroke:
        attrs += [f'{A}strokeColor="{stroke}"', f'{A}strokeWidth="{el.get("stroke-width", "1")}"']
        if el.get("stroke-linecap"):
            attrs.append(f'{A}strokeLineCap="{el.get("stroke-linecap")}"')
        if el.get("stroke-linejoin"):
            attrs.append(f'{A}strokeLineJoin="{el.get("stroke-linejoin")}"')
        if a < 1:
            attrs.append(f'{A}strokeAlpha="{a:g}"')
    out.append(f"{pad}<path {' '.join(attrs)} />")


def convert(svg: Path, dst: Path):
    root = ET.parse(svg).getroot()
    vb = [float(v) for v in root.get("viewBox").split()]
    w, h = vb[2], vb[3]
    out = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- 자동 생성: art/cat/{svg.name} → tools/svg2vd.py. 직접 고치지 말고 SVG 를 고친 뒤 다시 생성한다. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{w:g}dp" android:height="{h:g}dp"',
        f'    android:viewportWidth="{w:g}" android:viewportHeight="{h:g}">',
    ]
    for c in root:
        convert_node(c, out, 1, 1.0)
    out.append("</vector>")
    dst.write_text("\n".join(out) + "\n", encoding="utf-8")


def main():
    files = sorted(SRC.glob("*.svg"))
    if not files:
        sys.exit(f"{SRC} 에 SVG 가 없습니다")
    for svg in files:
        dst = DST / f"cat_{svg.stem}.xml"
        convert(svg, dst)
        print(f"{svg.relative_to(ROOT)} → {dst.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
