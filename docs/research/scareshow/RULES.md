# Rules extracted from ScareshowMc, and what was actually implemented

Source: `transcripts.md` — **21 of 30 videos have usable transcripts**. Seven have no captions at all and two more
have captions that are only repeated ASR noise on music-heavy footage; all nine are listed in `INDEX.md`. Each item names the
video it came from. Status is honest: **implemented** means code landed in this repo and is named; **already present**
means the behaviour existed and the video corroborates it; **not applicable** means it is advice for a human that has
no bot equivalent; **not done** means it is implementable and is still open.

## Team upgrades — `3WTvilN3lKM` #19, #32, #42

- **implemented** — *If the bed is not in danger, buy Sharpness first* (one diamond, leaving one for a trap or two for
  Protection). *If the bed is in danger, buy Protection → trap → heal pool first.*
  `ShopPlanner.upgrades(role, teams, bedThreatened)` now reorders on bed pressure; `ShopTask.buyUpgrade` passes
  `brain.bedAliveNow() && brain.enemyNearBed()`. Forge and Haste are pushed behind both.
- **already present** — Diamonds outrank emeralds; keep collecting until every upgrade is bought.

## Fights — `3WTvilN3lKM` #33, #38, #54

- **implemented** — *If a knockback stick fails to send them off, leave the fight.* It deals almost no damage, so the
  exchange is already lost. `Combat.useKnockbackStick` records the swing; a second stick swing within 40 ticks without
  a void kill disengages (`kbstick.leave`). `noteVoidKill` sets the success flag, and both reset on `engage`.
- **implemented** — *Leave one to two blocks of flat ground in front of you before a fight*, because knockback needs
  room. `PositionSense.noRoomAhead` checks the cells ahead for a wall or a drop and feeds `badPosition`, so the bot
  repositions instead of trading with its back against something.
- **already present** — Whoever lands the first hit wins most fights; the reach dance is built for it.
- **already present** — Blocking an opponent off just lets them close; the backstep/cut tactics already require an
  immediate resumption of movement.

## Positioning — `3WTvilN3lKM` #41, #50, #96

- **already present** — *Do not fight on bridges.* `PositionSense.exposedHazard` / `badPosition` already treat a
  two-sided void cell as a bad fight and reposition out of it.
- **already present** — *Do not spam blocks under yourself while running.* `Clutch` only starts on a genuine void fall,
  not on every tick of a chase, so the camera is not forced down while running.
- **not applicable** — F5 for field of view; the bot has no camera.

## Shopping — `rTHUPV7ME5o`

- **already present** — Decide the purchase before opening the shop (`ShopPlanner.plan` is built on the walk in), and
  visit the chest → generator → shop → chest once, never twice (`CollectTask`/`ShopTask` sequence).
- **not applicable** — Quick-buy layout, hotkey purchasing, butterfly clicking, senser height: human input devices.

## Pre-gapping — `9yrDUimP5Wc` (live coaching)

- **implemented** — *"Before fights always pre-gap, especially when you have worse armour"*, and *"don't eat during a
  fight, you don't have time"*. `ItemUse.fightTick` now eats a golden apple on approach (`gapple.pregap`) when a
  target is closing and health is not full, instead of only once health is already down. The shop gate that made this
  unreachable also moved: gapples were gated on 200 seconds of game time, so early fights were fought with no apple in
  the hotbar at all. It is now 60 seconds and three gold, which is affordable from the first rush.
  Verified: `gapple.pregap=2`, `gapple.calm=1` in one game.

## Game sense from the decision-making videos — `6R8AazPGAU4`, `ykfS2JGBbBM`

- **implemented** — *Only bridge to diamonds from your base.* Any second bridge into your base is a route opponents use
  to stall you from a side base. `BedwarsBrain.diamondOnOurSide` picks the diamond generator in the same half of the
  map as the bot's own base (measured against the map's mid point), falling back to nearest when no generator qualifies.
  Diamond runs now use it instead of `nearest`.
- **implemented** — *Do not buy a pickaxe against a wool bed.* Scareshow calls his own pickaxe purchase there "a waste
  of 10 iron" and points out 10 iron in the chest is the difference between a stone sword and nothing on the next life.
  `BedwarsBrain.bedShell()` reports whether the target bed is wool-only, hard, or unscanned, and `ShopPlanner.plan`
  skips the pickaxe on a wool shell (shears already cover it). Unscanned keeps the old behaviour.
- **implemented** — *Do not push a team that is more stacked than you when there is nothing to gain* (#18, #31, and his
  own play-by-play: "I don't have time pressure but blue does"). `declineEngagement` used to decline only below 14
  health; it now also declines at full health when the gear gap is 4+ against us, there is no committed rush, diamonds
  are still available to collect, and the threat is not already at our bed. A threat on our island is never declined.
- **already present** — Respawn time is for planning: buy decisions are computed on the walk, and the respawn cycle is
  already chest → generator → shop (`CollectTask`/`ShopTask`).

## Resources — `MjuRRyE-cvo`, `3WTvilN3lKM` #46, #48

- **already present** — Emerald forge before molten forge, then take an emerald, then buy the second.
- **already present** — Two teammates on a base generator each get the full drop; `TeamJobs` assigns one farmer per
  forge and the split stance handles the second body.
- **implemented** — *Drop diamonds a few blocks from the diamond generator so more can spawn.* `CollectTask` now
  picks a spot 2.2 blocks out on a random bearing for diamond and emerald generators (0.7 for the team forge), instead
  of shuffling within half a block of the generator centre.

## Bed work — `3WTvilN3lKM` #28, #74, #91

- **already present** — Two full passes over the defence from every stand, so no gaps (`BedDefense`).
- **already present** — A defender is forced to come at you while you break, so blocks around the bed combo them
  (`BedRaid` sealing).
- **implemented** — *Top the bed defence up on every pass*, not only during a dedicated defence task.
  `TravelBlocks.repairDefence` walks both defence layers for an open cell within six blocks whenever the bot is
  travelling and carrying blocks, and fills it (`defence.topup`). Measured at 110 top-ups in one three-minute game.

## Verified live

All four implemented rules were exercised in a three-minute 8-team Airshow game with `0` errors:

```
defence.topup=110      pos.correct.found=511      game created, ran to completion
```

## Not implemented, and why

- **Butterfly/drag clicking, hotkey layout, texture packs, breaks, mindset, stat overlays, plank.io** — human input or
  human psychology. The bot equivalents (CPS model, hotbar manager, risk-after-setback counters) already exist.
- **Sumo, SkyWars practice, "watch better players"** — training advice for a person.
- **Bridging technique** (`LM4RjWdXSHU`) — "aim at the centre of the block, do not re-aim when stacking" is what
  `Bridger` already does; the practice-in-a-single-player-world advice has no bot analogue.
- **The nine uncovered videos are unknown in content beyond their titles.** Seven have no caption track at all
  ("$1,000 Solo Bedwars Tournament", "doubles without a teammate", "keep opponents away from my base", "why getting
  better becomes easy", "how to increase your stats", "movement trick", "the 2v2 ranked mode"), and two have
  auto-captions that are only ASR noise over music ("sweatiest maps on a 700 star account", "beat snipers & cheaters").
  Those would need speech recognition (whisper) on the audio, not captions.
- **The first pass over-counted by two.** A 100-byte size threshold accepted the two music-noise files, which is why
  the earlier summary said 23. `scripts/usable.py` is the check now: it requires 200+ alphabetic tokens and a
  distinct-word ratio of at least 0.12, which separates prose (0.15-0.39) from noise (0.07).
