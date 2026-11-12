#!/usr/bin/env python3
"""
Regenerates the A_STROKE / R_STROKE point data in
app/src/main/java/com/arkarium/app/ui/LogoPoints.kt from
app/src/main/res/drawable-nodpi/ic_splash_logo.png.

Companion to gen_logo_points.py (which produces the single-path POINTS list).
This instead splits the mark's ink in half at its bounding box's horizontal
midpoint - left half ("A", including the left side of the shared crossbar)
and right half ("R", including the right side) - and orders each half
starting from the point nearest that midpoint (where SplashScreen's whoosh
lines hand off into their trace) and walking outward, so each list reads as
one continuous stroke sweeping from the crossbar out to that letter's own far
extremity.

Run this (from the repo root) whenever ic_splash_logo.png is redrawn, then
paste the two printed lists back into LogoPoints.kt.

Usage:
    pip install pillow
    python3 tools/gen_logo_strokes.py
"""
import os

from PIL import Image

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO_ROOT, "app/src/main/res/drawable-nodpi/ic_splash_logo.png")

# Same density knobs as gen_logo_points.py, split across two strokes instead
# of one - see its own comments for why these particular values.
TARGET_TOTAL_POINTS = 220
MIN_SPACING = 6  # px, at the source PNG's native resolution
ALPHA_THRESHOLD = 40  # ignore near-fully-transparent pixels (anti-aliasing fringe)


def _order_from_center(points, mid_x, mid_y):
    """Greedy nearest-neighbor ordering, starting from the point closest to
    (mid_x, mid_y) - i.e. the crossbar - rather than an arbitrary point, so
    the stroke reads as sweeping outward from the hand-off point."""
    remaining = points[:]
    remaining.sort(key=lambda p: (p[0] - mid_x) ** 2 + (p[1] - mid_y) ** 2)
    ordered = [remaining.pop(0)]
    while remaining:
        lx, ly, _a = ordered[-1]
        best_idx, best_dist = 0, None
        for idx, (x, y, _a2) in enumerate(remaining):
            d = (x - lx) ** 2 + (y - ly) ** 2
            if best_dist is None or d < best_dist:
                best_dist, best_idx = d, idx
        ordered.append(remaining.pop(best_idx))
    return ordered


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

    if len(points) > TARGET_TOTAL_POINTS:
        stride = len(points) / TARGET_TOTAL_POINTS
        thinned = []
        i = 0.0
        while int(i) < len(points):
            thinned.append(points[int(i)])
            i += stride
        points = thinned

    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)
    mid_x = (min_x + max_x) / 2
    mid_y = (min_y + max_y) / 2

    left = [p for p in points if p[0] <= mid_x]  # "A" half, including left of crossbar
    right = [p for p in points if p[0] > mid_x]  # "R" half, including right of crossbar

    left_ordered = _order_from_center(left, mid_x, mid_y)
    right_ordered = _order_from_center(right, mid_x, mid_y)

    def normalize(ordered):
        return [
            (round((x - min_x) / (max_x - min_x), 4), round((y - min_y) / (max_y - min_y), 4))
            for x, y, _a in ordered
        ]

    def emit(name, pts):
        print(f"    val {name}: List<Pair<Float, Float>> = listOf(")
        for i, (nx, ny) in enumerate(pts):
            comma = "," if i < len(pts) - 1 else ""
            print(f"        {nx}f to {ny}f{comma}")
        print("    )")

    print(f"// aspect ratio (w/h): {(max_x - min_x) / (max_y - min_y)}")
    print(f"// A_STROKE point count: {len(left_ordered)}, R_STROKE point count: {len(right_ordered)}")
    emit("A_STROKE", normalize(left_ordered))
    print()
    emit("R_STROKE", normalize(right_ordered))


if __name__ == "__main__":
    main()
