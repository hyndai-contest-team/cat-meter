#!/usr/bin/env python3
"""
냥 미터기 3D 고양이 생성기 v0.2 → app/src/main/assets/models/cat_<털>.glb (치즈·고등어·까망·하양 4개)

3D 고양이는 이 스크립트가 원본이다. 모양·색·애니메이션을 고친 뒤 다시 돌린다.
  python3 tools/make_cat_glb.py
  python3 tools/preview_glb.py          # 미리보기 PNG (선택)

만드는 방식
- 몸·머리·발·꼬리는 거리 함수(SDF)를 부드럽게 합친 뒤 표면을 뽑는다 (surface nets).
  덩어리를 이어 붙인 티가 나지 않는 말랑한 피규어 표면이 된다.
- 털 색·배 털·줄무늬·볼 터치·귀 안쪽은 꼭짓점 색(COLOR_0)으로 부드럽게 칠한다.
  색이 털마다 달라서 털 색마다 GLB 를 하나씩 만든다 (재질도 털마다 다르다: 까망은 유광 비닐, 나머지는 무광).
- 표준 라이브러리만 쓴다 (Blender 등 외부 도구 없음). 실행에 1~2분 걸린다.
- 노드·재질·애니메이션 이름은 docs/spec/cat-model-spec.md 를 따른다. 바꾸면 명세와 앱(ui/cat3d)을 함께 고친다.

좌표계: glTF 기본 (Y 위, +Z 가 고양이 정면), 바닥 y=0, 키 약 2 (귀 끝까지).
"""
import json
import math
import struct
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "app" / "src" / "main" / "assets" / "models"
MODEL_VERSION = "0.2.0"
PI = math.pi
RES = 0.022  # 표면 격자 간격. 작을수록 매끈하지만 느리고 파일이 커진다.


# ───────────────────────── 벡터 ─────────────────────────
def add(a, b): return (a[0] + b[0], a[1] + b[1], a[2] + b[2])
def sub(a, b): return (a[0] - b[0], a[1] - b[1], a[2] - b[2])
def mul(a, s): return (a[0] * s, a[1] * s, a[2] * s)
def dot(a, b): return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
def cross(a, b): return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
def length(a): return math.sqrt(dot(a, a))


def norm(a):
    l = length(a)
    return (0.0, 0.0, 0.0) if l < 1e-12 else (a[0] / l, a[1] / l, a[2] / l)


def clamp(x, a, b): return a if x < a else b if x > b else x


def smoothstep(e0, e1, x):
    t = clamp((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def mix(a, b, t): return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)


def quat(axis, deg):
    k = norm(axis)
    h = math.radians(deg) / 2
    s = math.sin(h)
    return (k[0] * s, k[1] * s, k[2] * s, math.cos(h))


def qmul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return (aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz)


def qrot(q, v):
    x, y, z, w = q
    u = (x, y, z)
    t = mul(cross(u, v), 2)
    return add(add(v, mul(t, w)), cross(u, t))


def qinv(q): return (-q[0], -q[1], -q[2], q[3])


QI = (0.0, 0.0, 0.0, 1.0)
X, Y, Z = (1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 1.0)


def lin(hex_rgb):
    """'#RRGGBB'(sRGB) → 선형 RGB"""
    h = hex_rgb.lstrip("#")
    out = []
    for i in (0, 2, 4):
        c = int(h[i:i + 2], 16) / 255
        out.append(c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4)
    return tuple(out)


# ───────────────────────── 거리 함수 ─────────────────────────
def smin(a, b, k):
    h = max(k - abs(a - b), 0.0) / k
    return min(a, b) - h * h * k * 0.25


def smax(a, b, k): return -smin(-a, -b, k)


def sd_ellipsoid(p, c, r):
    x, y, z = (p[0] - c[0]) / r[0], (p[1] - c[1]) / r[1], (p[2] - c[2]) / r[2]
    k0 = math.sqrt(x * x + y * y + z * z)
    x, y, z = x / r[0], y / r[1], z / r[2]
    k1 = math.sqrt(x * x + y * y + z * z)
    return k0 * (k0 - 1.0) / k1 if k1 > 1e-9 else -min(r)


def sd_capsule(p, a, b, ra, rb=None):
    """a→b 캡슐. rb 를 주면 반지름이 ra→rb 로 변한다."""
    pa, ba = sub(p, a), sub(b, a)
    h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0)
    r = ra if rb is None else ra + (rb - ra) * h
    return length(sub(pa, mul(ba, h))) - r


def gradient(f, p, e=1e-3):
    return (f((p[0] + e, p[1], p[2])) - f((p[0] - e, p[1], p[2])),
            f((p[0], p[1] + e, p[2])) - f((p[0], p[1] - e, p[2])),
            f((p[0], p[1], p[2] + e)) - f((p[0], p[1], p[2] - e)))


def march_front(f, x, y, z0=2.0):
    """(x, y) 에서 -Z 로 표면을 찾아 (점, 노멀)"""
    z = z0
    for _ in range(200):
        d = f((x, y, z))
        if abs(d) < 1e-5:
            break
        z -= d * 0.9
    p = (x, y, z)
    return p, norm(gradient(f, p))


def frame_from_normal(n):
    ez = norm(n)
    ex = norm(cross(Y, ez))
    return ex, cross(ez, ex), ez


