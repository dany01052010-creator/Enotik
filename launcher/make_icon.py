#!/usr/bin/env python3
"""Рисует иконку-енота Enotik Launcher и кладёт в launcher/extra/icon/mipmap-*."""
import os
from PIL import Image, ImageDraw

S = 2048  # рабочий холст (потом уменьшается)
BG = (43, 47, 58, 255)

def raccoon():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c = S / 2
    def el(cx, cy, rx, ry, fill, rot=None):
        d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=fill)
    gray, dgray, light, dark, white = (150, 156, 166), (96, 101, 110), (200, 204, 210), (30, 33, 40), (246, 246, 246)
    # уши
    for sx in (-1, 1):
        el(c + sx * 430, c - 420, 190, 190, dgray)
        el(c + sx * 430, c - 400, 110, 110, (214, 170, 160))
    # голова
    el(c, c + 40, 640, 540, gray)
    # светлые щёки и лоб
    el(c, c - 250, 130, 200, light)
    # маска вокруг глаз
    for sx in (-1, 1):
        pts = []
        import math
        for a in range(0, 360, 6):
            t = math.radians(a)
            x = 250 * math.cos(t); y = 150 * math.sin(t)
            r = math.radians(sx * 22)
            pts.append((c + sx * 300 + x * math.cos(r) - y * math.sin(r), c - 30 + x * math.sin(r) + y * math.cos(r)))
        d.polygon(pts, fill=dark)
    # морда
    el(c, c + 300, 330, 260, white)
    # глаза
    for sx in (-1, 1):
        el(c + sx * 290, c - 40, 78, 86, white)
        el(c + sx * 300, c - 30, 44, 54, dark)
        el(c + sx * 312, c - 50, 15, 15, white)
    # нос
    el(c, c + 190, 120, 82, dark)
    el(c - 30, c + 168, 34, 18, (110, 114, 124))
    # рот
    d.line([(c, c + 270), (c, c + 350)], fill=dark, width=18)
    d.arc([c - 150, c + 280, c, c + 420], 10, 160, fill=dark, width=18)
    d.arc([c, c + 280, c + 150, c + 420], 20, 170, fill=dark, width=18)
    return im

fg_big = raccoon()
dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "extra", "icon")
for name, k in dens.items():
    d = os.path.join(out, "mipmap-" + name)
    os.makedirs(d, exist_ok=True)
    fsz = int(108 * k)
    # адаптивная иконка: рисунок занимает центральные ~62%
    fg = Image.new("RGBA", (fsz, fsz), (0, 0, 0, 0))
    inner = fg_big.resize((int(fsz * 0.74), int(fsz * 0.74)), Image.LANCZOS)
    fg.paste(inner, ((fsz - inner.width) // 2, (fsz - inner.height) // 2), inner)
    fg.save(os.path.join(d, "ic_launcher_foreground.webp"), "WEBP", lossless=True)
    # обычная иконка (старые Android): фон + рисунок, квадрат со скруглением и круг
    lsz = int(48 * k)
    for fname, circle in (("ic_launcher.webp", False), ("ic_launcher_round.webp", True)):
        big = Image.new("RGBA", (lsz * 4, lsz * 4), (0, 0, 0, 0))
        m = Image.new("L", big.size, 0)
        md = ImageDraw.Draw(m)
        if circle:
            md.ellipse([0, 0, big.width - 1, big.height - 1], fill=255)
        else:
            md.rounded_rectangle([0, 0, big.width - 1, big.height - 1], radius=big.width // 5, fill=255)
        bg = Image.new("RGBA", big.size, BG)
        r = fg_big.resize((int(big.width * 0.9), int(big.height * 0.9)), Image.LANCZOS)
        bg.paste(r, ((big.width - r.width) // 2, (big.height - r.height) // 2), r)
        big.paste(bg, (0, 0), m)
        big.resize((lsz, lsz), Image.LANCZOS).save(os.path.join(d, fname), "WEBP", lossless=True)
# превью
prev = Image.new("RGBA", (512, 512), BG)
r = fg_big.resize((460, 460), Image.LANCZOS)
prev.paste(r, (26, 26), r)
prev.save(os.path.join(os.environ.get("PREVIEW_DIR", "."), "preview.png"))
