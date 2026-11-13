package net.h2020.practice;

import net.h2020.api.H2020;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameMode;
import net.h2020.api.game.GameState;
import net.h2020.api.game.GameTeam;
import net.h2020.practice.drill.Drill;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.Set;

/**
 * A practice session: one drill, one generated arena, any number of players and bots.
 *
 * <p>It is a {@link Game} so the rest of the server treats it like any other match - the player is in a game, the lobby
 * router leaves them alone, and {@code /hub} gets them out. What it is not is a match: there are no teams, no
 * generators and no winner. The drill decides what counts and the game only carries bodies, kits and respawns.
 */
public final class PracticeGame implements Game {

    private final PracticeMode type;
    private final String id;
    private final Drill drill;
    private final PracticeArena arena;
    private final Set<Player> players = new LinkedHashSet<Player>();
    private GameState state = GameState.WAITING;
    private int hudTick;
    /** Watcher -> the body they are riding. A player watching a bot bridge follows it instead of standing still. */
    private final java.util.Map<UUID, Player> following = new java.util.HashMap<UUID, Player>();

    PracticeGame(PracticeMode type, String id, Drill drill, PracticeArena arena) {
        this.type = type;
        this.id = id;
        this.drill = drill;
        this.arena = arena;
        drill.build(arena);
    }

    public Drill drill() { return drill; }
    public PracticeArena arena() { return arena; }

    // ---- Game ----

    @Override public String id() { return id; }
    @Override public GameMode type() { return type; }
    @Override public GameState state() { return state; }
    @Override public org.bukkit.World world() { return arena.world(); }
    @Override public String mapName() { return drill.display(); }
    @Override public List<Player> players() { return new ArrayList<Player>(players); }
    @Override public List<Player> alivePlayers() { return new ArrayList<Player>(players); }
    @Override public List<? extends GameTeam> teams() { return Collections.emptyList(); }
    @Override public GameTeam teamOf(Player p) { return null; }
    @Override public boolean isSpectator(Player p) { return false; }
    @Override public int secondsUntilStart() { return -1; }
    @Override public Location spectatorSpawn() { return drill.spectatorSpawn(); }

    @Override
    public boolean addPlayer(Player p) {
        if (players.contains(p)) { reset(p); return true; }
        players.add(p);
        if (state == GameState.WAITING) {
            state = GameState.PLAYING;
            drill.start(this);
        }
        reset(p);
        drill.onJoin(this, p);
        p.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + drill.display() + ChatColor.RESET
                + ChatColor.GRAY + " - " + drill.hint());
        p.sendMessage(ChatColor.GRAY + "Leave with " + ChatColor.WHITE + "/practice leave"
                + ChatColor.GRAY + ", change drill with " + ChatColor.WHITE + "/practice <drill>");
        return true;
    }

    @Override
    public void removePlayer(Player p) {
        following.remove(p.getUniqueId());
        if (!players.remove(p)) return;
        drill.onQuit(this, p);
        if (players.isEmpty()) shutdown();
    }

    /**
     * Watch a body: spectator, dropped at it once so the view starts on the bot that is bridging. From there the
     * spectator moves themselves - a camera that follows a bot around is not watching, and it fights the client's own
     * movement every tick.
     */
    public void follow(Player watcher, Player target) {
        if (watcher == null || target == null) return;
        following.put(watcher.getUniqueId(), target);
        watcher.setGameMode(org.bukkit.GameMode.SPECTATOR);
        watcher.setAllowFlight(true);
        watcher.setFlying(true);
        watcher.teleport(target.getLocation().clone().add(0, 1.2, 0));
        watcher.sendMessage(ChatColor.GRAY + "Spectating " + ChatColor.WHITE + target.getName()
                + ChatColor.GRAY + " - fly to look around, " + ChatColor.WHITE + "/practice leave"
                + ChatColor.GRAY + " to stop");
    }

    public boolean isFollowing(Player p) { return following.containsKey(p.getUniqueId()); }

    /** Put one player back at the drill's start with its kit: used on join, on respawn and after a fall. */
    public void reset(Player p) {
        if (following.containsKey(p.getUniqueId())) {
            // a watcher has no body in the drill: put them in spectator once and leave them where they are. They fly
            // themselves from there - being dragged along behind a bot is not watching, it is being carried.
            p.setGameMode(org.bukkit.GameMode.SPECTATOR);
            return;
        }
        p.teleport(drill.spawn());
        p.setGameMode(org.bukkit.GameMode.SURVIVAL);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.setExp(0f);
        p.setLevel(0);
        for (PotionEffect e : new ArrayList<PotionEffect>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        drill.kit(p);
        p.updateInventory();
    }

    /** Once per tick, from the module's single task. */
    void tick() {
        if (state != GameState.PLAYING) return;
        drill.tick(this);
        if (++hudTick % 4 == 0) {
            for (Player p : new ArrayList<Player>(players)) {
                if (!p.isOnline()) continue;
                String line = drill.hud(this, p);
                if (line != null) sendActionBar(p, line);
            }
        }
    }

    void handleAttack(Player p, org.bukkit.entity.Entity target) {
        if (!players.contains(p)) return;
        drill.onAttack(this, p, target);
    }

    boolean handleDamage(Player p, EntityDamageEvent e) {
        if (!players.contains(p)) return false;
        return drill.onDamage(this, p, e);
    }

    void handleDeath(Player p) {
        if (!players.contains(p)) return;
        drill.onFall(this, p);
        // the drill decides what a fall costs; the body always comes back rather than leaving the session
        Bukkit.getScheduler().runTaskLater(H2020.plugin(), new Runnable() {
            @Override public void run() {
                if (p.isOnline() && players.contains(p)) reset(p);
            }
        }, 1L);
    }

    @Override
    public void shutdown() {
        if (state == GameState.ENDING) return;
        state = GameState.ENDING;
        try { drill.cleanup(this); } catch (Throwable ignored) { }
        for (Player p : new ArrayList<Player>(players)) {
            GameManager gm = H2020.find(GameManager.class);
            if (gm != null) gm.sendToLobby(p, null);
        }
        players.clear();
        following.clear();
        GameManager gm = H2020.find(GameManager.class);
        if (gm != null) gm.finished(this);
        arena.destroy();
    }

    /** 1.8 has no action bar API; the packet is the only way to keep a HUD off the chat. */
    private static void sendActionBar(Player p, String text) {
        try {
            net.minecraft.server.v1_8_R3.IChatBaseComponent chat =
                    net.minecraft.server.v1_8_R3.IChatBaseComponent.ChatSerializer.a(
                    "{\"text\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
            net.minecraft.server.v1_8_R3.PacketPlayOutChat packet =
                    new net.minecraft.server.v1_8_R3.PacketPlayOutChat(chat, (byte) 2);
            ((org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer) p).getHandle().playerConnection.sendPacket(packet);
        } catch (Throwable ignored) {
        }
    }
}
