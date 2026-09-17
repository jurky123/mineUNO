#!/usr/bin/env python3
"""生成 MineUNO 材质包：卡牌贴图 + item model 定义 + pack.mcmeta。
用法: python3 gen_pack.py   (输出 pack/out/mineuno.zip)
"""
import json
import os
import shutil
import zipfile
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "src")
OUT = os.path.join(HERE, "out")
S = 256
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

COLORS = {
    "red": (206, 43, 43),
    "yellow": (233, 176, 24),
    "green": (52, 148, 66),
    "blue": (34, 88, 190),
}
DARK = (32, 32, 38)
WHITE = (255, 255, 255, 255)


def font(size):
    return ImageFont.truetype(FONT_BOLD, size)


def rounded_mask(size, box, radius):
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(box, radius, fill=255)
    return mask


def text_center(draw, xy, text, f, fill, stroke=0, stroke_fill=None):
    box = draw.textbbox((0, 0), text, font=f, stroke_width=stroke)
    draw.text((xy[0] - (box[2] - box[0]) / 2 - box[0], xy[1] - (box[3] - box[1]) / 2 - box[1]),
              text, font=f, fill=fill, stroke_width=stroke, stroke_fill=stroke_fill)


def draw_skip(d, cx, cy, size, color, width=None):
    r = size * 0.42
    w = width or max(3, int(size * 0.13))
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=color, width=w)
    k = r * 0.72
    d.line([cx - k, cy + k, cx + k, cy - k], fill=color, width=w)


def draw_reverse(d, cx, cy, size, color, width=None):
    w = width or max(3, int(size * 0.12))
    r = size * 0.40
    head = size * 0.26
    for direction in (1, -1):
        y = cy - direction * r * 0.52
        x0, x1 = cx - r, cx + r
        if direction < 0:
            x0, x1 = x1, x0
        d.line([x0, y, x1 - direction * head * 0.5, y], fill=color, width=w)
        tip = x1
        d.polygon([(tip, y), (tip - direction * head, y - head * 0.62), (tip - direction * head, y + head * 0.62)], fill=color)


def draw_pie(d, box, colors, start=-90):
    step = 360 / len(colors)
    for i, c in enumerate(colors):
        d.pieslice(box, start + i * step, start + (i + 1) * step, fill=c)
    d.ellipse(box, outline=DARK, width=4)


def draw_glyph(img, d, kind, number, cx, cy, size, color, edge=None):
    f = font(int(size))
    if kind == "num":
        text_center(d, (cx, cy), str(number), f, color, 4, edge or WHITE)
    elif kind == "draw2":
        text_center(d, (cx, cy), "+2", f, color, 4, edge or WHITE)
    elif kind == "wild4":
        text_center(d, (cx, cy), "+4", f, color, 5, edge or DARK)
    elif kind == "skip":
        draw_skip(d, cx, cy, size, color)
    elif kind == "reverse":
        draw_reverse(d, cx, cy, size, color)
    elif kind == "wild":
        draw_pie(d, [cx - size * 0.46, cy - size * 0.34, cx + size * 0.46, cy + size * 0.34],
                 [(206, 43, 43), (233, 176, 24), (34, 88, 190), (52, 148, 66)])


