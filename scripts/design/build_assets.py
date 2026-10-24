#!/usr/bin/env python3
"""One-off generator for logo, badge and favicon SVGs (not part of the site build).
Needs fontTools + brotli (installed in a throwaway venv, NOT added to the project):
  python3 -m venv /tmp/zt && /tmp/zt/bin/pip install fonttools brotli
  /tmp/zt/bin/python scripts/design/build_assets.py
Text is converted to outlines using the self-hosted OFL fonts so the SVGs render
identically as <img>, with no font loading. PNGs are then rendered with Inkscape
(see scripts/design/render_pngs.sh).
"""
import os
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
A = os.path.join(ROOT, "assets")
PLAYFAIR = TTFont(os.path.join(A, "fonts/playfair-display-latin-600.woff2"))
INTER = TTFont(os.path.join(A, "fonts/inter-latin-600.woff2"))

BLACK, GOLD, GOLD_LIGHT, GOLD_DEEP = "#0B0B0B", "#D6A94F", "#F4CC6A", "#8A6310"
IVORY, TEXT, ERROR = "#F6F0E4", "#1A1713", "#B42318"

def text_path(font, s, size, x=0, y=0, tracking=0.0):
    """Return (svg path d, advance width) for text outlined at baseline y."""
    gs, cmap, upm = font.getGlyphSet(), font.getBestCmap(), font["head"].unitsPerEm
    k, pen_x, d = size / upm, x, []
    for ch in s:
        name = cmap[ord(ch)]
        pen = SVGPathPen(gs, ntos=lambda v: f"{v:.2f}".rstrip("0").rstrip("."))
        gs[name].draw(TransformPen(pen, (k, 0, 0, -k, pen_x, y)))
        d.append(pen.getCommands())
        pen_x += gs[name].width * k + tracking
    return " ".join(d), pen_x - x - tracking

def mark(fill_z, fill_wave, fill_star, ox=0, oy=0, scale=1.0, wave_edge=None):
    """Z monogram in a 120x120 box, traced by eye from business/images/logo/zandy-logo.jpeg:
    a wide serif Z, a ribbon wave sweeping in from the left through the diagonal, and a star
    in the pocket of the upper curl. Drawn in the logo's own crop coordinates, then scaled."""
    z = ("M268 24H418V50H408Q404 40 390 40L330 186H384Q402 186 410 168H420V212H266V196H288"
         "L356 40H296Q282 40 278 50H268Z")
    wave = ("M176 152C198 118 240 100 280 104C320 108 335 140 366 146C396 150 412 128 408 98"
            "C422 130 410 170 372 170C336 170 320 140 282 130C246 120 208 134 176 152Z")
    cx, cy, r = 364, 124, 12   # five-point star
    import math
    pts = []
    for i in range(10):
        ang = -math.pi / 2 + i * math.pi / 5
        rad = r if i % 2 == 0 else r * 0.42
        pts.append(f"{cx + rad * math.cos(ang):.1f} {cy + rad * math.sin(ang):.1f}")
    star = "M" + "L".join(pts) + "Z"
    edge = f' stroke="{wave_edge}" stroke-width="5" stroke-linejoin="round" paint-order="stroke"' if wave_edge else ""
    return (f'<g transform="translate({ox} {oy}) scale({scale})"><g transform="translate(0 15) scale(.48) translate(-176 -18)">'
            f'<path d="{z}" fill="{fill_z}"/><path d="{wave}" fill="{fill_wave}"{edge}/>'
            f'<path d="{star}" fill="{fill_star}"/></g></g>')

def svg(w, h, body, title, bg=None):
    r = f'<rect width="{w}" height="{h}" fill="{bg}"/>' if bg else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" '
            f'role="img" aria-label="{title}"><title>{title}</title>{r}{body}</svg>\n')

def write(rel, content):
    with open(os.path.join(A, rel), "w") as f: f.write(content)

variants = {"": dict(z=GOLD, wave=GOLD_LIGHT, star=GOLD_LIGHT, edge=BLACK, word=GOLD, slogan=IVORY, rule=GOLD),
            "-light": dict(z=BLACK, wave=GOLD_DEEP, star=GOLD_DEEP, edge=IVORY, word=BLACK, slogan=TEXT, rule=GOLD_DEEP)}

