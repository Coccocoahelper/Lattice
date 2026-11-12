package net.h2020.bots.ai.brain;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.game.BedwarsView;
import net.h2020.api.game.GameState;
import net.h2020.api.game.GameTeam;
import net.h2020.api.game.TeamColor;
import net.h2020.bots.ai.BotAI;
import net.h2020.bots.ai.brain.bedwars.BedDefense;
import net.h2020.bots.ai.brain.bedwars.BwTask;
import net.h2020.bots.ai.brain.bedwars.CollectTask;
import net.h2020.bots.ai.brain.bedwars.DefendTask;
import net.h2020.bots.ai.brain.bedwars.GuardTask;
import net.h2020.bots.ai.brain.bedwars.Role;
import net.h2020.bots.ai.brain.bedwars.RushTask;
import net.h2020.bots.ai.brain.bedwars.ShareTask;
import net.h2020.bots.ai.brain.bedwars.ShopPlanner;
import net.h2020.bots.ai.brain.bedwars.ShopTask;
import net.h2020.bots.ai.brain.bedwars.TravelBlocks;
import net.h2020.bots.ai.brain.bedwars.TeamPlay;
import net.h2020.bots.ai.brain.bedwars.TeamJobs;
import net.h2020.bots.ai.brain.bedwars.ScoutModel;
import net.h2020.bots.ai.brain.bedwars.DepositTask;
import net.h2020.bots.ai.brain.bedwars.HuntTask;
import net.h2020.bots.ai.brain.bedwars.WithdrawTask;
import net.h2020.bots.ai.brain.bedwars.GiftTask;
import net.h2020.bots.ai.brain.bedwars.VoidTask;
import net.h2020.bots.ai.perception.TeamTalk;
import net.h2020.bots.ai.brain.bedwars.EndChestTask;
import net.h2020.bots.ai.brain.bedwars.VoidItemsTask;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.perception.Sighting;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Bed Wars decision layer: a utility-scored task selector over shop trips, iron collection, bed defense, rushing,
 * diamond/emerald runs, team upgrades, guarding and resource sharing, with fight and "enemy at our bed" interrupts.
 * Roles (defender / rusher / farmer) come from personality and, in team modes, from coordination among the
 * team's bots (one defends, one rushes). Reacts to BED DESTRUCTION (final-life caution), respawns (re-buys gear).
 */
public final class BedwarsBrain extends GameBrain {
    private final BedwarsView view;
    private TeamColor color;
    private Role role = Role.FARMER;
    private BwTask task;
    /** Surroundings snapshot: behind us, heard sounds, third parties. */
    private net.h2020.bots.ai.perception.Awareness aware;
    /** Idle watchdog: position and tick of the last real movement. */
    private double lastX = Double.NaN, lastZ = Double.NaN;
    private long lastMovedTick;
    /** Purpose-first placement: seal, deny a landing, escape. Runs between the fight and the chore. */
    private net.h2020.bots.ai.combat.PlacementPlanner planner;
    private long taskStartTick;
    private int taskStartIron;
    private BedDefense defense;
    private TravelBlocks travelBlocks;
    private IdleBehavior pregame;
    private long playStart = -1;
    private long nextDecision;
    private boolean bedWasAlive = true;
    private boolean finalLife;
    private boolean justRespawned;
    private long lastShare = -100000;
    private long lastDeposit = -100000;
    private long lastHuntTry = -100000;
    private boolean roleRewarded;
    /** Last decide() inputs and scores, for /bot why <name>. */
    private double[] lastScores;
    private boolean lastCanShop;
    private TeamColor lastTarget;
    private int lastBlocks, lastGsec;
    private String lastJob = "-";

    public String whyString() {
        StringBuilder sb = new StringBuilder();
        sb.append("role=").append(role).append(" task=").append(task == null ? "-" : task.name())
                .append(" blocks=").append(lastBlocks).append(" canShop=").append(lastCanShop)
                .append(" target=").append(lastTarget).append(" gsec=").append(lastGsec)
                .append(" teamJob=").append(lastJob);
        sb.append(" | shop=").append(f(lastScores, 0)).append(" upg=").append(f(lastScores, 1))
                .append(" iron=").append(f(lastScores, 2)).append(" defend=").append(f(lastScores, 3))
                .append(" rush=").append(f(lastScores, 4)).append(" dia=").append(f(lastScores, 5))
                .append(" em=").append(f(lastScores, 6)).append(" guard=").append(f(lastScores, 7));
        BwTask t = task;
        if (t != null) sb.append(" | goal=").append(t.goal() == null ? "-" : t.goal().getBlockX() + "," + t.goal().getBlockZ());
        sb.append(" | nav=").append(ctx.nav.active() ? (ctx.nav.bridging() ? "bridging" : "walking") : "idle")
                .append(" inv iron=").append(Inv.count(ctx.player, org.bukkit.Material.IRON_INGOT))
                .append(" gold=").append(Inv.count(ctx.player, org.bukkit.Material.GOLD_INGOT));
        return sb.toString();
    }

    private static String f(double[] a, int i) {
        return a == null || i >= a.length ? "-" : String.format(java.util.Locale.ROOT, "%.2f", a[i]);
    }
    private static boolean loggedTaskError;
    private long campSince = -1;
    private int campReports;
    private int defendStalls;            // consecutive defences that left the wall no better than they found it
    private long lastEnemySeen = -1;     // last tick any enemy was visible at all         // tick we started loitering at our own base with no enemy near
    private long lastRoleCheck = -1000;
    private int defenses;
    private boolean defenceFullLogged;
    private int jumpTimer;
    private long bedAlertCooldown;
    /** Team whose bed the bot is going for right now (kept for the in-fight bed raid and for shop priorities). */
    private TeamColor raidTarget;
    /** This farmer goes for the emeralds at mid rather than the diamond islands. */
    private boolean emeraldRunner, emeraldPicked;
    private final java.util.Map<Location, Long> emeraldVisit = new java.util.HashMap<Location, Long>();
    private double defendDoneIntegrity = 2;
    private long defendRetryAt;
    private final java.util.Map<Class<?>, Long> quickFailUntil = new java.util.HashMap<>();
    /** How much of the team chat this bot has already read. */
    private long talkVersion;
    private long lastDoomTry = -1000;
    /** Enemy candidates for the next-opponent model, as this bot saw them at the last refresh. */
    private double[][] oppFeats;
    private Player[] oppList;
    private Player predictedOpponent, lastSeenTarget;
    private long oppTick = -100;
    /** The base of the team this bot rushed first (null once that team is out). */
    private TeamColor firstRushTeam;

    public BedwarsBrain(BedwarsView view) {
        super(view);
        this.view = view;
    }

    // ---- accessors for tasks ----
    public BedwarsView view() { return view; }
    public boolean bedAliveNow() { return color != null && view.bedAlive(color); }
    public int roleIndexPublic() { return roleIndex(); }
    public TeamColor color() { return color; }
    public Role role() { return role; }
    /** The task this bot is running (null when idle) - teammates read it for coordination. */
    public BwTask task() { return task; }

    @Override public String taskName() { return task == null ? "none" : task.name(); }
    /** The colour of the team a player is on (null for spectators / unknown). */
    public TeamColor teamColorOf(Player p) {
        net.h2020.api.game.GameTeam t = game.teamOf(p);
        return t == null ? null : t.color();
    }

    @Override
    public String toString() {
        return "BedwarsBrain[" + role + " " + (task == null ? "-" : task.name()) + (finalLife ? " final" : "") + "]";
    }
    public boolean finalLife() { return finalLife; }
    public boolean teamMode() { return game.type().teamSize() > 1; }
    public int blocks() { return Inv.count(ctx.player, Inv.BLOCK); }

    /**
     * The next player to hunt: somebody on a team whose bed is gone. The last player of a team is worth a lot (it is
     * a final kill and a team out of the game), so it is pulled to the front of the queue.
     */
    public Player nextHuntTarget(Player except) {
        Player best = null;
        double bd = 1e9;
        for (GameTeam t : game.teams()) {
            if (t.color() == color || !t.alive()) continue;
            if (view.bedAlive(t.color())) continue;              // still has a bed: that is a rush, not a hunt
            int alive = 0;
            for (Player p : t.members()) if (!p.isDead()) alive++;
            for (Player p : t.members()) {
                if (p == except || p.isDead() || p.getWorld() != ctx.player.getWorld()) continue;
                double d = ctx.pos().distance(p.getLocation()) - (alive <= 1 ? 40 : 0);
                if (d < bd) { bd = d; best = p; }
            }
        }
        return best;
    }

