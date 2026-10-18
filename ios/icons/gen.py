"""Renders the iOS app icons (1024 px) from the Android launcher vectors (app-*/src/main/res/drawable/ic_launcher.xml).

The vectors are a background square plus circles written as two arcs ("Mx,ya r,r 0,1 1,0 2r a ..."), so the circles
are read straight from their path data. Run: python3 ios/icons/gen.py (needs Pillow).
"""
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
APPS = {"Pro": "app-pro", "Auto": "app-auto", "Camera": "app-camera"}
SIZE = 1024
SUPER = 4  # supersampling for smooth edges
ANDROID = "{http://schemas.android.com/apk/res/android}"


def colour(argb):
    v = argb.lstrip("#")
    return tuple(int(v[i:i + 2], 16) for i in (2, 4, 6))  # #AARRGGBB -> RGB


def render(xml_path, out_png):
    root = ET.parse(xml_path).getroot()
    view = float(root.get(ANDROID + "viewportWidth"))
    s = SIZE * SUPER / view
    img = Image.new("RGB", (SIZE * SUPER, SIZE * SUPER))
    draw = ImageDraw.Draw(img)
    for p in root.iter("path"):
        data = p.get(ANDROID + "pathData")
        fill = colour(p.get(ANDROID + "fillColor"))
        rect = re.fullmatch(r"M([\d.]+),([\d.]+)h([\d.]+)v([\d.]+)h-[\d.]+z", data)
        circle = re.match(r"M([\d.]+),([\d.]+)a([\d.]+),", data)
        if rect:
            x, y, w, h = map(float, rect.groups())
            draw.rectangle([x * s, y * s, (x + w) * s, (y + h) * s], fill=fill)
        elif circle:
            x, top, r = map(float, circle.groups())
            cy = top + r
            draw.ellipse([(x - r) * s, (cy - r) * s, (x + r) * s, (cy + r) * s], fill=fill)
        else:
            sys.exit(f"{xml_path}: unsupported path {data}")
    img.resize((SIZE, SIZE), Image.LANCZOS).save(out_png)


for app, module in APPS.items():
    icon_set = ROOT / "ios" / app / "Assets.xcassets" / "AppIcon.appiconset"
    icon_set.mkdir(parents=True, exist_ok=True)
    render(ROOT / module / "src/main/res/drawable/ic_launcher.xml", icon_set / "icon-1024.png")
    (icon_set / "Contents.json").write_text(json.dumps({
        "images": [{"filename": "icon-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}],
        "info": {"author": "xcode", "version": 1},
    }, indent=2) + "\n")
    (icon_set.parent / "Contents.json").write_text(json.dumps({"info": {"author": "xcode", "version": 1}}, indent=2) + "\n")
    print("wrote", icon_set)
