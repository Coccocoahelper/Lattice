#!/usr/bin/env python3
"""Classify a folder of transcripts as usable / unusable.

A byte-size threshold is not enough: music-heavy videos get auto-captions that are a handful of
repeated ASR words ("Heat. Heat. Heat."), which pass any size check and contain nothing.

A transcript is usable when it has at least MIN_TOKENS alphabetic words of 3+ characters and the
ratio of distinct to total words is at least MIN_UNIQUE_RATIO. Human prose on these videos sits at
0.15-0.39; ASR noise on a music video sits at 0.07.

usage: usable.py <dir> [--json]
"""
import json
import os
import re
import sys

MIN_TOKENS = 200
MIN_UNIQUE_RATIO = 0.12


def classify(text):
    clean = re.sub(r"\[[^\]]*\]", "", text)          # [music] [snorts] are not words
    toks = [w.strip('.,!?"').lower() for w in clean.split()]
    toks = [w for w in toks if len(w) > 2 and w.isalpha()]
    if len(toks) < MIN_TOKENS:
        return "short", len(toks), 0.0
    ratio = len(set(toks)) / len(toks)
    return ("usable" if ratio >= MIN_UNIQUE_RATIO else "noise"), len(toks), ratio


def main(argv):
    d = argv[1] if len(argv) > 1 else "."
    out = {}
    for f in sorted(os.listdir(d)):
        if not f.endswith(".txt"):
            continue
        text = open(os.path.join(d, f), encoding="utf-8", errors="replace").read()
        kind, n, ratio = classify(text)
        out[f[:-4]] = {"kind": kind, "tokens": n, "ratio": round(ratio, 3), "bytes": len(text)}
    if "--json" in argv:
        print(json.dumps(out, indent=1))
    else:
        for v, r in sorted(out.items(), key=lambda kv: kv[1]["ratio"]):
            print(f"{r['kind']:7} {r['tokens']:6} tok  {r['ratio']:.3f}  {v}")
    counts = {}
    for r in out.values():
        counts[r["kind"]] = counts.get(r["kind"], 0) + 1
    print("\n" + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
