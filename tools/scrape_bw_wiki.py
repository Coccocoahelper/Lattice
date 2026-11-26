#!/usr/bin/env python3
"""Scrape Bed Wars map metadata from hypixel.minecraft.wiki.

Two things come out of this, and they come from two different pages:

- **Build limits** live on each map's own page, inside a `wikitable` whose headers are
  `Minimum Build Limit`, `Maximum Build Limit`, `Build Radius`. The map page is `/w/<Name>_(Bed_Wars)`.
- **Release dates** live on the Bed Wars Maps index, `/w/Bed_Wars:Maps`, as a table of
  `Image | Name | Release Date` (with `rowspan` on the date cell, so one date covers several rows).

The index links each name at its map page, which is what makes this one pass instead of a guess at URL
shapes.

Values that are not numbers are dropped rather than coerced: several maps carry `?` for a field nobody
filled in, and a `?` written into a map.yml is a build limit of zero.
"""
import html
import json
import re
import sys
import time
import subprocess

BASE = "https://hypixel.minecraft.wiki"
INDEX = BASE + "/w/Bed_Wars:Maps"



def get(url, tries=3):
    """Fetch through curl.

    <p>urllib is refused with a 403 whatever User-Agent it sends - the wiki fingerprints the client, not just the header
    (measured: `Mozilla/5.0` via urllib 403, the same string via curl 200, `curl/8.0` via urllib 200). Shelling out to
    curl is the honest fix; spoofing a browser stack to get past a filter is not worth it for a metadata scrape.
    """
    for n in range(tries):
        try:
            r = subprocess.run(["curl", "-sS", "--max-time", "60", "-A", "curl/8.0", url],
                               capture_output=True, text=True, timeout=90)
            if r.returncode == 0:
                return r.stdout
            if "404" in (r.stderr or ""):
                return None
        except Exception:
            pass
        time.sleep(1 + n)
    return None


def text_of(fragment):
    return " ".join(html.unescape(re.sub(r"<[^>]+>", " ", fragment)).split())


def clean_date(s):
    """'May 20, 2026[3]' -> '2026-05-20'. Returns None when the cell is not a date."""
    if not s:
        return None
    s = re.sub(r"\[\s*\d+\s*\]", "", s)           # footnote markers
    s = s.strip().strip("[]").strip()
    for fmt in ("%B %d, %Y", "%b %d, %Y", "%d %B %Y", "%d %b %Y"):
        try:
            return time.strftime("%Y-%m-%d", time.strptime(s, fmt))
        except ValueError:
            continue
    return None


def parse_index(page):
    """{map name: {'date': iso, 'page': url}} from the Bed Wars Maps index."""
    out = {}
    for row in re.findall(r"<tr[^>]*>(.*?)</tr>", page, re.S):
        cells = re.findall(r"<t[hd][^>]*>(.*?)</t[hd]>", row, re.S)
        if len(cells) < 3:
            continue
        link = re.search(r'href="(/w/[^"]+)"[^>]*title="([^"]+)"', cells[1])
        if not link:
            continue
        href, title = link.group(1), html.unescape(link.group(2))
        # a title like "Atlas Prime (Bed Wars)" is the map's own page; the index also links other things
        if "(Bed Wars)" not in title:
            continue
        date = clean_date(text_of(cells[2]))
        # a rowspan'd date cell belongs to the first row only; later rows in the same group repeat it via rowspan, so
        # a missing date here is "same as the previous map in the group", which the caller fills in
        out[title] = {"date": date, "page": BASE + href}
    return out


def parse_limits(page):
    """{'min': int|None, 'max': int|None, 'radius': int|None} from a map's own page."""
    out = {"min": None, "max": None, "radius": None}
    if not page:
        return out
    for table in re.findall(r'<table class="wikitable".*?</table>', page, re.S):
        heads = [text_of(h) for h in re.findall(r"<th[^>]*>(.*?)</th>", table, re.S)]
        rows = re.findall(r"<tr[^>]*>(.*?)</tr>", table, re.S)
        if not any("Build Limit" in h for h in heads):
            continue
        # the first data row after the header row
        for row in rows:
            cells = re.findall(r"<td[^>]*>(.*?)</td>", row, re.S)
            if len(cells) != len(heads):
                continue
            for h, c in zip(heads, cells):
                v = text_of(c)
                num = re.match(r"^-?\d+$", v)
                if not num:
                    continue
                n = int(v)
                if "Minimum Build Limit" in h:
                    out["min"] = n
                elif "Maximum Build Limit" in h:
                    out["max"] = n
                elif "Build Radius" in h:
                    out["radius"] = n
            break
    return out


def main():
    only = sys.argv[2] if len(sys.argv) > 2 else None
    page = get(INDEX)
    if not page:
        sys.exit("could not fetch " + INDEX)
    index = parse_index(page)
    print("%d map pages found in the index" % len(index), file=sys.stderr)

    # rowspan: walk the index in order and carry the last date forward within a group
    ordered = []
    for row in re.findall(r"<tr[^>]*>(.*?)</tr>", page, re.S):
        cells = re.findall(r"<t[hd][^>]*>(.*?)</t[hd]>", row, re.S)
        if len(cells) < 3:
            continue
        link = re.search(r'href="(/w/[^"]+)"[^>]*title="([^"]+)"', cells[1])
        if not link or "(Bed Wars)" not in html.unescape(link.group(2)):
            continue
        ordered.append((html.unescape(link.group(2)), BASE + link.group(1), clean_date(text_of(cells[2]))))

    last_date = None
    out = {}
    for title, url, date in ordered:
        if date:
            last_date = date
        out[title] = {"page": url, "date": date or last_date}

    results = {}
    names = [n for n in out if not only or only.lower() in n.lower()]
    for i, name in enumerate(names):
        rec = dict(out[name])
        rec.update(parse_limits(get(rec["page"])))
        results[name] = rec
        if i % 20 == 0:
            print("  %d/%d" % (i, len(names)), file=sys.stderr)
        time.sleep(0.15)

    json.dump(results, sys.stdout, indent=1, sort_keys=True)
    print(file=sys.stderr)
    got = sum(1 for r in results.values() if r["min"] is not None and r["max"] is not None)
    print("%d maps, %d with both build limits, %d with a date"
          % (len(results), got, sum(1 for r in results.values() if r["date"])), file=sys.stderr)


if __name__ == "__main__":
    main()
