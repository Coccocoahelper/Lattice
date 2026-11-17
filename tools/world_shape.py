#!/usr/bin/env python3
"""Report the shape of a Minecraft 1.8 world by reading its region files directly.

A WorldDownloader capture is a normal world folder: `region/r.X.Z.mca`, 32x32 chunks each, an Anvil header pointing at
zlib-compressed chunk NBT. For a duels map the numbers that decide everything are: the floor height, how wide the
playable area is, and whether it is point-symmetric about the middle (what makes Hypixel's duels maps fair).

This reads the chunk's `Level/Sections/*/Blocks` byte arrays, which is where 1.8 keeps block ids.

Usage: python3 world_shape.py <world-dir> [--floor]
"""
import os
import struct
import sys
import zlib
from collections import Counter

# --- a minimal NBT reader (1.8 chunk payloads only need these tag types) -------------------------------------------

def _str(buf, i):
    n = struct.unpack('>H', buf[i:i + 2])[0]
    return buf[i + 2:i + 2 + n].decode('utf-8', 'replace'), i + 2 + n


def nbt(buf, i=0):
    """Parse one tag at i. Returns (tag_id, value, next_i); compounds are dicts, lists are lists."""
    tag = buf[i]
    i += 1
    if tag == 0:
        return 0, None, i
    _, i = _str(buf, i)                    # name, unused here
    if tag == 1:
        return tag, struct.unpack('>b', buf[i:i + 1])[0], i + 1
    if tag == 2:
        return tag, struct.unpack('>h', buf[i:i + 2])[0], i + 2
    if tag == 3:
        return tag, struct.unpack('>i', buf[i:i + 4])[0], i + 4
    if tag == 4:
        return tag, struct.unpack('>q', buf[i:i + 8])[0], i + 8
    if tag == 5:
        return tag, struct.unpack('>f', buf[i:i + 4])[0], i + 4
    if tag == 6:
        return tag, struct.unpack('>d', buf[i:i + 8])[0], i + 8
    if tag == 7:
        n = struct.unpack('>i', buf[i:i + 4])[0]
        return tag, buf[i + 4:i + 4 + n], i + 4 + n
    if tag == 8:
        s, i = _str(buf, i)
        return tag, s, i
    if tag == 9:
        item = buf[i]
        n = struct.unpack('>i', buf[i + 1:i + 5])[0]
        i += 5
        out = []
        for _ in range(n):
            if item == 0:
                out.append(None)
                continue
            # a list's items carry no name; re-parse by wrapping the item id in front
            t, v, i = _anonymous(buf, i, item)
            out.append(v)
        return tag, out, i
    if tag == 10:
        out = {}
        while True:
            t = buf[i]
            if t == 0:
                return tag, out, i + 1
            k, i = _str(buf, i + 1)
            t2, v, i = nbt_named(buf, i, t)
            out[k] = v
    if tag == 11:
        n = struct.unpack('>i', buf[i:i + 4])[0]
        vals = list(struct.unpack('>%di' % n, buf[i + 4:i + 4 + 4 * n]))
        return tag, vals, i + 4 + 4 * n
    raise ValueError("tag %d" % tag)


def nbt_named(buf, i, tag):
    """Parse a tag whose name was already consumed."""
    if tag == 1:
        return tag, struct.unpack('>b', buf[i:i + 1])[0], i + 1
    if tag == 2:
        return tag, struct.unpack('>h', buf[i:i + 2])[0], i + 2
    if tag == 3:
        return tag, struct.unpack('>i', buf[i:i + 4])[0], i + 4
    if tag == 4:
        return tag, struct.unpack('>q', buf[i:i + 8])[0], i + 8
    if tag == 5:
        return tag, struct.unpack('>f', buf[i:i + 4])[0], i + 4
    if tag == 6:
        return tag, struct.unpack('>d', buf[i:i + 8])[0], i + 8
    if tag == 7:
        n = struct.unpack('>i', buf[i:i + 4])[0]
        return tag, buf[i + 4:i + 4 + n], i + 4 + n
    if tag == 8:
        s, j = _str(buf, i)
        return tag, s, j
    if tag == 9:
        item = buf[i]
        n = struct.unpack('>i', buf[i + 1:i + 5])[0]
        i += 5
        out = []
        for _ in range(n):
            if item == 0:
                out.append(None)
                continue
            t, v, i = _anonymous(buf, i, item)
            out.append(v)
        return tag, out, i
    if tag == 10:
        out = {}
        while True:
            t = buf[i]
            if t == 0:
                return tag, out, i + 1
            k, i = _str(buf, i + 1)
            t2, v, i = nbt_named(buf, i, t)
            out[k] = v
    if tag == 11:
        n = struct.unpack('>i', buf[i:i + 4])[0]
        return tag, list(struct.unpack('>%di' % n, buf[i + 4:i + 4 + 4 * n])), i + 4 + 4 * n
    raise ValueError("tag %d" % tag)


