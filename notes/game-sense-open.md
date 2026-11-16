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

## 2026-11-16 22:0x - the void is a clutch problem, and the clutch is a geometry problem

`/bot pvp debug` on one game names the cause of the deaths instead of guessing at it:

- `clutch tried and failed` is 70-95% of every death list (`/bot deaths`). It is not the fall that kills,
  it is the save that misses.
- The three shapes in the debug log:
  - `no clutch (blocks=false)` - the bot falls with **no block in the inventory at all**.
  - `no cell` / `no edge face` - the body is already 1.5-4 blocks **below** the lip when the picker runs, and
    the old picker only looked at the lip's own level (`L = floorY .. floorY-2`, still above it).
  - `wall ... aimErr=107..165` - the only support is the island wall it fell past, 90-160 degrees off the
    crosshair; the hand starts 90-190 ms late and the feet pass the cell top before it arrives.

The void guard was wrong in the same direction: it asked the block under the **centre point**, and a 0.6-wide
body on the last centimetres of a block has its centre already over the next cell. The fall trails show the
body leaving at `x=56.75` from a block whose cell is 57 - held up by five hundredths of its width, centre over
the void - so the guard read "air below", disarmed, and let it walk the last half-step.

Changes (`38f2410`):

- `Clutch.pickCell` now also asks the question at the body's **own** level: a side face of anything solid within
  4.4 blocks, one to three blocks under the feet (`clutch.wall`). The lip-first search is unchanged in front of it.
- `Clutch.blockClutch` drops an aim the body has fallen past instead of holding it (`clutch.stale`), and only
  retries the lip while the body is still level with it.
- `Motor.voidAhead` asks whether **any cell of the footprint** is solid, not the centre cell.

Measured, 8 bots, Speedway, one 150 s window per line, counters reset at the start of each:

| window | void deaths | unassisted | clutch.wall | clutch.block.place | edgeguard |
|---|---|---|---|---|---|
| before (mini48E) | 21 | 20 | 0 | 0 | ~2/s |
| after, win 1 | 11 | 11 | 1 | 0 | 5/s |
| after, win 2 | 29 | 22 | 4 | 2 | 16/s |
| after, win 3 | 12 | 12 | 5 | 1 | 9/s |

Mean after: 15 unassisted void deaths per window against 20 before. **That is inside the game-to-game spread**
(the same build gave 11 and 29), so the honest statement is: the two mechanisms were real and are now covered,
but the void rate is not measurably better yet, and no single change has been isolated.

What the numbers do say: the wall branch finds a face the old picker never looked for (5 of 12 falls in the last
window), and the guard now fires for the half-step it was blind to. The clutch still saves almost nothing
(`clutch.block.place` 1-2 per window, `fall.saved` 1) because the window is ~4 ticks and the flick is 90-160
degrees.

## Next, in order

1. **Do not step off.** Every unassisted fall has `v` near zero at the lip - the body walks off, it is not
   launched. Look at the approach to a lip (a bridge that aborted, `BridgeTaker` give-ups that leave the nav
   walking) rather than at the clutch. `bridge.giveup.ninja` is still ~2x `bridge.done.ninja`.
2. `clutch.noblock` / `(blocks=false)`: bots reach a lip with no blocks. Check `ShopPlanner` wool purchases and
   whether a rusher leaves spawn with fewer than the 24 its path will bridge.
3. `place.fail.nosupport` (65-171 per window) is the same geometry error one level up: the placer wants a face
   the body cannot see from where it stands.
4. Kills: bots now do fight (5 melee deaths in one window where every earlier window had 0), but they lose.
   `fight.declined.stacked` is the gate that decides most engagements - the threshold is in armor+weapon points
   where leather (7) to iron (15) alone is a gap of 8, so `ge <= -4` declines almost every fight.
