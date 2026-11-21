"""Draws the 48x48 ability-bar icon (three connected nodes) as a PNG.

Pure standard library, no PIL. Run from the repo root:
    python tools/make_icon.py
Writes mod/graphics/icons/abilities/multisector_multiplayer.png
"""
import math
import os
import struct
import sys
import zlib

SIZE = 48
SS = 4  # supersampling factor for anti-aliasing

NODES = [(24, 11), (11, 35), (37, 35)]
NODE_R = 6.0
LINE_W = 2.6
RING_R = 19.5
RING_W = 1.6


def seg_dist(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def coverage(x, y):
    """1 if the point is inside the shape, else 0. Coordinates in icon pixels."""
    for nx, ny in NODES:
        if math.hypot(x - nx, y - ny) <= NODE_R:
            return 1.0
    for i in range(3):
        a, b = NODES[i], NODES[(i + 1) % 3]
        if seg_dist(x, y, a[0], a[1], b[0], b[1]) <= LINE_W / 2:
            return 1.0
    if abs(math.hypot(x - 24, y - 24) - RING_R) <= RING_W / 2:
        return 0.55  # faint outer ring
    return 0.0


def render():
    rows = []
    for py in range(SIZE):
        row = bytearray([0])  # PNG filter type 0
        for px in range(SIZE):
            acc = 0.0
            for sy in range(SS):
                for sx in range(SS):
                    acc += coverage(px + (sx + 0.5) / SS, py + (sy + 0.5) / SS)
            a = int(round(255 * acc / (SS * SS)))
            row += bytes((255, 255, 255, a))
        rows.append(bytes(row))
    return b"".join(rows)


def png(raw):
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)  # 8-bit RGBA
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root, "mod", "graphics", "icons", "abilities", "multisector_multiplayer.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "wb") as f:
        f.write(png(render()))
    print("wrote", out)


if __name__ == "__main__":
    sys.exit(main())
