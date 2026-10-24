#!/bin/sh
# Render favicon/app-icon PNGs from the SVG masters with Inkscape (one-off helper).
set -e
cd "$(dirname "$0")/../../assets"
for s in 16 32; do inkscape favicon.svg -w $s -h $s -o favicon-$s.png >/dev/null 2>&1; done
for s in 180 192 512; do inkscape logo/app-icon.svg -w $s -h $s -o favicon-$s.png >/dev/null 2>&1; done
mv favicon-180.png apple-touch-icon-180.png; mv favicon-192.png icon-192.png; mv favicon-512.png icon-512.png
