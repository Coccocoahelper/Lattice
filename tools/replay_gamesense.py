#!/usr/bin/env python3
"""Game-sense metrics from Lattice replay files, per player.

Usage: python3 tools/replay_gamesense.py Sovacos run/plugins/lattice-replay/replays/games/mini*.jsonl

The recorder writes one json line per sample: {"t": ms, "p": [[name, team, x, y, z, yaw, pitch, hp, ..], ...]}
plus {"t": ms, "b": [x, y, z, blockId, .., player]} for a placed block. Both a human replay and a bot replay use
the same format, so the same numbers can be put side by side.

Metrics per player:
  void     number of separate falls that went below the void line (one per death, not per sample)
  deaths   respawns: hp back to full after being 0, or a teleport to spawn
  kills    other players whose death happens within 4 blocks of this player during their last second
  blocks   blocks placed per minute
  roam     share of the game spent more than 30 blocks from the player's own spawn
"""
import json, sys, math, glob, os, collections

VOID_Y = 45.0
NEAR = 4.0


def load(path):
    rows = []
    for line in open(path, errors='replace'):
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except Exception:
            continue                      # a replay cut off mid-line
    return rows


def analyse(path, who):
    rows = load(path)
    if not rows:
        return None
    head = rows[0]
    dur = max((r.get('t', 0) for r in rows), default=0) / 1000.0
    samples = []                          # (t, x, y, z, hp)
    blocks = 0
    other = collections.defaultdict(list)  # name -> [(t,x,y,z,hp)]
    for r in rows:
        t = r.get('t', 0)
        if 'p' in r:
            for e in r['p']:
                if len(e) < 8:
                    continue
                name, x, y, z, hp = e[0], float(e[2]), float(e[3]), float(e[4]), float(e[7])
                if name == who:
                    samples.append((t, x, y, z, hp))
                else:
                    other[name].append((t, x, y, z, hp))
        if 'b' in r and len(r['b']) >= 7 and who in str(r['b'][6]):
            blocks += 1
    if len(samples) < 20:
        return None
    # spawn: median of the first two seconds after the game clock leaves the lobby height
    start = [s for s in samples if s[2] < 100]
    if not start:
        start = samples
    sx = sorted(s[1] for s in start[:40])
    sz = sorted(s[3] for s in start[:40])
    spawn = (sx[len(sx) // 2], sz[len(sz) // 2]) if sx else (0.0, 0.0)

    void = 0
    depth = False
    deaths = 0
    prev_hp = None
    for t, x, y, z, hp in samples:
        if y < VOID_Y:
            if not depth:
                void += 1
                depth = True
        elif y > VOID_Y + 5:
            depth = False
        if prev_hp is not None and hp < prev_hp and hp <= 0:
            deaths += 1
        prev_hp = hp

    kills = 0
    for name, pts in other.items():
        was_alive = True
        for i, (t, x, y, z, hp) in enumerate(pts):
            if was_alive and hp <= 0:
                near = any(math.hypot(q[1] - x, q[3] - z) <= NEAR and abs(q[0] - t) < 1500 for q in samples)
                if near:
                    kills += 1
                was_alive = False
            if hp > 0:
                was_alive = True

    far = sum(1 for t, x, y, z, hp in samples if math.hypot(x - spawn[0], z - spawn[1]) > 30)
    return {
        'game': os.path.basename(path).replace('.jsonl', ''),
        'who': who,
        'dur': dur,
        'void': void,
        'deaths': deaths,
        'kills': kills,
        'bpm': blocks / max(dur / 60.0, 0.1),
        'roam': far / len(samples),
        'n': len(samples),
    }


def report(paths, who):
    out = []
    for p in paths:
        m = analyse(p, who)
        if m:
            out.append(m)
    if not out:
        print(f"no replay of {who}")
        return
    print(f"=== {who}: {len(out)} replays ===")
    print("game\tdur_s\tvoid\tdeaths\tkills\tblocks/min\troam%")
    for m in sorted(out, key=lambda r: r['game']):
        print("%s\t%.0f\t%d\t%d\t%d\t%.1f\t%.0f" % (m['game'], m['dur'], m['void'], m['deaths'], m['kills'],
                                                    m['bpm'], m['roam'] * 100))
    vg = sum(m['void'] for m in out) / len(out)
    pg = sum(m['kills'] for m in out) / len(out)
    dg = sum(m['deaths'] for m in out) / len(out)
    bpm = sum(m['bpm'] for m in out) / len(out)
    roam = sum(m['roam'] for m in out) / len(out)
    print(f"--- mean: void/game {vg:.2f}  deaths/game {dg:.2f}  kills/game {pg:.2f}  blocks/min {bpm:.1f}  roam {roam*100:.0f}%")


if __name__ == '__main__':
    who = sys.argv[1]
    paths = []
    for a in sys.argv[2:]:
        paths.extend(sorted(glob.glob(a)))
    report(paths, who)
