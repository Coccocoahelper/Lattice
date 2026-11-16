# Game sense: where the bots actually fall short

Measured with `tools/replay_gamesense.py` over the recorded replays (same recorder for humans and bots).

## Reference, real player

Sovacos, 11 Bed Wars replays: **1.8 void falls per game, 30.5 blocks placed per minute, 0.45 kills per game,
65% of the game more than 30 blocks from his own spawn.**

## Bots

| game | duration | blocks/min | roam | void/game | what changed |
|---|---|---|---|---|---|
| mini196A | 68 s | 1.8 | 82% | 0.0 | stuck opening |
| mini89G | 430 s | 1.6 | 97% | 0.5 | stuck opening |
| mini132G | 356 s | 6.5 | 93% | 1.8 | path search cost fixed |
| mini19G | 440 s | 6.2 | 86% | 7.4 | edge guard exempt without a bound (too loose) |
| mini148F | 213 s | 8.4 | 87% | 2.9 | bounded exemption, earlier tap crouch |
| mini304F | 199 s | 10.8 | 86% | 3.1 | crouch held while the lip lasts |

Human: 30.5 blocks/min, 1.8 void, 0.45 kills.

## What the opening was actually doing (fixed)

The task list read `rush-white` / `collect-iron` and the body never moved because the **navigator sat in
PLANNING**: `/bot navtrace` showed `state=PLANNING ticket=run age=97 exp=8287` for ninety seconds.

- A cross-map rush asked for 12000 nodes of A*; one node cost 9-17 us (CraftBlock per cell, a boxed
  HashMap of nodes, a per-cell hash cache, and 24-deep void column scans), so a single plan was 60+ ticks
  spread over the shared budget while `RushTask` gave up at 40 (`rush.nopath` 2209 in one session).
- Eight bots asking at once queued behind each other; the queue never drained, and every task that timed out
  asked again. That is the statue.

Fixes, measured from the server counters:

- `WorldView`: a 64k table classifies `(id<<4|data)` once; cells are read straight out of the chunk section
  array. `PathFinder`: long -> PathNode open addressing. `PathService`: shortest goal first, waiting buys
  priority, 700 us slices. `RushTask`: 3000-node cap, walk the best partial route.
- Result: `rush.nopath` 2209 -> 1 in a 150 s window; 8 of 8 bots leave spawn; blocks/min 1.6 -> 10.8.

## Bridging (the next blocker, partly addressed)

The motor's void guard cancels any step whose next cell has nothing under it - which is the whole approach
a bridge has to walk. `move.edgeguard` fired 74697 times; tap-crouch techniques stood 0.4 blocks short of
the lip until `giveUpTicks` ran out (`bridge.giveup.ninja` 464 against `bridge.done.ninja` 110).

- A bridge now marks its step as planned while `h < 0.30` (still within the lip), so a missed placement is
  caught by the guard instead of walking off the edge; the tap crouch starts at `h > -0.12` and is held
  whenever the next cell has no floor.
- Result: 180 ninja bridge blocks placed in 170 s (58 before), 7 bridges finished against 14 given up.

Still short: **void falls are 3.1 a game against the human's 1.8** (the guard-only baseline was 1.0, but
nobody moved then). The fall trails show the body upright at the lip with pitch 78-80, one block into the
void, at the diamond-island edges: the placement is refused (`[refuse] click ... there=MaterialGas`) or the
click lands a tick after the body has stepped. That is a placement/confirmation problem, not travel.

## Next

1. Bridge placement reliability: `[refuse]` lines and ghost blocks (client-predicted block, server rejects).
   That is where the 3.1 void falls and the 14 give-ups per 3 minutes come from.
2. Kills are still 0.0 a game: bots bridge into a base and break a bed (1-2 a game) but never win a fight.
   Look at `Combat` after a rush arrives, not at travel.
3. Blocks/min 10.8 against 30.5: what is left is the shop trip (idle.src.shop) and the repeated failed
   walk searches (`search failed ... bridge<=0`, one every tick for a goal across a gap: a caller re-plans
   with walking options where it needs bridging).
