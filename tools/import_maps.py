#!/usr/bin/env python3
"""Turn downloaded Hypixel Map Archive zips into maps-import/<family>/<id>/ trees.

Each zip holds one Anvil world directory (region/ + level.dat). Lattice wants:

    maps-import/<family>/<map-id>/
        map.yml          name, family, teams, build-limit
        world/           region/, level.dat, data/, DIM-1/, DIM1/

What this does NOT do is invent coordinates. The archive ships worlds without the arena configs, so a
converted map has no team spawns, no bed positions and no generators. `map.yml` therefore carries only
what can be derived from the world itself (its level.dat spawn and its size) plus the family. Points that
need to be authored are left out and reported, rather than filled with plausible numbers: a map with a
guessed bed coordinate is worse than a map that says it has none.

Usage:
    import_maps.py <zip-dir> [--family bedwars|skywars|duels] [--dry]
"""
import gzip
import io
import zlib
import os
import re
import shutil
import struct
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "maps-import")


# ---------------------------------------------------------------- NBT, minimally

def _read_tag(buf, i):
    t = buf[i]
    i += 1
    n = struct.unpack(">H", buf[i:i + 2])[0]
    i += 2
    return t, buf[i:i + n].decode("utf8", "replace"), i + n


def _payload(buf, i, t):
    if t == 1:
        return struct.unpack(">b", buf[i:i + 1])[0], i + 1
    if t == 2:
        return struct.unpack(">h", buf[i:i + 2])[0], i + 2
    if t == 3:
        return struct.unpack(">i", buf[i:i + 4])[0], i + 4
    if t == 4:
        return struct.unpack(">q", buf[i:i + 8])[0], i + 8
    if t == 5:
        return struct.unpack(">f", buf[i:i + 4])[0], i + 4
    if t == 6:
        return struct.unpack(">d", buf[i:i + 8])[0], i + 8
    if t == 7:
        n = struct.unpack(">i", buf[i:i + 4])[0]
        return buf[i + 4:i + 4 + n], i + 4 + n
    if t == 8:
        n = struct.unpack(">H", buf[i:i + 2])[0]
        return buf[i + 2:i + 2 + n].decode("utf8", "replace"), i + 2 + n
    if t == 9:
        et = buf[i]
        n = struct.unpack(">i", buf[i + 1:i + 5])[0]
        i += 5
        out = []
        for _ in range(n):
            v, i = _payload(buf, i, et)
            out.append(v)
        return out, i
    if t == 10:
        out = {}
        while True:
            tt = buf[i]
            if tt == 0:
                return out, i + 1
            _, name, j = _read_tag(buf, i)
            v, i = _payload(buf, j, tt)
            out[name] = v
    if t == 11:
        n = struct.unpack(">i", buf[i:i + 4])[0]
        i += 4
        out = list(struct.unpack(">%di" % n, buf[i:i + 4 * n]))
        return out, i + 4 * n
    raise ValueError("tag %d" % t)


def level_data(world_dir):
    """The Data compound of level.dat."""
    p = os.path.join(world_dir, "level.dat")
    d = open(p, "rb").read()
    raw = gzip.decompress(d) if d[:2] == b"\x1f\x8b" else d
    t, _, i = _read_tag(raw, 0)
    root, _ = _payload(raw, i, t)
    return root.get("Data", root)


# ---------------------------------------------------------------- names

def stage(fn):
    """The archive encodes the path in the filename: Family__Map.zip."""
    base = os.path.basename(fn)
    if base.endswith(".zip"):
        base = base[:-4]
    parts = [p for p in base.split("__") if p]
    return parts


def map_name_from(parts):
    """Last path element is the map name; strip a trailing reskin note."""
    if not parts:
        return None
    name = parts[-1]
    name = re.sub(r"\s*\(.*?Reskin\)\s*", " ", name, flags=re.I).strip()
    return name


def map_id(name):
    s = re.sub(r"[^A-Za-z0-9]+", "_", name).strip("_").lower()
    return s or "map"


