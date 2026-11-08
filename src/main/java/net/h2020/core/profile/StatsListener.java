package net.h2020.core.profile;

import net.h2020.api.game.Game;
import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameState;
import net.h2020.api.game.events.GameKillEvent;
import net.h2020.api.game.events.GameStateChangeEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Keeps the lobby scoreboard stats moving: kills, final kills, wins, coins, Bed Wars XP, SkyWars progress. */
public final class StatsListener implements Listener {
    private final ProfileServiceImpl profiles;

    public StatsListener(ProfileServiceImpl profiles) { this.profiles = profiles; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(GameKillEvent e) {
        Player k = e.killer();
        if (k == null || k.equals(e.victim()) || e.game() == null) return;
        ProfileData d = profiles.data(k);
        if (e.game().type().family() == GameFamily.BEDWARS) {
            d.bwKills++;
            if (e.finalKill()) { d.bwFinalKills++; d.bwCoins += 20; d.addBedwarsExp(10); }
            else { d.bwCoins += 10; d.addBedwarsExp(5); }
        } else {
            d.swKills++;
            d.swCoins += e.game().type().insane() ? 25 : 10;
            addSkywarsProgress(d, 2);
        }
        d.addNetworkExp(e.finalKill() ? 50 : 15);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onState(GameStateChangeEvent e) {
        if (e.to() != GameState.ENDING || e.from() != GameState.PLAYING) return;
        Game g = e.game();
        if (g == null) return;
        // only a real win counts: every survivor on one team (draws, time-outs and forced shutdowns award nothing)
        java.util.List<Player> alive = g.alivePlayers();
        java.util.Set<Object> teams = new java.util.HashSet<>();
        for (Player p : alive) {
            Object t = null;
            try { t = g.teamOf(p); } catch (Throwable ignored) {}
            teams.add(t == null ? p.getUniqueId() : t);
        }
        if (teams.size() != 1) return;
        for (Player p : alive) {
            ProfileData d = profiles.data(p);
            if (g.type().family() == GameFamily.BEDWARS) {
                d.bwWins++;
                d.bwCoins += 100;
                d.addBedwarsExp(100);
                if (Math.random() < 0.15) d.bwLootChests++;
            } else {
                d.swWins++;
                d.swCoins += g.type().insane() ? 100 : 50;
                addSkywarsProgress(d, 10);
            }
            d.addNetworkExp(250);
        }
    }

    private static void addSkywarsProgress(ProfileData d, int pct) {
        d.swExpProgressPct += pct;
        while (d.swExpProgressPct >= 100) { d.swExpProgressPct -= 100; d.swLevel++; }
    }
}
