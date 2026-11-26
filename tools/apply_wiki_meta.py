#!/usr/bin/env python3
"""Write scraped wiki metadata into the imported maps' map.yml files.

Two fields, both optional and both additive:

    build-floor: 60       the minimum build height (absent before this; the game then uses 0)
    added: 2017-03-17     the date the map entered the rotation, ISO

Neither overwrites a value a human already set: a hand-authored map that has its own build-limit keeps it.
The point of `added` is the map list, which can then show when each map appeared in the game.

Matching is by normalised name, because the wiki writes "Atlas Prime" and a folder is `atlas_prime`. A
name that matches nothing is reported, not guessed at - "Hollow Hills" and "Hollow" are different maps.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def norm(s):
    s = re.sub(r"\s*\(Bed Wars\)\s*", "", s, flags=re.I)
    s = re.sub(r"\s*\(.*?Reskin\)\s*", "", s, flags=re.I)
    s = re.sub(r"[^A-Za-z0-9]+", "_", s).strip("_").lower()
    return s


def main():
    wiki_path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/bw_wiki.json"
    wiki = json.load(open(wiki_path))
    by_norm = {norm(k): v for k, v in wiki.items()}

    total = written = 0
    unmatched = []
    for family in ("bedwars",):
        base = os.path.join(ROOT, "maps-import", family)
        if not os.path.isdir(base):
            continue
        for d in sorted(os.listdir(base)):
            y = os.path.join(base, d, "map.yml")
            if not os.path.isfile(y):
                continue
            total += 1
            rec = by_norm.get(norm(d))
            if rec is None:
                unmatched.append(d)
                continue
            text = open(y).read()
            changed = []

            if rec.get("min") is not None and "build-floor:" not in text:
                text = add_scalar(text, "build-floor", str(rec["min"]))
                changed.append("floor")
            if rec.get("max") is not None:
                # only fill a ceiling the map has not already got from a real config
                m = re.search(r"^build-limit:\s*(\S+)", text, re.M)
                if m is None or m.group(1) in ("100", "255"):
                    text = set_scalar(text, "build-limit", str(rec["max"]))
                    changed.append("ceiling")
            if rec.get("date"):
                if re.search(r"^added:", text, re.M):
                    text = set_scalar(text, "added", rec["date"])
                else:
                    text = add_scalar(text, "added", rec["date"])
                changed.append("date")

            if changed:
                open(y, "w").write(text)
                written += 1

    print("%d maps considered, %d updated, %d without a wiki page" % (total, written, len(unmatched)))
    if unmatched:
        print("no wiki match: " + ", ".join(unmatched[:14]) + (" ..." if len(unmatched) > 14 else ""))


def set_scalar(text, key, value):
    return re.sub(r"^%s:.*$" % re.escape(key), "%s: %s" % (key, value), text, count=1, flags=re.M)


def add_scalar(text, key, value):
    """Insert after build-limit if present, else after teams, so the header stays readable."""
    line = "%s: %s" % (key, value)
    for anchor in ("build-limit", "teams", "family"):
        m = re.search(r"^%s:.*$" % re.escape(anchor), text, re.M)
        if m:
            return text[:m.end()] + "\n" + line + text[m.end():]
    return text + "\n" + line + "\n"


if __name__ == "__main__":
    main()
