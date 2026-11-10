package net.h2020.bots.ai.ml;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.combat.Style;
import net.h2020.bots.ai.inv.Inv;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Which block placement to make, learned from the fights it wins.
 * <p>
 * A softmax policy over the eight things a 1.8 player can do with blocks in a duel (nothing, stomp, 2-block pillar,
 * middle exit, head hitter, tower, bridge wall, bridge cut). The score of each action is its prior - the persona's
 * habit, i.e. what that player would do anyway - plus a learned correction from the context the fight is in.
 * <p>
 * Learning is REINFORCE with a baseline: every decision is recorded with the distribution it was sampled from, and
 * when the fight ends the chosen action is credited or blamed by the fight result against the running average. A
 * placement that keeps showing up in lost fights loses its score in that kind of fight, one that keeps showing up in
 * won fights gains it. Nothing about the geometry is learned - whether a stomp is even possible stays a physics check
 * in BlockTactics; this only decides what to try among the things that are possible.
 */
public final class PlacementPolicy {
    public static final int NONE = 0, STOMP = 1, DUEL_PILLAR = 2, MIDDLE_EXIT = 3, HEAD_HITTER = 4, TOWER = 5,
            WALL = 6, CUT = 7, SHELTER = 8, BACKSTEP = 9, RAMP = 10, DRIVE_THRU = 11, BED_BLOCKOFF = 12;
    public static final int ACTIONS = 13;
    public static final int N = 18;

    /** What a placement did in the twelve ticks after it went down. */
    public static final int OUTCOME_NONE = 0, OUTCOME_HIT = 1, OUTCOME_COLLAPSE = 2, OUTCOME_TAKEN = 3;
    private static final int OUTCOME_WINDOW = 12;

    private static final double LR = 0.06;
    private static final double[] W = new double[ACTIONS * N];
    private static double baseline = 0.5;
    private static long decisions, fights;
    private static File file;
    private static boolean dirty;

    /** All per-fight state of one bot. Sixty bots run their own Combat, so none of this may be shared. */
    private static final class State {
        final List<double[]> pending = new ArrayList<double[]>();
        final List<Boolean> resolved = new ArrayList<Boolean>();
        double[] lastX;
        double lastSpeed = 0.5;
        int armedPending = -1;
        long armedDoneAt = -1;
        double foeSpeed0 = -1;
        int lastOutcome = OUTCOME_NONE;
        int lastResolved = -1;
        long lastResolvedDoneAt = -1;
        int minedFast;
        final int[] armed = new int[ACTIONS];
        final int[][] outcomes = new int[ACTIONS][4];
    }
    private static final java.util.Map<java.util.UUID, State> STATES =
            new java.util.concurrent.ConcurrentHashMap<java.util.UUID, State>();

    private static State state(java.util.UUID id) {
        if (id == null) return null;
        State s = STATES.get(id);
        if (s == null) { s = new State(); State prev = STATES.putIfAbsent(id, s); if (prev != null) s = prev; }
        return s;
    }

    /**
     * Blocks we placed, when, and <b>whose</b> they were. The owner matters: the "mined fast" penalty is a judgement
     * on the bot that placed the block, not on whoever dug it out, and a human digging a bot's wall must not blame that
     * bot's fight.
     */
    private static final java.util.Map<Long, long[]> PLACED_AT = new java.util.HashMap<Long, long[]>();

    private PlacementPolicy() {}