for suf, c in variants.items():
    # monogram only
    write(f"logo/logo-mark{suf}.svg", svg(120, 120, mark(c["z"], c["wave"], c["star"], wave_edge=c["edge"]), "Zandy Touch monogram"))
    # wordmark only
    d, w = text_path(PLAYFAIR, "Zandy Touch", 56, 0, 44)
    write(f"logo/logo-wordmark{suf}.svg", svg(round(w) + 4, 64, f'<path d="{d}" fill="{c["word"]}" transform="translate(2 0)"/>', "Zandy Touch"))
    # horizontal lockup for the header: mark + wordmark
    d, w = text_path(PLAYFAIR, "Zandy Touch", 40, 0, 0)
    body = mark(c["z"], c["wave"], c["star"], 0, 0, 0.6, c["edge"]) + f'<path d="{d}" fill="{c["word"]}" transform="translate(80 46)"/>'
    write(f"logo/logo-horizontal{suf}.svg", svg(round(80 + w) + 2, 72, body, "Zandy Touch"))
    # stacked lockup with slogan
    d, w = text_path(PLAYFAIR, "Zandy Touch", 64, 0, 0)
    s, sw = text_path(INTER, "BEAUTY WITH CONFIDENCE", 13, 0, 0, tracking=3.2)
    W = round(max(w, sw + 140)) + 8; cx = W / 2
    body = (mark(c["z"], c["wave"], c["star"], cx - 50, 0, 100 / 120, c["edge"]) +
            f'<path d="{d}" fill="{c["word"]}" transform="translate({cx - w/2:.1f} 168)"/>'
            f'<path d="{s}" fill="{c["slogan"]}" transform="translate({cx - sw/2:.1f} 202)"/>'
            f'<path d="M{cx - sw/2 - 56:.1f} 197H{cx - sw/2 - 12:.1f}M{cx + sw/2 + 12:.1f} 197H{cx + sw/2 + 56:.1f}" stroke="{c["rule"]}" stroke-width="1.5"/>')
    write(f"logo/logo-lockup{suf}.svg", svg(W, 220, body, "Zandy Touch - Beauty with Confidence"))

# favicon: gold monogram on black rounded square
write("favicon.svg", svg(120, 120, mark(GOLD, GOLD_LIGHT, GOLD_LIGHT, 12, 12, 0.8, BLACK), "Zandy Touch", bg=None).replace(
    '<rect', '<rect').replace('</title>', '</title><rect width="120" height="120" rx="24" fill="#0B0B0B"/>'))
# square, full-bleed icon (apple touch / maskable source) with safe padding
write("logo/app-icon.svg", svg(120, 120, mark(GOLD, GOLD_LIGHT, GOLD_LIGHT, 24, 24, 0.6, BLACK), "Zandy Touch", bg=BLACK))

# Badges (design-spec.md section 6): 28px tall, Inter 600, 12px, uppercase, tracked.
# (id, label, fill, text, border)
WARN, WARNBG, OKC, OKBG, INFOBG, MUTED, ERRBG = "#7A4F00", "#FBEBC8", "#1E6B3B", "#E3F1E7", "#ECE5D4", "#5C5446", "#FBE4E1"
badges = [("new", "NEW", BLACK, GOLD, GOLD),
          ("zandy-touch", "ZANDY TOUCH", GOLD, BLACK, GOLD),
          ("sold-out", "SOLD OUT", IVORY, TEXT, "#8C826F"),
          ("featured", "FEATURED", "#FFFFFF", GOLD_DEEP, GOLD_DEEP),
          ("low-stock", "LOW STOCK", WARNBG, WARN, WARN)]
for bid, label, fill, tcol, border in badges:
    d, w = text_path(INTER, label, 12, 0, 0, tracking=1.2)
    W = round(w) + 24
    body = (f'<rect x=".5" y=".5" width="{W-1}" height="27" rx="4" fill="{fill}" stroke="{border}"/>'
            f'<path d="{d}" fill="{tcol}" transform="translate(12 18.5)"/>')
    write(f"badges/badge-{bid}.svg", svg(W, 28, body, label.title()))

# Draft notice: sentence case (it is a sentence), 4px warning bar on the left, text only.
label = "Draft text, not yet reviewed"
d, w = text_path(INTER, label, 13, 0, 0, tracking=0)
W = round(w) + 32
body = (f'<rect x=".5" y=".5" width="{W-1}" height="27" rx="4" fill="{WARNBG}" stroke="{WARN}"/>'
        f'<rect x="0" y="0" width="5" height="28" rx="2" fill="{WARN}"/>'
        f'<path d="{d}" fill="{WARN}" transform="translate(16 18.5)"/>')
write("badges/badge-draft-notice.svg", svg(W, 28, body, label))

# Admin status pills (pill shape, sentence case, 13px, same-hue text and border on a tint).
# Words always carry the meaning; colour only supports it.
pills = [("draft", "Draft", INFOBG, TEXT), ("published", "Published", OKBG, OKC),
         ("archived", "Archived", INFOBG, MUTED), ("placed", "Placed", INFOBG, TEXT),
         ("confirmed", "Confirmed", WARNBG, WARN), ("out-for-delivery", "Out for delivery", WARNBG, WARN),
         ("delivered", "Delivered", OKBG, OKC), ("cancelled", "Cancelled", ERRBG, ERROR)]
for pid, label, fill, tcol in pills:
    d, w = text_path(INTER, label, 13, 0, 0, tracking=0)
    W = round(w) + 28
    body = (f'<rect x=".5" y=".5" width="{W-1}" height="27" rx="13.5" fill="{fill}" stroke="{tcol}"/>'
            f'<path d="{d}" fill="{tcol}" transform="translate(14 18.5)"/>')
    write(f"badges/status-{pid}.svg", svg(W, 28, body, label))
print("done")
