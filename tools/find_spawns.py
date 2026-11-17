#!/usr/bin/env python3
"""Find the two spawn pads of a duels map from its blocks.

Hypixel's duels maps are point-symmetric: the two ends look the same, the middle is open, and each end has a flat pad
a player stands on. Rather than guessing, this walks the top surface of the arena and reports the block columns that
are:

  * at the dominant floor height (the playable level, not the roof or the decoration below it)
  * solid with air above, i.e. something you can stand on
  * far from the map centre on opposite sides

The two ends it returns are the spawn candidates, with the yaw that faces the middle.

Usage: python3 find_spawns.py <world-dir>
"""
import os
import struct
import sys
import zlib
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from world_shape import regions, chunk_blocks, IDS   # noqa: E402


def top_surface(world_dir):
    """{y: {(x,z): block_id}} for the highest solid block in each column."""
    tops = {}
    for data in regions(world_dir):
        for cx, cz, sy, blocks in chunk_blocks(data):
            if cx is None or cz is None:
                continue
            base_y = sy * 16
            for z in range(16):
                for x in range(16):
                    xz = z * 16 + x
                    wx = cx * 16 + x
                    wz = cz * 16 + z
                    for layer in range(16):
                        b = blocks[layer * 256 + xz] if len(blocks) >= 4096 else 0
                        if not b:
                            continue
                        wy = base_y + layer
                        cur = tops.get((wx, wz))
                        if cur is None or wy > cur[0]:
                            tops[(wx, wz)] = (wy, b)
    return tops


def main(world_dir):
    tops = top_surface(world_dir)
    if not tops:
        print("no blocks")
        return
    heights = Counter(y for y, _ in tops.values())
    floor_y = heights.most_common(1)[0][0]
    # the standable cells at the floor level: solid at floor_y with nothing above it in the same column
    cells = [(x, z) for (x, z), (y, b) in tops.items() if y == floor_y]
    if not cells:
        print("no cells at the dominant height")
        return
    xs = [c[0] for c in cells]
    zs = [c[1] for c in cells]
    cx0 = (min(xs) + max(xs)) / 2.0
    cz0 = (min(zs) + max(zs)) / 2.0
    print("%s: floor y=%d, %d standable cells, centre (%.0f, %.0f), extent x %d..%d z %d..%d" %
          (os.path.basename(world_dir.rstrip('/')), floor_y, len(cells), cx0, cz0,
           min(xs), max(xs), min(zs), max(zs)))

    # The two spawn pads of a duels map sit at the two ends of the long axis, on the perpendicular centre line -
    # not in the corners, which is what "furthest apart" finds (a corner is further from the centre than a pad is).
    span_x = max(xs) - min(xs)
    span_z = max(zs) - min(zs)
    axis = 'x' if span_x >= span_z else 'z'
    along_max = max(xs) if axis == 'x' else max(zs)
    along_min = min(xs) if axis == 'x' else min(zs)
    perp_centre = cz0 if axis == 'x' else cx0
    band = max(8, int((along_max - along_min) * 0.18))     # search the outer sixth of the map at each end

    def pad_at(extreme, side):
        best = None
        for (x, z) in cells:
            a = x if axis == 'x' else z
            perp = z if axis == 'x' else x
            if side > 0 and a < extreme - band:
                continue
            if side < 0 and a > extreme + band:
                continue
            score = abs(perp - perp_centre) + abs(a - extreme) * 0.25
            if best is None or score < best[0]:
                best = (score, (x, z))
        return best[1] if best else None

    far = pad_at(along_max, +1)
    near = pad_at(along_min, -1)
    if far is None or near is None:
        print("could not find pads at both ends")
        return
    print("%s: axis=%s end A (%d,%d,%d) end B (%d,%d,%d) separation %.1f" %
          ("", axis, far[0], floor_y + 1, far[1], near[0], floor_y + 1, near[1],
           ((far[0] - near[0]) ** 2 + (far[1] - near[1]) ** 2) ** 0.5))

    # what the two ends are made of, so the pads can be told from decorative corners
    def around(x, z, r=3):
        c = Counter()
        for dx in range(-r, r + 1):
            for dz in range(-r, r + 1):
                t = tops.get((x + dx, z + dz))
                if t:
                    c[t[1]] += 1
        return c.most_common(3)

    print("%s: end A surface %s | end B surface %s" % ("", around(*far), around(*near)))

    # yaw: each end faces the other. Minecraft yaw 0 = +z, 90 = -x, 180 = -z, 270 = +x
    import math
    for name, (x, z) in (("A", far), ("B", near)):
        yaw = math.degrees(math.atan2(-(cx0 - x), (cz0 - z))) % 360
        print("%s: spawn %s = {x: %d.5, y: %d, z: %d.5, yaw: %d}" % (name, name, x, floor_y + 1, z, round(yaw)))


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1])
