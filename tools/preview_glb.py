#!/usr/bin/env python3
"""
cat.glb 미리보기 → PNG (표준 라이브러리만 사용하는 간단한 소프트웨어 렌더러)

  python3 tools/preview_glb.py                      # 여러 각도·자세를 한 장에 (build/cat_preview.png)
  python3 tools/preview_glb.py --fur MACKEREL       # 털 색 바꿔 보기

앱의 Filament 화면과 조명·재질이 같지는 않다. 모양·비율·애니메이션 자세 확인용이다.
"""
import argparse
import json
import math
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "app" / "src" / "main" / "assets" / "models"

FURS = ["cheese", "mackerel", "black", "white"]  # models/cat_<털>.glb
ACCESSORIES = ["Hat", "Sunglasses", "Ribbon", "Scarf"]
BG = (0x14, 0x17, 0x1C)


def to_linear(hex_rgb):
    h = hex_rgb.lstrip("#")
    out = []
    for i in (0, 2, 4):
        c = int(h[i:i + 2], 16) / 255
        out.append(c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4)
    return out


def to_srgb8(c):
    c = max(0.0, min(1.0, c))
    c = c * 12.92 if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055
    return int(round(c * 255))


# ── GLB 읽기 ──
def load(path):
    data = path.read_bytes()
    magic, _ver, _total = struct.unpack_from("<III", data, 0)
    assert magic == 0x46546C67, "GLB 아님"
    jlen, _ = struct.unpack_from("<II", data, 12)
    gltf = json.loads(data[20:20 + jlen])
    blen, _ = struct.unpack_from("<II", data, 20 + jlen)
    binary = data[28 + jlen:28 + jlen + blen]

    def accessor(i):
        a = gltf["accessors"][i]
        v = gltf["bufferViews"][a["bufferView"]]
        n = {"SCALAR": 1, "VEC3": 3, "VEC4": 4}[a["type"]]
        fmt = {5126: "f", 5123: "H", 5125: "I", 5121: "B"}[a["componentType"]]
        vals = struct.unpack_from(f"<{a['count'] * n}{fmt}", binary, v["byteOffset"] + a.get("byteOffset", 0))
        if a.get("normalized"):
            vals = [x / 255 for x in vals]
        return list(vals) if n == 1 else [tuple(vals[k:k + n]) for k in range(0, len(vals), n)]

    return gltf, accessor


