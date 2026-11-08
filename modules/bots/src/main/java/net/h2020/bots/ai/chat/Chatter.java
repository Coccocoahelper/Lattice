package net.h2020.bots.ai.chat;

import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.util.AIMath;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * Chat personality. Most bots stay silent; chatty/toxic ones say the classic 2020 lines. Everything lowercase,
 * often without punctuation, with occasional typos, sent after a realistic "typing" delay and rate-limited.
 */
public final class Chatter {
    private static final String[] START = {"gl", "gl", "glhf", "gl hf", "good luck", "gl all", "gl everyone", "hi", "gl guys"};
    private static final String[] START_TOXIC = {"ez game", "gl ull need it", "1v1 me", "gl", "free win"};
    private static final String[] KILL_TOXIC = {"ez", "L", "ezz", "get good", "too ez", "lol", "bye", "ez L", "cya", "1", "e z", "sit"};
    private static final String[] KILL = {"gg", "lol", "close one", "gf", "nice try", "rip"};
    private static final String[] DEATH = {"bro what", "lag", "?", "omg", "wtf", "how", "bruh", "ok", "my ping", "lagg", "rip",
            "really", "bro", "what", "seriously", "lol ok", "nice"};
    private static final String[] DEATH_VOID = {"bro what", "lag", "omg", "wtf", "i lagged", "bruh", "my ping", "kb??", "lol", "?"};
    private static final String[] DEATH_SUS = {"hacker", "wdr", "reach", "nice reach", "report", "hacks", "nice autoclicker",
            "watchdog where", "bro is cheating", "hacker lol", "ok hacker", "wdr him", "obviously hacking"};
    private static final String[] END_WIN = {"gg", "gg", "gf", "ggs", "gg wp", "gg"};
    private static final String[] END_WIN_TOXIC = {"gg ez", "ez", "gg ez", "too ez", "L", "gg no re", "l cheater"};
    private static final String[] END_LOSE = {"gg", "gg", "gf", "ggs", "gg wp", "wp", "gg close", "gg"};
    private static final String[] LOBBY = {"lol", "anyone party?", "who wants to 1v1", "lag", "bedwars?", "hi", "anyone wanna duel",
            "skywars?", "lmao", "sup"};

    private static final class Pending {
        final String msg; final long due; final boolean command;
        Pending(String m, long d) { this(m, d, false); }
        Pending(String m, long d, boolean c) { msg = m; due = d; command = c; }
    }
    private static final String[] REPORT_REASONS = {"", "", "hacking", "killaura", "reach", "fly", "cheating", "hacks"};
    //private static final String[] REPORT_CMDS = {"wdr", "wdr", "report", "watchdogreport"};

    private final BotContext ctx;
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private long lastSent = -10000;

    public Chatter(BotContext ctx) { this.ctx = ctx; }

    private double chatty() { return ctx.persona.skill.chattiness; }

    public void tick() {
        Pending p = queue.peekFirst();
        if (p == null || ctx.tick < p.due) return;
        queue.pollFirst();
        if (ctx.tick - lastSent < 50) return; // nobody spams within 2.5s
        lastSent = ctx.tick;
        try {
            if (p.command) ctx.c.command(p.msg); else ctx.c.chat(p.msg);
        } catch (Throwable ignored) {}
    }

    public void clear() { queue.clear(); }

    /** Queue a message with a human typing delay. */
    public void say(String raw, int extraDelayTicks) {
        if (queue.size() > 2) return;
        String m = humanize(raw);
        int typing = 12 + (int) (m.length() * AIMath.range(ctx.rng, 1.2, 2.4)) + AIMath.range(ctx.rng, 0, 20);
        long base = queue.isEmpty() ? ctx.tick : queue.peekLast().due;
        queue.addLast(new Pending(m, Math.max(base, ctx.tick) + typing + Math.max(0, extraDelayTicks)));
    }

    /** Maybe say one line from the pool, with probability scaled by chattiness. */
    public boolean maybe(double baseChance, String[] pool, int delay) {
        double c = chatty();
        if (ctx.rng.nextDouble() >= baseChance * (0.15 + 1.6 * c * c)) return false;
        say(pool[ctx.rng.nextInt(pool.length)], delay);
        return true;
    }

    // ----------------------------------------------------------------------------------- events

    public void gameStart() {
        if (ctx.persona.toxic && ctx.rng.nextDouble() < 0.3) maybe(0.5, START_TOXIC, 20);
        else maybe(ctx.persona.saysGlhf ? 0.6 : 0.25, START, AIMath.range(ctx.rng, 0, 60));
    }

    public void kill(boolean finalKill) {
        if (ctx.persona.toxic) maybe(finalKill ? 0.65 : 0.4, KILL_TOXIC, 0);
        else maybe(0.08, KILL, 10);
    }

    public void died(boolean byPlayer, boolean suspicious, boolean voided) {
        if (suspicious && ctx.rng.nextDouble() < 0.75) { maybe(0.8, DEATH_SUS, 5); return; }
        maybe(byPlayer ? 0.28 : 0.2, voided ? DEATH_VOID : DEATH, 0);
    }

    /** Actually file a /wdr against a suspected cheater (Watchdog then boosts their priority), after a delay. */
    /*public void report(String name) {
        if (name == null || queue.size() > 2) return;
        String reason = REPORT_REASONS[ctx.rng.nextInt(REPORT_REASONS.length)];
        String line = REPORT_CMDS[ctx.rng.nextInt(REPORT_CMDS.length)] + " " + name + (reason.isEmpty() ? "" : " " + reason);
        int typing = 15 + (int) (line.length() * AIMath.range(ctx.rng, 1.2, 2.4)) + AIMath.range(ctx.rng, 30, 120);
        long base = queue.isEmpty() ? ctx.tick : queue.peekLast().due;
        queue.addLast(new Pending(line, Math.max(base, ctx.tick) + typing, true));
    }*/

    public void gameEnd(boolean won) {
        if (won && ctx.persona.toxic && ctx.rng.nextDouble() < 0.5) { maybe(0.9, END_WIN_TOXIC, 10); return; }
        maybe(won ? 0.75 : 0.55, won ? END_WIN : END_LOSE, AIMath.range(ctx.rng, 5, 40));
    }

    public void lobbyIdle() { maybe(0.02, LOBBY, 0); }

    public void callout(String text, double chance) {
        if (ctx.rng.nextDouble() < chance * (0.25 + chatty())) say(text, 0);
    }

    // ----------------------------------------------------------------------------------- typing

    String humanize(String s) {
        Random r = ctx.rng;
        String m = s.toLowerCase();
        if (m.equals("l")) m = "L"; // "L" is the one uppercase meme
        if (m.length() > 3 && r.nextDouble() < ctx.persona.typoRate) m = typo(m, r);
        if (r.nextDouble() < 0.05 && !m.endsWith("?")) m = m + (r.nextBoolean() ? "!" : "");
        return m;
    }

    private static String typo(String m, Random r) {
        int i = 1 + r.nextInt(m.length() - 2);
        char[] c = m.toCharArray();
        switch (r.nextInt(4)) {
            case 0: { char t = c[i]; c[i] = c[i + 1]; c[i + 1] = t; return new String(c); } // swap
            case 1: return m.substring(0, i) + m.substring(i + 1);                              // drop
            case 2: return m.substring(0, i) + c[i] + m.substring(i);                           // double
            default: return m.replace(" ", r.nextBoolean() ? "" : " ");                          // no space
        }
    }
}
