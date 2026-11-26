#!/usr/bin/env python3
"""Report the vertical extent of the blocks in an Anvil world.

Used to derive a build floor/ceiling suggestion for a map whose heights nobody published: the floor
is below the lowest solid block a player can stand on, the ceiling above the highest. Reading the
actual build beats guessing from the arena points, which sit at walking level and say nothing about
how tall the islands or the mid tower are.

Chunk format is 1.8 Anvil: Level.Sections[*].Blocks is a 4096-byte array of block ids in
y,z,x order, so any non-zero byte is a placed block.
"""
import glob
import gzip
import io
import os
import struct
import sys
import zlib


def read_tag(buf, i):
    t = buf[i]; i += 1
    n = struct.unpack(">H", buf[i:i + 2])[0]; i += 2
    return t, buf[i:i + n].decode("utf8", "replace"), i + n


def payload(buf, i, t):
    if t == 1: return struct.unpack(">b", buf[i:i + 1])[0], i + 1
    if t == 2: return struct.unpack(">h", buf[i:i + 2])[0], i + 2
    if t == 3: return struct.unpack(">i", buf[i:i + 4])[0], i + 4
    if t == 4: return struct.unpack(">q", buf[i:i + 8])[0], i + 8
    if t == 5: return struct.unpack(">f", buf[i:i + 4])[0], i + 4
    if t == 6: return struct.unpack(">d", buf[i:i + 8])[0], i + 8
    if t == 7:
        n = struct.unpack(">i", buf[i:i + 4])[0]
        return buf[i + 4:i + 4 + n], i + 4 + n
    if t == 8:
        n = struct.unpack(">H", buf[i:i + 2])[0]
        return buf[i + 2:i + 2 + n].decode("utf8", "replace"), i + 2 + n
    if t == 9:
        et = buf[i]; n = struct.unpack(">i", buf[i + 1:i + 5])[0]; i += 5
        out = []
        for _ in range(n):
            v, i = payload(buf, i, et)
            out.append(v)
        return out, i
    if t == 10:
        out = {}
        while True:
            tt = buf[i]
            if tt == 0: return out, i + 1
            _, name, j = read_tag(buf, i)
            v, i = payload(buf, j, tt)
            out[name] = v
    if t == 11:
        n = struct.unpack(">i", buf[i:i + 4])[0]
        return list(struct.unpack(">%di" % n, buf[i + 4:i + 4 + 4 * n])), i + 4 + 4 * n
    raise ValueError(t)


def chunks(region_file):
    with open(region_file, "rb") as f:
        hdr = f.read(4096)
        for i in range(1024):
            if hdr[i * 4 + 3] == 0:
                continue
            off = struct.unpack(">I", b"\x00" + hdr[i * 4:i * 4 + 3])[0]
            if off == 0:
                continue
            f.seek(off * 4096)
            ln = struct.unpack(">I", f.read(4))[0]
            comp = f.read(1)[0]
            body = f.read(ln - 1)
            try:
                raw = zlib.decompress(body) if comp == 2 else gzip.decompress(body)
            except Exception:
                continue
            yield raw


def main():
    world = sys.argv[1]
    # optional radius in blocks around the origin: the world often carries scenery the arena does not use, and a
    # limit derived from a decorative spire 120 blocks away is not a limit for the islands
    radius = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    region = os.path.join(world, "region")
    files = sorted(glob.glob(os.path.join(region, "*.mca")))
    if not files:
        sys.exit("no region files in " + region)

    lo, hi = 255, 0
    counts = {}
    scanned = 0
    for f in files:
        for raw in chunks(f):
            scanned += 1
            t, _, i = read_tag(raw, 0)
            root, _ = payload(raw, i, t)
            lvl = root.get("Level", root)
            cx, cz = lvl.get("xPos"), lvl.get("zPos")
            if radius and cx is not None and cz is not None:
                if abs(cx * 16) > radius + 16 or abs(cz * 16) > radius + 16:
                    continue
            for sec in lvl.get("Sections", []):
                blocks = sec.get("Blocks")
                if not blocks:
                    continue
                sy = sec.get("Y", 0) * 16
                # a section is all air or it is not; find its own bottom and top
                first = last = None
                for y in range(16):
                    row = blocks[y * 256:(y + 1) * 256]
                    if any(b != 0 for b in row):
                        if first is None:
                            first = y
                        last = y
                if first is None:
                    continue
                lo = min(lo, sy + first)
                hi = max(hi, sy + last + 1)
    print("world:      %s" % world)
    print("chunks:     %d" % scanned)
    print("lowest:     %d" % lo)
    print("highest:    %d" % hi)
    # a build floor below the lowest standable block, and a ceiling with room to bridge above the tallest structure
    print("suggested:  build-floor %d  build-limit %d" % (max(0, lo - 4), min(255, hi + 12)))


if __name__ == "__main__":
    main()
