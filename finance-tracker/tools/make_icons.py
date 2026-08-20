#!/usr/bin/env python3
"""Regenerate the PWA icons. No third-party dependencies.

    python3 tools/make_icons.py

Draws a rupee mark on the app's accent colour, supersampled 3x for clean edges.
"""
import os
import struct
import zlib

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'icons')
BG_TOP = (79, 70, 229)      # --accent
BG_BOTTOM = (56, 108, 217)
FG = (255, 255, 255)
SS = 3                       # supersampling factor


def rect(x0, y0, x1, y1):
    return lambda x, y: x0 <= x <= x1 and y0 <= y <= y1


def segment(ax, ay, bx, by, half):
    """Thick line segment as a distance test."""
    dx, dy = bx - ax, by - ay
    length2 = dx * dx + dy * dy

    def hit(x, y):
        t = 0.0 if length2 == 0 else ((x - ax) * dx + (y - ay) * dy) / length2
        t = max(0.0, min(1.0, t))
        px, py = ax + t * dx, ay + t * dy
        return (x - px) ** 2 + (y - py) ** 2 <= half * half
    return hit


def rupee_shapes(scale, offset):
    """The ₹ mark, expressed in unit coordinates then scaled into the canvas."""
    raw = [
        rect(0.28, 0.235, 0.72, 0.295),          # top bar
        rect(0.28, 0.355, 0.72, 0.415),          # second bar
        rect(0.315, 0.295, 0.375, 0.60),         # left stem
        rect(0.60, 0.415, 0.66, 0.53),           # bowl right edge
        rect(0.315, 0.53, 0.66, 0.59),           # bowl bottom
        segment(0.42, 0.585, 0.70, 0.80, 0.032), # diagonal leg
    ]
    def place(fn):
        return lambda x, y: fn((x - offset) / scale, (y - offset) / scale)
    return [place(f) for f in raw]


def rounded(radius):
    def hit(x, y):
        cx = min(max(x, radius), 1 - radius)
        cy = min(max(y, radius), 1 - radius)
        dx, dy = x - cx, y - cy
        return dx * dx + dy * dy <= radius * radius
    return hit


def render(size, corner_radius, glyph_scale):
    """Returns rows of RGBA tuples."""
    n = size * SS
    inside = rounded(corner_radius) if corner_radius else (lambda x, y: True)
    offset = (1.0 - glyph_scale) / 2.0
    shapes = rupee_shapes(glyph_scale, offset)

    rows = []
    for py in range(size):
        row = []
        for px in range(size):
            bg_hits = fg_hits = 0
            for sy in range(SS):
                for sx in range(SS):
                    x = (px * SS + sx + 0.5) / n
                    y = (py * SS + sy + 0.5) / n
                    if not inside(x, y):
                        continue
                    bg_hits += 1
                    if any(s(x, y) for s in shapes):
                        fg_hits += 1
            total = SS * SS
            alpha = bg_hits / total
            if alpha == 0:
                row.append((0, 0, 0, 0))
                continue
            t = py / max(1, size - 1)
            base = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3))
            k = (fg_hits / bg_hits) if bg_hits else 0
            colour = tuple(int(base[i] + (FG[i] - base[i]) * k) for i in range(3))
            row.append(colour + (int(round(alpha * 255)),))
        rows.append(row)
    return rows


def write_png(path, rows):
    height = len(rows)
    width = len(rows[0])
    raw = b''.join(b'\x00' + bytes(v for px in row for v in px) for row in rows)

    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data
                + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff))

    header = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    blob = (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', header)
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))
    with open(path, 'wb') as fh:
        fh.write(blob)
    return len(blob)


def main():
    os.makedirs(OUT, exist_ok=True)
    jobs = [
        ('icon-192.png', 192, 0.22, 1.0),
        ('icon-512.png', 512, 0.22, 1.0),
        ('icon-maskable-512.png', 512, 0.0, 0.68),  # full bleed, glyph inside the safe zone
    ]
    for name, size, radius, glyph in jobs:
        path = os.path.join(OUT, name)
        written = write_png(path, render(size, radius, glyph))
        print('%-24s %4dpx  %6.1f KB' % (name, size, written / 1024.0))


if __name__ == '__main__':
    main()