    public static void install(JavaPlugin plugin) {
        if (plugin == null) return;
        file = new File(plugin.getDataFolder(), "placement-policy.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { if (dirty) save(); }
        }, 1400L, 1400L);
    }

    /** The context of one decision, in the order the weights expect it. */
    public static double[] features(BotContext ctx, Style style, Player target, double d, boolean onLine, boolean losing, double pWin) {
        double[] x = new double[N];
        Player me = ctx.player;
        double myHp = Math.max(0, me.getHealth()), theirHp = target == null ? 20 : Math.max(0, target.getHealth());
        x[0] = 1;
        x[1] = Math.min(1, myHp / 20.0);
        x[2] = Math.min(1, theirHp / 20.0);
        x[3] = clamp((myHp - theirHp) / 20.0, -1, 1);
        x[4] = clamp(d / 8.0, 0, 1);
        x[5] = onLine ? 1 : 0;
        x[6] = Math.min(1, Inv.count(me, Inv.BLOCK) / 64.0);
        x[7] = ctx.persona.skill.overall;
        x[8] = losing ? 1 : 0;
        x[9] = clamp((pWin - 0.5) * 2, -1, 1);
        x[10] = target != null && !target.isOnGround() ? 1 : 0;
        x[11] = ctx.c.onGround() ? 1 : 0;
        x[12] = style.bridgeBlock ? 1 : 0;
        x[13] = ctx.persona.tryhard ? 1 : 0;
        // height, exposure, what they can dig with, and whether they can answer with blocks: the four things that
        // decide whether a wall is worth two blocks or a waste of the stack
        x[14] = target == null ? 0 : clamp((target.getLocation().getY() - me.getLocation().getY()) / 3.0, -1, 1);
        x[15] = Math.min(1, ctx.world().voidSides((int) Math.floor(me.getLocation().getX()),
                (int) Math.floor(me.getLocation().getY() + 0.01), (int) Math.floor(me.getLocation().getZ())) / 4.0);
        x[16] = target != null && Inv.has(target, Inv.PICKAXE) ? 1 : 0;
        x[17] = target == null ? 0 : Math.min(1, Inv.count(target, Inv.BLOCK) / 64.0);
        return x;
    }

    /** The persona's habits: the score the action starts with before any learning. */
    private static double prior(Style style, int action, double d, boolean onLine, boolean losing, double hp) {
        switch (action) {
            case STOMP: return style.stomp * 1.1;
            case DUEL_PILLAR: return onLine ? -1 : style.duelPillar * 0.8;
            case MIDDLE_EXIT: return onLine ? style.middleExit * 1.0 : -1;
            case HEAD_HITTER: return style.headHitter * 0.5;
            case TOWER: return (losing && hp <= 8 && !onLine) ? 0.7 : -0.6;
            case WALL: return onLine && style.bridgeBlock ? 0.6 : -1;
            case CUT: return onLine && style.bridgeBlock ? 0.45 : -1;
            case SHELTER: return losing || hp <= 10 ? 0.4 : 0.1;          // box up when the trade is going badly
            case BACKSTEP: return losing ? 0.35 : 0.05;                   // block the chaser while backing off
            case RAMP: return d > 2.6 && d < 5.5 ? 0.3 : -0.2;            // a step up for high ground in a fight
            default: return 0.25;                       // doing nothing is always an option
        }
    }

    /**
     * Rank the actions for this moment, highest score first: the caller tries them in order and keeps the first one
     * that is physically possible. The features used are remembered for the learning update.
     */
    public static int[] rank(java.util.UUID id, BotContext ctx, Style style, Player target, double d, boolean onLine,
            boolean losing, double pWin) {
        State s = state(id);
        if (s == null) return new int[0];
        double[] x = features(ctx, style, target, d, onLine, losing, pWin);
        double[] score = new double[ACTIONS];
        double hp = ctx.health();
        // what a person does in this exact state is the prior the bot's own learning adjusts; it never overrides a
        // placement that is physically impossible here (those keep their negative priors)
        double[] human = HumanPlacements.state(ctx.player, target);
        for (int a = 0; a < ACTIONS; a++) {
            score[a] = prior(style, a, d, onLine, losing, hp) + dot(a, x);
            if (a == BED_BLOCKOFF) score[a] = -1e9;   // driven by the bed raid, never by the duel placement policy
            int h = humanClass(a);
            if (h >= 0) score[a] += 0.55 * HumanPlacements.prob(h, human);
        }
        // experimentation: the bandit has to try what it has not tried, weighted by how rarely each arm was used, so
        // a new placement type gets discovered instead of the policy settling on the three it already knows
        for (int a = 0; a < ACTIONS; a++) {
            if (score[a] > 0.02) score[a] += 0.9 / (1.0 + TRIES[a]);
        }
        double explore = 0.32 - 0.20 * ctx.persona.skill.overall;
        if (ctx.rng.nextDouble() < explore) {
            int pick = -1, seen = 0;
            for (int a = 0; a < ACTIONS; a++) {
                if (score[a] <= 0.02) continue;
                seen++;
                if (ctx.rng.nextDouble() < 1.0 / seen) pick = a;
            }
            if (pick >= 0 && score[pick] > 0.02) score[pick] += 10.0;      // force it to the front of the ranking
        }
        int[] order = new int[ACTIONS];
        for (int i = 0; i < ACTIONS; i++) order[i] = i;
        for (int i = 1; i < ACTIONS; i++) {
            int v = order[i], j = i - 1;
            while (j >= 0 && score[order[j]] < score[v]) { order[j + 1] = order[j]; j--; }
            order[j + 1] = v;
        }
        TRIES[order[0]] += 1;
        s.lastX = x;
        decisions++;
        return order;
    }

    /** The human model's class for each placement action (-1 when people cannot teach us that one). */
    private static int humanClass(int action) {
        switch (action) {
            case STOMP: return HumanPlacements.STOMP;
            case DUEL_PILLAR: return HumanPlacements.PILLAR;
            case WALL: return HumanPlacements.WALL;
            case HEAD_HITTER: return HumanPlacements.HEAD;
            case CUT: return HumanPlacements.FLOOR;
            default: return -1;                          // middle exit / tower are not observable from a human's block
        }
    }

    /** How often each arm has been tried, for the novelty term. */
    private static final double[] TRIES = new double[ACTIONS];

    /**
     * The action that actually started, at `chosenIndex` in the ranking: remember it with the probability its rank
     * carried, for the REINFORCE update when the fight ends.
     */
    public static void record(java.util.UUID id, int[] ranking, int chosenIndex) {
        State s = state(id);
        if (s == null || ranking == null || s.lastX == null || ranking.length == 0) return;
        int idx = Math.max(0, Math.min(ranking.length - 1, chosenIndex));
        double[] p = rankProbabilities(ranking.length, 1.0);
        double[] u = new double[N + 4];
        u[0] = ranking[idx];
        u[1] = p[idx];
        System.arraycopy(s.lastX, 0, u, 2, N);
        u[N + 2] = s.lastSpeed;
        s.lastSpeed = 0.5;
        s.pending.add(u);
        // remember this row as the one whose placement is about to go down; the row stores its own pending index
        // until the outcome overwrites it, and resolved keeps unresolved rows scoring zero
        if (u[0] == NONE) {
            // a no-op decision places nothing: it must not swallow the next real placement's outcome
            s.armedPending = -1;
            u[N + 3] = OUTCOME_NONE;
        } else {
            s.armedPending = s.pending.size() - 1;
            u[N + 3] = s.armedPending;
        }
        s.resolved.add(Boolean.FALSE);
        s.armedDoneAt = -1;
        s.foeSpeed0 = -1;
        s.lastOutcome = OUTCOME_NONE;
        s.lastResolved = -1;
        s.lastResolvedDoneAt = -1;
        dirty = true;
    }

    /** Softmax over rank positions with temperature T. */
    private static double[] rankProbabilities(int n, double t) {
        double[] raw = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) { raw[i] = Math.exp(-i / t); sum += raw[i]; }
        for (int i = 0; i < n; i++) raw[i] /= sum;
        return raw;
    }

    /**
     * Fight over: credit (or blame) the actions taken in it by the result against the running baseline. Only the
     * chosen action is moved - the ones that were never tried may simply have been impossible from where we stood.
     */
    /** A placement went down: remember where, so a fast break can be blamed on it. */
    public static void notePlaced(java.util.UUID owner, int x, int y, int z, long nowMs) {
        if (PLACED_AT.size() > 96) {
            java.util.Iterator<java.util.Map.Entry<Long, long[]>> it = PLACED_AT.entrySet().iterator();
            while (it.hasNext()) if (nowMs - it.next().getValue()[0] > 20_000L) it.remove();
        }
        long ownerLo = owner == null ? 0L : owner.getMostSignificantBits() ^ owner.getLeastSignificantBits();
        PLACED_AT.put(cellKey(x, y, z), new long[]{nowMs, ownerLo});
        net.h2020.api.stats.CombatStats.inc("ml.place.tracked");
    }

    private static long cellKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    /** Somebody broke a block; if it was ours and recent, that placement failed at its job. */
    public static void noteBroken(int x, int y, int z, long nowMs) {
        long[] at = PLACED_AT.remove(cellKey(x, y, z));
        if (at == null) return;
        net.h2020.api.stats.CombatStats.inc("ml.place.broken");
        if (nowMs - at[0] > 3000L) return;
        // the penalty belongs to the bot that placed the block, found from the ownership recorded with it
        State s = stateByHash(at[1]);
        if (s != null) s.minedFast++;
        net.h2020.api.stats.CombatStats.inc("ml.place.mined");
    }

    /** Resolve a stored owner hash back to its State (null when that bot has no state or has since gone). */
    private static State stateByHash(long hash) {
        for (java.util.Map.Entry<java.util.UUID, State> e : STATES.entrySet()) {
            java.util.UUID id = e.getKey();
            if ((id.getMostSignificantBits() ^ id.getLeastSignificantBits()) == hash) return e.getValue();
        }
        return null;
    }

    public static void finish(java.util.UUID id, boolean win) {
        State s = state(id);
        if (s == null) return;
        fights++;
        double y = win ? 1 : 0;
        double adv = y - baseline;
        baseline = 0.97 * baseline + 0.03 * y;
        double lr = LR / (1.0 + fights / 300.0);
        // a block that was taken down within three seconds did not do its job, whatever the fight ended up doing
        double minedSc = -0.25 * Math.min(3, s.minedFast);
        for (double[] u : s.pending) {
            int a = (int) u[0];
            double prob = u[1];
            double speed = u[N + 2];
            double outcome = outcomeScore(s, u);   // what the block itself achieved, independent of the fight result
            double sc = (adv + minedSc) * (1.0 + 0.25 * speed) + 0.9 * outcome;
            for (int i = 0; i < N; i++) W[a * N + i] += lr * sc * (1 - prob) * u[2 + i];
        }
        s.pending.clear();
        s.resolved.clear();
        s.armedPending = -1;
        s.armedDoneAt = -1;
        s.foeSpeed0 = -1;
        s.lastOutcome = OUTCOME_NONE;
        s.lastResolved = -1;
        s.lastResolvedDoneAt = -1;
        s.minedFast = 0;
        dirty = true;
    }

    /** The placement that was started finished in `ticks` ticks: remember how quick it was. */
    public static void completed(java.util.UUID id, int ticks) {
        State s = state(id);
        if (s == null) return;
        s.lastSpeed = clamp(1.0 - ticks / 80.0, 0, 1);
    }

    // -------------------------------------------------------------------------------------- placement outcomes

    /**
     * The placer reported DONE: the block of the decision at {@link State#armedPending} is down. Sample the target's
     * horizontal speed now - the stomp's signal is that it collapses in the ticks that follow. Only the first DONE of
     * a decision arms it (a two-block stomp reports twice).
     */
    public static void placementDone(java.util.UUID id, long tick, Player target) {
        State s = state(id);
        if (s == null) return;
        if (s.armedPending < 0 || s.armedDoneAt >= 0) return;
        if (s.armedPending >= s.pending.size()) { s.armedPending = -1; return; }
        s.armedDoneAt = tick;
        s.foeSpeed0 = horizontalSpeed(target);
        int a = (int) s.pending.get(s.armedPending)[0];
        if (a >= 0 && a < ACTIONS) s.armed[a]++;
        net.h2020.api.stats.CombatStats.inc("ml.place.armed");
    }

    /** Every combat tick: resolve the armed placement's outcome once the window closes or its stomp lands. */
    public static void poll(java.util.UUID id, long tick, Player target) {
        State s = state(id);
        if (s == null) return;
        if (s.armedPending < 0 || s.armedDoneAt < 0 || s.lastOutcome != OUTCOME_NONE) return;
        double speed = horizontalSpeed(target);
        // the block stopped him dead: that is the stomp's own effect and it counts even if no hit follows
        if (s.foeSpeed0 >= 0.08 && speed <= s.foeSpeed0 * 0.5) { resolve(s, OUTCOME_COLLAPSE); return; }
        if (tick - s.armedDoneAt > OUTCOME_WINDOW) resolve(s, OUTCOME_NONE);
    }

    /** A hit of ours landed: a placement that was still being judged gets credit for it (a hit beats a collapse). */
    public static void onLanded(java.util.UUID id, long tick, Player victim) {
        State s = state(id);
        if (s == null) return;
        if (s.armedPending >= 0 && s.armedDoneAt >= 0 && tick - s.armedDoneAt <= OUTCOME_WINDOW) {
            resolve(s, OUTCOME_HIT);
            return;
        }
        upgradeToHit(s, tick);
    }

    /** We were hit inside the window: the placement gave the opponent a free hit rather than taking one away. */
    public static void onTaken(java.util.UUID id, long tick) {
        State s = state(id);
        if (s == null) return;
        if (s.armedPending < 0 || s.armedDoneAt < 0) return;
        if (tick - s.armedDoneAt <= OUTCOME_WINDOW) resolve(s, OUTCOME_TAKEN);
    }

    /** Write the outcome back into the row that produced it and count it, then clear the armed state. */
    private static void resolve(State s, int outcome) {
        if (s.armedPending < 0 || s.armedPending >= s.pending.size()) {
            s.armedPending = -1; s.armedDoneAt = -1; s.foeSpeed0 = -1;
            return;
        }
        double[] row = s.pending.get(s.armedPending);
        row[N + 3] = outcome;
        s.resolved.set(s.armedPending, Boolean.TRUE);
        int a = (int) row[0];
        if (a >= 0 && a < ACTIONS) s.outcomes[a][outcome]++;
        switch (outcome) {
            case OUTCOME_HIT: net.h2020.api.stats.CombatStats.inc("ml.place.outcome.hit"); break;
            case OUTCOME_COLLAPSE: net.h2020.api.stats.CombatStats.inc("ml.place.outcome.collapse"); break;
            case OUTCOME_TAKEN: net.h2020.api.stats.CombatStats.inc("ml.place.outcome.taken"); break;
            default: net.h2020.api.stats.CombatStats.inc("ml.place.outcome.none"); break;
        }
        s.lastResolved = s.armedPending;
        s.lastResolvedDoneAt = s.armedDoneAt;
        s.lastOutcome = outcome;
        s.armedPending = -1;
        s.armedDoneAt = -1;
        s.foeSpeed0 = -1;
    }

    /** A collapse already resolved inside this window and a hit landed after it: the hit is the better outcome. */
    private static void upgradeToHit(State s, long tick) {
        if (s.lastOutcome != OUTCOME_COLLAPSE || s.lastResolved < 0 || s.lastResolved >= s.pending.size()) return;
        if (s.lastResolvedDoneAt < 0 || tick - s.lastResolvedDoneAt > OUTCOME_WINDOW) return;
        double[] row = s.pending.get(s.lastResolved);
        row[N + 3] = OUTCOME_HIT;
        int a = (int) row[0];
        if (a >= 0 && a < ACTIONS) {
            if (s.outcomes[a][OUTCOME_COLLAPSE] > 0) s.outcomes[a][OUTCOME_COLLAPSE]--;
            s.outcomes[a][OUTCOME_HIT]++;
        }
        net.h2020.api.stats.CombatStats.add("ml.place.outcome.collapse", -1);
        net.h2020.api.stats.CombatStats.inc("ml.place.outcome.hit");
        s.lastOutcome = OUTCOME_HIT;
    }

    private static double horizontalSpeed(Player p) {
        if (p == null) return 0;
        org.bukkit.util.Vector v = p.getVelocity();
        return Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
    }

    /** What one placement achieved: +1 hit, +0.5 collapse, -0.6 taken, 0 for none / never resolved. */
    private static double outcomeScore(State s, double[] u) {
        int idx = s.pending.indexOf(u);
        if (idx < 0 || idx >= s.resolved.size() || !s.resolved.get(idx).booleanValue()) return 0;
        int o = (int) u[N + 3];
        if (o == OUTCOME_HIT) return 1.0;
        if (o == OUTCOME_COLLAPSE) return 0.5;
        if (o == OUTCOME_TAKEN) return -0.6;
        return 0;
    }

    /** The fight ended without a result (both walked away): nothing to learn from it. */
    public static void discard(java.util.UUID id) {
        State s = state(id);
        if (s == null) return;
        s.pending.clear();
        s.resolved.clear();
        s.armedPending = -1;
        s.armedDoneAt = -1;
        s.foeSpeed0 = -1;
        s.lastOutcome = OUTCOME_NONE;
        s.lastResolved = -1;
        s.lastResolvedDoneAt = -1;
    }

    private static double dot(int action, double[] x) {
        double z = 0;
        int base = action * N;
        for (int i = 0; i < N; i++) z += W[base + i] * x[i];
        return z;
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** Re-read this model from disk (a training generation was restored). */
    public static void reload() { load(); }

    private static void load() {
        if (file == null || !file.exists()) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            List<Double> w = y.getDoubleList("weights");
            if (y.getInt("n", 0) != N) {   // feature vector changed shape: the old weights mean nothing here
                net.h2020.api.stats.CombatStats.inc("ml.placementpolicy.reset");
                return;
            }
            for (int i = 0; i < Math.min(W.length, w.size()); i++) W[i] = w.get(i);
            baseline = y.getDouble("baseline", 0.5);
            fights = y.getLong("fights", 0);
            decisions = y.getLong("decisions", 0);
        } catch (Throwable ignored) { }
    }

    public static void save() {
        if (file == null) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
            List<Double> w = new ArrayList<Double>(W.length);
            for (double v : W) w.add(v);
            y.set("weights", w);
            y.set("n", N);
            y.set("baseline", baseline);
            y.set("fights", fights);
            y.set("decisions", decisions);
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (Throwable ignored) { }
    }

    public static void reset() {
        for (int i = 0; i < W.length; i++) W[i] = 0;
        baseline = 0.5;
        fights = 0;
        decisions = 0;
        STATES.clear();
        dirty = true;
        save();
    }

    /**
     * Headless check of the attribution path: no fight, no player, just the three outcomes, the update and two bots
     * whose placement state must not interfere. Run from the console with {@code /bot ml placeoutcome}.
     */
    public static String selftest() {
        reset();
        StringBuilder bad = new StringBuilder();
        StringBuilder ibad = new StringBuilder();
        java.util.UUID a = java.util.UUID.randomUUID();
        State sa = state(a);
        if (sa.lastX == null) { sa.lastX = new double[N]; sa.lastX[0] = 1; }
        // 1) a hit inside the window is the placement's hit
        record(a, new int[]{STOMP, NONE}, 0);
        placementDone(a, 100, null);
        onLanded(a, 106, null);
        if (sa.lastOutcome != OUTCOME_HIT || sa.outcomes[STOMP][OUTCOME_HIT] != 1) bad.append(" hit");
        // 2) taking a hit inside the window is the placement's fault
        record(a, new int[]{STOMP, NONE}, 0);
        placementDone(a, 200, null);
        onTaken(a, 203);
        if (sa.lastOutcome != OUTCOME_TAKEN || sa.outcomes[STOMP][OUTCOME_TAKEN] != 1) bad.append(" taken");
        // 3) no hit either way by the time the window closes: the placement changed nothing
        record(a, new int[]{STOMP, NONE}, 0);
        placementDone(a, 300, null);
        poll(a, 313, null);
        if (sa.lastOutcome != OUTCOME_NONE || sa.outcomes[STOMP][OUTCOME_NONE] != 1) bad.append(" none");
        int hit = sa.outcomes[STOMP][OUTCOME_HIT], taken = sa.outcomes[STOMP][OUTCOME_TAKEN];
        int none = sa.outcomes[STOMP][OUTCOME_NONE], armed = sa.armed[STOMP];
        if (armed != 3) bad.append(" armed");
        // 4) two bots interleaved: a's hit is a's, and b's row must still be unresolved; finishing a cannot eat b
        java.util.UUID b = java.util.UUID.randomUUID();
        State sb = state(b);
        if (sb.lastX == null) { sb.lastX = new double[N]; sb.lastX[0] = 1; }
        record(a, new int[]{STOMP, NONE}, 0);
        record(b, new int[]{STOMP, NONE}, 0);
        placementDone(a, 400, null);
        onLanded(a, 406, null);
        if (sa.lastOutcome != OUTCOME_HIT) ibad.append(" a-hit");
        if (sb.resolved.isEmpty() || sb.resolved.get(0).booleanValue()) ibad.append(" b-resolved");
        try {
            finish(a, false);
        } catch (Throwable t) {
            bad.append(" finish:").append(t);
        }
        if (sb.pending.isEmpty()) ibad.append(" b-pending");
        if (!sa.pending.isEmpty()) ibad.append(" a-pending");
        reset();
        return String.format("placement outcome selftest: hit=%d taken=%d none=%d armed=%d (%s) interleave %s",
                hit, taken, none, armed, bad.length() == 0 ? "ok" : "FAILED" + bad,
                ibad.length() == 0 ? "ok" : "FAILED" + ibad);
    }

    public static List<String> report() {
        String[] names = {"none", "stomp", "pillar2", "midExit", "headHit", "tower", "wall", "cut", "shelter", "backstep",
                "ramp", "driveThru", "bed"};
        List<String> out = new ArrayList<String>();
        out.add(String.format("§6[ml] §eplacements: §f%d§7 decisions, §f%d§7 fights scored, win baseline §f%.0f%%",
                decisions, fights, baseline * 100));
        int tracked = 0;
        for (State s : STATES.values()) if (!s.pending.isEmpty()) tracked++;
        out.add(String.format("§7[ml] §etracked per bot: §f%d§7 bots with pending state", tracked));
        StringBuilder sb = new StringBuilder("§7per action, the strongest learned terms:");
        for (int a = 0; a < ACTIONS; a++) {
            int best = -1;
            double bv = 0;
            for (int i = 1; i < N; i++) {
                double v = W[a * N + i];
                if (Math.abs(v) > Math.abs(bv)) { bv = v; best = i; }
            }
            if (best < 0 || Math.abs(bv) < 0.02) continue;
            sb.append(String.format(" %s:%s%.2f", names[Math.min(a, names.length - 1)], featureName(best), bv));
        }
        out.add(sb.toString());
        // what each placement achieved across all bots, ordered by how often it was armed; only tried actions are printed
        int[] armed = new int[ACTIONS];
        int[][] outcomes = new int[ACTIONS][4];
        for (State s : STATES.values()) {
            for (int a = 0; a < ACTIONS; a++) {
                armed[a] += s.armed[a];
                for (int o = 0; o < 4; o++) outcomes[a][o] += s.outcomes[a][o];
            }
        }
        int[] byArmed = new int[ACTIONS];
        int n = 0;
        for (int a = 0; a < ACTIONS; a++) if (armed[a] > 0) byArmed[n++] = a;
        for (int i = 1; i < n; i++) {
            int v = byArmed[i], j = i - 1;
            while (j >= 0 && armed[byArmed[j]] < armed[v]) { byArmed[j + 1] = byArmed[j]; j--; }
            byArmed[j + 1] = v;
        }
        for (int i = 0; i < n; i++) {
            int a = byArmed[i];
            int hit = outcomes[a][OUTCOME_HIT], collapse = outcomes[a][OUTCOME_COLLAPSE];
            int taken = outcomes[a][OUTCOME_TAKEN], none = outcomes[a][OUTCOME_NONE];
            out.add(String.format("§7[ml] §e%-10s §7placed §f%d§7: §ahit §f%d (%d%%)§7, §ecollapse §f%d§7, "
                            + "§ctaken §f%d§7, §7none §f%d",
                    names[Math.min(a, names.length - 1)], armed[a], hit, 100 * hit / Math.max(1, armed[a]),
                    collapse, taken, none));
        }
        return out;
    }

    private static String featureName(int i) {
        switch (i) {
            case 1: return "hp";
            case 2: return "theirHp";
            case 3: return "hpDiff";
            case 4: return "dist";
            case 5: return "onLine";
            case 6: return "blocks";
            case 7: return "skill";
            case 8: return "losing";
            case 9: return "pWin";
            case 10: return "theyAir";
            case 11: return "ground";
            case 12: return "bridgeBlock";
            case 13: return "tryhard";
            default: return "x" + i;
        }
    }
}