def _anonymous(buf, i, tag):
    """A list item: same payload shapes as nbt_named, but the tag id is already known."""
    return nbt_named(buf, i, tag)


def chunk_blocks(data):
    """Yield (cx, cz, {y: bytes(4096)}) from one decompressed chunk payload."""
    tag, root, _ = nbt(data)
    if not isinstance(root, dict):
        return
    level = root.get('Level') or root
    xpos = level.get('xPos')
    zpos = level.get('zPos')
    for sec in level.get('Sections') or []:
        if not isinstance(sec, dict):
            continue
        y = sec.get('Y')
        blocks = sec.get('Blocks')
        if y is None or blocks is None:
            continue
        yield xpos, zpos, int(y), bytes(b & 0xFF for b in blocks)


def regions(world_dir):
    d = os.path.join(world_dir, 'region')
    if not os.path.isdir(d):
        return
    for name in sorted(os.listdir(d)):
        if not name.endswith('.mca'):
            continue
        raw = open(os.path.join(d, name), 'rb').read()
        if len(raw) < 8192:
            continue
        for k in range(1024):
            off = struct.unpack('>I', b'\x00' + raw[k * 4:k * 4 + 3])[0]
            if off == 0:
                continue
            start = off * 4096
            if start + 5 > len(raw):
                continue
            length = struct.unpack('>I', raw[start:start + 4])[0]
            comp = raw[start + 4]
            body = raw[start + 5:start + 4 + length]
            try:
                data = zlib.decompress(body) if comp == 2 else (body if comp == 3 else None)
            except Exception:
                continue
            if data:
                yield data


IDS = {1: 'stone', 2: 'grass', 3: 'dirt', 4: 'cobblestone', 5: 'planks', 13: 'gravel', 17: 'log', 18: 'leaves',
       20: 'glass', 35: 'wool', 41: 'gold', 42: 'iron', 44: 'slab', 45: 'bricks', 49: 'obsidian', 54: 'chest',
       57: 'diamond', 87: 'netherrack', 89: 'glowstone', 95: 'stained glass', 98: 'stone brick', 126: 'wood slab',
       128: 'sandstone stairs', 139: 'cobble wall', 155: 'quartz', 156: 'quartz stairs', 159: 'stained clay',
       160: 'stained pane', 162: 'dark oak log', 163: 'acacia stairs', 168: 'prismarine', 179: 'red sandstone',
       251: 'concrete', 252: 'concrete powder', 205: 'purpur'}


def scan(world_dir):
    ycount = Counter()
    ids = Counter()
    chunk_set = set()
    total = 0
    for data in regions(world_dir):
        for cx, cz, y, blocks in chunk_blocks(data):
            nz = sum(1 for b in blocks if b)
            if not nz:
                continue
            chunk_set.add((cx, cz))
            total += nz
            ycount[y] += nz
            for b in blocks:
                if b:
                    ids[b] += 1
    if not chunk_set:
        print("%s: no readable chunks" % world_dir)
        return
    xs = [c[0] for c in chunk_set]
    zs = [c[1] for c in chunk_set]
    print("%-13s chunks=%4d x=[%5d..%5d] z=[%5d..%5d] solid=%d" %
          (os.path.basename(world_dir.rstrip('/')), len(chunk_set),
           min(xs) * 16, max(xs) * 16 + 15, min(zs) * 16, max(zs) * 16 + 15, total))
    bands = ", ".join("y%d:%d" % (y * 16, n) for y, n in ycount.most_common(4))
    print("%-13s bands %s" % ("", bands))
    print("%-13s blocks %s" % ("", ", ".join("%s:%d" % (IDS.get(i, str(i)), n) for i, n in ids.most_common(8))))


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    scan(sys.argv[1])
