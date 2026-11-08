package net.h2020.core.profile;

import net.h2020.api.core.Rank;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameFamily;
import net.h2020.core.Core;
import net.h2020.core.HypixelFormat;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Iterator;

/**
 * Hypixel 2020 chat: "§b[MVP§c+§b] Name§f: msg" (ranked, white text) / "§7Name§7: msg" (non, gray text), with the
 * Bed Wars star / SkyWars level prefix in those lobbies and games. Chat is per lobby / per game (per world).
 * Join/quit/kick broadcast messages are suppressed globally; games and lobbies print their own.
 * Runs at LOW so game modules (team chat, /shout, spectator chat) can override the format/recipients later.
 */
public final class ChatListener implements Listener {
    private final Core core;

    public ChatListener(Core core) { this.core = core; }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        ProfileData d = core.profiles.data(p);
        World w = p.getWorld();
        Game g = core.games.gameByWorld(w);
        GameFamily family = g != null ? g.type().family() : familyLobby(w);
        String level = "";
        if (family == GameFamily.BEDWARS) level = HypixelFormat.bedwarsStar(d.bwStar) + " ";
        else if (family == GameFamily.SKYWARS) level = HypixelFormat.skywarsLevelColor(d.swLevel) + "[" + d.swLevel + "⋆] ";
        String head = d.rank == Rank.NONE
                ? ChatColor.GRAY + p.getName() + ChatColor.GRAY + ": "
                : d.rank.prefix(d.plusColor) + p.getName() + ChatColor.WHITE + ": ";
        e.setFormat((level + head).replace("%", "%%") + "%2$s");
        e.setMessage(ChatColor.stripColor(e.getMessage()));
        Iterator<Player> it = e.getRecipients().iterator();
        while (it.hasNext()) {
            Player r = it.next();
            if (!r.getWorld().equals(w)) it.remove();
        }
    }

    private GameFamily familyLobby(World w) {
        try { return core.lobby == null ? null : core.lobby.familyOf(w); } catch (Throwable t) { return null; }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) { e.setJoinMessage(null); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) { e.setQuitMessage(null); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onKick(PlayerKickEvent e) { e.setLeaveMessage(null); }
}