def card_face(color_name, kind, number=None):
    base = COLORS.get(color_name, DARK)
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([4, 4, S - 4, S - 4], 30, fill=WHITE)
    d.rounded_rectangle([14, 14, S - 14, S - 14], 24, fill=base)

    oval = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(oval).ellipse([38, 62, S - 38, S - 62], fill=WHITE)
    oval = oval.rotate(-20, resample=Image.BICUBIC, center=(S // 2, S // 2))
    img.alpha_composite(Image.composite(oval, Image.new("RGBA", (S, S), (0, 0, 0, 0)),
                                        rounded_mask(S, [14, 14, S - 14, S - 14], 24)))

    if kind == "wild":
        draw_glyph(img, d, "wild", None, S // 2, S // 2, 118, base)
        for x, y in ((44, 44), (S - 44, 44), (44, S - 44), (S - 44, S - 44)):
            draw_pie(d, [x - 18, y - 13, x + 18, y + 13],
                     [(206, 43, 43), (233, 176, 24), (34, 88, 190), (52, 148, 66)])
    elif kind == "wild4":
        draw_glyph(img, d, "wild", None, S // 2, S // 2, 128, base)
        draw_glyph(img, d, "wild4", None, S // 2, S // 2, 116, WHITE)
        for x, y in ((44, 44), (S - 44, 44), (44, S - 44), (S - 44, S - 44)):
            d.rectangle([x - 14, y - 20, x + 14, y + 20],
                        fill=[(206, 43, 43), (233, 176, 24), (34, 88, 190), (52, 148, 66)][((x > S // 2) * 1 + (y > S // 2) * 2) % 4],
                        outline=WHITE, width=3)
    else:
        draw_glyph(img, d, kind, number, S // 2, S // 2, 150, base)
        pip = 46
        draw_glyph(img, d, kind, number, 54, 54, pip, WHITE)
        corner = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        draw_glyph(corner, ImageDraw.Draw(corner), kind, number, 32, 32, pip, WHITE)
        corner = corner.rotate(180)
        img.alpha_composite(corner, (S - 64 - 26, S - 64 - 26))
    return img


def card_back():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([4, 4, S - 4, S - 4], 30, fill=WHITE)
    d.rounded_rectangle([14, 14, S - 14, S - 14], 24, fill=(28, 38, 96))
    oval = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(oval).ellipse([34, 58, S - 34, S - 58], fill=WHITE)
    oval = oval.rotate(-20, resample=Image.BICUBIC, center=(S // 2, S // 2))
    img.alpha_composite(Image.composite(oval, Image.new("RGBA", (S, S), (0, 0, 0, 0)),
                                        rounded_mask(S, [14, 14, S - 14, S - 14], 24)))
    text_center(d, (S // 2, S // 2 - 4), "M", font(150), (206, 43, 43), 5, (255, 233, 90))
    text_center(d, (S // 2, S // 2 + 88), "MINEUNO", font(22), (28, 38, 96))
    return img


def color_disc(name):
    img = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = COLORS[name]
    d.ellipse([4, 4, 124, 124], fill=c, outline=DARK, width=6)
    d.ellipse([26, 26, 102, 102], outline=WHITE, width=6)
    d.ellipse([48, 48, 80, 80], fill=WHITE)
    return img


def dir_arrow(cw=True):
    img = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([4, 4, 124, 124], fill=(40, 40, 48, 235), outline=WHITE, width=6)
    box = [32, 32, 96, 96]
    if cw:
        d.arc(box, start=-160, end=120, fill=WHITE, width=11)
    else:
        d.arc(box, start=-30, end=200, fill=WHITE, width=11)
    # 箭头
    if cw:
        tip = (86, 42)
        d.polygon([tip, (tip[0] - 30, tip[1] + 2), (tip[0] - 4, tip[1] + 30)], fill=WHITE)
    else:
        tip = (42, 42)
        d.polygon([tip, (tip[0] + 30, tip[1] + 2), (tip[0] + 4, tip[1] + 30)], fill=WHITE)
    return img


def page_arrow(point_right=True):
    img = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([6, 6, 122, 122], fill=(38, 38, 46, 225), outline=(255, 255, 255, 235), width=5)
    if point_right:
        d.polygon([(46, 32), (88, 64), (46, 96)], fill=(255, 255, 255, 245))
    else:
        d.polygon([(82, 32), (40, 64), (82, 96)], fill=(255, 255, 255, 245))
    return img


def table_texture():
    img = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, 512, 512], 44, fill=(122, 82, 50, 255))
    d.rounded_rectangle([10, 10, 502, 502], 36, fill=(88, 58, 36, 255))
    d.rounded_rectangle([26, 26, 486, 486], 26, fill=(28, 84, 58, 255))
    d.rounded_rectangle([34, 34, 478, 478], 22, outline=(20, 62, 44, 255), width=5)
    d.rounded_rectangle([62, 62, 450, 450], 18, outline=(210, 180, 90, 120), width=3)
    d.ellipse([150, 150, 362, 362], outline=(210, 180, 90, 60), width=3)
    return img


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def item_defs(name, texture):
    write_json(os.path.join(SRC, "assets/mineuno/items", name + ".json"),
               {"model": {"type": "minecraft:model", "model": "mineuno:item/" + name}})
    write_json(os.path.join(SRC, "assets/mineuno/models/item", name + ".json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": "mineuno:item/" + texture}})


def save(img, name):
    path = os.path.join(SRC, "assets/mineuno/textures/item", name + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    return name


def main():
    if os.path.exists(SRC):
        shutil.rmtree(SRC)

    textures = []
    for color in COLORS:
        for n in range(10):
            textures.append(save(card_face(color, "num", n), "card/%s_%d" % (color, n)))
        for kind, suffix in (("skip", "skip"), ("reverse", "reverse"), ("draw2", "draw2")):
            textures.append(save(card_face(color, kind), "card/%s_%s" % (color, suffix)))
    textures.append(save(card_face("wild", "wild"), "card/wild"))
    textures.append(save(card_face("wild", "wild4"), "card/wild4"))
    textures.append(save(card_back(), "card/back"))
    save(table_texture(), "table")
    item_defs("table", "table")
    for color in COLORS:
        save(color_disc(color), "color/" + color)
        item_defs("color/" + color, "color/" + color)
    for name, cw in (("cw", True), ("ccw", False)):
        save(dir_arrow(cw), "dir/" + name)
        item_defs("dir/" + name, "dir/" + name)
    for name, right in (("next", True), ("prev", False)):
        save(page_arrow(right), "page/" + name)
        item_defs("page/" + name, "page/" + name)
    for texture in textures:
        item_defs(texture, texture)

    write_json(os.path.join(SRC, "pack.mcmeta"), {"pack": {
        "pack_format": 88,
        "supported_formats": {"min_inclusive": 1, "max_inclusive": 999},
        "description": "MineUNO 材质包（自定义卡牌与桌面）",
    }})

    os.makedirs(OUT, exist_ok=True)
    zip_path = os.path.join(OUT, "mineuno.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(SRC):
            for f in sorted(files):
                full = os.path.join(root, f)
                z.write(full, os.path.relpath(full, SRC))
    print("生成完成: %s (%d KB, %d 个文件)" % (zip_path, os.path.getsize(zip_path) // 1024,
          sum(len(fs) for _, _, fs in os.walk(SRC))))


if __name__ == "__main__":
    main()
