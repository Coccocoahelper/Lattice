package net.h2020.core.tab;

import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The tab list shows the people you are actually with: the players of your game if you are in one, the players of your
 * lobby if you are in a lobby. Vanilla shows everybody on the server, which is wrong here - a Bed Wars lobby has no
 * business listing 60 strangers from the SkyWars lobby and three games.
 * <p>
 * Implemented on the player-info packet the tab is built from: everybody outside the viewer's world is removed from
 * their list, everybody inside it is (re)added. Refreshed once a second and immediately on join / world change, and
 * driven with diffs so nothing is spammed.
 */
public final class TabFilter implements Listener {
    private final JavaPlugin plugin;
    /** What each viewer's tab currently shows, as far as we have told it. */
    private final Map<UUID, Set<UUID>> shown = new HashMap<UUID, Set<UUID>>();

    public TabFilter(JavaPlugin plugin) { this.plugin = plugin; }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { refreshAll(); }
        }, 40L, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        // vanilla has just sent this player the whole server's list: adopt that as the starting state
        Set<UUID> all = new HashSet<UUID>();
        for (Player p : Bukkit.getOnlinePlayers()) all.add(p.getUniqueId());
        shown.put(e.getPlayer().getUniqueId(), all);
        plugin.getServer().getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() { refreshAll(); }
        }, 5L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) {
        plugin.getServer().getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() { refreshAll(); }
        }, 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        shown.remove(e.getPlayer().getUniqueId());
        for (Set<UUID> s : shown.values()) s.remove(e.getPlayer().getUniqueId());
    }

    private void refreshAll() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            try { refresh(viewer); } catch (Throwable ignored) { }
        }
    }

    private void refresh(Player viewer) {
        if (!(viewer instanceof CraftPlayer)) return;
        Set<UUID> want = new HashSet<UUID>();
        for (Player p : viewer.getWorld().getPlayers()) want.add(p.getUniqueId());
        Set<UUID> have = shown.get(viewer.getUniqueId());
        if (have == null) {
            have = new HashSet<UUID>();
            for (Player p : Bukkit.getOnlinePlayers()) have.add(p.getUniqueId());
        }
        EntityPlayer vh = ((CraftPlayer) viewer).getHandle();
        if (vh.playerConnection == null) return;
        for (UUID id : have) {
            if (want.contains(id)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            vh.playerConnection.sendPacket(new PacketPlayOutPlayerInfo(
                    PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, ((CraftPlayer) p).getHandle()));
        }
        for (UUID id : want) {
            if (have.contains(id)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            vh.playerConnection.sendPacket(new PacketPlayOutPlayerInfo(
                    PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, ((CraftPlayer) p).getHandle()));
        }
        shown.put(viewer.getUniqueId(), want);
    }

    /** Force a viewer's tab to rebuild on the next tick (used when a game starts). */
    public void refreshSoon(final Player viewer) {
        plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() { shown.remove(viewer.getUniqueId()); refresh(viewer); }
        });
    }
}
