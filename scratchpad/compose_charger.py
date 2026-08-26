"""Junta o acumulador fechado em cima do handcart, num unico GLB.

    python scratchpad/compose_charger.py

Escreve `frontend/models/tek26e_charger.glb`, que e o modelo da pagina de
Carregamento. Os dois ficheiros de origem ficam intactos e continuam a servir
os outros ecras.

Porque e que sao juntos aqui e nao no browser: o <model-viewer> mostra UM
modelo por elemento. Duas instancias sobrepostas nao partilham camara nem
iluminacao, e qualquer arrasto desalinhava-as. Compor uma vez, em disco, deixa
o frontend a fazer o que ja faz para todos os outros modelos.

O encaixe sai das caixas envolventes, nao de numeros escritos a mao: o
acumulador e assente pela base no topo do chassis e centrado na plataforma. Se
algum dos dois modelos for reexportado com outra origem, correr isto outra vez
chega -- os valores sao recalculados.
"""

from __future__ import annotations

import json
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CART = ROOT / "frontend" / "models" / "handcart_t26.glb"
PACK = ROOT / "frontend" / "models" / "tek26e_closed.glb"
OUT = ROOT / "frontend" / "models" / "tek26e_charger.glb"

JSON_CHUNK = 0x4E4F534A
BIN_CHUNK = 0x004E4942


# ── leitura e escrita de GLB ───────────────────────────────────────────────

def read_glb(path: Path) -> tuple[dict, bytes]:
    data = path.read_bytes()
    magic, _ver, length = struct.unpack_from("<III", data, 0)
    if magic != 0x46546C67:
        raise SystemExit(f"{path.name} nao e um GLB")
    off, gltf, blob = 12, None, b""
    while off < length:
        clen, ctype = struct.unpack_from("<II", data, off)
        body = data[off + 8: off + 8 + clen]
        if ctype == JSON_CHUNK:
            gltf = json.loads(body.decode("utf-8"))
        elif ctype == BIN_CHUNK:
            blob = body
        off += 8 + clen + ((4 - clen % 4) % 4 if clen % 4 else 0)
    if gltf is None:
        raise SystemExit(f"{path.name} nao tem chunk JSON")
    return gltf, blob


def write_glb(path: Path, gltf: dict, blob: bytes) -> None:
    js = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    js += b" " * ((4 - len(js) % 4) % 4)          # chunks alinhados a 4 bytes
    blob += b"\x00" * ((4 - len(blob) % 4) % 4)
    total = 12 + 8 + len(js) + (8 + len(blob) if blob else 0)
    out = bytearray()
    out += struct.pack("<III", 0x46546C67, 2, total)
    out += struct.pack("<II", len(js), JSON_CHUNK) + js
    if blob:
        out += struct.pack("<II", len(blob), BIN_CHUNK) + blob
    path.write_bytes(bytes(out))


# ── geometria ──────────────────────────────────────────────────────────────

def quat_mat(q):
    x, y, z, w = q
    return [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]]


# Um quarto de volta em torno do eixo vertical, no sentido anti-horario visto
# de cima. Sem isto o acumulador fica atravessado: o lado comprido (0,639 m)
# cai sobre a largura do tabuleiro (0,540 m) e transborda dos dois lados. Com a
# rotacao passa a assentar 0,542 x 0,639 sobre 0,540 x 0,765, que e como esta
# montado na realidade.
SIN45 = 0.7071067811865476
PACK_ROTATION = [0.0, SIN45, 0.0, SIN45]          # (x, y, z, w)

# Prefixo posto nos materiais que vem do carro de carga. O frontend usa-o para
# lhes dar acabamento proprio sem apanhar as pecas do acumulador.
CART_TAG = "handcart:"


COMPONENT = {5120: ("b", 1), 5121: ("B", 1), 5122: ("h", 2),
             5123: ("H", 2), 5125: ("I", 4), 5126: ("f", 4)}


def read_positions(gltf: dict, blob: bytes, acc_i: int) -> list[tuple]:
    """Vertices de um acessor POSITION, ja desquantizados."""
    a = gltf["accessors"][acc_i]
    bv = gltf["bufferViews"][a["bufferView"]]
    fmt, size = COMPONENT[a["componentType"]]
    stride = bv.get("byteStride") or size * 3
    base = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    norm = a.get("normalized") and a["componentType"] == 5122
    out = []
    for i in range(a["count"]):
        v = struct.unpack_from("<" + fmt * 3, blob, base + i * stride)
        out.append(tuple(c / 32767.0 for c in v) if norm else v)
    return out