# ───────────────────────── 메시 ─────────────────────────
class Mesh:
    def __init__(self, pos=None, nrm=None, idx=None):
        self.pos, self.nrm, self.idx = pos or [], nrm, idx or []
        self.inside = []
        self.hard = False

    # ── 매개변수 격자 (눈·코·소품 등 작은 부품) ──
    def grid(self, rows, insides):
        base = len(self.pos)
        nu, nv = len(rows), len(rows[0])
        for i in range(nu):
            self.pos.extend(rows[i])
            self.inside.extend(insides[i])
        tris = []
        for i in range(nu - 1):
            for j in range(nv - 1):
                a = base + i * nv + j
                tris += [(a, a + nv, a + nv + 1), (a, a + nv + 1, a + 1)]
        vote = 0.0
        for a, b, c in tris:
            n = cross(sub(self.pos[b], self.pos[a]), sub(self.pos[c], self.pos[a]))
            cen = mul(add(add(self.pos[a], self.pos[b]), self.pos[c]), 1 / 3)
            ins = mul(add(add(self.inside[a], self.inside[b]), self.inside[c]), 1 / 3)
            vote += dot(n, sub(cen, ins))
        if vote < 0:
            tris = [(a, c, b) for a, b, c in tris]
        self.idx.extend(tris)
        return self

    def merge(self, other):
        base = len(self.pos)
        self.pos.extend(other.pos)
        self.inside.extend(other.inside)
        self.idx.extend([(a + base, b + base, c + base) for a, b, c in other.idx])
        return self

    def finalize(self):
        if self.nrm is not None:
            return self
        if self.hard:
            pos, nrm, idx = [], [], []
            for a, b, c in self.idx:
                n = norm(cross(sub(self.pos[b], self.pos[a]), sub(self.pos[c], self.pos[a])))
                if n == (0.0, 0.0, 0.0):
                    continue  # 넓이 0 삼각형
                k = len(pos)
                pos += [self.pos[a], self.pos[b], self.pos[c]]
                nrm += [n, n, n]
                idx.append((k, k + 1, k + 2))
            self.pos, self.nrm, self.idx = pos, nrm, idx
            return self
        remap, pos, new_index = {}, [], []
        for p in self.pos:
            key = (round(p[0], 5), round(p[1], 5), round(p[2], 5))
            if key not in remap:
                remap[key] = len(pos)
                pos.append(p)
            new_index.append(remap[key])
        idx = []
        for a, b, c in self.idx:
            a, b, c = new_index[a], new_index[b], new_index[c]
            if a != b and b != c and a != c:
                idx.append((a, b, c))
        acc = [(0.0, 0.0, 0.0)] * len(pos)
        for a, b, c in idx:
            n = cross(sub(pos[b], pos[a]), sub(pos[c], pos[a]))
            acc[a], acc[b], acc[c] = add(acc[a], n), add(acc[b], n), add(acc[c], n)
        self.pos, self.idx = pos, idx
        self.nrm = [nn if nn != (0.0, 0.0, 0.0) else Y for nn in (norm(n) for n in acc)]
        return self


def surface_nets(f, lo, hi, h=RES):
    """거리 함수 f 의 0 등위면 → 메시. 꼭짓점은 표면으로 끌어당기고 노멀은 f 의 기울기로 구한다."""
    nx, ny, nz = (int(math.ceil((hi[i] - lo[i]) / h)) + 1 for i in range(3))
    vals = [0.0] * (nx * ny * nz)
    for i in range(nx):
        x = lo[0] + i * h
        for j in range(ny):
            y = lo[1] + j * h
            base = (i * ny + j) * nz
            for k in range(nz):
                vals[base + k] = f((x, y, lo[2] + k * h))

    def at(i, j, k): return vals[(i * ny + j) * nz + k]

    corners = [(0, 0, 0), (1, 0, 0), (0, 1, 0), (1, 1, 0), (0, 0, 1), (1, 0, 1), (0, 1, 1), (1, 1, 1)]
    edges = [(0, 1), (2, 3), (4, 5), (6, 7), (0, 2), (1, 3), (4, 6), (5, 7), (0, 4), (1, 5), (2, 6), (3, 7)]
    near = 3.5 * h  # 납작하게 누른 귀처럼 거리를 크게 어림하는 함수도 있어 여유 있게
    cell_vert, pos = {}, []
    for i in range(nx - 1):
        for j in range(ny - 1):
            for k in range(nz - 1):
                v0 = at(i, j, k)
                if v0 > near or v0 < -near:
                    continue
                cv = [at(i + a, j + b, k + c) for a, b, c in corners]
                if min(cv) >= 0 or max(cv) < 0:
                    continue
                sx = sy = sz = 0.0
                n = 0
                for e0, e1 in edges:
                    a, b = cv[e0], cv[e1]
                    if (a < 0) != (b < 0):
                        t = a / (a - b)
                        c0, c1 = corners[e0], corners[e1]
                        sx += c0[0] + (c1[0] - c0[0]) * t
                        sy += c0[1] + (c1[1] - c0[1]) * t
                        sz += c0[2] + (c1[2] - c0[2]) * t
                        n += 1
                cell_vert[(i, j, k)] = len(pos)
                pos.append((lo[0] + (i + sx / n) * h, lo[1] + (j + sy / n) * h, lo[2] + (k + sz / n) * h))

    quads = []
    for (i, j, k) in cell_vert:
        v0 = at(i, j, k)
        # 이 칸의 최소 꼭짓점에서 나가는 세 모서리
        if j > 0 and k > 0 and (v0 < 0) != (at(i + 1, j, k) < 0):
            quads.append(((i, j - 1, k - 1), (i, j, k - 1), (i, j, k), (i, j - 1, k)))
        if i > 0 and k > 0 and (v0 < 0) != (at(i, j + 1, k) < 0):
            quads.append(((i - 1, j, k - 1), (i, j, k - 1), (i, j, k), (i - 1, j, k)))
        if i > 0 and j > 0 and (v0 < 0) != (at(i, j, k + 1) < 0):
            quads.append(((i - 1, j - 1, k), (i, j - 1, k), (i, j, k), (i - 1, j, k)))

    # 표면으로 끌어당기기 + 노멀
    nrm = []
    e = 1e-4
    for vi, p in enumerate(pos):
        for _ in range(3):
            d = f(p)
            g = mul(gradient(f, p, e), 1 / (2 * e))
            gg = dot(g, g)
            if gg < 1e-12:
                break
            p = sub(p, mul(g, d / gg))
        pos[vi] = p
        nrm.append(norm(gradient(f, p)))

    idx = []
    for q in quads:
        try:
            a, b, c, d = (cell_vert[x] for x in q)
        except KeyError:
            continue
        # 짧은 대각선으로 나누기
        if length(sub(pos[a], pos[c])) <= length(sub(pos[b], pos[d])):
            tris = [(a, b, c), (a, c, d)]
        else:
            tris = [(a, b, d), (b, c, d)]
        for t0, t1, t2 in tris:
            fn = cross(sub(pos[t1], pos[t0]), sub(pos[t2], pos[t0]))
            if dot(fn, add(add(nrm[t0], nrm[t1]), nrm[t2])) < 0:
                t1, t2 = t2, t1
            idx.append((t0, t1, t2))
    return Mesh(pos, nrm, idx)


