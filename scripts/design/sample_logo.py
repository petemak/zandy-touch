#!/usr/bin/env python3
"""Sample gold and background colours from the owner's logo JPEG (one-off helper).
Needs Pillow in a throwaway venv (NOT a project dependency):
  python3 -m venv /tmp/zt && /tmp/zt/bin/pip install pillow
  /tmp/zt/bin/python scripts/design/sample_logo.py [path-to-jpeg ...]
Prints median and bright-end gold of the wordmark and Z, and the field colour.
"""
import os, sys, statistics
from PIL import Image
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
paths = sys.argv[1:] or [os.path.join(ROOT, "business/images/logo/zandy-logo.jpeg")]
REGIONS = {"wordmark": (90, 370, 710, 450), "Z monogram": (320, 160, 490, 360)}  # for the 812x1080 logo
hx = lambda t: "#%02x%02x%02x" % tuple(int(v) for v in t)
for p in paths:
    im = Image.open(p).convert("RGB"); print(p, im.size)
    for name, box in REGIONS.items():
        px = sorted((q for q in im.crop(box).getdata() if max(q) > 150), key=sum)
        med = [statistics.median(c) for c in zip(*px)]
        top = [statistics.mean(c) for c in zip(*px[-max(1, len(px) // 10):])]
        print(f"  {name}: median {hx(med)}  brightest 10% {hx(top)}")
    print("  field:", hx(im.getpixel((20, 20))))
