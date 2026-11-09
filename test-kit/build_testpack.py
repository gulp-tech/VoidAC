#!/usr/bin/env python3
"""Сборка VoidAC-Test-XRay.mcpack — ресурс-пак для проверки ЛОКАЛЬНЫХ визуальных
векторов: X-Ray и видимость руды. НЕ содержит и не может содержать боевых читов."""
import json, os, shutil, struct, uuid, zipfile, zlib

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "build")
OUT = os.path.join(ROOT, "VoidAC-Test-XRay.mcpack")

NS = uuid.UUID("6f1a2b3c-4d5e-4f60-8a71-9b2c3d4e5f60")
def uid(name): return str(uuid.uuid5(NS, name))

def png(w, h, rgba):
    """Минимальный корректный PNG: RGBA, без палитры."""
    raw = b"".join(b"\x00" + bytes(rgba) * w for _ in range(h))
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))

# Блоки-заполнители: их текстуры становятся полностью прозрачными.
# Руды НЕ трогаем — именно на их фоне видно, что пак работает.
FILLER = ["stone", "deepslate", "dirt", "gravel", "andesite", "diorite",
          "granite", "tuff", "calcite", "netherrack", "cobblestone"]

def build():
    if os.path.isdir(SRC): shutil.rmtree(SRC)
    os.makedirs(os.path.join(SRC, "textures", "blocks"))

    # 1) Манифест. format_version 2 — актуальный для ресурс-паков.
    manifest = {
        "format_version": 2,
        "header": {
            "name": "VoidAC Test — X-Ray",
            "description": "Локальная проверка X-Ray. Только визуал, никаких чит-функций.",
            "uuid": uid("header"),
            "version": [1, 0, 0],
            "min_engine_version": [1, 21, 0],
        },
        "modules": [
            {"type": "resources", "uuid": uid("module"), "version": [1, 0, 0]}
        ],
    }
    json.dump(manifest, open(os.path.join(SRC, "manifest.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)

    # 2) Прозрачные текстуры поверх ванильных путей — переопределение по пути.
    transparent = png(16, 16, (0, 0, 0, 0))
    for name in FILLER:
        open(os.path.join(SRC, "textures", "blocks", name + ".png"), "wb").write(transparent)

    # 3) blocks.json: alpha_test, иначе непрозрачный метод отрисовки покажет
    #    прозрачные пиксели чёрным вместо того, чтобы убрать блок.
    blocks = {"format_version": [1, 1, 0]}
    for name in FILLER:
        blocks[name] = {"sound": "stone", "textures": name, "render_method": "alpha_test"}
    json.dump(blocks, open(os.path.join(SRC, "blocks.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)

    # 4) Иконка пака.
    open(os.path.join(SRC, "pack_icon.png"), "wb").write(png(64, 64, (24, 26, 32, 255)))

    # 5) Упаковка: manifest.json строго в корне архива.
    if os.path.exists(OUT): os.remove(OUT)
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        for base, _, files in os.walk(SRC):
            for f in sorted(files):
                full = os.path.join(base, f)
                z.write(full, os.path.relpath(full, SRC))
    return OUT

if __name__ == "__main__":
    p = build()
    print("собран:", p, os.path.getsize(p), "байт")