def param_surface(fn, inside, nu, nv):
    rows = [[fn(i / nu, j / nv) for j in range(nv + 1)] for i in range(nu + 1)]
    return Mesh().grid(rows, [[inside] * (nv + 1) for _ in range(nu + 1)])


def ellipsoid(center, radii, frame=None, deform=None, nu=20, nv=32):
    def f(u, v):
        th, ph = u * PI, v * 2 * PI
        d = (math.sin(th) * math.sin(ph), math.cos(th), math.sin(th) * math.cos(ph))
        s = deform(d) if deform else (1, 1, 1)
        l = (radii[0] * s[0] * d[0], radii[1] * s[1] * d[1], radii[2] * s[2] * d[2])
        if frame:
            ex, ey, ez = frame
            l = add(add(mul(ex, l[0]), mul(ey, l[1])), mul(ez, l[2]))
        return add(center, l)
    return param_surface(f, center, nu, nv)


def catmull(points, samples_per_seg=8):
    pts = [points[0]] + list(points) + [points[-1]]
    out = []
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
        for s in range(samples_per_seg):
            t = s / samples_per_seg
            t2, t3 = t * t, t * t * t
            out.append(tuple(0.5 * ((2 * p1[k]) + (-p0[k] + p2[k]) * t + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t2
                                    + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t3) for k in range(3)))
    out.append(points[-1])
    return out


def tube(path, radius_fn, sides=12, closed=False, caps=True, flatten=1.0):
    n = len(path)
    rings, prev = [], None
    for i, c in enumerate(path):
        if closed:
            t = norm(sub(path[(i + 1) % (n - 1)], path[(i - 1) % (n - 1)]))
            nn = norm(sub(Y, mul(t, dot(Y, t))))  # 고리는 늘 같은 기준축 → 이음새 틈 없음
        else:
            t = norm(sub(path[min(i + 1, n - 1)], path[max(i - 1, 0)]))
            if prev is None:
                ref = Y if abs(dot(t, Y)) < 0.9 else X
                nn = norm(cross(cross(t, ref), t))
            else:
                nn = norm(sub(prev, mul(t, dot(prev, t))))
        prev = nn
        rings.append((c, nn, cross(t, nn), radius_fn(i / (n - 1)), t))
    if caps and not closed:
        steps = 4
        c, nn, bb, r, t = rings[0]
        start = [(sub(c, mul(t, r * math.sin(PI / 2 * k / steps))), nn, bb, r * math.cos(PI / 2 * k / steps), t)
                 for k in range(steps, 0, -1)]
        c, nn, bb, r, t = rings[-1]
        end = [(add(c, mul(t, r * math.sin(PI / 2 * k / steps))), nn, bb, r * math.cos(PI / 2 * k / steps), t)
               for k in range(1, steps + 1)]
        rings = start + rings + end
    rows, ins = [], []
    for c, nn, bb, r, _ in rings:
        rows.append([add(c, add(mul(nn, r * math.cos(2 * PI * j / sides)), mul(bb, r * flatten * math.sin(2 * PI * j / sides))))
                     for j in range(sides + 1)])
        ins.append([c] * (sides + 1))
    return Mesh().grid(rows, ins)