def deck_height(gltf: dict, blob: bytes, node_name: str) -> float:
    """Altura da plataforma onde o acumulador assenta.

    NAO e o topo da caixa envolvente. O chassis tem uma aba estreita no bordo
    de tras, 30 cm acima da plataforma; usar o maximo da caixa punha o
    acumulador a flutuar exatamente essa distancia.

    A plataforma e reconhecida por ser a superficie horizontal mais alta que
    ainda cobre a maior parte da pegada do carro -- uma aba de 3 cm de fundo
    nao cobre, uma plataforma de 76 cm cobre.
    """
    idx = next(i for i, n in enumerate(gltf["nodes"]) if n.get("name") == node_name)
    n = gltf["nodes"][idx]
    t = n.get("translation", [0, 0, 0])
    q = n.get("rotation", [0, 0, 0, 1])
    s = n.get("scale", [1, 1, 1])
    R = quat_mat(q)
    M = [[R[r][c] * s[c] for c in range(3)] for r in range(3)]

    pts = []
    for pr in gltf["meshes"][n["mesh"]]["primitives"]:
        for v in read_positions(gltf, blob, pr["attributes"]["POSITION"]):
            pts.append([sum(M[r][c] * v[c] for c in range(3)) + t[r] for r in range(3)])

    span_x = max(p[0] for p in pts) - min(p[0] for p in pts)
    span_z = max(p[2] for p in pts) - min(p[2] for p in pts)

    slabs: dict[float, list] = {}
    for p in pts:
        slabs.setdefault(round(p[1], 2), []).append(p)

    best = None
    for y, group in slabs.items():
        gx = max(p[0] for p in group) - min(p[0] for p in group)
        gz = max(p[2] for p in group) - min(p[2] for p in group)
        if gx * gz >= 0.6 * span_x * span_z and (best is None or y > best):
            best = y
    if best is None:
        raise SystemExit(f"nao encontrei plataforma em {node_name}")
    return best


def deck_extent(gltf: dict, blob: bytes, node_name: str, y: float):
    """Contorno da plataforma a essa altura, para centrar o acumulador nela."""
    idx = next(i for i, n in enumerate(gltf["nodes"]) if n.get("name") == node_name)
    n = gltf["nodes"][idx]
    t = n.get("translation", [0, 0, 0])
    q = n.get("rotation", [0, 0, 0, 1])
    s = n.get("scale", [1, 1, 1])
    R = quat_mat(q)
    M = [[R[r][c] * s[c] for c in range(3)] for r in range(3)]
    xs, zs = [], []
    for pr in gltf["meshes"][n["mesh"]]["primitives"]:
        for v in read_positions(gltf, blob, pr["attributes"]["POSITION"]):
            w = [sum(M[r][c] * v[c] for c in range(3)) + t[r] for r in range(3)]
            if abs(w[1] - y) < 0.01:
                xs.append(w[0])
                zs.append(w[2])
    return (min(xs), max(xs)), (min(zs), max(zs))


def world_bbox(gltf: dict, skip_names: set[str] = frozenset()):
    """Caixa envolvente da cena, ja com as transformacoes dos nos aplicadas.

    Os acessores quantizados guardam min/max em i16 normalizado; sem os
    converter, a caixa sai em dezenas de milhares em vez de metros.
    """
    lo = [float("inf")] * 3
    hi = [float("-inf")] * 3

    def walk(idx, PM, PT):
        n = gltf["nodes"][idx]
        if n.get("name") in skip_names:
            return
        t = n.get("translation", [0, 0, 0])
        q = n.get("rotation", [0, 0, 0, 1])
        s = n.get("scale", [1, 1, 1])
        R = quat_mat(q)
        L = [[R[r][c] * s[c] for c in range(3)] for r in range(3)]
        M = [[sum(PM[r][k] * L[k][c] for k in range(3)) for c in range(3)] for r in range(3)]
        T = [sum(PM[r][k] * t[k] for k in range(3)) + PT[r] for r in range(3)]
        if "mesh" in n:
            for pr in gltf["meshes"][n["mesh"]]["primitives"]:
                a = gltf["accessors"][pr["attributes"]["POSITION"]]
                amin, amax = a.get("min"), a.get("max")
                if not amin:
                    continue
                if a.get("normalized") and a["componentType"] == 5122:
                    amin = [v / 32767.0 for v in amin]
                    amax = [v / 32767.0 for v in amax]
                for dx in (amin[0], amax[0]):
                    for dy in (amin[1], amax[1]):
                        for dz in (amin[2], amax[2]):
                            p = [dx, dy, dz]
                            w = [sum(M[r][c] * p[c] for c in range(3)) + T[r] for r in range(3)]
                            for k in range(3):
                                lo[k] = min(lo[k], w[k])
                                hi[k] = max(hi[k], w[k])
        for c in n.get("children", []):
            walk(c, M, T)

    I = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
    for r in gltf["scenes"][gltf.get("scene", 0)]["nodes"]:
        walk(r, I, [0, 0, 0])
    return lo, hi


