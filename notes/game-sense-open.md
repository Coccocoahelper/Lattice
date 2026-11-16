# Game sense: where the bots actually fall short

Measured with `tools/replay_gamesense.py` over the recorded replays (same recorder for humans and bots).

## Reference, real player

Sovacos, 11 Bed Wars replays: **1.8 void falls per game, 30.5 blocks placed per minute, 0.45 kills per game,
65% of the game more than 30 blocks from his own spawn.**

## Bots

| game | duration | bots that never left spawn | blocks placed |
|---|---|---|---|
| mini196A | 68 s | 8 of 8 | 16 |
| mini248D | 102 s | 7 of 8 | 68 |
| mini89G | 128 s | 8 of 8 | 6 |
| mini65H | 314 s | 3 of 8 | 172 |

Void deaths: 6.0 per game before the motor edge guard, 1.0 after (below the human's 1.8).

**The opening is the problem.** For the first 60–130 s most bots stand at their spawn. Their task lists read
`rush-white`, `collect-diamond`, `collect-iron`, so the macro layer is choosing sensibly; the body never acts.
Because nothing travels, nothing downstream happens either: no bed rushes, no fights, no mid control, 4 blocks a
minute against a human's 30.

## Ruled out

- `rush.nopath` and `rush.navfail` are both 0, so the navigator is not returning "no route" during a rush.
- `canShop=false` in `/bot why` only means "nothing worth buying with the iron in hand yet" (`ShopPlanner.plan` is
  empty at zero iron), not a broken shop. The bots do buy: they reach 32–57 blocks in the longer games.
- `MoveType` has a bridging edge and `RushTask` has an own-bridge fallback (`startExtend`), and bots do bridge in
  the long game (172 blocks, 3 of 8 stuck).

## Next

1. Watch one bot's first 60 s with `/bot why <name>` every 5 s and `/bot list` for position, in a game where 8 of 8
   stay home (`mini89G` conditions). Find which task phase is absorbing the ticks: collect-iron with no generator
   registered, a shop that plans but never walks, or a rush phase that returns before TRAVEL.
2. `registerGenerators()` runs every 40 ticks — confirm the generator list is non-empty on Speedway before blaming
   the task either. An empty list turns `collect-iron` into standing still, which is exactly the symptom.
3. Only after the opening moves: travel quality (blocks per minute), then target choice (which bed, when), then
   mid control.
