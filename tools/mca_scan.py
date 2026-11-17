#!/usr/bin/env python3
"""Read Minecraft 1.8 region files and report the shape of a map.

A WorldDownloader capture is a normal world folder: `region/r.X.Z.mca`, each holding 32x32 chunk columns. This reads
the block arrays straight out of the chunk NBT so a map's floor height, footprint and the two spawn ends can be
measured instead of guessed - which is what a duels map needs before it can have spawn points.

Usage: mca_scan.py <world-dir> [--blocks]
"""
import gzip
import os
import struct
import sys
import zlib
from collections import Counter


def read_tag(buf, i):
    """Read one NBT tag, return (name, payload, next_index)."""
    tag = buf[i]
    i += 1
    if tag == 0:
        return None, None, i
    nlen = struct.unpack('>H', buf[i:i + 2])[0]
    i += 2
    name = buf[i:i + nlen].decode('utf-8', 'replace')
    i += nlen
    if tag == 1:  # byte
        return name, struct.unpack('>b', buf[i:i + 1])[0], i + 1
    if tag == 2:  # short
        return name, struct.unpack('>h', buf[i:i + 2])[0], i + 2
    if tag == 3:  # int
        return name, struct.unpack('>i', buf[i:i + 4])[0], i + 4
    if tag == 4:  # long
        return name, struct.unpack('>q', buf[i:i + 8])[0], i + 8
    if tag == 5:  # float
        return name, struct.unpack('>f', buf[i:i + 4])[0], i + 4
    if tag == 6:  # double
        return name, struct.unpack('>d', buf[i:i + 8])[0], i + 8
    if tag == 7:  # byte array
        n = struct.unpack('>i', buf[i:i + 4])[0]
        return name, buf[i + 4:i + 4 + n], i + 4 + n
    if tag == 8:  # string
        n = struct.unpack('>H', buf[i:i + 2])[0]
        return name, buf[i + 2:i + 2 + n].decode('utf-8', 'replace'), i + 2 + n
    if tag == 9:  # list
        item = buf[i]
        n = struct.unpack('>i', buf[i + 1:i + 5])[0]
        i += 5
        out = []
        for _ in range(n):
            _, v, i = read_tag(bytes([item]) + b'\x00\x00' + buf[i:], 0)
            out.append(v)
        return name, out, i
    if tag == 10:  # compound
        out = {}
        while True:
            k, v, i = read_tag(buf, i)
            if k is None:
                return name, out, i
            out[k] = v
    if tag == 11:  # int array
        n = struct.unpack('>i', buf[i:i + 4])[0]
        vals = struct.unpack('>%di' % n, buf[i + 4:i + 4 + 4 * n])
        return name, vals, i + 4 + 4 * n
    raise ValueError("unknown tag %d at %d" % (tag, i))


def chunks(region_path):
    raw = open(region_path, 'rb').read()
    for k in range(1024):
        off = struct.unpack('>I', b'\x00' + raw[k * 4:k * 4 + 3])[0]
        if off == 0:
            continue
        start = off * 4096
        length = struct.unpack('>I', raw[start:start + 4])[0]
        comp = raw[start + 4]
        data = raw[start + 5:start + 4 + length]
        try:
            if comp == 2:
                data = zlib.decompress(data)
            elif comp == 1:
                data = gzip.decompress(data)
            else:
                continue
        except Exception:
            continue
        yield k % 32, k // 32, data


def scan(world_dir):
    regions = os.path.join(world_dir, 'region')
    if not os.path.isdir(regions):
        print("no region folder in", world_dir)
        return
    columns = []
    block_ids = Counter()
    for name in sorted(os.listdir(regions)):
        if not name.endswith('.mca'):
            continue
        rx = int(name.split('.')[1])
        rz = int(name.split('.')[2])
        for cx, cz, data in chunks(os.path.join(regions, name)):
            try:
                _, root, _ = read_tag(data, 0)
            except Exception:
                continue
            if not isinstance(root, dict):
                continue
            level = root.get('Level', root)
            sections = level.get('Sections') or []
            blocks = level.get('Blocks')
            height = 256
            top = -1
            solid = 0
            for y in range(height):
                idx = y + height * 0
                pass
            if blocks:
                for y in range(height):
                    for z in range(16):
                        for x in range(16):
                            b = blocks[y * 256 + z * 16 + x] if len(blocks) >= 4096 * 16 else None
                            if b is None:
                                continue
                            if b != 0:
                                solid += 1
                                if y > top:
                                    top = y
                                block_ids[b] += 1
            wx = (rx * 32 + cx) * 16
            wz = (rz * 32 + cz) * 16
            columns.append((wx, wz, top, solid))
    if not columns:
        print("no readable chunks in", world_dir)
        return
    tops = [c[2] for c in columns if c[2] >= 0]
    xs = [c[0] for c in columns]
    zs = [c[1] for c in columns]
    print("%-14s chunks=%3d x=[%d..%d] z=[%d..%d]" % (os.path.basename(world_dir.rstrip('/')),
                                                       len(columns), min(xs), max(xs) + 15, min(zs), max(zs) + 15))
    if tops:
        c = Counter(tops)
        print("%-14s top_y: most common %s | min %d max %d" % ("", c.most_common(3), min(tops), max(tops)))
    print("%-14s blocks: %s" % ("", block_ids.most_common(8)))


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    scan(sys.argv[1])