# ── 행렬 ──
def mat_trs(t, r, s):
    x, y, z, w = r
    m = [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
         [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
         [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]]
    return [[m[i][0] * s[0], m[i][1] * s[1], m[i][2] * s[2], t[i]] for i in range(3)] + [[0, 0, 0, 1]]


def mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def slerp(a, b, t):
    d = sum(x * y for x, y in zip(a, b))
    if d < 0:
        b, d = tuple(-x for x in b), -d
    if d > 0.9995:
        r = tuple(x + (y - x) * t for x, y in zip(a, b))
    else:
        th = math.acos(d)
        s = math.sin(th)
        r = tuple((math.sin((1 - t) * th) * x + math.sin(t * th) * y) / s for x, y in zip(a, b))
    l = math.sqrt(sum(x * x for x in r))
    return tuple(x / l for x in r)


def sample(times, vals, t, rotation):
    if t <= times[0]:
        return vals[0]
    if t >= times[-1]:
        return vals[-1]
    for i in range(len(times) - 1):
        if times[i] <= t <= times[i + 1]:
            f = (t - times[i]) / (times[i + 1] - times[i])
            if rotation:
                return slerp(vals[i], vals[i + 1], f)
            return tuple(a + (b - a) * f for a, b in zip(vals[i], vals[i + 1]))


# ── 렌더 ──
def render(gltf, accessor, size=360, yaw=25, pitch=8, anims=(), hide=(), ss=2):
    nodes = gltf["nodes"]
    trs = [[n.get("translation", (0, 0, 0)), n.get("rotation", (0, 0, 0, 1)), n.get("scale", (1, 1, 1))] for n in nodes]
    for name, t in anims:
        anim = next(a for a in gltf["animations"] if a["name"] == name)
        for ch in anim["channels"]:
            smp = anim["samplers"][ch["sampler"]]
            path = ch["target"]["path"]
            v = sample(accessor(smp["input"]), accessor(smp["output"]), t, path == "rotation")
            trs[ch["target"]["node"]][{"translation": 0, "rotation": 1, "scale": 2}[path]] = v

    colors = {m["name"]: m["pbrMetallicRoughness"]["baseColorFactor"][:3] for m in gltf["materials"]}
    rough = {m["name"]: m["pbrMetallicRoughness"]["roughnessFactor"] for m in gltf["materials"]}
    coat = {m["name"]: m.get("extensions", {}).get("KHR_materials_clearcoat", {}).get("clearcoatFactor", 0.0)
            for m in gltf["materials"]}
    hidden = set(hide)

    W = size * ss
    cam_d, fov = 6.2, math.radians(30)
    target = (0.0, 1.0, 0.0)
    cy, sy, cp, sp = math.cos(math.radians(yaw)), math.sin(math.radians(yaw)), math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
    f = (W / 2) / math.tan(fov / 2)
    key = norm3((-0.5, 0.8, 0.6))
    fill = norm3((0.7, 0.2, 0.4))

    zbuf = [1e9] * (W * W)
    img = [None] * (W * W)

    def view(p):
        x, y, z = p[0] - target[0], p[1] - target[1], p[2] - target[2]
        x, z = x * cy - z * sy, x * sy + z * cy           # yaw (y 축)
        y, z = y * cp - z * sp, y * sp + z * cp           # pitch (x 축)
        return x, y, cam_d - z

    def walk(i, parent, hidden_branch):
        n = nodes[i]
        m = mat_mul(parent, mat_trs(*trs[i]))
        hb = hidden_branch or n["name"] in hidden
        if "mesh" in n and not hb:
            for prim in gltf["meshes"][n["mesh"]]["primitives"]:
                draw(m, prim)
        for c in n.get("children", []):
            walk(c, m, hb)

    def draw(m, prim):
        mat = gltf["materials"][prim["material"]]["name"]
        base = colors[mat]
        r = rough[mat]
        cc = coat[mat]
        vcol = accessor(prim["attributes"]["COLOR_0"]) if "COLOR_0" in prim["attributes"] else None
        pos = accessor(prim["attributes"]["POSITION"])
        nrm = accessor(prim["attributes"]["NORMAL"])
        idx = accessor(prim["indices"])
        wp = [tuple(m[k][0] * p[0] + m[k][1] * p[1] + m[k][2] * p[2] + m[k][3] for k in range(3)) for p in pos]
        wn = [norm3(tuple(m[k][0] * q[0] + m[k][1] * q[1] + m[k][2] * q[2] for k in range(3))) for q in nrm]
        vp = [view(p) for p in wp]
        sc = [(W / 2 + f * x / z, W / 2 - f * y / z, z) for x, y, z in vp]
        cam_pos = inv_view((0, 0, 0))
        for t in range(0, len(idx), 3):
            a, b, c = idx[t], idx[t + 1], idx[t + 2]
            (x0, y0, z0), (x1, y1, z1), (x2, y2, z2) = sc[a], sc[b], sc[c]
            area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
            if area >= 0:  # 뒷면 (화면 y 가 아래로 커지므로 반시계 = 음수)
                continue
            minx, maxx = max(int(min(x0, x1, x2)), 0), min(int(max(x0, x1, x2)) + 1, W - 1)
            miny, maxy = max(int(min(y0, y1, y2)), 0), min(int(max(y0, y1, y2)) + 1, W - 1)
            inv_area = 1 / area
            for py in range(miny, maxy + 1):
                for px in range(minx, maxx + 1):
                    sx, syy = px + 0.5, py + 0.5
                    w0 = ((x1 - sx) * (y2 - syy) - (x2 - sx) * (y1 - syy)) * inv_area
                    w1 = ((x2 - sx) * (y0 - syy) - (x0 - sx) * (y2 - syy)) * inv_area
                    w2 = 1 - w0 - w1
                    if w0 < 0 or w1 < 0 or w2 < 0:
                        continue
                    z = w0 * z0 + w1 * z1 + w2 * z2
                    k = py * W + px
                    if z >= zbuf[k]:
                        continue
                    zbuf[k] = z
                    n = norm3(tuple(w0 * wn[a][i] + w1 * wn[b][i] + w2 * wn[c][i] for i in range(3)))
                    p = tuple(w0 * wp[a][i] + w1 * wp[b][i] + w2 * wp[c][i] for i in range(3))
                    v = norm3(tuple(cam_pos[i] - p[i] for i in range(3)))
                    col = base
                    if vcol:
                        col = tuple(base[i] * (w0 * vcol[a][i] + w1 * vcol[b][i] + w2 * vcol[c][i]) for i in range(3))
                    img[k] = shade_px(col, r, n, v, key, fill, cc)

    def inv_view(_):
        # 카메라 위치 (월드)
        x, y, z = 0.0, 0.0, cam_d
        y, z = y * cp + z * sp, -y * sp + z * cp
        x, z = x * cy + z * sy, -x * sy + z * cy
        return (x + target[0], y + target[1], z + target[2])

    ident = [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]
    walk(gltf["scenes"][0]["nodes"][0], ident, False)

    bg_lin = [to_linear("#%02X%02X%02X" % BG)[i] for i in range(3)]
    out = []
    for y in range(size):
        row = []
        for x in range(size):
            acc = [0.0, 0.0, 0.0]
            for dy in range(ss):
                for dx in range(ss):
                    c = img[(y * ss + dy) * W + x * ss + dx] or bg_lin
                    acc = [acc[i] + c[i] for i in range(3)]
            row.append(tuple(to_srgb8(a / (ss * ss)) for a in acc))
        out.append(row)
    return out


def norm3(v):
    l = math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) or 1
    return (v[0] / l, v[1] / l, v[2] / l)