    /** A player's team base (null if unknown). */
    public Location baseOf(Player p) {
        TeamColor t = teamColorOf(p);
        return t == null ? null : view.teamSpawn(t);
    }

    /** +1 when the finished task achieved its goal, -1 when it failed, 0 when it is neither (guard, share...). */
    private double taskResult(BwTask t) {
        if (t instanceof CollectTask) return ((CollectTask) t).reachedTarget() ? 1 : -1;
        if (t instanceof ShopTask) {
            ShopTask st = (ShopTask) t;
            if (st.bought() > 0) return 1;
            return st.tried() ? -1 : 0;          // nothing worth buying is not a failure of the idea
        }
        if (t instanceof RushTask) return ((RushTask) t).brokeBed() ? 1 : -1;
        if (t instanceof DefendTask) return ((DefendTask) t).placed() > 0 ? 1 : -1;
        if (t instanceof DepositTask || t instanceof WithdrawTask) return 1;   // the trip itself is the point
        // (role model: the first job a role takes on tells whether the role suits this bot)
        return 0;
    }

    private java.util.List<Player> enemyTeamMembers(TeamColor t) {
        java.util.List<Player> out = new java.util.ArrayList<Player>();
        for (GameTeam team : game.teams()) {
            if (team.color() != t) continue;
            for (Player p : team.members()) if (!p.isDead()) out.add(p);
        }
        return out;
    }

    /** Alive teammates, not counting this bot. */
    private int aliveTeammates() {
        int n = 0;
        for (Player p : teammates()) if (!p.isDead()) n++;
        return n;
    }

    /** Role as the macro policy's index: rusher 0, farmer 1, defender 2. */
    private int roleIndex() { return role == Role.RUSHER ? 0 : role == Role.FARMER ? 1 : 2; }

    private double distanceToBed(TeamColor t) {
        Location bed = t == null ? null : view.bedLocation(t);
        return bed == null || bed.getWorld() != ctx.player.getWorld() ? 100 : ctx.pos().distance(bed);
    }
    public int gameSeconds() { return playStart < 0 ? 0 : (int) ((ctx.tick - playStart) / 20); }

    public BedDefense defense() {
        if (defense == null && color != null && view.bedLocation(color) != null) defense = BedDefense.of(view.bedLocation(color));
        return defense;
    }

    @Override
    protected void onGameStart() {
        pregame = new IdleBehavior(ctx, 7, false);
        // tell the pathfinder and the block tactics which blocks of this world are player-placed (the rest is map)
        net.h2020.api.game.PlacedRegistry.register(game.world(), view);
        registerGenerators();
        travelBlocks = new TravelBlocks(ctx, this);
        GameTeam t = myTeam();
        if (t != null) color = t.color();
        configureCombat();
    }

    private void configureCombat() {
        ctx.combat.allowThrowables = false;
        ctx.combat.allowBow = true;
        ctx.combat.allowGapple = true;
        ctx.combat.allowPearl = ctx.persona.tryhard;
        ctx.combat.allowFireball = ctx.persona.tryhard || gameSeconds() > 240;
        ctx.combat.leash = role == Role.DEFENDER ? 22 : 35;
        // bed gone: clutch. Nothing to defend any more, so trade: longer leash, pearls and fireballs always on, and
        // the shop buys the knockback-stick / pearl / potion kit (ShopPlanner) instead of another wool stack.
        ctx.combat.clutchMode = finalLife;
        if (finalLife) {
            ctx.combat.leash = 45;
            ctx.combat.allowPearl = true;
            ctx.combat.allowFireball = true;
            ctx.combat.allowGapple = true;
        }
        NavOptions chase = NavOptions.bridging(8);
        chase.maxNodes = 3500;
        ctx.combat.chaseOptions = net.h2020.bots.ai.combat.Combat.chase(chase);
        // (the brain hands the fight layer a live view of the bed it should break when the chance shows up)
        final BedwarsBrain self = this;
        ctx.combat.bedInfo = new net.h2020.bots.ai.combat.Combat.BedInfo() {
            @Override public Location foot() { return self.bedInFightReach(); }
            @Override public boolean alive() { return self.bedInFightReach() != null; }
        };
    }

    /**
     * The enemy bed a bot may break in the middle of a fight: the rush target while it is still up, otherwise the
     * nearest enemy bed within 14 blocks. Null when there is nothing worth breaking right here.
     */
    public Location bedInFightReach() {
        Location me = ctx.pos();
        if (raidTarget != null && view.bedAlive(raidTarget)) {
            Location b = view.bedLocation(raidTarget);
            if (b != null && b.getWorld() == me.getWorld() && b.distance(me) < 14) return b;
        }
        Location best = null;
        double bd = 14;
        for (GameTeam t : game.teams()) {
            if (t.color() == color || !t.alive() || !view.bedAlive(t.color())) continue;
            Location b = view.bedLocation(t.color());
            if (b == null || b.getWorld() != me.getWorld()) continue;
            double d = b.distance(me);
            if (d < bd) { bd = d; best = b; }
        }
        return best;
    }

    /**
     * The bed we are rushing is behind wool / end stone and we have no tool for that material: the shop trip has to
     * buy shears / a pickaxe before the next luxury (this is how a 2020 player shops before a bed break).
     */
    public boolean needsBedTools() {
        TeamColor t = raidTarget;
        if (t == null) return false;
        Location bed = view.bedLocation(t);
        if (bed == null || !view.bedAlive(t)) return false;
        boolean wool = false, hard = false;
        for (int dx = -1; dx <= 2; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 2; dz++) {
            Material m = ctx.world().type(bed.getBlockX() + dx, bed.getBlockY() + dy, bed.getBlockZ() + dz);
            if (m == Material.WOOL) wool = true;
            else if (m == Material.ENDER_STONE || m == Material.STAINED_CLAY || m == Material.HARD_CLAY || m == Material.OBSIDIAN) hard = true;
        }
        if (wool && !Inv.has(ctx.player, Inv.SHEARS)) return true;
        return hard && !Inv.has(ctx.player, Inv.PICKAXE);
    }

    @Override
    protected boolean canAct() {
        return super.canAct() && !view.isRespawning(ctx.player);
    }

    @Override
    protected void onInactive() {
        if (task != null) { task.abort(); task = null; }
        if (travelBlocks != null) travelBlocks.cancel();
    }

    @Override
    protected void onActive() {
        if (playStart >= 0) justRespawned = true;
        nextDecision = ctx.tick + AIMath.range(ctx.rng, 4, 16);
    }

