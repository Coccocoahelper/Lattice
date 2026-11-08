package net.h2020.bots.ai.brain;

/** Main/family lobby: no fighting, just a player killing time. */
public final class LobbyBrain extends BaseBrain {
    private IdleBehavior idle;

    @Override
    protected void onStart() {
        idle = new IdleBehavior(ctx, 22, true);
    }

    @Override
    protected void onActive() {
        idle.setHome(ctx.pos());
    }

    @Override
    protected void think() {
        idle.tick();
    }

    @Override
    public String toString() { return "LobbyBrain"; }
}
