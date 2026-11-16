package net.h2020.bots.ai.nav;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Global path-search scheduler. Every tick it spends at most {@link #budgetNanos} across all pending searches,
 * round-robin, so 50 bots replanning at once never lag the server: searches simply take a few more ticks.
 */
public final class PathService {
    /** A pending/finished search the requester polls. */
    public static final class Ticket {
        final PathFinder finder;
        volatile boolean done, cancelled;
        volatile Path path;
        final long createdTick;
        long spentNanos;
        /** Straight-line length of the query: short trips are served first, long bridge plans cannot starve them. */
        final int priority;
        long rank;

        Ticket(PathFinder f, long tick) {
            this.finder = f;
            this.createdTick = tick;
            PathRequest r = f.request();
            this.priority = (int) (Math.abs(r.gx - r.sx) + Math.abs(r.gz - r.sz));
        }

        public boolean done() { return done; }
        /** null when no route at all. */
        public Path path() { return path; }
        /** Nodes expanded so far and CPU spent on it, for the nav trace. */
        public int expanded() { return finder.expanded(); }
        public long spentMicros() { return spentNanos / 1000L; }
        public void cancel() { cancelled = true; }
        public boolean cancelled() { return cancelled; }
    }

    private static final ArrayDeque<Ticket> QUEUE = new ArrayDeque<>();
    private static long budgetNanos = 4_000_000L;
    private static long tick;
    /** Slice per search before rotating to the next one. */
    private static final long SLICE = 700_000L;


    private PathService() {}

    public static void setBudgetMicros(long micros) { budgetNanos = Math.max(200, micros) * 1000L; }

    public static Ticket submit(PathRequest req) {
        Ticket t = new Ticket(new PathFinder(req), tick);
        QUEUE.addLast(t);
        return t;
    }

    public static int pending() { return QUEUE.size(); }

    /** Called once per server tick by BotAI. */
    public static void tick() {
        tick++;
        if (QUEUE.isEmpty()) return;
        long start = System.nanoTime();
        // a burst of searches (cages open: every bot plans its route off the island at once) gets up to twice the
        // budget so nobody waits half a minute at the cage; the normal trickle stays at the base budget
        long end = start + budgetNanos + budgetNanos * Math.min(QUEUE.size(), 8) / 8;
        // Nearest goal first. Searches used to rotate strictly, so a ten-block walk to the team forge queued behind
        // every cross-map bridge plan and still needed a full rotation to get a slice; meanwhile the task that asked
        // for it timed out and asked again. Waiting buys priority (20 blocks a tick), so nothing is starved either.
        java.util.List<Ticket> order = new java.util.ArrayList<>(QUEUE.size());
        for (Ticket t = QUEUE.pollFirst(); t != null; t = QUEUE.pollFirst()) {
            t.rank = t.priority - (tick - t.createdTick) * 20L;
            order.add(t);
        }
        order.sort((a, b) -> Long.compare(a.rank, b.rank));
        for (Ticket t : order) {
            if (t.cancelled) continue;
            long now = System.nanoTime();
            if (now >= end) { QUEUE.addLast(t); continue; }
            PathFinder.Status s;
            try {
                s = t.finder.step(Math.min(end, now + SLICE));
                t.spentNanos += System.nanoTime() - now;
            } catch (Throwable ex) {
                if (NavStats.debug) org.bukkit.Bukkit.getLogger().log(java.util.logging.Level.WARNING, "[navdebug] path search crashed", ex);
                t.path = null; t.done = true;
                continue;
            }
            if (s == PathFinder.Status.RUNNING) {
                QUEUE.addLast(t);
            } else {
                t.path = t.finder.result();
                t.done = true;
                if (t.path == null) NavStats.searchFailed(t.finder.world());
                if (NavStats.debug && t.path == null) org.bukkit.Bukkit.getLogger().info("[navdebug] search failed: " + t.finder.describe());
                if (NavStats.debug && tick - t.createdTick > 40) org.bukkit.Bukkit.getLogger().info("[navdebug] slow search " + (tick - t.createdTick)
                        + " ticks (queue " + QUEUE.size() + ", " + t.spentNanos / 1000 / Math.max(1, t.finder.expanded()) + "us/node) " + s + " " + t.finder.describe());
            }
        }
    }

    public static void clear() {
        for (Iterator<Ticket> it = QUEUE.iterator(); it.hasNext(); ) { Ticket t = it.next(); t.done = true; }
        QUEUE.clear();
    }
}