# ── fusao ──────────────────────────────────────────────────────────────────

# Listas do glTF que sao referenciadas por indice. Juntar dois ficheiros e
# concatenar cada uma e depois corrigir TODOS os indices do segundo.
ARRAYS = ("accessors", "bufferViews", "meshes", "materials", "nodes",
          "samplers", "textures", "images", "skins", "animations", "cameras")


def merge(base: dict, base_bin: bytes, add: dict, add_bin: bytes,
          translation: list[float], wrapper_name: str,
          rotation: list[float] | None = None) -> tuple[dict, bytes]:
    """Traz `add` para dentro de `base`, deslocado por `translation`."""
    offsets = {k: len(base.get(k, [])) for k in ARRAYS}
    bin_offset = len(base_bin)
    # Alinhar antes de concatenar: os bufferViews do segundo ficheiro sao
    # deslocados por este valor, e um offset desalinhado parte os acessores.
    pad = (4 - bin_offset % 4) % 4
    base_bin = base_bin + b"\x00" * pad
    bin_offset += pad

    new = json.loads(json.dumps(add))  # copia, para nao mexer no original

    for bv in new.get("bufferViews", []):
        bv["byteOffset"] = bv.get("byteOffset", 0) + bin_offset
        bv["buffer"] = 0

    for a in new.get("accessors", []):
        if "bufferView" in a:
            a["bufferView"] += offsets["bufferViews"]
        if "sparse" in a:
            for key in ("indices", "values"):
                if key in a["sparse"]:
                    a["sparse"][key]["bufferView"] += offsets["bufferViews"]

    for m in new.get("meshes", []):
        for pr in m["primitives"]:
            pr["attributes"] = {k: v + offsets["accessors"] for k, v in pr["attributes"].items()}
            if "indices" in pr:
                pr["indices"] += offsets["accessors"]
            if "material" in pr:
                pr["material"] += offsets["materials"]
            for tgt in pr.get("targets", []):
                for k in tgt:
                    tgt[k] += offsets["accessors"]

    for t in new.get("textures", []):
        if "source" in t:
            t["source"] += offsets["images"]
        if "sampler" in t:
            t["sampler"] += offsets["samplers"]

    def bump_texrefs(obj):
        if isinstance(obj, dict):
            if "index" in obj and "texCoord" in obj:
                obj["index"] += offsets["textures"]
            for v in obj.values():
                bump_texrefs(v)
        elif isinstance(obj, list):
            for v in obj:
                bump_texrefs(v)

    for mat in new.get("materials", []):
        bump_texrefs(mat)

    for n in new.get("nodes", []):
        if "mesh" in n:
            n["mesh"] += offsets["meshes"]
        if "camera" in n:
            n["camera"] += offsets["cameras"]
        if "skin" in n:
            n["skin"] += offsets["skins"]
        if "children" in n:
            n["children"] = [c + offsets["nodes"] for c in n["children"]]

    for s in new.get("skins", []):
        if "inverseBindMatrices" in s:
            s["inverseBindMatrices"] += offsets["accessors"]
        s["joints"] = [j + offsets["nodes"] for j in s.get("joints", [])]
        if "skeleton" in s:
            s["skeleton"] += offsets["nodes"]

    for anim in new.get("animations", []):
        for smp in anim.get("samplers", []):
            smp["input"] += offsets["accessors"]
            smp["output"] += offsets["accessors"]
        for ch in anim.get("channels", []):
            if "node" in ch.get("target", {}):
                ch["target"]["node"] += offsets["nodes"]

    for key in ARRAYS:
        if new.get(key):
            base.setdefault(key, []).extend(new[key])

    # As raizes do modelo importado passam a pender de um no que so faz o
    # deslocamento. Assim o modelo original nao e alterado peca a peca.
    roots = [r + offsets["nodes"] for r in new["scenes"][new.get("scene", 0)]["nodes"]]
    wrapper = {"name": wrapper_name, "translation": translation, "children": roots}
    if rotation:
        wrapper["rotation"] = rotation
    base["nodes"].append(wrapper)
    base["scenes"][base.get("scene", 0)]["nodes"].append(len(base["nodes"]) - 1)

    for key in ("extensionsUsed", "extensionsRequired"):
        merged = list(dict.fromkeys(base.get(key, []) + add.get(key, [])))
        if merged:
            base[key] = merged

    return base, base_bin + add_bin


