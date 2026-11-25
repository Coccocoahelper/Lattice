# Hypixel map archive: what was imported

Source: MEGA folder `zigm2SIb` (`tYUOFzpdng-PBsOx5cr85Q`), a community "Hypixel Map Archive".
Recovered and imported with `tools/mega_ls.py`, `tools/mega_get.py`, `tools/import_maps.py`.

## Counts, verified per pool against the archive listing

| family | served | pool completeness |
|---|---|---|
| BedWars | 265 | Solo-Doubles 115 (108 listed + seasonal duplicates), Triples-Quads 99/99, 4v4 6/6, Voidless 5/5, Castle 2/2, One Block 1/1, Capture 1/1 |
| SkyWars | 283 | Standard 171/171, Ranked 43/43, Mega 19/19, Mini 16/16 |
| Duels | 118 | Standard 33, Sumo 11, BedWars Duels 12, SkyWars Duels 13 — all complete |

**634 templates load in ~1.5 s.** Before this work the server had 6.

Only two files in the whole archive are not zips (a Bow Spleef note and a Standard Duels note, both `.txt`); they are
skipped.

## What plays, measured

- **BedWars — yes**, with no authoring. `bedwars_eight_two on Keep` / `Acropora` / `Apollo` all create running games.
  A map without team points falls back to generated islands.
- **SkyWars — the two shipped maps only.** `solo_normal on Ancient` fills to 9/12 and starts. The imported ones throw
  `Map X has no island cages`: `SkywarsGame` requires `island.N.cage` for every island and cages cannot be derived from
  blocks, because two arenas of the same shape put them in different places.
- **Duels — yes.** `DuelsModule.create` accepts a plain `MapTemplate` capture from the map folder.

## The authoring gap, stated plainly

The archive ships **worlds only**. No team spawns, bed positions, shop/upgrade NPCs, generators, island cages or mid
chests. `import_maps.py` therefore writes a `map.yml` with the family, a team count derived from the *queue* folder, and
a `waiting_lobby` hint derived from the geometry — and a comment saying what is missing. It does not guess coordinates.

To make the rest playable: author the points per map (`/h2020 map set`, or by hand), roughly 12 island cages plus mid
chests for SkyWars, and 8 team spawn/bed/shop/generator sets for BedWars. The three BedWars maps that shipped with
Swofty coordinates (Airshow, Lighthouse, Rooftop, Speedway — 40 points each) are the template for what that looks like.

## Traps this cost, all written into the skill

- MEGA `k` can carry several `key-id:key` pairs; taking the first yields handles instead of names and **ciphertext on
  disk** with no error. Test each pair and keep the one whose attributes parse.
- The archive `level.dat` is stripped (`SpawnY 256`), so the stored spawn is the world ceiling, not the arena.
- SkyWars Standard serves Solo (12 islands) and Teams (8) from one world; the count belongs to the queue.
- `cp -rn` silently skips existing directories — verify with `find -name map.yml | wc -l`, not `ls | wc -l`.