def box(center, size, rot=QI):
    hx, hy, hz = size[0] / 2, size[1] / 2, size[2] / 2
    m = Mesh()
    m.hard = True
    for n, u, v in ((Z, X, Y), (mul(Z, -1), mul(X, -1), Y), (X, mul(Z, -1), Y),
                    (mul(X, -1), Z, Y), (Y, X, mul(Z, -1)), (mul(Y, -1), X, Z)):
        ext = lambda a: abs(dot(a, X)) * hx + abs(dot(a, Y)) * hy + abs(dot(a, Z)) * hz
        c = mul(n, ext(n))
        quad = [add(center, qrot(rot, add(add(c, mul(u, su * ext(u))), mul(v, sv * ext(v)))))
                for su, sv in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
        k = len(m.pos)
        m.pos += quad
        m.inside += [center] * 4
        m.idx += [(k, k + 1, k + 2), (k, k + 2, k + 3)]
    return m


# ───────────────────────── 노드 ─────────────────────────
class Node:
    def __init__(self, name, pivot, parent=None):
        self.name, self.pivot, self.parent = name, pivot, parent
        self.prims = []  # [(재질, Mesh, 칠하기 함수 또는 None)]
        self.children = []
        if parent:
            parent.children.append(self)

    def add(self, material, mesh, paint=None):
        self.prims.append((material, mesh.finalize(), paint))
        return mesh

    @property
    def rest_translation(self):
        return self.pivot if not self.parent else sub(self.pivot, self.parent.pivot)


def walk(n):
    yield n
    for c in n.children:
        yield from walk(c)


def find(root, name):
    return next(n for n in walk(root) if n.name == name)


# ───────────────────────── 털 ─────────────────────────
# 앱의 Fur(trip/Models.kt) 와 순서·이름을 맞춘다. 색은 3D 조명 아래에서 보기 좋게 따로 잡았다.
FURS = {
    "cheese":   dict(main="#F4AE57", light="#FFF4E3", shade="#DE8433", stripes=True, socks=True, glossy=False,
                     iris="#3B2416", iris_low="#7A4A2A", whisker="#B98E70", nose="#F29AA0", ear="#F7B7AF", blush="#F79A9A"),
    "mackerel": dict(main="#AEB4BE", light="#F6F7F9", shade="#68707C", stripes=True, socks=True, glossy=False,
                     iris="#2C2622", iris_low="#5E5048", whisker="#7F8590", nose="#EE98A3", ear="#F2B2B4", blush="#F4A0A8"),
    "black":    dict(main="#232329", light="#2B2B33", shade="#1B1B20", stripes=False, socks=False, glossy=True,
                     iris="#E39A2E", iris_low="#F7CF63", whisker="#C8C8D0", nose="#EE8A7E", ear="#D9767E", blush="#7A3E4A"),
    "white":    dict(main="#FBF7F1", light="#FFFFFF", shade="#ECE2D6", stripes=False, socks=False, glossy=False,
                     iris="#3A2A22", iris_low="#6E5444", whisker="#CDBFB2", nose="#F4A3AC", ear="#F8BDBD", blush="#F9AEB2"),
}
PUPIL = "#120C0A"

# 고정 색 재질 (털마다 같음)
FIXED_MATERIALS = {
    "EyeHighlight": dict(color="#FFFFFF", rough=0.35),
    "Mouth":        dict(color="#5B3B33", rough=0.6),
    "Paper":        dict(color="#FFFBF2", rough=0.85, double=True),
    "Ink":          dict(color="#2B2B2B", rough=0.85),
    "HatMain":      dict(color="#2E3A59", rough=0.8),
    "HatBand":      dict(color="#F2C35B", rough=0.7),
    "Lens":         dict(color="#1F1E24", rough=0.08, clearcoat=1.0),
    "Frame":        dict(color="#2B2B2B", rough=0.4),
    "Ribbon":       dict(color="#F28DB2", rough=0.55),
    "Scarf":        dict(color="#E2574C", rough=0.85),
}


def materials_for(fur):
    f = FURS[fur]
    m = {
        # 꼭짓점 색을 쓰는 재질: baseColorFactor 는 흰색
        "Fur": dict(color="#FFFFFF", rough=0.32 if f["glossy"] else 0.74, clearcoat=0.7 if f["glossy"] else 0.0,
                    clearcoat_rough=0.12),
        "Eye": dict(color="#FFFFFF", rough=0.05, clearcoat=1.0, clearcoat_rough=0.02),
        "Nose": dict(color=f["nose"], rough=0.45),
        "Whisker": dict(color=f["whisker"], rough=0.6),
    }
    m.update(FIXED_MATERIALS)
    if fur == "black":
        m["Mouth"] = dict(color="#55505A", rough=0.5)
    return m


# ───────────────────────── 고양이 모양 ─────────────────────────
HEAD_C, HEAD_R = (0.0, 1.20, 0.04), (0.70, 0.58, 0.60)
CHEEKS = [((sx * 0.38, 0.98, 0.16), (0.36, 0.30, 0.36)) for sx in (-1, 1)]
EAR_H, EAR_R0, EAR_R1, EAR_FLAT = 0.44, 0.25, 0.065, 0.55


def ear_frame(sx):
    base = (sx * 0.42, 1.52, -0.03)
    q = qmul(quat(Z, -sx * 21), quat(X, -9))
    return base, q


def ear_local(p, sx):
    base, q = ear_frame(sx)
    l = qrot(qinv(q), sub(p, base))
    return (l[0], l[1], l[2] / EAR_FLAT)


def sd_ear(p, sx):
    l = ear_local(p, sx)
    return sd_capsule(l, (0, 0, 0), (0, EAR_H, 0), EAR_R0, EAR_R1) * EAR_FLAT * 1.3


def sd_head(p):
    d = sd_ellipsoid(p, HEAD_C, HEAD_R)
    for c, r in CHEEKS:
        d = smin(d, sd_ellipsoid(p, c, r), 0.2)
    d = smin(d, sd_ear(p, 1), 0.1)
    d = smin(d, sd_ear(p, -1), 0.1)
    return d


BODY_PARTS = [
    ((0.0, 0.40, -0.03), (0.45, 0.44, 0.41)),     # 몸통
    ((0.0, 0.50, 0.13), (0.30, 0.30, 0.27)),      # 가슴
    ((0.26, 0.20, -0.09), (0.26, 0.21, 0.29)),    # 엉덩이
    ((-0.26, 0.20, -0.09), (0.26, 0.21, 0.29)),
    ((0.29, 0.07, 0.22), (0.14, 0.075, 0.17)),    # 뒷발
    ((-0.29, 0.07, 0.22), (0.14, 0.075, 0.17)),
]


def sd_body(p):
    d = 1e9
    for c, r in BODY_PARTS:
        d = smin(d, sd_ellipsoid(p, c, r), 0.12)
    return smax(d, -p[1], 0.05)  # 바닥 아래는 잘라 낸다


def paw_parts(sx):
    shoulder = (sx * 0.17, 0.52, 0.19)
    return shoulder, (sx * 0.17, 0.12, 0.37), (sx * 0.17, 0.085, 0.42)


def sd_paw(p, sx):
    s, e, c = paw_parts(sx)
    return smin(sd_capsule(p, s, e, 0.105, 0.095), sd_ellipsoid(p, c, (0.12, 0.085, 0.14)), 0.06)


TAIL_PTS = [(0.18, 0.15, -0.33), (0.48, 0.10, -0.24), (0.63, 0.11, 0.02), (0.60, 0.17, 0.28),
            (0.50, 0.33, 0.43), (0.55, 0.52, 0.48), (0.66, 0.61, 0.43)]
TAIL_PATH = catmull(TAIL_PTS, 6)
TAIL_SPLIT = 3 * 6  # 4번째 조절점 = 꼬리 끝 절반의 시작


def tail_radius(t): return 0.085 + 0.012 * math.sin(PI * t)


def sd_tail_range(p, i0, i1):
    d = 1e9
    n = len(TAIL_PATH) - 1
    for i in range(i0, i1):
        d = min(d, sd_capsule(p, TAIL_PATH[i], TAIL_PATH[i + 1], tail_radius(i / n), tail_radius((i + 1) / n)))
    return d


def tail_param(p):
    best, bt = 1e9, 0.0
    n = len(TAIL_PATH) - 1
    for i in range(n):
        a, b = TAIL_PATH[i], TAIL_PATH[i + 1]
        ba = sub(b, a)
        h = clamp(dot(sub(p, a), ba) / dot(ba, ba), 0, 1)
        d = length(sub(p, add(a, mul(ba, h))))
        if d < best:
            best, bt = d, (i + h) / n
    return bt


# ── 칠하기 (월드 좌표 p, 노멀 n, 털 f → 선형 RGB) ──
def paint_head(p, n, f):
    x, y, z = p
    c = lin(f["main"])
    # 머리 위쪽은 아주 조금 진하게 (입체감)
    c = mix(c, lin(f["shade"]), 0.18 * smoothstep(1.35, 1.85, y))
    # 주둥이·턱 밝은 털
    d = (x / 0.31) ** 2 + ((y - 0.92) / 0.17) ** 2
    c = mix(c, lin(f["light"]), (1 - smoothstep(0.45, 1.0, d)) * smoothstep(0.25, 0.45, z))
    d = (x / 0.36) ** 2 + ((y - 0.74) / 0.13) ** 2
    c = mix(c, lin(f["light"]), (1 - smoothstep(0.4, 1.0, d)) * smoothstep(0.1, 0.3, z))
    if f["stripes"]:
        w = 0.0
        for cx, y0, y1, wid in ((-0.14, 1.44, 1.70, 0.032), (0.0, 1.40, 1.78, 0.038), (0.14, 1.44, 1.70, 0.032)):
            t = (y - y0) / (y1 - y0)
            if 0 <= t <= 1 and z > 0:
                ww = wid * (math.sin(PI * t) ** 0.6)
                w = max(w, 1 - smoothstep(ww * 0.55, ww, abs(x - cx)))
        for yc in (1.04, 1.13):  # 볼 옆 줄무늬
            for sx in (-1, 1):
                t = (sx * x - 0.58) / 0.16
                if 0 <= t <= 1:
                    ww = 0.022 * (math.sin(PI * t) ** 0.6)
                    w = max(w, 1 - smoothstep(ww * 0.5, ww, abs(y - yc - 0.03 * t)))
        c = mix(c, lin(f["shade"]), 0.85 * w)
    # 귀 안쪽
    for sx in (-1, 1):
        l = ear_local(p, sx)
        hy = l[1] / EAR_H
        if 0.0 < hy < 1.0 and l[2] > 0:
            r = EAR_R0 + (EAR_R1 - EAR_R0) * hy
            w = (1 - smoothstep(0.42, 0.66, abs(l[0]) / r)) * smoothstep(0.08, 0.22, hy) * (1 - smoothstep(0.75, 0.92, hy))
            c = mix(c, lin(f["ear"]), w)
    # 볼 터치
    for sx in (-1, 1):
        d = length(sub(p, (sx * 0.43, 0.97, 0.53)))
        c = mix(c, lin(f["blush"]), 0.55 * (1 - smoothstep(0.03, 0.11, d)))
    return c


def paint_body(p, n, f):
    x, y, z = p
    c = lin(f["main"])
    c = mix(c, lin(f["shade"]), 0.15 * smoothstep(0.0, -0.4, z))
    d = (x / 0.27) ** 2 + ((y - 0.38) / 0.30) ** 2
    c = mix(c, lin(f["light"]), (1 - smoothstep(0.45, 1.0, d)) * smoothstep(0.05, 0.25, z))
    if f["socks"] and y < 0.16 and z > 0.12:
        c = mix(c, lin(f["light"]), smoothstep(0.16, 0.10, y))
    if f["stripes"]:
        s = smoothstep(0.45, 0.8, math.sin(y * 19 + 1.2)) * smoothstep(0.08, -0.12, z) * smoothstep(0.16, 0.26, y)
        c = mix(c, lin(f["shade"]), 0.8 * s)
    return c


def paint_paw(p, n, f):
    c = lin(f["main"])
    if f["socks"]:
        c = mix(c, lin(f["light"]), 1 - smoothstep(0.13, 0.22, p[1]))
    return c


def paint_tail(p, n, f):
    c = lin(f["main"])
    if f["stripes"]:
        t = tail_param(p)
        s = smoothstep(0.35, 0.75, math.sin(t * 34)) * smoothstep(0.35, 0.5, t)
        c = mix(c, lin(f["shade"]), 0.85 * s)
        c = mix(c, lin(f["shade"]), smoothstep(0.93, 1.0, t))
    return c


def eye_painter(center, frame, radii):
    ex, ey, ez = frame

    def paint(p, n, f):
        q = sub(p, center)
        lx, ly, lz = dot(q, ex) / radii[0], dot(q, ey) / radii[1], dot(q, ez)
        r = math.sqrt(lx * lx + ly * ly)
        if lz < 0:
            return lin(PUPIL)
        iris = mix(lin(f["iris"]), lin(f["iris_low"]), smoothstep(0.1, -0.75, ly))
        c = mix(lin(PUPIL), iris, smoothstep(0.40, 0.48, r))
        return mix(c, lin(PUPIL), smoothstep(0.84, 0.92, r))
    return paint


def log(what, t0):
    print(f"  {what} … {time.time() - t0:.1f}s", file=sys.stderr)


def build():
    t0 = time.time()
    root = Node("CatRoot", (0.0, 0.0, 0.0))

    body = Node("Body", (0.0, 0.0, 0.0), root)
    body.add("Fur", surface_nets(sd_body, (-0.75, -0.05, -0.6), (0.75, 0.98, 0.62)), paint_body)
    log("몸", t0)

    head = Node("Head", (0.0, 0.72, 0.02), root)
    head.add("Fur", surface_nets(sd_head, (-1.02, 0.52, -0.66), (1.02, 2.12, 0.86)), paint_head)
    log("머리", t0)

    # 눈: 크고 반짝이는 눈 (홍채 + 동공 + 큰·작은 하이라이트)
    eye_pos = {}
    for name, sx in (("LeftEye", 1), ("RightEye", -1)):
        p, n = march_front(sd_head, sx * 0.265, 1.11)
        fr = frame_from_normal(n)
        radii = (0.118, 0.134, 0.06)
        c = sub(p, mul(n, 0.022))
        eye = Node(name, c, head)
        eye.add("Eye", ellipsoid(c, radii, fr, nu=24, nv=36), eye_painter(c, fr, radii))
        ex, ey, ez = fr
        big = add(add(add(p, mul(ex, -0.036)), mul(ey, 0.05)), mul(ez, 0.03))
        small = add(add(add(p, mul(ex, 0.042)), mul(ey, -0.05)), mul(ez, 0.03))
        hl = ellipsoid(big, (0.036, 0.034, 0.012), fr, nu=10, nv=16)
        hl.merge(ellipsoid(small, (0.017, 0.016, 0.008), fr, nu=8, nv=12))
        eye.add("EyeHighlight", hl)
        eye_pos[sx] = (p, n)

    # 코·입
    p, n = march_front(sd_head, 0.0, 0.985)
    nose = Node("Nose", p, head)
    nose.add("Nose", ellipsoid(sub(p, mul(n, 0.006)), (0.05, 0.033, 0.03), frame_from_normal(n),
                               deform=lambda d: (1 + 0.4 * d[1], 1, 1), nu=12, nv=20))
    pts = []
    for x, y in ((-0.074, 0.952), (-0.05, 0.936), (-0.022, 0.94), (0.0, 0.962), (0.022, 0.94), (0.05, 0.936), (0.074, 0.952)):
        q, nq = march_front(sd_head, x, y)
        pts.append(add(q, mul(nq, 0.003)))
    mouth = Node("Mouth", pts[3], head)
    m = tube(catmull(pts, 6), lambda t: 0.0085, 8)
    q1, n1 = march_front(sd_head, 0.0, 0.975)
    m.merge(tube([add(q1, mul(n1, 0.002)), pts[3]], lambda t: 0.0075, 8))
    mouth.add("Mouth", m)

    # 수염: 볼에서 바깥으로 살짝 휘어 나간다
    whiskers = Mesh()
    for sx in (-1, 1):
        for y0, y1 in ((1.0, 1.06), (0.975, 0.985), (0.95, 0.9)):
            s, ns = march_front(sd_head, sx * 0.25, y0)
            e = (sx * 0.82, y1, s[2] - 0.2)
            mid = add(mul(add(s, e), 0.5), (0, 0.025, 0.04))
            whiskers.merge(tube(catmull([sub(s, mul(ns, 0.01)), mid, e], 6), lambda t: 0.0062 * (1 - 0.5 * t), 6))
    head.add("Whisker", whiskers)
    log("얼굴", t0)

    # 앞발
    for name, sx in (("LeftFrontPaw", 1), ("RightFrontPaw", -1)):
        s, _, c = paw_parts(sx)
        paw = Node(name, s, root)
        paw.add("Fur", surface_nets(lambda p, sx=sx: sd_paw(p, sx),
                                    (sx * 0.17 - 0.16, -0.02, 0.05), (sx * 0.17 + 0.16, 0.66, 0.6)), paint_paw)
    log("앞발", t0)

    # 꼬리 (뿌리 절반 Tail + 끝 절반 TailTip)
    tail = Node("Tail", TAIL_PATH[0], root)
    tip = Node("TailTip", TAIL_PATH[TAIL_SPLIT], tail)
    tail.add("Fur", surface_nets(lambda p: sd_tail_range(p, 0, TAIL_SPLIT + 1), (0.0, -0.02, -0.5), (0.82, 0.36, 0.42)), paint_tail)
    tip.add("Fur", surface_nets(lambda p: sd_tail_range(p, TAIL_SPLIT, len(TAIL_PATH) - 1), (0.3, 0.0, 0.1), (0.82, 0.76, 0.64)), paint_tail)
    log("꼬리", t0)

    build_accessories(head, body, eye_pos)

    receipt = Node("Receipt", (0.0, 0.62, 0.74), root)
    tilt = quat(X, -12)
    rc = receipt.pivot
    receipt.add("Paper", box(rc, (0.34, 0.46, 0.012), tilt))
    ink = Mesh()
    ink.hard = True
    for i, w in enumerate((0.18, 0.24, 0.24, 0.17, 0.24, 0.12)):
        y = 0.165 - i * 0.056
        x = -0.12 + w / 2 if i != 5 else 0.12 - w / 2
        ink.merge(box(add(rc, qrot(tilt, (x, y, 0.008))), (w, 0.019 if i != 5 else 0.034, 0.004), tilt))
    receipt.add("Ink", ink)
    return root


def build_accessories(head, body, eye_pos):
    acc = Node("Accessories", head.pivot, head)
    # 니트 모자
    hat = Node("Hat", (0.0, 1.55, 0.02), acc)
    hc = (0.0, 1.52, 0.02)
    cap = ellipsoid(hc, (0.66, 0.47, 0.62), nu=20, nv=40)
    cap.pos = [p if p[1] >= hc[1] else (p[0], hc[1], p[2]) for p in cap.pos]
    hat.add("HatMain", cap)
    band = [(0.675 * math.sin(PI + 2 * PI * i / 56), hc[1] + 0.03, hc[2] + 0.635 * math.cos(PI + 2 * PI * i / 56)) for i in range(57)]
    hat.add("HatBand", tube(band, lambda t: 0.075, 14, closed=True))
    hat.add("HatBand", ellipsoid((0.0, 2.02, 0.02), (0.12, 0.11, 0.12), nu=12, nv=18))
    # 선글라스
    glasses = Node("Sunglasses", head.pivot, acc)
    lens = Mesh()
    centers = []
    for sx in (-1, 1):
        p, n = eye_pos[sx]
        c = add(p, mul(n, 0.07))
        centers.append(c)
        lens.merge(ellipsoid(c, (0.17, 0.14, 0.03), frame_from_normal(n), nu=12, nv=28))
    glasses.add("Lens", lens)
    bridge = [centers[0], add(mul(add(centers[0], centers[1]), 0.5), (0, 0.03, 0.03)), centers[1]]
    glasses.add("Frame", tube(catmull(bridge, 6), lambda t: 0.018, 10))
    # 리본: 왼쪽 귀 밑
    ribbon = Node("Ribbon", (0.5, 1.6, 0.32), acc)
    rc = ribbon.pivot
    bow = Mesh()
    for sx in (-1, 1):
        bow.merge(ellipsoid(add(rc, (sx * 0.095, 0, 0)), (0.095, 0.066, 0.034),
                            deform=lambda d, sx=sx: (1, 1 + 0.45 * d[0] * sx, 1), nu=12, nv=20))
    bow.merge(ellipsoid(rc, (0.04, 0.04, 0.04), nu=10, nv=14))
    ribbon.add("Ribbon", bow)

    acc_body = Node("BodyAccessories", body.pivot, body)
    scarf = Node("Scarf", (0.0, 0.66, 0.0), acc_body)
    ring = [(0.48 * math.sin(PI + 2 * PI * i / 56), 0.66 - 0.03 * math.cos(PI + 2 * PI * i / 56), 0.03 + 0.44 * math.cos(PI + 2 * PI * i / 56))
            for i in range(57)]
    sc = tube(ring, lambda t: 0.085, 16, closed=True)
    sc.merge(tube(catmull([(-0.24, 0.64, 0.44), (-0.31, 0.5, 0.49), (-0.33, 0.33, 0.48)], 6), lambda t: 0.07 - 0.01 * t, 14, flatten=0.5))
    scarf.add("Scarf", sc)


# ───────────────────────── 애니메이션 ─────────────────────────
def animations(root):
    def rot(node, keys): return (node, "rotation", keys)
    def trans(node, keys): return (node, "translation", [(t, add(node.rest_translation, d)) for t, d in keys])
    def scale(node, keys): return (node, "scale", keys)

    body, head = find(root, "Body"), find(root, "Head")
    tail, tip = find(root, "Tail"), find(root, "TailTip")
    le, re = find(root, "LeftEye"), find(root, "RightEye")
    lp, rp = find(root, "LeftFrontPaw"), find(root, "RightFrontPaw")
    receipt = find(root, "Receipt")

    idle_t = [0, 1.5, 3.0]
    tail_t = [0, 0.4, 0.8, 1.2, 1.6]
    wave_t = [0, 0.3, 0.55, 0.8, 1.05, 1.3, 1.6]
    up = qmul(quat(Z, 55), quat(X, -118))  # 앞발을 얼굴 옆으로 (팔이 짧아 어깨째 살짝 들어 올린다)
    lift = (-0.04, 0.15, 0.08)
    # Idle(몸통·머리) / TailWag(꼬리) / Blink(눈) 은 건드리는 노드가 달라 동시에 재생해도 된다.
    # Wave·ShowReceipt 는 머리도 움직이므로 Idle 다음에 적용해 덮어쓴다. 둘은 앞발이 겹치니 하나만 재생한다.
    return {
        "Idle": [
            scale(body, [(t, (1.0, s, 1.0)) for t, s in zip(idle_t, (1.0, 1.02, 1.0))]),
            trans(head, [(t, (0, d, 0)) for t, d in zip(idle_t, (0.0, 0.016, 0.0))]),
            rot(head, [(t, quat(Z, a)) for t, a in zip(idle_t, (-1.5, 1.5, -1.5))]),
        ],
        "TailWag": [
            rot(tail, [(t, quat(Y, a)) for t, a in zip(tail_t, (0, 5, 0, -5, 0))]),
            rot(tip, [(t, qmul(quat(Z, a), quat(X, a * 0.4))) for t, a in zip(tail_t, (0, -16, 0, 16, 0))]),
        ],
        "Blink": [
            scale(e, [(0.0, (1, 1, 1)), (0.07, (1, 0.1, 1)), (0.12, (1, 0.1, 1)), (0.2, (1, 1, 1))]) for e in (le, re)
        ],
        "Wave": [
            rot(rp, list(zip(wave_t, (QI, up, qmul(quat(Z, 12), up), qmul(quat(Z, -10), up), qmul(quat(Z, 12), up), up, QI)))),
            trans(rp, list(zip(wave_t, ((0, 0, 0), lift, lift, lift, lift, lift, (0, 0, 0))))),
            rot(head, [(t, quat(Z, a)) for t, a in zip(wave_t, (0, 4, 5, 4, 5, 4, 0))]),
        ],
        "ShowReceipt": [
            rot(lp, [(0, QI), (0.45, quat(X, -74)), (1.2, quat(X, -74))]),
            rot(rp, [(0, QI), (0.45, quat(X, -74)), (1.2, quat(X, -74))]),
            scale(receipt, [(0, (0.001,) * 3), (0.3, (0.001,) * 3), (0.6, (1.08,) * 3), (0.75, (1.0,) * 3), (1.2, (1.0,) * 3)]),
            trans(receipt, [(0, (0, -0.2, -0.2)), (0.3, (0, -0.2, -0.2)), (0.6, (0, 0, 0)), (1.2, (0, 0, 0))]),
            rot(head, [(0, QI), (0.6, quat(X, 7)), (1.2, quat(X, 5))]),
        ],
    }


# ───────────────────────── GLB 쓰기 ─────────────────────────
class GltfWriter:
    def __init__(self):
        self.bin = bytearray()
        self.views, self.accessors = [], []

    def _view(self, data, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        v = {"buffer": 0, "byteOffset": len(self.bin), "byteLength": len(data)}
        if target:
            v["target"] = target
        self.bin.extend(data)
        self.views.append(v)
        return len(self.views) - 1

    def floats(self, rows, typ, target=None, minmax=False):
        n = {"SCALAR": 1, "VEC3": 3, "VEC4": 4}[typ]
        flat = [x for r in rows for x in (r if n > 1 else (r,))]
        acc = {"bufferView": self._view(struct.pack(f"<{len(flat)}f", *flat), target),
               "componentType": 5126, "count": len(rows), "type": typ}
        if minmax:
            f32 = lambda x: struct.unpack("<f", struct.pack("<f", x))[0]
            cols = [[(r if n == 1 else r[k]) for r in rows] for k in range(n)]
            acc["min"] = [f32(min(c)) for c in cols]
            acc["max"] = [f32(max(c)) for c in cols]
        self.accessors.append(acc)
        return len(self.accessors) - 1

    def colors(self, rows):
        """COLOR_0: 정규화된 unsigned byte RGBA (선형 값)"""
        flat = []
        for r in rows:
            flat += [int(round(clamp(c, 0, 1) * 255)) for c in r] + [255]
        self.accessors.append({"bufferView": self._view(struct.pack(f"<{len(flat)}B", *flat), 34962),
                               "componentType": 5121, "normalized": True, "count": len(rows), "type": "VEC4"})
        return len(self.accessors) - 1

    def indices(self, tris, vcount):
        flat = [i for t in tris for i in t]
        data, ct = (struct.pack(f"<{len(flat)}H", *flat), 5123) if vcount < 65536 else (struct.pack(f"<{len(flat)}I", *flat), 5125)
        self.accessors.append({"bufferView": self._view(data, 34963), "componentType": ct, "count": len(flat), "type": "SCALAR"})
        return len(self.accessors) - 1


def write_glb(root, anims, fur, path):
    w = GltfWriter()
    mats = materials_for(fur)
    mat_names = list(mats)
    materials, uses_clearcoat = [], False
    for name in mat_names:
        m = mats[name]
        mat = {"name": name, "pbrMetallicRoughness": {
            "baseColorFactor": list(lin(m["color"])) + [1.0], "metallicFactor": 0.0, "roughnessFactor": m["rough"]}}
        if m.get("double"):
            mat["doubleSided"] = True
        if m.get("clearcoat"):
            uses_clearcoat = True
            mat["extensions"] = {"KHR_materials_clearcoat": {
                "clearcoatFactor": m["clearcoat"], "clearcoatRoughnessFactor": m.get("clearcoat_rough", 0.1)}}
        materials.append(mat)

    nodes, meshes, index_of = [], [], {}

    def emit(node):
        i = len(nodes)
        index_of[node.name] = i
        entry = {"name": node.name}
        t = node.rest_translation
        if any(abs(c) > 1e-9 for c in t):
            entry["translation"] = list(t)
        nodes.append(entry)
        if node.prims:
            prims = []
            for mat, mesh, paint in node.prims:
                attrs = {"POSITION": w.floats([sub(p, node.pivot) for p in mesh.pos], "VEC3", 34962, minmax=True),
                         "NORMAL": w.floats(mesh.nrm, "VEC3", 34962)}
                if paint:
                    attrs["COLOR_0"] = w.colors([paint(p, n, FURS[fur]) for p, n in zip(mesh.pos, mesh.nrm)])
                prims.append({"attributes": attrs, "indices": w.indices(mesh.idx, len(mesh.pos)),
                              "material": mat_names.index(mat)})
            meshes.append({"name": node.name, "primitives": prims})
            entry["mesh"] = len(meshes) - 1
        kids = [emit(c) for c in node.children]
        if kids:
            entry["children"] = kids
        return i

    emit(root)

    gl_anims = []
    for name, channels in anims.items():
        samplers, chans = [], []
        for node, path_, keys in channels:
            inp = w.floats([k[0] for k in keys], "SCALAR", minmax=True)
            out = w.floats([k[1] for k in keys], "VEC4" if path_ == "rotation" else "VEC3")
            samplers.append({"input": inp, "output": out, "interpolation": "LINEAR"})
            chans.append({"sampler": len(samplers) - 1, "target": {"node": index_of[node.name], "path": path_}})
        gl_anims.append({"name": name, "samplers": samplers, "channels": chans})

    while len(w.bin) % 4:
        w.bin.append(0)
    gltf = {
        "asset": {"version": "2.0", "generator": f"cat-meter tools/make_cat_glb.py {MODEL_VERSION}"},
        "scene": 0, "scenes": [{"name": "Cat", "nodes": [0]}],
        "nodes": nodes, "meshes": meshes, "materials": materials, "animations": gl_anims,
        "accessors": w.accessors, "bufferViews": w.views, "buffers": [{"byteLength": len(w.bin)}],
    }
    if uses_clearcoat:
        gltf["extensionsUsed"] = ["KHR_materials_clearcoat"]
    js = json.dumps(gltf, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    js += b" " * ((4 - len(js) % 4) % 4)
    out = struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(w.bin))
    out += struct.pack("<II", len(js), 0x4E4F534A) + js
    out += struct.pack("<II", len(w.bin), 0x004E4942) + bytes(w.bin)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(out)
    tri = sum(len(m.idx) for n in walk(root) for _, m, _ in n.prims)
    print(f"{path.relative_to(ROOT)}  {len(out) / 1024:.0f} KB, 노드 {len(nodes)}, 삼각형 {tri}")


if __name__ == "__main__":
    cat = build()
    anims = animations(cat)
    for fur in FURS:
        write_glb(cat, anims, fur, OUT_DIR / f"cat_{fur}.glb")
    old = OUT_DIR / "cat.glb"
    if old.exists():
        old.unlink()  # v0.1 의 단일 모델 (털 색을 앱에서 바꾸던 방식)
