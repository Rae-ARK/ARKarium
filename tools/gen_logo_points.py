#!/usr/bin/env python3
"""
Regenerates the point data in
app/src/main/java/com/arkarium/app/ui/LogoPoints.kt from
app/src/main/res/drawable-nodpi/ic_splash_logo.png.

Run this (from the repo root) whenever ic_splash_logo.png is redrawn, then
paste the printed POINTS block back into LogoPoints.kt.

Usage:
    pip install pillow
    python3 tools/gen_logo_points.py
"""
import json
import os
from PIL import Image

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO_ROOT, "app/src/main/res/drawable-nodpi/ic_splash_logo.png")

# Roughly how many points to end up with. Denser strokes (a thicker or more
# detailed redraw of the mark) will want a smaller MIN_SPACING to keep the
# same visual density.
TARGET_POINTS = 220
MIN_SPACING = 6  # px, at the source PNG's native resolution
ALPHA_THRESHOLD = 40  # ignore near-fully-transparent pixels (anti-aliasing fringe)


def main() -> None:
    img = Image.open(SRC).convert("RGBA")
    w, h = img.size
    px = img.load()

    candidates = []
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a > ALPHA_THRESHOLD:
                candidates.append((x, y, a))

    # Grid-bucket downsample: keeps the highest-alpha pixel per MIN_SPACING
    # cell, so points spread evenly along the (thin) stroke instead of
    # clumping wherever the stroke happens to be thicker.
    buckets = {}
    for x, y, a in candidates:
        key = (x // MIN_SPACING, y // MIN_SPACING)
        if key not in buckets or a > buckets[key][2]:
            buckets[key] = (x, y, a)
    points = list(buckets.values())

    if len(points) > TARGET_POINTS:
        stride = len(points) / TARGET_POINTS
        thinned = []
        i = 0.0
        while int(i) < len(points):
            thinned.append(points[int(i)])
            i += stride
        points = thinned

    # Greedy nearest-neighbor ordering, so the reconstruction animation reads
    # as one continuous sweep tracing the mark rather than points arriving in
    # random raster order.
    remaining = points[:]
    ordered = [remaining.pop(0)]
    while remaining:
        lx, ly, _ = ordered[-1]
        best_idx, best_dist = 0, None
        for idx, (x, y, _a) in enumerate(remaining):
            d = (x - lx) ** 2 + (y - ly) ** 2
            if best_dist is None or d < best_dist:
                best_dist, best_idx = d, idx
        ordered.append(remaining.pop(best_idx))

    xs = [p[0] for p in ordered]
    ys = [p[1] for p in ordered]
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)
    range_x = max(max_x - min_x, 1)
    range_y = max(max_y - min_y, 1)

    norm = [
        (round((x - min_x) / range_x, 4), round((y - min_y) / range_y, 4))
        for x, y, _a in ordered
    ]

    print(f"// aspect ratio (w/h): {(max_x - min_x) / (max_y - min_y)}")
    print(f"// point count: {len(norm)}")
    print("val POINTS: List<Pair<Float, Float>> = listOf(")
    for i, (nx, ny) in enumerate(norm):
        comma = "," if i < len(norm) - 1 else ""
        print(f"    {nx}f to {ny}f{comma}")
    print(")")


if __name__ == "__main__":
    main()