    @Override
    protected void think() {
        if (aware == null) aware = new net.h2020.bots.ai.perception.Awareness(ctx);
        aware.tick();
        pollState();
        if (color == null) { GameTeam t = myTeam(); if (t != null) color = t.color(); }
        GameState gs = game.state();
        if (gs == GameState.WAITING || gs == GameState.STARTING) {
            if (pregame != null) pregame.tick();
            return;
        }
        if (gs == GameState.ENDING) {
            if (task != null) { task.abort(); task = null; }
            ctx.nav.stop();
            if (won() && --jumpTimer <= 0) { ctx.motor.jump(true); jumpTimer = AIMath.range(ctx.rng, 2, 25); }
            return;
        }
        if (playStart < 0) {
            playStart = ctx.tick;
            assignRole();
            nextDecision = ctx.tick + AIMath.range(ctx.rng, 2, 12);
        }
        if (ctx.tick - lastRoleCheck > 600) assignRole();
        if (ctx.tick % 20 == 0) checkBed();
        if (ctx.tick % 20 == 0) scout();
        if (ctx.tick % 20 == 0) scoutOpponents();
        if (ctx.tick % 40 == 0) registerGenerators();
        // (questions come from the server now, see ServerQuestions)

        // the fight is lost and it knows it: get the valuables out before the hit lands
        boolean locked = task != null && !task.interruptible();
        if (!locked && doomedItems()) return;
        // point-blank: a player standing on top of you is not a perception question. The sighting meter can lag when
        // his body is behind your aim (you were looking at the bed), and bots used to stand at 1.4 blocks doing nothing
        // while a player broke our bed. Being touched is information in itself.
        Player touching = nearestEnemyPlayer(4.2);
        if (!locked && touching != null && ctx.combat.target() != touching) ctx.combat.engage(touching);
        // pick the fight, do not wait to be hit: a player with better gear and health walks at you, and a player who
        // is behind on both keeps farming. This is the difference between aggro and feeding.
        if (!locked && ctx.combat.target() == null && !ctx.nav.bridging() && ctx.health() >= 16
                && !(task instanceof RushTask)) {
            Player seen = nearestEnemyPlayer(engageRange());
            if (seen != null && gearEdge(seen) >= 0) {
                ctx.combat.engage(seen);
                net.h2020.api.stats.CombatStats.inc("fight.engage.advantage");
            }
        }

        // fights. A rusher with the bed in front of it walks past players that are not stopping it - that is what a
        // rush is. It turns around only when it is being hit or when somebody is right on top of it.
        boolean rushing = task instanceof RushTask && ((RushTask) task).committed();
        boolean mustFight = (!rushing || ctx.combat.recentlyHurt(50) || threatWithin(3.2)) && !declineEngagement();
        // blocks going down beside our own bed and nobody in sight: that is a raid in progress, not a coincidence
        if (!locked && aware.bedNoise() && !ctx.combat.engaged() && !(task instanceof GuardTask)
                && bedAliveNow() && ctx.tick > bedAlertCooldown + 100) {
            net.h2020.api.stats.CombatStats.inc("aware.bednoise.defend");
            switchTo(new GuardTask(this, 20 * 20));
            bedAlertCooldown = ctx.tick;
            return;
        }
        // a third party inside eight blocks turns a duel into a trap: break it off while there is still room
        if (mustFight && aware.thirdParty() && ctx.combat.target() != null && ctx.health() < 16) {
            net.h2020.api.stats.CombatStats.inc("aware.thirdparty");
            ctx.combat.disengage();
            mustFight = false;
        }
        if (planner == null) planner = new net.h2020.bots.ai.combat.PlacementPlanner(this);
        if (!locked && !ctx.combat.engaged() && !ctx.nav.bridging() && planner.tick()) return;
        if (!locked && mustFight && combatPolicy(engageRange())) {
            // two of us on one of them and the fight is already won: the weaker one leaves and takes their bed, the
            // other keeps fighting (TeamPlay claims the hand-off so only one goes)
            Player enemy = ctx.combat.target();
            if (enemy != null && !(task instanceof RushTask)) {
                TeamColor trade = TeamPlay.peelOff(this, enemy, ctx.combat.winProbability(), blocks());
                if (trade != null && view.bedAlive(trade)) {
                    ctx.combat.disengage();
                    switchTo(new RushTask(this, trade));
                    if (teamMode()) ctx.chat.callout(ctx.rng.nextBoolean() ? "ill take bed" : "going bed", 0.4);
                    return;
                }
            }
            if (task != null && !(task instanceof RushTask) && !(task instanceof GuardTask)) {
                // dropping a chore to answer a threat is the right call, and the macro policy should hear that
                net.h2020.bots.ai.ml.MacroPolicy.abandon();
                task.abort();
                task = null;
            }
            ctx.combat.tick();
            return;
        }
        // someone at our bed
        if (color != null && view.bedAlive(color) && ctx.tick > bedAlertCooldown && !(task instanceof GuardTask) && enemyNearBed()) {
            double d = ctx.pos().distance(view.bedLocation(color));
            if (d < 45 && (role == Role.DEFENDER || d < 22)) {
                switchTo(new GuardTask(this, 20 * 25));
                if (teamMode()) ctx.chat.callout(ctx.rng.nextBoolean() ? "someone at bed" : "bed", 0.4);
                bedAlertCooldown = ctx.tick + 400;
            }
        }
        // what the team is saying: "inc" pulls us home, "void" gets us out, "drop" hands the valuables over
        if (!locked && handleTeamTalk()) return;
        // blocks while travelling: put a floor block back into a holed bridge, or a head hitter over a contested one
        if (travelBlocks != null && !ctx.combat.engaged() && !ctx.c.containerOpen()
                && (task == null || task.interruptible()) && travelBlocks.auto()) return;
        idleWatchdog();
        if (task != null) {
            boolean running;
            try { running = task.tick(); } catch (RuntimeException ex) { running = false; }
            if (!running) {
                if (task instanceof DefendTask && ((DefendTask) task).placed() > 3) {
                    defenses++;
                    if (teamMode() && defenses == 1) ctx.chat.callout("bed defended", 0.5);
                }
                // the macro policy learns from what the plan actually achieved, not from how long it lasted
                double result = taskResult(task);
                double taskSeconds = (ctx.tick - taskStartTick) / 20.0;
                int ironNow = net.h2020.bots.ai.inv.Inv.count(ctx.player, net.h2020.bots.ai.inv.Inv.type(org.bukkit.Material.IRON_INGOT))
                        + net.h2020.bots.ai.inv.Inv.count(ctx.player, net.h2020.bots.ai.inv.Inv.type(org.bukkit.Material.GOLD_INGOT));
                net.h2020.bots.ai.ml.MacroPolicy.reward(result, taskSeconds, Math.max(0, taskStartIron - ironNow));
                if (!roleRewarded && result != 0) { net.h2020.bots.ai.ml.RoleModel.reward(result > 0); roleRewarded = true; }
                // a trip that gave up straight away (no route yet, nav cooldown): don't pick it again every tick
                if ((task instanceof RushTask || task instanceof CollectTask) && ctx.tick - task.startedTick() < 40) {
                    quickFailUntil.put(task.getClass(), ctx.tick + 20 * 8);
                }
                if (task instanceof DefendTask) {
                    // whatever is still open could not be placed from anywhere: don't stand there retrying it
                    BedDefense d = defense();
                    double left = d == null ? 1 : d.integrity(ctx.world());
                    defendDoneIntegrity = left;
                    // an unfinished wall is a hole in the base: come straight back to it. Only a wall that is done (or
                    // that has nothing placeable left) earns the long back-off, which is what used to stretch one
                    // defence over minutes.
                    // A defence that leaves the wall exactly as it found it is not going to finish by being retried:
                    // two of those in a row and the bot leaves it alone for 45 s instead of re-deciding every 3 s,
                    // which is what kept the mini89A bots at their beds for minutes.
                    if (left >= defendDoneIntegrity - 0.01) {
                        if (++defendStalls >= 2) {
                            defendStalls = 0;
                            defendRetryAt = ctx.tick + 20 * 45;
                            net.h2020.api.stats.CombatStats.inc("defend.stalled");
                        } else {
                            defendRetryAt = ctx.tick + 20 * 3;
                        }
                    } else {
                        defendStalls = 0;
                        defendRetryAt = ctx.tick + (left >= 0.99 || Inv.count(ctx.player, Inv.BLOCK) < 4 ? 20 * 30 : 20 * 3);
                    }
                }
                task = null;
                nextDecision = ctx.tick + AIMath.range(ctx.rng, 2, 10);
            }
            return;
        }
        if (ctx.tick < nextDecision) return;
        BwTask next = decide();
        if (next == null) {
            // the macro found nothing it liked: standing still is never the answer. Collecting iron is always useful
            // and always moving, and it puts the bot back in a position to be given a real job a moment later.
            net.h2020.api.stats.CombatStats.inc("brain.fallback");
            Location fg = color == null ? null : view.teamGenerator(color);
            if (fg == null) return;
            next = new net.h2020.bots.ai.brain.bedwars.CollectTask(this, fg,
                    net.h2020.bots.ai.brain.bedwars.CollectTask.Kind.IRON, 16, 20 * 15);
            if (next == null) return;
        }
        switchTo(next);
        nextDecision = ctx.tick + 10;
    }

    private void switchTo(BwTask t) {
        if (task != null) task.abort();
        taskStartTick = ctx.tick;
        taskStartIron = net.h2020.bots.ai.inv.Inv.count(ctx.player, net.h2020.bots.ai.inv.Inv.type(org.bukkit.Material.IRON_INGOT))
                + net.h2020.bots.ai.inv.Inv.count(ctx.player, net.h2020.bots.ai.inv.Inv.type(org.bukkit.Material.GOLD_INGOT));
        task = t;
        announce(t);
    }

    /** Tell the team what we are doing - what a teammate would hear, not a status dump. */
    private void announce(BwTask t) {
        if (t == null || !teamMode()) return;
        if (ctx.rng.nextDouble() > 0.2 + 0.5 * ctx.persona.skill.chattiness) return;
        String n = t.name();
        if (n.startsWith("rush-")) ctx.chat.callout("rushing " + n.substring(5), 0.35);
        else if (n.equals("collect-emerald")) ctx.chat.callout(ctx.rng.nextBoolean() ? "mid" : "going mid", 0.25);
        else if (n.equals("collect-diamond")) ctx.chat.callout(ctx.rng.nextBoolean() ? "dias" : "diamonds", 0.25);
        else if (n.equals("deposit")) ctx.chat.callout("dropping at base", 0.3);
        else if (n.equals("defend")) ctx.chat.callout("defending", 0.3);
        else if (n.equals("shop")) ctx.chat.callout("shopping", 0.15);
    }

