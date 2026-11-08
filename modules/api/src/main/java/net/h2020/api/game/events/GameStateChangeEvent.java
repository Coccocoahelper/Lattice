package net.h2020.api.game.events;

import net.h2020.api.game.Game;
import net.h2020.api.game.GameState;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired by games on every state transition (main thread), and once with from == null right after creation. */
public class GameStateChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Game game;
    private final GameState from, to;

    public GameStateChangeEvent(Game game, GameState from, GameState to) { this.game = game; this.from = from; this.to = to; }

    public Game game() { return game; }
    public GameState from() { return from; }
    public GameState to() { return to; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
