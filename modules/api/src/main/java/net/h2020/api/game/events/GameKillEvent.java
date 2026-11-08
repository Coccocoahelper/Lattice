package net.h2020.api.game.events;

import net.h2020.api.game.Game;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired by games when a player dies in-game. killer may be null (void/fall with no recent attacker). */
public class GameKillEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Game game;
    private final Player victim, killer;
    private final boolean finalKill;

    public GameKillEvent(Game game, Player victim, Player killer, boolean finalKill) {
        this.game = game; this.victim = victim; this.killer = killer; this.finalKill = finalKill;
    }

    public Game game() { return game; }
    public Player victim() { return victim; }
    public Player killer() { return killer; }
    public boolean finalKill() { return finalKill; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
