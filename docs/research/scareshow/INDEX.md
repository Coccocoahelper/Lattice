# ScareshowMc — channel index

Channel: https://www.youtube.com/@ScareshowMc (`UCcRzfXOtaGnOqACoDMaW6Ow`). 30 videos enumerated through the
InnerTube browse endpoint (2 pages).

**21 of 30 have usable transcripts.** Nine do not, for two different reasons:

- **7 have no captions at all** — the transcript site returns its explicit "no captions / unavailable" page. Each
  was retried three times against a fresh solver session; the result did not change.
- **2 have captions that are unusable** — music-heavy videos whose auto-captions are a handful of repeated ASR
  words (`Lkg4WGD7MmA`: 89 words, 4 unique — "Heat. Heat. Heat."). Counting them as covered would be dishonest.

The first pass reported "23" because a 100-byte size threshold let those two through. Uniqueness ratio and token
count separate them (`scripts/usable.py` in this directory).

| # | title | id | transcript |
|---|---|---|---|
| 1 | I played in the $1.000 Solo Bedwars Tournament | `GjnpxjAY3DQ` | **none — no captions** |
| 2 | I played doubles Bedwars without a teammate (and won every game) | `tRzFLAdYA-g` | yes (8,474 B) |
| 3 | How good is qBedwars at Bedwars? (game analysis) | `gHFP8-ivTtE` | yes (18,938 B) |
| 4 | Scareshow's 1k pack + settings release (beating cheaters & sweats) | `89O4koTmVR4` | yes (13,779 B) |
| 5 | How I shop 5x quicker in Bedwars | `rTHUPV7ME5o` | yes (6,046 B) |
| 6 | 5 Bedwars players try to unite a brain cell | `JtVi0nHJ-vk` | yes (24,574 B) |
| 7 | How I beat cheaters in Bedwars (gamesense + team coordination) | `ClJuEyrg3oM` | yes (24,840 B) |
| 8 | I watched Purpled's Bedwars games (to see how good he is) | `r1iPbb3ds-M` | yes (21,939 B) |
| 9 | I played with the chillest Bedwars Youtuber ft. @danlamanmc | `X_bqFX8vmyo` | yes (12,380 B) |
| 10 | How I keep opponents away from my base (and never lose my bed) | `ZlEbQ_sWVXo` | **none — no captions** |
| 11 | How I beat cheaters in 3v3v3v3 Bedwars | `g9X6h4kmvgw` | yes (3,848 B) |
| 12 | How I control every Bedwars game (live decision making) | `6R8AazPGAU4` | yes (7,373 B) |
| 13 | Why getting better at Bedwars becomes easy at a certain point | `sT3AvNh3WhM` | **none — no captions** |
| 14 | Why you throw every Bedwars game (and how to fix it) | `tk78eApvVFs` | yes (5,266 B) |
| 15 | How to increase your stats in Bedwars | `Lz4usKiUQu0` | **none — no captions** |
| 16 | How changing my hotkeys made me 30% better at Bedwars | `EuDV0nZG8ME` | yes (8,421 B) |
| 17 | Every Bedwars player should learn this movement trick | `usSxCbitGM4` | **none — no captions** |
| 18 | How I make decisions ingame (Bedwars analysis) | `ykfS2JGBbBM` | yes (11,157 B) |
| 19 | I played the sweatiest Bedwars maps on a 700 Star 20 FKDR account | `Lkg4WGD7MmA` | **captions unusable (music/ASR)** |
| 20 | How I speedbridge really fast in Bedwars (and never fall) | `LM4RjWdXSHU` | yes (6,077 B) |
| 21 | I played the new 2v2 ranked Bedwars mode (and won every game) | `4h-0ux-xabg` | yes (12,547 B) |
| 22 | I queued Hypixel Bedwars and beat Snipers & Cheaters | `qsAktijQ51Q` | **captions unusable (music/ASR)** |
| 23 | I helped my friend to win in Bedwars (live coaching session) | `9yrDUimP5Wc` | yes (19,210 B) |
| 24 | Chill Doubles Bedwars (on a 200+ FKDR account) | `IbYebO95BOY` | **none — no captions** |
| 25 | How I got a 204 FKDR in Bedwars (and you can too) | `i9o6XFyccYc` | yes (11,912 B) |
| 26 | Clutching In Bedwars Against Cheaters | `iq6YplVDR4c` | yes (15,303 B) |
| 27 | 101 Tips to get Better at Bedwars | `3WTvilN3lKM` | yes (21,723 B) |
| 28 | Top 0.1% Bedwars gameplay (on a 125+ FKDR account) | `s2CFCq0RQ2k` | **none — no captions** |
| 29 | How I get 76% more resources in Bedwars (and win every game) | `MjuRRyE-cvo` | yes (11,039 B) |
| 30 | How I got a 154 winstreak in Bedwars (and you can too) | `ngG-62QepXM` | yes (11,810 B) |

## Re-running

```bash
CHANNEL=UCcRzfXOtaGnOqACoDMaW6Ow
python3 enum.py          # InnerTube browse + continuation -> ids.json
# then flare_transcript.py per id, 2 s apart
```