def drop_cameras(gltf: dict) -> int:
    """Tira as camaras exportadas do CAD.

    O <model-viewer> usa a sua propria camara em orbita e ignora estas, mas
    ficam a ocupar espaco e a aparecer na arvore de nos como se fossem peca.
    """
    cams = [i for i, n in enumerate(gltf["nodes"]) if "camera" in n]
    if not cams:
        return 0
    scene = gltf["scenes"][gltf.get("scene", 0)]
    scene["nodes"] = [i for i in scene["nodes"] if i not in cams]
    for i in cams:
        gltf["nodes"][i].pop("camera", None)
    gltf.pop("cameras", None)
    return len(cams)


def main() -> int:
    for f in (CART, PACK):
        if not f.is_file():
            print(f"falta {f}")
            return 1

    cart, cart_bin = read_glb(CART)
    pack, pack_bin = read_glb(PACK)

    # A camara do CAD vem no handcart e nao serve para nada aqui.
    drop_cameras(cart)

    # Marcar de onde vem cada material. Os dois modelos usam o mesmo cinzento
    # 228,228,228 -- o chassis do carro e o aluminio do acumulador -- e sem
    # isto o viewer nao os pode tratar de maneira diferente. Um prefixo no nome
    # sobrevive a reexportacao; um indice nao.
    for m in cart.get("materials", []):
        nome = m.get("name", "color")
        if not nome.startswith(CART_TAG):
            m["name"] = CART_TAG + nome

    cart_lo, cart_hi = world_bbox(cart)
    pack_lo, pack_hi = world_bbox(pack)

    # A plataforma do carro, medida na geometria e nao pelo topo da caixa.
    deck_y = deck_height(cart, cart_bin, "handcart-1")
    (dx0, dx1), (dz0, dz1) = deck_extent(cart, cart_bin, "handcart-1", deck_y)

    # A caixa do acumulador DEPOIS de rodado. Em glTF um no aplica T * R * S,
    # ou seja roda em torno da sua propria origem e so depois desloca -- medir
    # a caixa antes da rotacao daria um centro errado.
    R = quat_mat(PACK_ROTATION)
    corners = [[x, y, z] for x in (pack_lo[0], pack_hi[0])
               for y in (pack_lo[1], pack_hi[1])
               for z in (pack_lo[2], pack_hi[2])]
    rot = [[sum(R[r][c] * p[c] for c in range(3)) for r in range(3)] for p in corners]
    rlo = [min(p[k] for p in rot) for k in range(3)]
    rhi = [max(p[k] for p in rot) for k in range(3)]

    # Assente pela base, centrado na plataforma nos dois eixos horizontais.
    translation = [
        (dx0 + dx1) / 2 - (rlo[0] + rhi[0]) / 2,
        deck_y - rlo[1],
        (dz0 + dz1) / 2 - (rlo[2] + rhi[2]) / 2,
    ]

    print(f"handcart    {cart_hi[0]-cart_lo[0]:.3f} x {cart_hi[1]-cart_lo[1]:.3f} x {cart_hi[2]-cart_lo[2]:.3f} m")
    print(f"acumulador  {pack_hi[0]-pack_lo[0]:.3f} x {pack_hi[1]-pack_lo[1]:.3f} x {pack_hi[2]-pack_lo[2]:.3f} m"
          f"   (rodado 90 graus: {rhi[0]-rlo[0]:.3f} x {rhi[1]-rlo[1]:.3f} x {rhi[2]-rlo[2]:.3f})")
    print(f"plataforma  y = {deck_y:.3f} m   x[{dx0:+.3f},{dx1:+.3f}]  z[{dz0:+.3f},{dz1:+.3f}]")
    print(f"deslocacao  ({translation[0]:+.4f}, {translation[1]:+.4f}, {translation[2]:+.4f})")

    folga_x = (dx1 - dx0) - (rhi[0] - rlo[0])
    folga_z = (dz1 - dz0) - (rhi[2] - rlo[2])
    for eixo, folga in (("x", folga_x), ("z", folga_z)):
        estado = "de folga" if folga >= 0 else "a transbordar"
        print(f"  {eixo}: {abs(folga)*1000:.0f} mm {estado}")

    merged, merged_bin = merge(cart, cart_bin, pack, pack_bin, translation,
                               "acumulador", rotation=PACK_ROTATION)
    merged["buffers"] = [{"byteLength": len(merged_bin)}]

    write_glb(OUT, merged, merged_bin)

    check, _ = read_glb(OUT)
    lo, hi = world_bbox(check)
    tris = sum(check["accessors"][pr["indices"]]["count"] // 3
               for m in check["meshes"] for pr in m["primitives"] if "indices" in pr)
    print(f"\n{OUT.name}  {OUT.stat().st_size/1048576:.1f} MB")
    print(f"  bbox   {[round(v,3) for v in lo]} -> {[round(v,3) for v in hi]}")
    print(f"  altura {hi[1]-lo[1]:.3f} m   nos {len(check['nodes'])}   triangulos {tris:,}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