    /** Nearest enemy player within `range` blocks, straight from the world - no sighting required. */
    public Player nearestEnemyPlayer(double range) {
        Player best = null;
        double bd = range;
        for (Player p : ctx.player.getWorld().getPlayers()) {
            if (p == ctx.player || p.isDead() || !p.isOnline()) continue;
            if (teammates().contains(p)) continue;
            GameTeam t = game.teamOf(p);
            if (t == null) continue;                          // spectators
            if (t.color() == color) continue;
            double d = p.getLocation().distance(ctx.player.getLocation());
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /** Sword + armour tier difference, ours minus theirs. Positive means the trade is ours to take. */
    private int gearEdge(Player other) {
        return (int) Math.round(ctx.combat.gearDiffVs(other));
    }

    private double engageRange() {
        double r;
        switch (role) {
            case RUSHER: r = 12; break;
            case DEFENDER: r = 10; break;
            default: r = 9;
        }
        if (finalLife) r *= 0.75;
        return r;
    }

    // ------------------------------------------------------------------------------------------------ roles

    private void assignRole() {
        lastRoleCheck = ctx.tick;
        double a = ctx.persona.skill.aggression;
        if (!teamMode()) {
            role = a > 0.62 ? Role.RUSHER : (a < 0.3 ? Role.DEFENDER : Role.FARMER);
            if (!emeraldPicked) { emeraldPicked = true; emeraldRunner = ctx.rng.nextDouble() < 0.3; }
            configureCombat();
            return;
        }
        // coordinate with the other bots on the team: most aggressive rushes, least aggressive defends
        List<Player> mates = new ArrayList<>(teammates());
        mates.add(ctx.player);
        List<Player> bots = new ArrayList<>();
        for (Player p : mates) {
            BaseBrain b = BotAI.brainOf(p);
            if (b instanceof BedwarsBrain && b.context() != null) bots.add(p);
        }
        Collections.sort(bots, new Comparator<Player>() {
            public int compare(Player x, Player y) {
                double ax = BotAI.brainOf(x).context().persona.skill.aggression;
                double ay = BotAI.brainOf(y).context().persona.skill.aggression;
                int c = Double.compare(ay, ax);
                return c != 0 ? c : x.getName().compareTo(y.getName());
            }
        });
        int idx = bots.indexOf(ctx.player);
        int n = bots.size();
        boolean humansOnTeam = mates.size() > n;
        if (n <= 1) role = humansOnTeam ? (a > 0.5 ? Role.RUSHER : Role.DEFENDER) : (a > 0.62 ? Role.RUSHER : Role.FARMER);
        else if (idx == 0) role = Role.RUSHER;
        else if (idx == n - 1) role = Role.DEFENDER;
        else role = Role.FARMER;
        // with two farmers one runs diamonds, the other the emeralds at mid
        emeraldRunner = role == Role.FARMER && n >= 4 && idx == n - 2;
        // the learned correction on top of the personality-based prior
        double[] prior = new double[net.h2020.bots.ai.ml.RoleModel.ACTIONS];
        prior[net.h2020.bots.ai.ml.RoleModel.RUSHER] = a * 1.2;
        prior[net.h2020.bots.ai.ml.RoleModel.FARMER] = 0.55;
        prior[net.h2020.bots.ai.ml.RoleModel.DEFENDER] = (1.0 - a) * 1.1;
        double[] rx = net.h2020.bots.ai.ml.RoleModel.features(ctx, game.type().teamSize(),
                teammates().size(), bedAliveNow(), gameSeconds());
        int picked = net.h2020.bots.ai.ml.RoleModel.choose(ctx, prior, rx);
        role = picked == net.h2020.bots.ai.ml.RoleModel.RUSHER ? Role.RUSHER
                : picked == net.h2020.bots.ai.ml.RoleModel.DEFENDER ? Role.DEFENDER : Role.FARMER;
        configureCombat();
    }

    // ------------------------------------------------------------------------------------------------ bed

    /** The generators of this game, for anything that needs to know whether somebody is farming. */
    private void registerGenerators() {
        java.util.List<Location> gens = new java.util.ArrayList<Location>();
        gens.addAll(view.diamondGenerators());
        gens.addAll(view.emeraldGenerators());
        if (color != null && view.teamGenerator(color) != null) gens.add(view.teamGenerator(color));
        net.h2020.api.game.GeneratorRegistry.set(game.world(), gens);
        java.util.List<Location> forges = new java.util.ArrayList<Location>();
        for (GameTeam t : game.teams()) {
            Location f = view.teamGenerator(t.color());
            if (f != null) forges.add(f);
        }
        net.h2020.api.game.GeneratorRegistry.setPoints(game.world(), net.h2020.api.game.GeneratorRegistry.TEAM_GEN, forges);
        net.h2020.api.game.GeneratorRegistry.setPoints(game.world(), net.h2020.api.game.GeneratorRegistry.DIAMOND_GEN, view.diamondGenerators());
        net.h2020.api.game.GeneratorRegistry.setPoints(game.world(), net.h2020.api.game.GeneratorRegistry.EMERALD_GEN, view.emeraldGenerators());
        java.util.List<Location> shops = new java.util.ArrayList<Location>();
        for (GameTeam t : game.teams()) {
            if (view.shopNpc(t.color()) != null) shops.add(view.shopNpc(t.color()));
            if (view.upgradesNpc(t.color()) != null) shops.add(view.upgradesNpc(t.color()));
        }
        net.h2020.api.game.GeneratorRegistry.setPoints(game.world(), net.h2020.api.game.GeneratorRegistry.SHOP, shops);
        java.util.List<Location> beds = new java.util.ArrayList<Location>();
        for (GameTeam t : game.teams()) {
            if (!view.bedAlive(t.color())) continue;
            Location b = view.bedLocation(t.color());
            if (b != null) beds.add(b);
        }
        net.h2020.api.game.GeneratorRegistry.setPoints(game.world(), net.h2020.api.game.GeneratorRegistry.BED, beds);
        java.util.List<Location> islands = new java.util.ArrayList<Location>();
        java.util.List<String> islandNames = new java.util.ArrayList<String>();
        for (GameTeam t : game.teams()) {
            Location sp = view.teamSpawn(t.color());
            if (sp != null) { islands.add(sp); islandNames.add(t.color().name()); }
        }
        net.h2020.api.game.GeneratorRegistry.setIslands(game.world(), islands, islandNames);
    }

    /**
     * Occasionally ask a real player what he is doing, when the behaviour model cannot tell: one yes/no question,
     * rate-limited per player. The answer is a label the bot could not have observed.
     */
    private void askHumans() {
        if (ctx.tick < 400) return;
        for (Player p : game.players()) {
            if (p == null || p.isDead() || p.getWorld() != ctx.player.getWorld()) continue;
            if (net.h2020.bots.ai.BotAI.isBotPlayer(p)) continue;
            if (ctx.pos().distance(p.getLocation()) > 28) continue;
            // alternate: the hidden facts (in a generator? low? where are you bridging to?) and the activity
            String q = (ctx.tick / 60) % 2 == 0
                    ? net.h2020.bots.ai.ml.QuestionBook.questionFor(p)
                    : net.h2020.bots.ai.ml.BehaviourModel.questionFor(p, ctx.tick);
            if (q == null) q = net.h2020.bots.ai.ml.BehaviourModel.questionFor(p, ctx.tick);
            if (q == null) continue;
            ctx.chat.say(q, 4);
        }
    }

    /** Fold what we can see of the other teams into the scout model (skill, gear, resources in hand). */
    private void scout() {
        if (color == null) return;
        for (Sighting s : ctx.perception.all()) {
            if (!s.known || s.ally || s.player == null || s.player.isDead()) continue;
            if (!s.visible) continue;
            TeamColor tc = teamColorOf(s.player);
            if (tc == null || tc == color) continue;
            ScoutModel.observe(game.world(), tc, s.player, s, ctx.tick);
            org.bukkit.inventory.ItemStack hand = s.player.getItemInHand();
            if (hand != null) {
                if (hand.getType() == Material.EMERALD || hand.getType() == Material.DIAMOND) {
                    ScoutModel.sawResource(game.world(), tc, hand.getType());
                } else if (hand.getType() == Material.DIAMOND_SWORD || hand.getType().name().startsWith("DIAMOND_")) {
                    ScoutModel.sawResource(game.world(), tc, hand.getType());
                }
            }
        }
    }

    /**
     * Who is this bot likely to run into next? The candidate enemies are everybody still in the game on another team
     * (the tab list is public); where they are is our memory of them, or their base if we have never seen them. The
     * scout weights learn from every engagement who actually showed up.
     */
    private void scoutOpponents() {
        if (color == null) return;
        java.util.List<Player> cands = new java.util.ArrayList<Player>();
        java.util.List<double[]> feats = new java.util.ArrayList<double[]>();
        Location myBed = view.bedLocation(color);
        Location firstBase = firstRushTeam == null ? null : view.bedLocation(firstRushTeam);
        for (GameTeam t : game.teams()) {
            if (t.color() == color || !t.alive()) continue;
            int alive = 0;
            for (Player p : t.members()) if (!p.isDead()) alive++;
            for (Player p : t.members()) {
                if (p.isDead() || p.getWorld() != ctx.player.getWorld()) continue;
                Sighting s = ctx.perception.get(p);
                Location pos = s != null && s.lastPos != null ? s.estimate(ctx.tick) : view.teamSpawn(t.color());
                if (pos == null) continue;
                cands.add(p);
                feats.add(net.h2020.bots.ai.ml.NextOpponent.features(ctx, p, pos, myBed, alive <= 1,
                        view.bedAlive(t.color()), firstBase));
            }
        }
        oppList = cands.toArray(new Player[0]);
        oppFeats = feats.toArray(new double[0][]);
        int best = net.h2020.bots.ai.ml.NextOpponent.best(oppFeats);
        predictedOpponent = best >= 0 && best < oppList.length ? oppList[best] : null;
        oppTick = ctx.tick;
        // "inc": the bot we expect to meet next is closing on our bed
        if (predictedOpponent != null && teamMode() && bedWasAlive && ctx.tick > bedAlertCooldown) {
            Location bed = color == null ? null : view.bedLocation(color);
            if (bed != null) {
                Sighting ps = ctx.perception.get(predictedOpponent);
                Location at = ps != null && ps.lastPos != null ? ps.estimate(ctx.tick) : null;
                if (at != null && at.getWorld() == bed.getWorld() && at.distance(bed) < 40) {
                    TeamColor tc = teamColorOf(predictedOpponent);
                    ctx.chat.callout("inc " + (tc == null ? "" : tc.name().toLowerCase()), 0.35);
                    bedAlertCooldown = ctx.tick + 400;
                }
            }
        }
    }

    /** An enemy showed up: teach the next-opponent model. */
    private void opponentArrived(Player enemy) {
        if (enemy == null || oppFeats == null || oppList == null) return;
        for (int i = 0; i < oppList.length; i++) {
            if (oppList[i] == enemy) { net.h2020.bots.ai.ml.NextOpponent.observe(oppFeats, i); return; }
        }
    }

    @Override
    public void onHurt(Player attacker, double damage) {
        opponentArrived(attacker);
        super.onHurt(attacker, damage);
    }

    /** The predicted next opponent (may be null). */
    public Player predictedOpponent() { return predictedOpponent; }

    /** One of our bots saw an enemy pick something up (called by the AI listener for nearby bots). */
    public void sawPickup(Player p, Material m) {
        if (color == null || p == null || p.getWorld() != ctx.player.getWorld()) return;
        TeamColor tc = teamColorOf(p);
        if (tc == null || tc == color) return;
        double d = ctx.pos().distance(p.getLocation());
        if (d > 24) return;
        Sighting s = ctx.perception.get(p);
        if (d > 8 && (s == null || !s.visible)) return;    // far away and not in sight: we cannot know
        ScoutModel.sawResource(game.world(), tc, m);
    }

    private void checkBed() {
        if (color == null) return;
        boolean alive = view.bedAlive(color);
        if (bedWasAlive && !alive) {
            finalLife = true;
            if (task instanceof DefendTask || task instanceof GuardTask) { task.abort(); task = null; }
            if (teamMode()) ctx.chat.callout(pick("bed gone", "they broke bed", "bed", "bro our bed"), 0.55);
            else ctx.chat.callout(pick("bruh", "bro", "omg", "really"), 0.3);
            configureCombat();
        }
        bedWasAlive = alive;
    }

    private String pick(String... s) { return s[ctx.rng.nextInt(s.length)]; }

    public boolean enemyNearBed() {
        Location bed = view.bedLocation(color);
        if (bed == null) return false;
        // whoever is physically within 12 of the bed, whether or not the sighting meter has caught up
        for (Player p : bed.getWorld().getPlayers()) {
            if (p == ctx.player || p.isDead() || teammates().contains(p)) continue;
            GameTeam t = game.teamOf(p);
            if (t == null || t.color() == color) continue;
            if (p.getLocation().distance(bed) < 12) return true;
        }
        for (Sighting s : ctx.perception.all()) {
            if (!s.known || s.ally || s.lastPos == null) continue;
            if (s.confidence(ctx.tick) < 0.3) continue;
            if (s.estimate(ctx.tick).distance(bed) < 9) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ utility

    private BwTask decide() {
        if (color == null) return null;
        // a team without a bed is a final kill waiting: hunt it, the last man first (if this bot is the hunting kind)
        if (!(task instanceof HuntTask) && ctx.tick - lastHuntTry > 400
                && ctx.rng.nextDouble() < 0.25 + 0.7 * ctx.persona.skill.aggression) {
            Player hunt = nextHuntTarget(null);
            if (hunt != null && ctx.pos().distance(hunt.getLocation()) < 70) {
                lastHuntTry = ctx.tick;
                return new HuntTask(this, hunt);
            }
        }
        // hurt and a teammate is right here: hand the valuables over instead of losing them to the next hit
        if (ctx.health() <= 8 && !(task instanceof GiftTask)) {
            Player mate = nearbyMate(7);
            if (mate != null && GiftTask.carriesValuables(ctx.player)) return new GiftTask(this, mate, false);
        }
        int gsec = gameSeconds();
        int iron = ShopPlanner.iron(ctx.player);
        int dia = ShopPlanner.diamonds(ctx.player);
        int blocks = blocks();
        Location spawn = view.teamSpawn(color);
        Location gen = view.teamGenerator(color);
        double toBase = spawn == null ? 0 : ctx.pos().distance(spawn);
        boolean nearBase = toBase < 30;
        boolean bedAlive = view.bedAlive(color);
        BedDefense def = defense();
        double integrity = def == null ? 1 : def.integrity(ctx.world());
        if (integrity >= 0.99 && !defenceFullLogged) { defenceFullLogged = true; net.h2020.api.stats.CombatStats.inc("defence.full"); net.h2020.bots.ai.stats.DefenceStats.completed(ctx.tick); }
        boolean canShop = !ShopPlanner.plan(ctx.player, role, ctx.persona.tryhard, gsec, finalLife, needsBedTools()).isEmpty();

        double sShop = canShop ? (blocks < 16 ? 1.2 : 0.75) * (nearBase ? 1 : 0.35) : 0;
        if (justRespawned && canShop) sShop += 0.7;
        double sUpg = (dia >= 2 && nearBase && (role == Role.FARMER || dia >= 4 || gsec > 300)) ? 0.85 : 0;
        double sIron = 0;
        // opening: grab just enough for the first wool (2020 players were at the shop within ~10 s), not a pile
        int openingIron = role == Role.RUSHER ? 12 : 8;
        boolean opening = gsec < 40 && blocks < 16 && iron < openingIron;
        if (opening) sIron = 1.35;
        else if (!canShop && nearBase && (blocks < 16 || role == Role.DEFENDER)) sIron = 0.5;
        // blocks in the bag but nothing the shop will sell us: standing still is the worst of the three options, so go
        // and mine what the next purchase needs (a sword, armour) instead of idling at spawn
        else if (!canShop && iron < 24) sIron = Math.max(sIron, 0.45);
        double sDefend = 0;
        // after a defend pass, only come back for it when the cover got worse (someone broke in) or a while later
        boolean defendWorthIt = integrity < defendDoneIntegrity - 0.01 || ctx.tick >= defendRetryAt;
        // start with enough wool to finish the layer in one pass: starting at 8 blocks meant running out halfway and
        // leaving the bed half covered while the bot walked back for more
        if (bedAlive && blocks >= 20 && integrity < 0.99 && defendWorthIt) {
            if (role == Role.DEFENDER) sDefend = 1.25;
            else if (role == Role.FARMER) sDefend = defenses == 0 && !teamHasDefender() ? 0.95 : 0.3;
            else sDefend = defenses == 0 && !teamMode() && ctx.rng.nextDouble() < 0.3 ? 0.9 : 0.2;
            // no wall at all and blocks in the bag: this is not a role preference, it is the bed being naked
            if (integrity < 0.6) sDefend = Math.max(sDefend, nearBase ? 0.95 : 0.75);
            if (!nearBase) sDefend *= 0.4;
        }
        // predicted intent: an enemy the model reads as "rushing", near our bed, is worth defending against now
        if (bedAlive && view.bedLocation(color) != null) {
            for (Sighting sg : ctx.perception.all()) {
                if (!sg.known || sg.ally || sg.player.isDead()) continue;
                Location est = sg.estimate(ctx.tick);
                if (est == null) continue;
                boolean rushing = net.h2020.bots.ai.ml.IntentModel.predict(sg.player) == net.h2020.bots.ai.ml.IntentModel.RUSH;
                // the classical read: looking at our base with wool in the bag, or closing on it
                boolean aimedAtUs = color.name().equals(net.h2020.bots.ai.ml.IntentModel.observedRush(sg.player));
                if ((rushing || aimedAtUs) && est.distance(view.bedLocation(color)) < 40) {
                    sDefend = Math.max(sDefend, 0.6);
                    break;
                }
            }
        }
        boolean secondLayer = role == Role.DEFENDER && (Inv.has(ctx.player, Inv.type(Material.ENDER_STONE)) || blocks >= 40);
        if (bedAlive && def != null && secondLayer && defendWorthIt && integrity >= 0.99 && !def.open(def.layer2, ctx.world()).isEmpty() && blocks >= 12) sDefend = Math.max(sDefend, 0.9);

        TeamColor target = chooseTarget();
        if (target != null) {
            raidTarget = target;
            if (firstRushTeam == null) firstRushTeam = target;
        }
        if (firstRushTeam != null && !view.bedAlive(firstRushTeam)) firstRushTeam = null;   // that base is done
        // what the team has been told about them: somebody who is low or stuck in a generator is an opportunity
        if (target != null) {
            for (Player e : enemyTeamMembers(target)) {
                if (net.h2020.bots.ai.ml.QuestionBook.belief(e, net.h2020.bots.ai.ml.QuestionBook.LOW_HP) > 0.6
                        || net.h2020.bots.ai.ml.QuestionBook.belief(e, net.h2020.bots.ai.ml.QuestionBook.IN_GENERATOR) > 0.6) {
                    raidTarget = target;
                    break;
                }
            }
        }
        double sRush = 0;
        // late game / clutch: eight blocks are enough to reach a bed, holding back for a full stack is how a game is
        // lost; a bed that is already half dug out is a bed worth running at right now
        // how many blocks the trip actually needs: bridging the gap to that bed, not a fixed number. Rushing with eight
        // blocks at a base thirty blocks away just leaves the bot stranded on a half bridge in the void.
        int need = target == null ? 64 : (int) Math.ceil(Math.max(4, distanceToBed(target) - 3));
        int rushBlocks = Math.max(4, Math.min(64, need));
        int staged = Math.min(rushBlocks, 24);          // one stack and a bit is enough to make progress
        if (target != null && blocks >= staged) {
            switch (role) {
                case RUSHER: sRush = 1.0 + (ShopPlanner.swordTier(ctx.player) >= 2 ? 0.25 : 0); break;
                case FARMER: sRush = gsec > 240 ? 0.8 : 0.1; break;
                default: sRush = gsec > 600 || !bedAlive ? 0.6 : 0.05;
            }
            if (finalLife) sRush += 0.35;                 // our bed is gone: trading beds is the only way back in
            if (gsec > 420) sRush += 0.2;
            BedDefense td = defenseOf(target);
            if (td != null && view.bedLocation(target) != null && td.integrity(ctx.world()) < 0.75) sRush += 0.25;
            if (teamIsAway(target)) sRush += 0.15;        // nobody home: free bed
            if (view.emeraldGenerators().size() >= 4 && !teamMode()) sRush += 0.15;   // duel on a solo map: rush is the plan
            if (ShopPlanner.armorTier(ctx.player) <= 1 && gsec > 300) sRush *= 0.7;
        }
        double sDia = 0;
        List<Location> dgens = view.diamondGenerators();
        if (!dgens.isEmpty() && blocks >= 12 && dia < 8) {
            // farmers head out as soon as they have blocks (and the bed has its first layer, see sDefend)
            if (role == Role.FARMER && gsec > 15) sDia = 1.15;   // the farmer's job outranks a second rush
            else if (role == Role.DEFENDER && !teamMode() && gsec > 60 && integrity >= 0.99) sDia = 0.6;
            else if (ctx.persona.tryhard && gsec > 90) sDia = 0.5;
            // diamonds are an early objective for everyone, not a farmer speciality: a team upgrade is worth more
            // than the second rush, and on short games the old 400 s gate meant nobody ever went
            else if (gsec > 45 && dia < 4) sDia = 0.4;
            else if (gsec > 400) sDia = 0.3;
        }
        double sEm = 0;
        if (!view.emeraldGenerators().isEmpty() && blocks >= 16 && ShopPlanner.emeralds(ctx.player) < 8) {
            // emeralds buy the whole mid game (potions, pearls, diamond armour, a bridge egg): a farmer goes as soon as
            // the first layer is up, the others once their diamonds and their bed are done
            if (role == Role.FARMER && gsec > 60) sEm = 0.95;
            // early emeralds are worth more than early diamonds when they buy the obsidian the bed has been missing
            if (role == Role.FARMER && gsec > 25 && gsec < 240 && bedAlive && integrity >= 0.99 && !bedHasObsidian()) {
                sEm = Math.max(sEm, 0.9);
            }
            else if (gsec > 120 && ShopPlanner.emeralds(ctx.player) < 4) sEm = 0.4;
            else if (ctx.persona.tryhard && gsec > 120) sEm = 0.7;
            else if (role == Role.RUSHER && gsec > 240 && integrity >= 0.9) sEm = 0.5;
            else if (gsec > 300) sEm = 0.4;
        }
        double sGuard = bedAlive ? (role == Role.DEFENDER ? 0.55 : 0.12) : 0;
        double sShare = 0;
        Player needy = null;
        if (teamMode() && iron >= 32 && ctx.tick - lastShare > 1200) {
            needy = needyMate();
            if (needy != null) sShare = 0.7;
        }

        // depositing: resources go home while the team can still use them
        boolean solo = !teamMode() || aliveTeammates() <= 0;
        double sDeposit = 0;
        if (DepositTask.worthDepositing(this) && nearBase && ctx.tick - lastDeposit > 600
                && DepositTask.spot(this, solo) != null) {
            sDeposit = solo ? 0.45 : 0.6;
        }
        // the next-opponent model: somebody we expect to meet near our bed keeps us closer to it
        if (predictedOpponent != null && bedAlive) {
            Location bed = view.bedLocation(color);
            Sighting ps = ctx.perception.get(predictedOpponent);
            Location at = ps != null && ps.lastPos != null ? ps.estimate(ctx.tick) : predictedOpponent.getLocation();
            // a bot that just died is already in the lobby while its brain still holds game-world locations: measuring
            // across worlds throws, so compare only when both sides are in the same world
            if (bed != null && at != null && bed.getWorld() == at.getWorld() && at.distance(bed) < 40) sGuard += 0.15;
        }

        // Camping watch. A bot that falls back to GuardTask twice in a row stands at its bed for the rest of the game:
        // the replay of mini89A shows bots 50-90% stationary at their own base while one player walks the whole ring.
        // After 20 s of loitering with nobody within 24 blocks, defending stops scoring and pushing or a diamond run takes
        // over, which is what a real player does with a quiet base.
        boolean atHome = false;
        try {
            org.bukkit.Location bed = color == null ? null : view.bedLocation(color);
            if (bed != null && ctx.player.getWorld() == bed.getWorld())
                atHome = ctx.player.getLocation().distance(bed) < 14;
        } catch (Throwable ignored) { }
        boolean enemyNear = nearestEnemyPlayer(24) != null;
        if (!ctx.perception.visibleEnemies().isEmpty()) lastEnemySeen = ctx.tick;
        boolean stale = lastEnemySeen > 0 && ctx.tick - lastEnemySeen > 20 * 30;
        if (atHome && !enemyNear) {
            if (campSince < 0) campSince = ctx.tick;
        } else campSince = -1;
        boolean camping = campSince > 0 && ctx.tick - campSince > 20 * 20;

        // hurt with somebody hunting: standing still in a corner is how bots die. Shop/heal or move, don't guard.
        boolean hurtAndHunted = ctx.health() <= 12 && nearestEnemyPlayer(25) != null && !finalLife;
        if (hurtAndHunted) {
            sGuard = 0;
            sDefend *= 0.5;
            if (canShop) sShop = Math.max(sShop, 1.0);
            if (sRush > 0) sRush *= 0.6;                       // do not start a push at 12 hp
            net.h2020.api.stats.CombatStats.inc("state.hurt_hunted");
        }
        if (stale && gsec > 25 && bedAliveNow()) {
            sRush = Math.max(sRush, 0.9);
            sDia = Math.max(sDia, 0.75);
            sDefend *= 0.15;
            sGuard = 0;
            if (++campReports % 60 == 1) net.h2020.api.stats.CombatStats.inc("state.rotate");
        }
        if (camping) {
            sGuard = 0;
            if (bedAliveNow()) sDefend *= 0.25;                // keep a token defence score only while the bed stands
            sRush = Math.max(sRush, 0.85);
            sDia = Math.max(sDia, 0.7);
            if (++campReports % 100 == 1) net.h2020.api.stats.CombatStats.inc("state.anticamp");
        }
        if (role == Role.FARMER && gsec > 30) sRush *= 0.55;   // a farmer that rushes is a farmer not farming
        if (cooling(RushTask.class)) sRush = 0;
        if (cooling(CollectTask.class)) { sDia = 0; sEm = 0; if (!opening) sIron = 0; }
        double[] sc = {sShop, sUpg, sIron, sDefend, sRush, sDia, sEm, sGuard, sShare, sDeposit};
        String[] taskNames = {"shop", "upgrades", "iron", "defend", "rush", "diamond", "emerald", "guard", "share", "deposit"};
        // the team's board first: the job this bot was given gets a real bonus, so four bots do not all pick the same
        // one, and nobody is left without a job
        int teamJob = TeamJobs.jobOf(this);
        int jobIdx = TeamJobs.scoreIndex(teamJob);
        if (jobIdx >= 0 && sc[jobIdx] > 0.02) sc[jobIdx] += 0.55;
        lastJob = TeamJobs.name(teamJob);
        lastScores = sc;
        lastCanShop = canShop;
        lastTarget = target;
        lastBlocks = blocks;
        lastGsec = gsec;
        for (int i = 0; i < sc.length; i++) if (sc[i] > 0) sc[i] += ctx.rng.nextGaussian() * 0.08;
        // the learned macro policy picks among them, with the hand-written scores as its prior
        double[] macroX = net.h2020.bots.ai.ml.MacroPolicy.features(ctx, gsec, blocks, iron, dia,
                ShopPlanner.emeralds(ctx.player), bedAlive, roleIndex(), teammates().size(),
                target == null ? 100 : distanceToBed(target));
        int best = net.h2020.bots.ai.ml.MacroPolicy.choose(ctx, sc, macroX);
        if (sc[best] <= 0.02) {
            net.h2020.bots.ai.ml.MacroPolicy.reward(0);   // nothing was even possible: no lesson
            return new GuardTask(this, 20 * 10);
        }
        justRespawned = false;
        net.h2020.api.stats.CombatStats.inc("brain.task." + taskNames[best]);
        switch (best) {
            case 0: return new ShopTask(this, false);
            case 1: {
                // team upgrades are paid with the diamonds in the team chest: fetch them first when we are short
                if (ShopPlanner.diamonds(ctx.player) < 2 && WithdrawTask.chestWithDiamonds(this) != null) {
                    return new WithdrawTask(this);
                }
                return new ShopTask(this, true);
            }
            case 2:
                if (teamMode() && blocks < 16) ctx.chat.callout("need iron", 0.06);
                return new CollectTask(this, gen, CollectTask.Kind.IRON, opening ? openingIron : Math.max(iron + 8, 24), 20 * 25);
            case 3: return new DefendTask(this, secondLayer);
            case 4: return new RushTask(this, target);
            case 5: return new CollectTask(this, nearest(dgens), CollectTask.Kind.DIAMOND, dia + AIMath.range(ctx.rng, 2, 4), 20 * 45);
            case 6: return new CollectTask(this, bestEmerald(), CollectTask.Kind.EMERALD,
                    ShopPlanner.emeralds(ctx.player) + 2, 20 * 50);
            case 7: return new GuardTask(this, 20 * AIMath.range(ctx.rng, 8, 25));
            case 8: lastShare = ctx.tick; ctx.chat.callout("here", 0.2); return new ShareTask(this, needy, Material.IRON_INGOT, AIMath.range(ctx.rng, 4, 10));
            case 9: {
                lastDeposit = ctx.tick;
                org.bukkit.block.Block chest = DepositTask.spot(this, solo);
                if (chest == null) return null;
                return new DepositTask(this, chest);
            }
            default: return null;
        }
    }

    /**
     * A human never stands still for long - the replay of a real player had a longest idle of six seconds. A bot with
     * a task that has stopped moving for two seconds is either waiting on something that will not come or is stuck,
     * and either way the next decision is better than the wait.
     */
    private void idleWatchdog() {
        double x = ctx.pos().getX(), z = ctx.pos().getZ();
        if (Double.isNaN(lastX)) { lastX = x; lastZ = z; lastMovedTick = ctx.tick; return; }
        if (Math.hypot(x - lastX, z - lastZ) > 0.12) {
            lastX = x; lastZ = z; lastMovedTick = ctx.tick;
            return;
        }
        if (ctx.tick - lastMovedTick < 45) return;          // two and a bit seconds
        if (task == null) return;
        if (ctx.combat.engaged() || ctx.nav.bridging() || ctx.c.containerOpen() || ctx.player.isSneaking()) {
            lastMovedTick = ctx.tick;                        // standing is the job right now
            return;
        }
        String name = task.name();
        if (name.startsWith("rush") || name.startsWith("bed") || name.startsWith("break")) {
            // breaking a bed or a wall is stationary on purpose
            if (ctx.tick - lastMovedTick < 100) return;
        }
        net.h2020.api.stats.CombatStats.inc("idle.break." + name);
        net.h2020.api.stats.CombatStats.inc("idle.break");
        if (teamMode()) ctx.chat.callout("moving", 0.02);
        task.abort();
        task = null;
        nextDecision = ctx.tick;                             // decide again now
        lastMovedTick = ctx.tick;
    }

    /** Does our own bed already have obsidian on it? */
    private boolean bedHasObsidian() {
        if (color == null) return true;
        Location bed = view.bedLocation(color);
        if (bed == null) return true;
        BedDefense d = BedDefense.of(bed);
        for (int[] c : d.layer1) if (ctx.world().type(c[0], c[1], c[2]) == Material.OBSIDIAN) return true;
        for (int[] c : d.layer2) if (ctx.world().type(c[0], c[1], c[2]) == Material.OBSIDIAN) return true;
        return false;
    }

    private BedDefense defenseOf(TeamColor t) {
        if (t == null) return null;
        Location bed = view.bedLocation(t);
        return bed == null ? null : BedDefense.of(bed);
    }

    /**
     * Nobody we know of is within 10 blocks of that bed (free bed / they are all out rushing). Only what we have
     * actually seen counts - an unspotted defender at home does not exist as far as this decision goes.
     */
    private boolean teamIsAway(TeamColor t) {
        Location bed = view.bedLocation(t);
        if (bed == null) return false;
        for (Sighting s : ctx.perception.all()) {
            if (!s.known || s.ally) continue;
            Location e = s.estimate(ctx.tick);
            if (e != null && e.getWorld() == bed.getWorld() && e.distance(bed) < 10) return false;
        }
        return true;
    }

    /**
     * Not every fight should be started: somebody above us inside jump-fall-hit range, or a fight offered from a
     * one-wide line with the void behind, is a fight to walk away from - unless he is already hitting us.
     */
    private boolean declineEngagement() {
        Sighting best = ctx.perception.bestThreat();
        if (best == null) return false;
        if (ctx.tick - best.lastHurtMeTick < 60) return false;
        Location e = best.estimate(ctx.tick);
        if (e == null) return false;
        Location me = ctx.pos();
        if (ctx.health() >= 14) return false;
        // out-geared and hurt: trading here is how a bot feeds a diamond sword
        Player threat = best.player;
        if (threat != null && gearEdge(threat) <= -5 && ctx.health() < 12) {
            net.h2020.api.stats.CombatStats.inc("fight.declined.gear");
            return true;
        }
        boolean bad = net.h2020.bots.ai.combat.PositionSense.badPosition(ctx, me, best.player, 0.4);
        if (bad) {
            // a bad stance that can be walked out of is not a reason to refuse the fight: take it and fix the ground
            if (net.h2020.bots.ai.combat.PositionSense.correctSpot(ctx, me, best.player) != null) {
                net.h2020.api.stats.CombatStats.inc("fight.declined.avoidable");
                return false;
            }
            net.h2020.api.stats.CombatStats.inc("fight.declined");
        }
        return bad;
    }

    /** Is a known enemy standing within `r` blocks of us (as far as our client can tell)? */
    private boolean threatWithin(double r) {
        Location me = ctx.pos();
        for (Sighting s : ctx.perception.visibleEnemies()) {
            Location e = s.estimate(ctx.tick);
            if (e != null && e.getWorld() == me.getWorld() && e.distance(me) < r) return true;
        }
        return false;
    }

    private boolean cooling(Class<?> c) {
        Long until = quickFailUntil.get(c);
        return until != null && ctx.tick < until;
    }

    private boolean teamHasDefender() {
        for (Player p : teammates()) {
            BaseBrain b = BotAI.brainOf(p);
            if (b instanceof BedwarsBrain && ((BedwarsBrain) b).role == Role.DEFENDER) return true;
        }
        return false;
    }

    private Player needyMate() {
        for (Player p : teammates()) {
            if (p.isDead() || view.isRespawning(p) || p.getLocation().distance(ctx.pos()) > 15) continue;
            if (Inv.count(p, Material.IRON_INGOT) < 8) return p;
        }
        return null;
    }

    /**
     * Which emerald island to run to. The nearest one is the instinct, but on maps like Airshow there are four of them
     * round the middle: the nearest is often the one the enemy team is sitting on, the one a teammate already stripped,
     * or the one we just left. Score by distance, danger, recency and crowding instead of distance alone.
     */
    private Location bestEmerald() {
        List<Location> islands = view.emeraldGenerators();
        if (islands == null || islands.isEmpty()) return null;
        Location me = ctx.pos();
        Location best = null;
        double bestScore = Double.MAX_VALUE;
        Location nearestIsland = nearest(islands);
        for (Location l : islands) {
            if (l.getWorld() != me.getWorld()) continue;
            double d = me.distance(l);
            double score = d * 0.02;
            // danger: an enemy standing on or near it
            for (Player e : me.getWorld().getPlayers()) {
                if (e == ctx.player || e.isDead() || sameTeam(e)) continue;
                if (e.getLocation().distance(l) < 9) { score += 8; break; }
            }
            // recency: we were just there and took what we came for
            Long last = emeraldVisit.get(l);
            if (last != null && ctx.tick - last < 20 * 90) score += 5;
            // crowding: a teammate is already working it
            for (Player mate : teammates()) {
                if (mate == ctx.player || mate.isDead() || mate.getWorld() != l.getWorld()) continue;
                if (mate.getLocation().distance(l) < 6) { score += 4; break; }
            }
            if (score < bestScore) { bestScore = score; best = l; }
        }
        if (best == null) return nearestIsland;
        if (nearestIsland != null && !best.equals(nearestIsland)) net.h2020.api.stats.CombatStats.inc("emerald.far");
        emeraldVisit.put(best, ctx.tick);
        return best;
    }

    private boolean sameTeam(Player other) {
        net.h2020.api.game.GameTeam a = game.teamOf(ctx.player), b = game.teamOf(other);
        return a != null && a == b;
    }

    private Location nearest(List<Location> ls) {
        Location me = ctx.pos();
        Location best = null;
        double bd = Double.MAX_VALUE;
        for (Location l : ls) {
            if (l.getWorld() != me.getWorld()) continue;
            double d = l.distanceSquared(me);
            if (d < bd) { bd = d; best = l; }
        }
        return best;
    }

    /** Nearest enemy team with a bed (or players when all beds are gone). Team bots rush the same target. */
    private TeamColor chooseTarget() {
        // rush with the team: if a teammate is already on a bed, that is the bed (no split pushes)
        TeamColor together = TeamPlay.rushTargetOfTeam(this);
        if (together != null && view.bedAlive(together)) return together;
        Location from = view.teamSpawn(color);
        if (from == null) from = ctx.pos();
        TeamColor best = null;
        double bd = Double.MAX_VALUE;
        for (GameTeam t : game.teams()) {
            if (t.color() == color || !t.alive()) continue;
            boolean bed = view.bedAlive(t.color());
            Location b = view.bedLocation(t.color());
            if (b == null) continue;
            BedDefense td = bed ? BedDefense.of(b) : null;
            double integrity = td == null ? 1 : td.integrity(ctx.world());
            double d = ScoutModel.targetScore(game.world(), t.color(), b.distance(from), bed,
                    !t.members().isEmpty(), integrity);
            if (d < bd) { bd = d; best = t.color(); }
        }
        return best;
    }

    /**
     * Teammate callouts, understood the way a team understands them. Nobody reacts to everything (awareness decides
     * whether the line was even noticed), never while a bed is being broken, and never anything that would throw the
     * game (voiding with the bed gone).
     * @return true when it decided something for this tick
     */
    private boolean handleTeamTalk() {
        TeamTalk.Line line = TeamTalk.latest(talkVersion, ctx.player);
        if (line == null) return false;
        talkVersion = TeamTalk.version();
        Player from = org.bukkit.Bukkit.getPlayerExact(line.sender);
        if (from == null || from.getWorld() != ctx.player.getWorld() || from.isDead()) return false;
        if (!teammates().contains(from)) return false;                   // enemy chatter is not a callout for us
        if (ctx.rng.nextDouble() > 0.35 + 0.6 * ctx.persona.skill.awareness) return false;
        switch (TeamTalk.intent(line.raw)) {
            case INC: {
                Location bed = color == null ? null : view.bedLocation(color);
                if (bed == null || !view.bedAlive(color)) return false;
                if (task instanceof GuardTask) return false;
                if (ctx.pos().distance(bed) > 48) return false;
                switchTo(new GuardTask(this, 20 * 20));
                ctx.chat.callout(ctx.rng.nextBoolean() ? "omw" : "im coming", 0.5);
                return true;
            }
            case VOID: {
                if (color == null || !view.bedAlive(color)) return false;   // no bed: that would be the final death
                if (task instanceof VoidTask) return false;
                // hand the valuables to whoever is close first, then void
                Player mate = nearbyMate(6);
                if (mate != null && GiftTask.carriesValuables(ctx.player)) { switchTo(new GiftTask(this, mate, true)); return true; }
                switchTo(new VoidTask(this));
                ctx.chat.callout("ok", 0.4);
                return true;
            }
            case DROP: {
                if (task instanceof GiftTask) return false;
                switchTo(new GiftTask(this, from, true));
                return true;
            }
            case SPLIT: {
                Location gen = color == null ? null : view.teamGenerator(color);
                if (gen == null) return false;
                switchTo(new CollectTask(this, gen, CollectTask.Kind.IRON, ShopPlanner.iron(ctx.player) + 8, 20 * 20));
                return true;
            }
            default:
                return false;
        }
    }

    /**
     * "I am going to die here, and I am carrying the team's emeralds." The learned fight model says the fight is lost;
     * the bot first tries to hand the valuables to a teammate, then to chest them in the island's ender chest, and
     * only then throws them into the void so the kill loots nothing.
     */
    private boolean doomedItems() {
        if (ctx.tick - lastDoomTry < 200) return false;
        if (ctx.health() > 8 || !ctx.combat.engaged()) return false;
        if (ctx.combat.winProbability() > 0.22) return false;
        if (!GiftTask.carriesValuables(ctx.player)) return false;
        lastDoomTry = ctx.tick;
        Player mate = nearbyMate(8);
        ctx.combat.disengage();
        if (mate != null) { switchTo(new GiftTask(this, mate, true)); return true; }
        Location chest = EndChestTask.nearest(ctx, 16);
        if (chest != null) { switchTo(new EndChestTask(this, chest)); return true; }
        switchTo(new VoidItemsTask(this));
        return true;
    }

    /** A living teammate within `r` blocks. */
    private Player nearbyMate(double r) {
        Player best = null;
        double bd = r;
        for (Player p : teammates()) {
            if (p == ctx.player || p.isDead() || p.getWorld() != ctx.player.getWorld()) continue;
            double d = ctx.pos().distance(p.getLocation());
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    @Override
    public void onDeath(Player killer, boolean voidDeath) {
        super.onDeath(killer, voidDeath);
        // dying is the most expensive thing that can happen: blame the plan that was running when it did
        net.h2020.bots.ai.ml.MacroPolicy.penalize(1.0, voidDeath ? "void" : "death");
        if (task != null) { task.abort(); task = null; }
    }

    @Override
    protected void onStop() {
        if (task != null) task.abort();
        net.h2020.api.game.PlacedRegistry.unregister(game.world());
        net.h2020.api.game.GeneratorRegistry.clear(game.world());
    }
}
