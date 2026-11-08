package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.game.BedwarsView;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.brain.BedwarsBrain;
import org.bukkit.Location;

/** One Bed Wars activity (shop trip, defend, rush...). tick() returns false when finished (or given up). */
public abstract class BwTask {
    protected final BedwarsBrain brain;
    protected final BotContext ctx;
    protected final BedwarsView view;
    protected long started;

    protected BwTask(BedwarsBrain brain) {
        this.brain = brain;
        this.ctx = brain.context();
        this.view = brain.view();
        this.started = ctx.tick;
    }

    public abstract String name();

    /** @return true while still running. */
    public abstract boolean tick();

    /**
     * Where this task is taking the bot (a bed, a generator, a player). Teammates compare goals to decide who
     * bridges: one bridger per target, not one per bed. Null when the task has no place to go.
     */
    public Location goal() { return null; }

    /** Interrupted or replaced: release anything held (GUI, actions). */
    public void abort() {
        ctx.nav.stop();
        if (ctx.c.containerOpen()) ctx.c.closeWindow();
    }

    public long startedTick() { return started; }

    protected long age() { return ctx.tick - started; }

    /** Whether a fight may interrupt this task right now. */
    public boolean interruptible() { return true; }
}
