package net.h2020.duels;

import net.h2020.api.H2020;
import net.h2020.api.bot.BotService;
import net.h2020.api.core.LastGame;
import net.h2020.api.core.PlayAgain;
import net.h2020.api.core.ScoreboardService;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameMode;
import net.h2020.api.game.GameState;
import net.h2020.api.game.GameTeam;
import net.h2020.api.game.TeamColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * One duel: two players, one arena, one mode.
 *
 * <p>The shape follows Hypixel's: a short countdown on the spawn pads, then the fight. Modes differ only in what ends
 * it - a kill, a fall, a hit count, or the border - and each of those is one branch in {@link #checkRound}. Everything
 * else (the kit, the scoreboard, the end screen, the requeue) is the same for all eight modes, which is what stops the
 * mode list from turning into eight near-copies of a game.
 */
public final class DuelsGame implements Game {

    private static final int COUNTDOWN_SECONDS = 3;
    private static final int END_SECONDS = 5;
    /** Hits within this window credit the attacker for a void death. */
    private static final long CREDIT_MS = 8000L;

    private final DuelsModule module;
    private final DuelsMode mode;
    private final DuelsMap map;
    private final String id;
    private final World world;
    private final DuelsArena arena;
    private final Location spec;
    /** The two spawn pads, A first. A real Hypixel capture's own points, or the generated arena's ends. */
    private final List<Location> pads;

    private final List<UUID> order = new ArrayList<UUID>();
    private final Map<UUID, Integer> hits = new LinkedHashMap<UUID, Integer>();
    private final Map<UUID, Integer> roundsWon = new LinkedHashMap<UUID, Integer>();
    private final Map<UUID, Long> lastHitAt = new HashMap<UUID, Long>();
    private final Map<UUID, UUID> lastAttacker = new HashMap<UUID, UUID>();

    private GameState state = GameState.WAITING;
    private int countdown = -1;
    private int elapsed;
    private int endSeconds;
    private boolean roundLive;
    private UUID roundWinner;
    private UUID matchWinner;
    private BukkitTask task;
    private boolean shutDown;
    private int borderStart = -1;
    private double borderRadius;

    public DuelsGame(DuelsModule module, DuelsMode mode, DuelsMap map, String id) {
        this.module = module;
        this.mode = mode;
        this.map = map;
        this.id = id;
        this.world = map.createInstance("duel_" + id + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFF));
        if (world == null) throw new IllegalStateException("duel arena failed for " + mode.displayName());
        this.arena = map.lastArena();
        // a real map has no arena object: its pads come from map.yml, and the spectator point with them
        this.spec = map.point(net.h2020.api.map.MapKeys.SPECTATOR) != null
                ? map.point(net.h2020.api.map.MapKeys.SPECTATOR).toLocation(world)
                : (arena != null ? arena.spectator() : world.getSpawnLocation());
        this.pads = map.spawns(world);
        if (pads.size() < 2) throw new IllegalStateException("map " + map.name() + " has no duel spawn points");
        this.borderRadius = DuelsArena.RADIUS;
        task = Bukkit.getScheduler().runTaskTimer(module.plugin(), new Runnable() {
            @Override public void run() {
                try { tick(); } catch (Throwable t) {
                    module.plugin().getLogger().log(Level.SEVERE, "duel tick " + DuelsGame.this.id, t);
                }
            }
        }, 1L, 1L);
    }

    // ---------------------------------------------------------------- Game

    @Override public String id() { return id; }
    @Override public GameMode type() { return mode; }
    @Override public GameState state() { return state; }
    @Override public World world() { return world; }
    @Override public String mapName() { return mode.displayName() + " Arena"; }
    @Override public List<Player> players() {
        List<Player> l = new ArrayList<Player>();
        for (UUID u : order) { Player p = Bukkit.getPlayer(u); if (p != null) l.add(p); }
        return l;
    }
    @Override public List<Player> alivePlayers() {
        List<Player> l = new ArrayList<Player>();
        for (UUID u : order) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && !p.isDead() && p.getWorld().equals(world)) l.add(p);
        }
        return l;
    }
    @Override public List<? extends GameTeam> teams() { return Collections.emptyList(); }
    @Override public GameTeam teamOf(Player p) { return null; }
    @Override public boolean isSpectator(Player p) { return false; }
    @Override public int secondsUntilStart() { return state == GameState.STARTING ? countdown : -1; }
    @Override public Location spectatorSpawn() { return spec.clone(); }

    public DuelsMode mode() { return mode; }

    /** The y a player stands at on this map: the pad's own height, so a capture's floor is respected. */
    private int floorY() {
        return pads.isEmpty() ? DuelsArena.FLOOR_Y : (int) Math.floor(pads.get(0).getY());
    }

    @Override
    public boolean addPlayer(Player p) {
        if (shutDown || (state != GameState.WAITING && state != GameState.STARTING)) return false;
        UUID u = p.getUniqueId();
        if (order.contains(u)) return true;
        if (order.size() >= 2) return false;
        order.add(u);
        hits.put(u, 0);
        roundsWon.put(u, 0);
        boolean first = order.size() == 1;
        p.teleport(pads.get(first ? 0 : 1));
        p.setGameMode(org.bukkit.GameMode.ADVENTURE);
        p.setAllowFlight(false);
        p.setFlying(false);
        DuelsKit.apply(mode, p);
        DuelsKit.effects(mode, p);
        serverLine("You joined " + mode.displayName() + " duel (" + order.size() + "/2)");
        p.sendMessage(ChatColor.YELLOW + "You joined " + ChatColor.AQUA + ChatColor.BOLD + mode.displayName()
                + ChatColor.YELLOW + " duel " + ChatColor.GRAY + "(" + order.size() + "/2)");
        updateBoards();
        if (order.size() == 2) beginCountdown();
        return true;
    }

    @Override
    public void removePlayer(Player p) {
        UUID u = p.getUniqueId();
        if (!order.contains(u)) return;
        boolean wasLive = roundLive;
        order.remove(u);
        reset(p);
        if (state == GameState.PLAYING && wasLive) {
            UUID other = order.isEmpty() ? null : order.get(0);
            if (other != null) endMatch(other);
            else endMatch(null);
        } else if (order.isEmpty() && state != GameState.ENDING) {
            shutdown();
        }
        updateBoards();
    }

    @Override
    public void shutdown() {
        if (shutDown) return;
        shutDown = true;
        if (task != null) task.cancel();
        state = GameState.ENDING;
        GameManager gm = H2020.find(GameManager.class);
        BotService bs = H2020.find(BotService.class);
        List<Player> everyone = players();
        for (Player p : everyone) {
            boolean bot = false;
            try { bot = bs != null && bs.isBot(p); } catch (Throwable ignored) { }
            reset(p);
            if (bot) continue;
            if (gm != null) { try { gm.sendToLobby(p, GameFamily.DUELS); } catch (Throwable ignored) { } }
        }
        order.clear();
        module.unregister(this);
        if (gm != null) {
            try { gm.finished(this); } catch (Throwable t) {
                module.plugin().getLogger().log(Level.WARNING, "GameManager.finished failed for " + id, t);
            }
        }
        // synchronous, like the practice drills: a deferred unload races the world ticker, and a second unload of a
        // world the ticker still holds crashes the server (see DuelsArena.destroy)
        if (arena != null) arena.destroy();
        else map.destroyInstance(world);
    }

    // ---------------------------------------------------------------- flow

    private void beginCountdown() {
        state = GameState.STARTING;
        countdown = COUNTDOWN_SECONDS;
        title(ChatColor.YELLOW + "" + ChatColor.BOLD + mode.displayName(), ChatColor.GRAY + "The duel begins...");
    }

    private void tick() {
        if (shutDown || state == GameState.ENDING) { if (state == GameState.ENDING) tickEnd(); return; }
        if (state == GameState.STARTING) {
            if (--countdown <= 0) beginRound();
            else if (countdown <= 3) {
                for (Player p : players()) {
                    p.playSound(p.getLocation(), Sound.NOTE_PLING, 1f, 1f);
                    title(ChatColor.GREEN + "" + ChatColor.BOLD + countdown, ChatColor.GRAY + mode.displayName());
                }
                updateBoards();
            }
            return;
        }
        if (state != GameState.PLAYING) return;
        elapsed++;
        if (roundLive) {
            tickBorder();
            if (mode.win() == DuelsMode.Win.KNOCKBACK) tickSumo();
        }
        if (elapsed % 20 == 0) {
            // Pin the hunger bar for the modes whose kit has no food in it. Done here rather than only through
            // FoodLevelChangeEvent: that event does not fire for a bot body, so a listener alone left the bar draining
            // in exactly the modes the rule is for (measured: the guard counter stayed at zero through a whole Boxing
            // round). A pin per second is deterministic and covers a human and a bot the same way.
            if (keepsHungerFull()) {
                net.h2020.api.stats.CombatStats.inc("duels.hunger.pin.tick");
                for (Player p : players()) {
                    if (p.getFoodLevel() != 20) {
                        p.setFoodLevel(20);
                        net.h2020.api.stats.CombatStats.inc("duels.hunger.refilled");
                    }
                    if (p.getSaturation() < 5f) p.setSaturation(5f);
                }
            }
            updateBoards();
            if (elapsed >= mode.roundSeconds * 20 && roundLive) {
                // Hypixel's rule: nobody wins, the duel is a draw
                endMatch(null);
            }
        }
    }

    private void tickEnd() {
        if (--endSeconds <= 0) shutdown();
    }

    private void beginRound() {
        net.h2020.api.stats.CombatStats.inc("duels.round.start");
        state = GameState.PLAYING;
        roundLive = true;
        roundWinner = null;
        elapsed = 0;
        borderStart = -1;
        hits.clear();
        for (UUID u : order) hits.put(u, 0);
        List<Player> ps = players();
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            p.teleport(pads.get(i == 0 ? 0 : Math.min(1, pads.size() - 1)));
            p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            DuelsKit.apply(mode, p);
            DuelsKit.effects(mode, p);
            p.setHealth(p.getMaxHealth());
            // log immediately after the kit, before anything else can touch the inventory

            if (keepsHungerFull()) { p.setFoodLevel(20); p.setSaturation(5f); }
        }
        // one line per round, so the kit is checkable without a client: armour, speed, hunger
        for (Player p : ps) {
            try {
                org.bukkit.inventory.ItemStack[] armour = p.getInventory().getArmorContents();
                int worn = 0;
                if (armour != null) for (org.bukkit.inventory.ItemStack i : armour)
                    if (i != null && i.getType() != org.bukkit.Material.AIR) worn++;
                org.bukkit.potion.PotionEffect sp = null;
                for (org.bukkit.potion.PotionEffect pe : p.getActivePotionEffects())
                    if (pe.getType() == org.bukkit.potion.PotionEffectType.SPEED) sp = pe;
                // a bot body does not report its own effects through getActivePotionEffects reliably, so the number
                // below is corroborated by the duels.speed.applied counter rather than trusted alone
                module.plugin().getLogger().info("[duels] kit " + p.getName() + " id=" + mode.id() + " mode=" + mode.displayName()
                        + " armour=" + worn + " speed=" + (sp == null ? "none" : sp.getAmplifier() + "/" + sp.getDuration())
                        + " food=" + p.getFoodLevel());
            } catch (Throwable ignored) { }
        }
        for (Player p : ps) {
            p.playSound(p.getLocation(), Sound.ENDERDRAGON_GROWL, 0.4f, 1.4f);
            title(ChatColor.GREEN + "" + ChatColor.BOLD + "FIGHT!", ChatColor.GRAY + mode.displayName());
        }
        updateBoards();
    }

    /** The border closes in on a schedule for the modes that have one, damaging anyone outside it. */
    private void tickBorder() {
        if (mode.borderAt <= 0) return;
        if (elapsed < mode.borderAt * 20) return;
        if (borderStart < 0) {
            borderStart = elapsed;
            for (Player p : players()) {
                p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "The border is closing in!");
                p.playSound(p.getLocation(), Sound.WITHER_SPAWN, 0.5f, 1.2f);
            }
        }
        // shrink from the arena radius to 7 over the remaining round, one block every few seconds
        double left = Math.max(0, mode.roundSeconds - mode.borderAt);
        double progress = Math.min(1.0, (elapsed - borderStart) / Math.max(1.0, left * 20));
        double target = 7.0;
        borderRadius = DuelsArena.RADIUS - (DuelsArena.RADIUS - target) * progress;
        for (Player p : players()) {
            Location l = p.getLocation();
            double d = Math.max(Math.abs(l.getX()), Math.abs(l.getZ()));
            if (d > borderRadius) {
                p.damage(1.0);
                p.setVelocity(p.getVelocity().add(new Vector(-l.getX(), 0.15, -l.getZ()).normalize().multiply(0.35)));
            }
        }
    }

    /** Sumo: the round is over the moment somebody is off the platform, so nobody has to die for it. */
    private void tickSumo() {
        for (Player p : players()) {
            Location l = p.getLocation();
            if (l.getY() < floorY() - 2) {
                net.h2020.api.stats.CombatStats.inc("duels.sumo.fell");
                UUID other = other(p.getUniqueId());
                if (other != null) winRound(other, p);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- combat

    /** Register a hit for the boxing mode and for kill credit. Returns true when the hit ended the round. */
    public boolean onHit(Player attacker, Player victim) {
        if (!roundLive || !order.contains(attacker.getUniqueId()) || !order.contains(victim.getUniqueId())) return false;
        lastAttacker.put(victim.getUniqueId(), attacker.getUniqueId());
        lastHitAt.put(victim.getUniqueId(), System.currentTimeMillis());
        if (mode.win() != DuelsMode.Win.HITS) return false;
        net.h2020.api.stats.CombatStats.inc("duels.hit");
        int h = hits.containsKey(attacker.getUniqueId()) ? hits.get(attacker.getUniqueId()) : 0;
        hits.put(attacker.getUniqueId(), ++h);
        // Hypixel: 100 hits wins, and so does being 50 ahead ("mercy")
        int mine = hits.get(attacker.getUniqueId());
        int theirs = hits.containsKey(victim.getUniqueId()) ? hits.get(victim.getUniqueId()) : 0;
        if (mine >= mode.hitTarget || mine - theirs >= mode.mercy) { winRound(attacker.getUniqueId(), victim); return true; }
        sendActionBar(attacker, ChatColor.GREEN + "Hits: " + ChatColor.WHITE + mine + ChatColor.GRAY + " ("
                + ChatColor.GREEN + "+" + (mine - theirs) + ChatColor.GRAY + ")");
        sendActionBar(victim, ChatColor.RED + "Hits: " + ChatColor.WHITE + theirs + ChatColor.GRAY + " ("
                + ChatColor.RED + (theirs - mine) + ChatColor.GRAY + ")");
        attacker.playSound(attacker.getLocation(), Sound.CLICK, 1f, 1.6f);
        return false;
    }

    /** A death in this duel: the round goes to whoever last hit the victim, or to the other player. */
    public void onDeath(Player victim) {
        if (!roundLive) return;
        UUID v = victim.getUniqueId();
        UUID credit = lastAttacker.get(v);
        Long at = lastHitAt.get(v);
        if (credit == null || at == null || System.currentTimeMillis() - at > CREDIT_MS) credit = other(v);
        if (credit == null) { endMatch(null); return; }
        winRound(credit, victim);
    }

    private UUID other(UUID u) {
        for (UUID o : order) if (!o.equals(u)) return o;
        return null;
    }

    private void winRound(UUID winner, Player loser) {
        roundWinner = winner;
        roundLive = false;
        roundsWon.put(winner, (roundsWon.containsKey(winner) ? roundsWon.get(winner) : 0) + 1);
        Player w = Bukkit.getPlayer(winner);
        if (w != null) {
            w.playSound(w.getLocation(), Sound.LEVEL_UP, 1f, 1.2f);
            title(w, ChatColor.GOLD + "" + ChatColor.BOLD + "ROUND WON!", ChatColor.GRAY + mode.displayName());
        }
        if (loser != null) title(loser, ChatColor.RED + "" + ChatColor.BOLD + "ROUND LOST", ChatColor.GRAY + "Next round...");
        // a Hypixel duel is one round except the modes that say otherwise
        endMatch(winner);
    }

    private void endMatch(UUID winner) {
        if (state == GameState.ENDING) return;
        net.h2020.api.stats.CombatStats.inc(winner == null ? "duels.round.draw" : "duels.round.win");
        matchWinner = winner;
        roundLive = false;
        state = GameState.ENDING;
        endSeconds = END_SECONDS;
        List<Player> ps = players();
        // One recording per line, not per player: the end screen is the same text for everybody, and recording it
        // inside the player loop duplicated the whole block once per participant (measured: a 5-line screen stored
        // as 10).
        List<String> shared = new ArrayList<String>();
        for (Player p : ps) {
            boolean won = winner != null && winner.equals(p.getUniqueId());
            List<String> block = new ArrayList<String>();
            block.add(ChatColor.GRAY + "" + ChatColor.STRIKETHROUGH + "------------------------------------------------");
            block.add(ChatColor.WHITE + "" + ChatColor.BOLD + "Duels" + ChatColor.GRAY + " - " + mode.displayName());
            block.add("");
            if (winner == null) block.add(ChatColor.YELLOW + "Winner " + ChatColor.GRAY + "- " + ChatColor.GRAY + "Nobody");
            else {
                Player w = Bukkit.getPlayer(winner);
                block.add(ChatColor.YELLOW + "Winner " + ChatColor.GRAY + "- " + ChatColor.WHITE
                        + (w == null ? "?" : w.getName()));
            }
            if (mode.win() == DuelsMode.Win.HITS) {
                for (UUID u : order) {
                    Player q = Bukkit.getPlayer(u);
                    if (q == null) continue;
                    block.add(ChatColor.GRAY + "" + q.getName() + ": " + ChatColor.WHITE + hits.get(u) + " hits");
                }
            }
            block.add(ChatColor.GRAY + "" + ChatColor.STRIKETHROUGH + "------------------------------------------------");
            for (String l : block) p.sendMessage(l);
            if (shared.isEmpty()) shared.addAll(block);
            LastGame.record(p, mode, mapName());
            PlayAgain.giveItem(p);
            if (won) title(p, ChatColor.GOLD + "" + ChatColor.BOLD + "VICTORY!",
                    ChatColor.GRAY + "You won the duel!", 5, 60, 20);
            else title(p, ChatColor.RED + "" + ChatColor.BOLD + "DEFEAT",
                    winner == null ? ChatColor.GRAY + "The duel was a draw." : ChatColor.GRAY + "You lost the duel.", 5, 60, 20);
        }
        for (String l : shared) serverLine(l);          // once for the match, not once per participant
        for (Player p : ps) PlayAgain.sendPrompt(p, winner != null && winner.equals(p.getUniqueId()), false);
        updateBoards();
    }

    /** Undo everything the duel did to a body. */
    private void reset(Player p) {
        try { p.closeInventory(); } catch (Throwable ignored) { }
        for (PotionEffect e : new ArrayList<PotionEffect>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[]{null, null, null, null});
        p.setFireTicks(0);
        p.setMaxHealth(20.0);
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setFlying(false);
        p.setAllowFlight(false);
        p.setGameMode(org.bukkit.GameMode.ADVENTURE);
        p.updateInventory();
        ScoreboardService sb = H2020.find(ScoreboardService.class);
        if (sb != null) sb.clearSidebar(p);
    }

    // ---------------------------------------------------------------- board / text

    private void updateBoards() {
        ScoreboardService sb = H2020.find(ScoreboardService.class);
        if (sb == null) return;
        for (Player p : players()) {
            List<String> lines = new ArrayList<String>();
            lines.add(ChatColor.GRAY + "07/14/20  " + ChatColor.DARK_GRAY + "duel" + id);
            lines.add("");
            lines.add(ChatColor.WHITE + mode.displayName() + ChatColor.GRAY + " Duel");
            lines.add("");
            Player a = order.isEmpty() ? null : Bukkit.getPlayer(order.get(0));
            Player b = order.size() < 2 ? null : Bukkit.getPlayer(order.get(1));
            lines.add(ChatColor.GREEN + "" + ChatColor.BOLD + "A " + ChatColor.WHITE + name(a));
            lines.add(ChatColor.RED + "" + ChatColor.BOLD + "B " + ChatColor.WHITE + name(b));
            if (mode.win() == DuelsMode.Win.HITS) {
                lines.add("");
                lines.add(ChatColor.GRAY + "Hits: " + ChatColor.WHITE
                        + (hits.containsKey(p.getUniqueId()) ? hits.get(p.getUniqueId()) : 0)
                        + ChatColor.GRAY + "/" + ChatColor.WHITE + mode.hitTarget);
            }
            lines.add("");
            if (state == GameState.STARTING) lines.add(ChatColor.GREEN + "Starting in " + countdown + "...");
            else if (state == GameState.PLAYING) lines.add(ChatColor.GREEN + "Duration: " + ChatColor.WHITE + clock(elapsed / 20));
            else if (state == GameState.ENDING) lines.add(ChatColor.GOLD + "Winner: " + ChatColor.WHITE + name(matchWinner));
            else lines.add(ChatColor.YELLOW + "Waiting...");
            lines.add("");
            lines.add(ChatColor.GRAY + "lattice.duels");
            sb.setSidebar(p, ChatColor.YELLOW + "" + ChatColor.BOLD + "DUELS", lines);
        }
    }

    private static String name(Player p) { return p == null ? "-" : p.getName(); }
    private static String name(UUID u) { if (u == null) return "-"; Player p = Bukkit.getPlayer(u); return p == null ? "?" : p.getName(); }

    private static String clock(int seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    /** Record a duel line with the replay, when one is running. */
    private void serverLine(String msg) {
        try {
            net.h2020.api.game.ReplayService rs = net.h2020.api.H2020.find(net.h2020.api.game.ReplayService.class);
            if (rs != null) rs.serverLine(world, ChatColor.stripColor(msg));
        } catch (Throwable ignored) { }
    }

    private void title(String t, String sub) {
        for (Player p : players()) title(p, t, sub, 0, 20, 10);
    }

    private static void title(Player p, String t, String sub) { title(p, t, sub, 0, 20, 10); }

    private static void title(Player p, String t, String sub, int in, int stay, int out) {
        sendTitle(p, t, sub, in, stay, out);
    }

    /**
     * Titles and the action bar on 1.8, reached by class name rather than by import.
     *
     * <p>NMS is not on this module's compile classpath (the plugins are compiled against the Spigot API only, and the
     * server supplies the rest at runtime), so the packet classes are looked up once and cached. That also keeps the
     * module buildable against any 1.8.x server build, where the packet constructors differ between CraftBukkit and
     * Spigot.
     */
    private static final class Packets {
        static final Class<?> CHAT_COMPONENT = cls("net.minecraft.server.v1_8_R3.IChatBaseComponent");
        static final Class<?> SERIALIZER = cls("net.minecraft.server.v1_8_R3.IChatBaseComponent$ChatSerializer");
        static final Class<?> PACKET = cls("net.minecraft.server.v1_8_R3.Packet");
        static final Class<?> TITLE = cls("net.minecraft.server.v1_8_R3.PacketPlayOutTitle");
        static final Class<?> TITLE_ACTION = cls("net.minecraft.server.v1_8_R3.PacketPlayOutTitle$EnumTitleAction");
        static final Class<?> CHAT_PACKET = cls("net.minecraft.server.v1_8_R3.PacketPlayOutChat");

        static Class<?> cls(String name) {
            try { return Class.forName(name); } catch (Throwable t) { return null; }
        }

        static Object component(String legacy) {
            if (SERIALIZER == null) return null;
            try {
                java.lang.reflect.Method a = SERIALIZER.getMethod("a", String.class);
                return a.invoke(null, "{\"text\":\"" + esc(legacy) + "\"}");
            } catch (Throwable t) { return null; }
        }

        static void send(Player p, Object packet) {
            if (packet == null || PACKET == null || !PACKET.isInstance(packet)) return;
            try {
                Object handle = p.getClass().getMethod("getHandle").invoke(p);
                Object conn = handle.getClass().getField("playerConnection").get(handle);
                conn.getClass().getMethod("sendPacket", PACKET).invoke(conn, packet);
            } catch (Throwable ignored) { }
        }
    }

    private static void sendTitle(Player p, String t, String sub, int in, int stay, int out) {
        if (Packets.TITLE == null || Packets.TITLE_ACTION == null) return;
        try {
            Object timing = Packets.TITLE.getConstructor(int.class, int.class, int.class).newInstance(in, stay, out);
            Packets.send(p, timing);
            Object title = Packets.TITLE_ACTION.getEnumConstants()[0];      // TITLE
            Object subtitle = Packets.TITLE_ACTION.getEnumConstants()[1];   // SUBTITLE
            java.lang.reflect.Constructor<?> c = Packets.TITLE.getConstructor(Packets.TITLE_ACTION, Packets.CHAT_COMPONENT);
            Packets.send(p, c.newInstance(title, Packets.component(t)));
            Packets.send(p, c.newInstance(subtitle, Packets.component(sub)));
        } catch (Throwable ignored) { }
    }

    private static void sendActionBar(Player p, String text) {
        if (Packets.CHAT_PACKET == null || Packets.CHAT_COMPONENT == null) return;
        try {
            Object c = Packets.CHAT_PACKET.getConstructor(Packets.CHAT_COMPONENT, byte.class)
                    .newInstance(Packets.component(text), (byte) 2);
            Packets.send(p, c);
        } catch (Throwable ignored) { }
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    /**
     * Modes with no food in the kit and no way to eat: Boxing and Sumo. Their hunger bar is held full for the whole
     * duel, because a draining bar there is unrecoverable rather than a resource to manage.
     */
    public boolean keepsHungerFull() {
        return mode.win() == DuelsMode.Win.HITS || mode.sumo;
    }

    /** Sumo's whole rule in one place, for a listener that only knows a player moved. */
    public boolean isSumo() { return mode.win() == DuelsMode.Win.KNOCKBACK; }

    /** Hits so far, for the boxing board and for a test. */
    public int hitsOf(UUID u) { Integer i = hits.get(u); return i == null ? 0 : i; }

    /** True while the fight is on (not the countdown, not the end screen). */
    public boolean roundLive() { return roundLive; }

    /** The material a mode's sword is, for the tab-list icon and for the arena signs. */
    public Material duelsIcon() { return Material.IRON_SWORD; }

    /** A friendly one-liner for the queue message. */
    public String describe() { return mode.displayName() + " duel on " + mapName(); }

    /** The opponent a player is facing, or null. */
    public Player opponentOf(Player p) {
        UUID o = other(p.getUniqueId());
        return o == null ? null : Bukkit.getPlayer(o);
    }

    /** Everything a spectator needs: the two names and the mode. */
    public String spectateLine() {
        return ChatColor.GRAY + "Spectating " + ChatColor.WHITE + name(order.isEmpty() ? null : order.get(0))
                + ChatColor.GRAY + " vs " + ChatColor.WHITE + name(order.size() < 2 ? null : order.get(1));
    }

    /** Team colours are not used by duels, but the interface asks. */
    public TeamColor colourOf(Player p) { return order.indexOf(p.getUniqueId()) == 0 ? TeamColor.RED : TeamColor.BLUE; }
}
