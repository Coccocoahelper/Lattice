package net.h2020.bots.ai.brain;

import net.h2020.api.game.Game;
import net.h2020.api.game.GameState;
import net.h2020.api.game.GameTeam;
import net.h2020.api.game.events.GameKillEvent;
import net.h2020.bots.ai.perception.Perception;
import org.bukkit.entity.Player;

import java.util.List;

/** A brain bound to one game: team awareness, perception filtering to participants, start/end chat. */
public abstract class GameBrain extends BaseBrain {
    protected final Game game;
    private boolean saidStart, saidEnd;
    private GameState seenState;

    protected GameBrain(Game game) { this.game = game; }

    public Game game() { return game; }

    @Override
    protected void onStart() {
        ctx.perception.setFilter(new Perception.Filter() {
            public boolean relevant(Player p) {
                return game.players().contains(p) && !game.isSpectator(p) && game.alivePlayers().contains(p);
            }
            public boolean ally(Player p) { return isAlly(p); }
        });
        onGameStart();
    }

    protected void onGameStart() {}

    public boolean isAlly(Player p) {
        GameTeam mine = game.teamOf(ctx.player);
        if (mine == null) return false;
        GameTeam theirs = game.teamOf(p);
        return theirs != null && theirs.color() == mine.color();
    }

    public GameTeam myTeam() { return game.teamOf(ctx.player); }

    public List<Player> teammates() {
        GameTeam t = myTeam();
        List<Player> out = new java.util.ArrayList<>();
        if (t == null) return out;
        for (Player p : t.members()) if (p != ctx.player) out.add(p);
        return out;
    }

    @Override
    protected boolean canAct() {
        return super.canAct() && ctx.player.getWorld() == game.world() && !game.isSpectator(ctx.player);
    }

    public void onGameState(GameState from, GameState to) {
        if ((to == GameState.PLAYING || to == GameState.STARTING) && !saidStart) {
            saidStart = true;
            ctx.chat.gameStart();
        }
        if (to == GameState.ENDING && !saidEnd) {
            saidEnd = true;
            ctx.chat.gameEnd(won());
            ctx.combat.disengage();
        }
    }

    /** Call at the top of think(): catches state transitions that happened before this brain existed. */
    protected void pollState() {
        GameState st = game.state();
        if (st != seenState) {
            GameState from = seenState;
            seenState = st;
            if (from != null || st == GameState.STARTING || st == GameState.ENDING) onGameState(from, st);
        }
    }

    /** Some kill happened in this game (any players). */
    public void onAnyKill(GameKillEvent e) {}

    protected boolean won() {
        GameTeam t = myTeam();
        if (t != null) {
            if (!t.alive()) return false;
            for (GameTeam o : game.teams()) if (o != t && o.alive()) return false;
            return true;
        }
        List<Player> alive = game.alivePlayers();
        return alive.size() == 1 && alive.contains(ctx.player);
    }

    protected boolean playing() { return game.state() == GameState.PLAYING; }
}