def family_of(parts):
    joined = "/".join(parts).lower()
    if "bedwars" in joined:
        return "bedwars", "BEDWARS"
    if "skywars" in joined:
        return "skywars", "SKYWARS"
    if "duels" in joined:
        return "duels", "DUELS"
    return None, None


def teams_of(parts, family):
    """Team count from the folder the map came from. This is a property of the QUEUE, not the world."""
    joined = "/".join(parts).lower()
    if family == "bedwars":
        if "solo-doubles" in joined:
            return 8
        if "triples-quads" in joined:
            return 4
        if "4v4" in joined:
            return 4
        if "castle" in joined:
            return 2
    if family == "skywars":
        if "mega" in joined:
            return 12
        return 12          # standard/mini/ranked are all one-island-per-player layouts
    if family == "duels":
        return 2
    return 2


# ---------------------------------------------------------------- convert

def convert(zip_path, dry=False):
    parts = stage(zip_path)
    family, family_tag = family_of(parts)
    if family is None:
        return None, "not bedwars/skywars/duels: %s" % "/".join(parts)
    name = map_name_from(parts)
    if not name:
        return None, "no map name in %s" % "/".join(parts)
    mid = map_id(name)
    dest = os.path.join(OUT, family, mid)
    if os.path.isdir(dest) and not dry:
        return mid, "exists"
    tmp = tempfile.mkdtemp(prefix="mapimp_")
    try:
        with zipfile.ZipFile(zip_path) as z:
            z.extractall(tmp)
        # the world is the single directory containing level.dat
        world = None
        for dirpath, _, files in os.walk(tmp):
            if "level.dat" in files:
                world = dirpath
                break
        if world is None:
            return None, "no level.dat inside %s" % os.path.basename(zip_path)
        data = level_data(world)
        raw_spawn = (data.get("SpawnX", 0), data.get("SpawnY", 64), data.get("SpawnZ", 0))
        # a stripped level.dat gives 0/256/0, which is the world ceiling and outside every arena;
        # fall back to the geometry of the build when the stored spawn is unusable
        if raw_spawn[1] >= 255 or raw_spawn == (0, 256, 0):
            spawn = build_centre(world)
        else:
            spawn = raw_spawn
        # 1.8 worlds carry no DataVersion; anything with one is a modern world and must not be shipped as 1.8
        modern = "DataVersion" in data
        if dry:
            return mid, "would import %s (%s) spawn=%s%s" % (
                name, family, spawn, " MODERN" if modern else "")
        os.makedirs(dest, exist_ok=True)
        wdest = os.path.join(dest, "world")
        if os.path.isdir(wdest):
            shutil.rmtree(wdest)
        shutil.copytree(world, wdest)
        # a delivered world must not carry a stale lock
        for junk in ("session.lock", "level.dat_old"):
            j = os.path.join(wdest, junk)
            if os.path.exists(j):
                try:
                    os.remove(j)
                except OSError:
                    pass
        teams = teams_of(parts, family)
        write_map_yml(dest, name, family_tag, teams, spawn, parts)
        return mid, "imported %s (%s, %d teams) spawn=%s" % (name, family, teams, spawn)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def build_centre(world_dir):
    """Centre of the non-air columns, at the highest solid block near that centre.

    <p>Both axes are the midpoint of the columns that actually contain blocks, so a map built off-origin is still found.
    This is a placement hint for the waiting lobby, not a team spawn: those have to be authored.
    """
    xs, zs = [], []
    height_at = {}
    region = os.path.join(world_dir, "region")
    for fn in sorted(os.listdir(region)) if os.path.isdir(region) else []:
        if not fn.endswith(".mca"):
            continue
        m = re.match(r"r\.(-?\d+)\.(-?\d+)\.mca", fn)
        if not m:
            continue
        rx, rz = int(m.group(1)), int(m.group(2))
        try:
            with open(os.path.join(region, fn), "rb") as f:
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
                    if comp != 2:
                        continue
                    raw = zlib.decompress(body)
                    if b"Blocks" not in raw:
                        continue
                    # region header order is x + z*32 inside 16x16 chunks
                    cx, cz = rx * 32 + (i % 32), rz * 32 + (i // 32)
                    xs.append(cx * 16 + 8)
                    zs.append(cz * 16 + 8)
        except Exception:
            continue
    if not xs:
        return (0, 64, 0)
    cx = int(round(sum(xs) / float(len(xs))))
    cz = int(round(sum(zs) / float(len(zs))))
    # highest solid block in that column, from the top down
    y = 64
    for fn in sorted(os.listdir(region)):
        m = re.match(r"r\.(-?\d+)\.(-?\d+)\.mca", fn)
        if not m:
            continue
        rx, rz = int(m.group(1)), int(m.group(2))
        lx, lz = cx - rx * 512, cz - rz * 512
        if not (0 <= lx < 512 and 0 <= lz < 512):
            continue
        idx = (lx // 16) + (lz // 16) * 32
        try:
            with open(os.path.join(region, fn), "rb") as f:
                hdr = f.read(4096)
                if hdr[idx * 4 + 3] == 0:
                    break
                off = struct.unpack(">I", b"\x00" + hdr[idx * 4:idx * 4 + 3])[0]
                f.seek(off * 4096)
                ln = struct.unpack(">I", f.read(4))[0]
                comp = f.read(1)[0]
                if comp != 2:
                    break
                raw = zlib.decompress(f.read(ln - 1))
                t, _, pos = _read_tag(raw, 0)
                root, _ = _payload(raw, pos, t)
                lvl = root.get("Level", root)
                for sec in lvl.get("Sections", []):
                    blocks = sec.get("Blocks")
                    if not blocks:
                        continue
                    sy = sec.get("Y", 0) * 16
                    bx, bz = lx % 16, lz % 16
                    for yy in range(15, -1, -1):
                        if blocks[(yy * 16 + bz) * 16 + bx] != 0:
                            y = max(y, sy + yy + 1)
                            break
        except Exception:
            pass
        break
        # only the chunk containing the centre matters
    return (cx, y, cz)


def write_map_yml(dest, name, family_tag, teams, spawn, parts):
    lines = []
    lines.append("# Imported from the Hypixel Map Archive (MEGA folder zigm2SIb).")
    lines.append("# The archive ships the WORLD only: it carries no arena config, so team spawns, bed")
    lines.append("# positions, shop/upgrade NPCs and generators are NOT set here. Author them with")
    lines.append("# /h2020 map set or by hand; a guessed coordinate is worse than a missing one.")
    lines.append("# source path: %s" % "/".join(parts))
    lines.append('name: "%s"' % name.replace('"', '\\"'))
    lines.append("family: %s" % family_tag)
    lines.append("teams: [%d]" % teams)
    lines.append("build-limit: 100")
    lines.append("points:")
    lines.append("  waiting_lobby:")
    lines.append("    - {x: %s, y: %s, z: %s}" % (spawn[0] + 0.5, spawn[1], spawn[2] + 0.5))
    open(os.path.join(dest, "map.yml"), "w").write("\n".join(lines) + "\n")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dry = "--dry" in sys.argv
    src = args[0]
    ok = skipped = failed = 0
    for fn in sorted(os.listdir(src)):
        if not fn.endswith(".zip"):
            continue
        path = os.path.join(src, fn)
        try:
            mid, why = convert(path, dry=dry)
        except Exception as e:
            print("  FAIL %-60s %s" % (fn[:60], e))
            failed += 1
            continue
        if mid is None:
            print("  skip %-60s %s" % (fn[:60], why))
            skipped += 1
        else:
            print("  ok   %-28s %s" % (mid, why))
            ok += 1
    print("\n%d imported, %d skipped, %d failed" % (ok, skipped, failed))


if __name__ == "__main__":
    main()