def shade_px(base, rough, n, v, key, fill, coat=0.0):
    nk = max(0.0, sum(n[i] * key[i] for i in range(3)))
    nf = max(0.0, sum(n[i] * fill[i] for i in range(3)))
    sky = 0.5 + 0.5 * n[1]
    nv = max(0.0, sum(n[i] * v[i] for i in range(3)))
    diff = 0.22 + 0.18 * sky + 0.95 * nk + 0.28 * nf
    h = norm3(tuple(key[i] + v[i] for i in range(3)))
    shin = 8 + (1 - rough) * 120
    spec = (0.04 + 0.5 * (1 - rough) ** 2) * max(0.0, sum(n[i] * h[i] for i in range(3))) ** shin
    rim = 0.12 * (1 - nv) ** 3
    if coat:  # 클리어코트: 날카로운 반사 한 겹 더
        spec += coat * 0.6 * max(0.0, sum(n[i] * h[i] for i in range(3))) ** 300 + coat * 0.04 * (1 - nv) ** 4
    return tuple(base[i] * diff + spec + rim for i in range(3))


def write_png(rows, path):
    h, w = len(rows), len(rows[0])
    raw = b"".join(b"\x00" + bytes(c for px in row for c in px) for row in rows)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


def sheet(images, cols):
    size = len(images[0])
    rows_n = (len(images) + cols - 1) // cols
    out = [[BG] * (size * cols) for _ in range(size * rows_n)]
    for i, im in enumerate(images):
        ox, oy = (i % cols) * size, (i // cols) * size
        for y in range(size):
            out[oy + y][ox:ox + size] = im[y]
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--glb", type=Path, default=None, help="기본: models/cat_<털>.glb")
    ap.add_argument("--out", type=Path, default=ROOT / "build" / "cat_preview.png")
    ap.add_argument("--fur", default="cheese", choices=FURS)
    ap.add_argument("--size", type=int, default=320)
    ap.add_argument("--set", default="views", choices=["views", "poses", "furs"])
    a = ap.parse_args()
    gltf, acc = load(a.glb or MODELS / f"cat_{a.fur}.glb")
    hide_all = ACCESSORIES + ["Receipt"]
    if a.set == "views":
        shots = [dict(yaw=0), dict(yaw=30), dict(yaw=80), dict(yaw=160, pitch=15)]
        imgs = [render(gltf, acc, a.size, hide=hide_all, **s) for s in shots]
    elif a.set == "poses":
        shots = [
            dict(anims=[("Blink", 0.1)], hide=hide_all),
            dict(anims=[("Wave", 0.55)], hide=hide_all),
            dict(anims=[("ShowReceipt", 1.2)], hide=ACCESSORIES),
            dict(hide=["Receipt"]),
        ]
        imgs = [render(gltf, acc, a.size, yaw=25, **s) for s in shots]
    else:
        imgs = []
        for k in FURS:
            g, ac = load(MODELS / f"cat_{k}.glb")
            imgs.append(render(g, ac, a.size, yaw=20, hide=hide_all))
    write_png(sheet(imgs, 4 if len(imgs) >= 4 else len(imgs)), a.out)
    print(a.out)
