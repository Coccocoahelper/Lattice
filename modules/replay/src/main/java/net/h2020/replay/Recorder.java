package net.h2020.replay;

import net.h2020.api.H2020;
import net.h2020.api.bot.BotService;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameTeam;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Records every game at ten frames a second: where each player was, which way they looked, their health, whether they
 * were crouched and what they were holding. Small enough to keep hours of games (16 players, 10 Hz, ~120 bytes a
 * frame), and enough to play a match back as moving player puppets in a fresh copy of the same map.
 * <p>
 * Only games with at least one bot are recorded (that is what there is to learn from), and the newest 40 recordings are
 * kept.
 */
public final class Recorder {
    public static final int HZ_TICKS = 1;             // every tick = 20 frames a second
    public static final long MAX_GAME_MS = 25L * 60L * 1000L;
    public static final int KEEP = 200;      // replays stay on disk: they are the hindsight training set

    private static final Map<String, BufferedWriter> OPEN = new HashMap<String, BufferedWriter>();
    private static final Map<String, Long> START = new HashMap<String, Long>();
    private static final Map<String, Long> LAST = new HashMap<String, Long>();
    private static final Map<java.util.UUID, Integer> FLAGS = new HashMap<java.util.UUID, Integer>();
    private static final Map<java.util.UUID, int[]> LAST_EQ = new HashMap<java.util.UUID, int[]>();
    private static File dir;
    private static final String ARENA = "arena";
    private static JavaPlugin plugin;
    private static long frames;

    private Recorder() {}

    public static void install(final JavaPlugin p) {
        plugin = p;
        Bukkit.getPluginManager().registerEvents(new Blocks(), p);
        dir = new File(p.getDataFolder(), "replays/games");
        if (!dir.isDirectory()) dir.mkdirs();
        Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
            @Override public void run() { tick(); }
        }, 100L, HZ_TICKS);
    }

    public static File dir() { return dir; }
    public static long frames() { return frames; }

    public static void shutdown() {
        for (BufferedWriter w : OPEN.values()) { try { w.close(); } catch (Throwable ignored) { } }
        OPEN.clear();
    }

    private static void tick() {
        GameManager gm = H2020.find(GameManager.class);
        BotService bs = H2020.find(BotService.class);
        if (gm == null || bs == null) return;
        long now = System.currentTimeMillis();
        for (Game g : new ArrayList<Game>(gm.games())) {
            String id = g.id();
            Long start = START.get(id);
            if (start == null) {
                if (!hasBot(g, bs)) continue;                    // lobby-only games are not worth the disk
                start = now;
                START.put(id, start);
                open(id, g);
            }
            if (now - start > MAX_GAME_MS) { close(id); continue; }
            Long last = LAST.get(id);
            if (last != null && now - last < 45) continue;       // never faster than 20 Hz
            LAST.put(id, now);
            frame(g, now - start);
        }
        // the PvP arena is not a game, but melee work there deserves the same recording
        String arenaName = net.h2020.api.core.Worlds.arena();
        org.bukkit.World arena = arenaName == null ? null : Bukkit.getWorld(arenaName);
        if (arena != null && !arena.getPlayers().isEmpty()) {
            Long start = START.get(ARENA);
            if (start == null) { START.put(ARENA, now); openWorld(ARENA, arena); start = now; }
            Long last = LAST.get(ARENA);
            if (last == null || now - last >= 45) { LAST.put(ARENA, now); frameWorld(ARENA, arena, now - start); }
        } else if (OPEN.containsKey(ARENA)) {
            close(ARENA);
        }
        // a game that vanished: finish its file
        java.util.Set<String> live = new java.util.HashSet<String>();
        for (Game gg : gm.games()) live.add(gg.id());
        for (String id : new ArrayList<String>(OPEN.keySet())) {
            if (ARENA.equals(id)) continue;              // the arena has its own lifecycle above
            if (!live.contains(id)) close(id);
        }
    }

    /** Arena sessions: same file format, keyed by world instead of game. */
    private static void openWorld(String id, org.bukkit.World world) {
        try {
            File f = new File(dir, id + "-" + (System.currentTimeMillis() / 1000L) + ".jsonl");
            BufferedWriter w = new BufferedWriter(new FileWriter(f, false));
            w.write("{\"h\":1,\"g\":\"" + id + "\",\"map\":\"" + world.getName() + "\",\"type\":\"arena\",\"npcs\":[]}\n");
            OPEN.put(id, w);
        } catch (Throwable ignored) { }
    }

    private static void frameWorld(String id, org.bukkit.World world, long t) {
        BufferedWriter w = OPEN.get(id);
        if (w == null) return;
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"t\":").append(t).append(",\"p\":[");
        boolean first = true;
        for (Player p : world.getPlayers()) {
            if (!first) sb.append(',');
            first = false;
            Location l = p.getLocation();
            sb.append("[\"").append(p.getName()).append("\",\"\",")
                    .append(round(l.getX())).append(',').append(round(l.getY())).append(',').append(round(l.getZ())).append(',')
                    .append(Math.round(l.getYaw())).append(',').append(Math.round(l.getPitch())).append(',')
                    .append(Math.round(p.getHealth())).append(',').append(p.isSneaking() ? 1 : 0).append(',')
                    .append(packed(p.getItemInHand())).append(",0,0,0,0,0]");
        }
        sb.append("]}");
        try { w.write(sb.toString()); w.write('\n'); frames++; } catch (Throwable ignored) { }
    }

    private static boolean hasBot(Game g, BotService bs) {
        for (Player p : g.players()) if (bs.isBot(p)) return true;
        return false;
    }

    private static void open(String id, Game g) {
        try {
            File f = new File(dir, id + ".jsonl");
            BufferedWriter w = new BufferedWriter(new FileWriter(f, false));
            StringBuilder h = new StringBuilder("{\"h\":1,\"g\":\"" + id + "\",\"map\":\"" + g.mapName()
                    + "\",\"type\":\"" + g.type().id() + "\",\"npcs\":[");
            boolean first = true;
            for (org.bukkit.entity.Entity e : g.world().getEntities()) {
                if (e instanceof Player) continue;
                if (!(e instanceof org.bukkit.entity.LivingEntity)) continue;
                if (e instanceof org.bukkit.entity.Monster) continue;
                // marker stands are holograms (generator labels, PUNCH TO), not NPCs
                if (e instanceof org.bukkit.entity.ArmorStand && ((org.bukkit.entity.ArmorStand) e).isMarker()) continue;
                if (!first) h.append(',');
                first = false;
                h.append("[\"").append(e.getType().name()).append("\",\"")
                        .append(Math.round(e.getLocation().getX() * 100.0) / 100.0).append("\",\"")
                        .append(Math.round(e.getLocation().getY() * 100.0) / 100.0).append("\",\"")
                        .append(Math.round(e.getLocation().getZ() * 100.0) / 100.0).append("\",\"")
                        .append(Math.round(e.getLocation().getYaw())).append("\",\"")
                        .append(e.getCustomName() == null ? "" : e.getCustomName().replace("\"", "'")).append("\"]");
            }
            // the shop and upgrade NPCs are packet entities, invisible to getEntities(): take them from the game
            if (g instanceof net.h2020.api.game.BedwarsView) {
                net.h2020.api.game.BedwarsView bw = (net.h2020.api.game.BedwarsView) g;
                for (net.h2020.api.game.GameTeam t : g.teams()) {
                    addNpc(h, "VILLAGER", bw.shopNpc(t.color()), "Item Shop");
                    addNpc(h, "VILLAGER", bw.upgradesNpc(t.color()), "Team Upgrades");
                }
            }
            h.append("]}");
            w.write(h.toString());
            w.write("\n");
            OPEN.put(id, w);
            prune();
        } catch (Throwable t) {
            plugin.getLogger().warning("[replay] could not record " + id + ": " + t);
        }
    }

    /** Arm swing, taking damage, eating: one bit each, collated per frame. */
    private static void flag(Player p, int bit) {
        if (p == null) return;
        Integer v = FLAGS.get(p.getUniqueId());
        FLAGS.put(p.getUniqueId(), (v == null ? 0 : v) | bit);
    }

    /** Blocks going down and coming out: a replay that only moves puppets is a replay of empty islands. */
    public static final class Blocks implements org.bukkit.event.Listener {
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onSwing(org.bukkit.event.player.PlayerAnimationEvent e) { flag(e.getPlayer(), 1); }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void onHurt(org.bukkit.event.entity.EntityDamageEvent e) {
            if (e.getEntity() instanceof Player) flag((Player) e.getEntity(), 2);
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onEat(org.bukkit.event.player.PlayerItemConsumeEvent e) { flag(e.getPlayer(), 4); }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onProjectile(org.bukkit.event.entity.ProjectileLaunchEvent e) {
            if (!(e.getEntity().getShooter() instanceof Player)) return;
            Player shooter = (Player) e.getEntity().getShooter();
            org.bukkit.entity.Entity pr = e.getEntity();
            org.bukkit.util.Vector v = pr.getVelocity();
            event(shooter, "[\"p\"," + n(pr.getLocation().getX()) + "," + n(pr.getLocation().getY()) + ","
                    + n(pr.getLocation().getZ()) + "," + n(v.getX()) + "," + n(v.getY()) + "," + n(v.getZ())
                    + ",\"" + pr.getType().name() + "\"]");
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent e) {
            if (!(e.getEntity().getShooter() instanceof Player)) return;
            org.bukkit.Location l = e.getEntity().getLocation();
            event((Player) e.getEntity().getShooter(), "[\"ph\"," + n(l.getX()) + "," + n(l.getY()) + ","
                    + n(l.getZ()) + "]");
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onDrop(org.bukkit.event.player.PlayerDropItemEvent e) {
            org.bukkit.entity.Item it = e.getItemDrop();
            org.bukkit.inventory.ItemStack st = it.getItemStack();
            event(e.getPlayer(), "[\"i\"," + n(it.getLocation().getX()) + "," + n(it.getLocation().getY()) + ","
                    + n(it.getLocation().getZ()) + "," + st.getTypeId() + "," + st.getDurability() + "]");
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onPickup(org.bukkit.event.player.PlayerPickupItemEvent e) {
            org.bukkit.Location l = e.getItem().getLocation();
            event(e.getPlayer(), "[\"ir\"," + n(l.getX()) + "," + n(l.getY()) + "," + n(l.getZ()) + "]");
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void onDamage(org.bukkit.event.block.BlockDamageEvent e) {
            // break progress: how long this block would take, so the replay can show the crack stages
            int ticks = Math.max(4, Math.min(200, e.getBlock().getType().getMaxDurability() > 0 ? 40 : 20));
            event(e.getPlayer(), "[\"br\"," + e.getBlock().getX() + "," + e.getBlock().getY() + ","
                    + e.getBlock().getZ() + "," + ticks + "]");
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void onExplode(org.bukkit.event.entity.EntityExplodeEvent e) {
            if (e.getLocation() == null) return;
            org.bukkit.entity.Entity src = e.getEntity();
            if (src instanceof org.bukkit.entity.Player) event((Player) src, "[\"x\"," + n(e.getLocation().getX())
                    + "," + n(e.getLocation().getY()) + "," + n(e.getLocation().getZ()) + "]");
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onPlace(org.bukkit.event.block.BlockPlaceEvent e) {
            block(e.getPlayer(), e.getBlockPlaced().getLocation(), e.getBlockPlaced().getTypeId(),
                    e.getBlockPlaced().getData(), true);
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onBreak(org.bukkit.event.block.BlockBreakEvent e) {
            block(e.getPlayer(), e.getBlock().getLocation(), e.getBlock().getTypeId(), e.getBlock().getData(), false);
        }
    }

    /** An arbitrary event line for a game: projectiles, items, breaks, explosions, effects. */
    static void event(Player p, String body) {
        if (p == null) return;
        String key = keyFor(p);
        if (key == null) return;
        BufferedWriter w = OPEN.get(key);
        Long start = START.get(key);
        if (w == null || start == null) return;
        try {
            w.write("{\"t\":" + (System.currentTimeMillis() - start) + ",\"e\":" + body + "}\n");
        } catch (Throwable ignored) { }
    }

    /** One place (id != 0) or break (id 0 with the old block in id2) at a recorded time. */
    /** Which recording a player belongs to: their game, or the arena session. */
    private static String keyFor(Player p) {
        net.h2020.api.game.GameManager gm = H2020.find(net.h2020.api.game.GameManager.class);
        net.h2020.api.game.Game g = gm == null ? null : gm.gameOf(p);
        if (g != null) return g.id();
        if (net.h2020.api.core.Worlds.isArena(p.getWorld())) return ARENA;
        return null;
    }

    private static void block(Player p, org.bukkit.Location at, int id, int data, boolean place) {
        if (p == null || at == null) return;
        String key = keyFor(p);
        if (key == null) return;
        BufferedWriter w = OPEN.get(key);
        Long start = START.get(key);
        if (w == null || start == null) return;
        long t = System.currentTimeMillis() - start;
        try {
            w.write("{\"t\":" + t + ",\"b\":[" + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ() + ","
                    + (place ? id : 0) + "," + data + "," + (place ? 0 : id) + ",\"" + p.getName() + "\"]}\n");
        } catch (Throwable ignored) { }
    }

    private static void frame(Game g, long t) {
        BufferedWriter w = OPEN.get(g.id());
        if (w == null) return;
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"t\":").append(t).append(",\"p\":[");
        boolean first = true;
        for (Player p : g.players()) {
            GameTeam team = g.teamOf(p);
            Location l = p.getLocation();
            int[] eq = {packed(p.getItemInHand()), packed(p.getInventory().getHelmet()),
                    packed(p.getInventory().getChestplate()), packed(p.getInventory().getLeggings()),
                    packed(p.getInventory().getBoots())};
            int[] prevEq = LAST_EQ.get(p.getUniqueId());
            int[] outEq = new int[5];
            for (int k = 0; k < 5; k++) outEq[k] = prevEq != null && prevEq[k] == eq[k] ? -1 : eq[k];
            LAST_EQ.put(p.getUniqueId(), eq);
            int held = outEq[0], helmet = outEq[1], chest = outEq[2], legs = outEq[3], boots = outEq[4];
            Integer f = FLAGS.remove(p.getUniqueId());
            int flags = f == null ? 0 : f;
            if (p.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY)) flags |= 8;
            if (!first) sb.append(',');
            first = false;
            sb.append("[\"").append(p.getName()).append("\",\"")
                    .append(team == null ? "-" : team.color().name()).append("\",")
                    .append(round(l.getX())).append(',').append(round(l.getY())).append(',').append(round(l.getZ())).append(',')
                    .append(Math.round(l.getYaw())).append(',').append(Math.round(l.getPitch())).append(',')
                    .append(Math.round(p.getHealth())).append(',')
                    .append(p.isSneaking() ? 1 : 0).append(',').append(held).append(',')
                    .append(helmet).append(',').append(chest).append(',').append(legs).append(',').append(boots).append(',')
                    .append(flags).append(']');
        }
        sb.append("]}");
        try {
            w.write(sb.toString());
            w.write('\n');
            frames++;
        } catch (Throwable ignored) { }
    }

    private static void close(String id) {
        BufferedWriter w = OPEN.remove(id);
        START.remove(id);
        LAST.remove(id);
        if (w == null) return;
        try {
            w.write("{\"end\":1}\n");
            w.close();
        } catch (Throwable ignored) { }
        // prune when a recording ends too: a long session would otherwise only ever trim at the next game start
        prune();
    }

    /** Keep only the newest KEEP recordings. */
    private static void prune() {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length <= KEEP) return;
            List<File> list = new ArrayList<File>();
            for (File f : files) if (f.getName().endsWith(".jsonl")) list.add(f);
            list.sort(new Comparator<File>() {
                @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
            });
            long total = 0;
            for (File f : list) total += f.length();
            long capBytes = 2L * 1024 * 1024 * 1024;              // 2 GB of recordings is plenty
            int i = 0;
            while (i < list.size() - 1 && (list.size() - i > KEEP || total > capBytes)) {
                total -= list.get(i).length();
                list.get(i).delete();
                i++;
            }
        } catch (Throwable ignored) { }
    }

    /**
     * One number per slot: leather armour is dyed, and dye lives in the item's colour, not in its durability, so dyed
     * leather is packed as 1,000,000 + RGB and everything else as id*100+data.
     */
    private static int packed(org.bukkit.inventory.ItemStack i) {
        if (i == null) return 0;
        if (i.getTypeId() >= 298 && i.getTypeId() <= 301) {           // leather helmet/chest/legs/boots
            org.bukkit.inventory.meta.ItemMeta meta = i.getItemMeta();
            if (meta instanceof org.bukkit.inventory.meta.LeatherArmorMeta) {
                org.bukkit.Color c = ((org.bukkit.inventory.meta.LeatherArmorMeta) meta).getColor();
                if (c != null) return 1_000_000 + (c.asRGB() & 0xFFFFFF);
            }
            return 1_000_000;
        }
        return i.getTypeId() * 100 + (i.getDurability() & 0xFF);
    }

    /** Append one static NPC entry to the header: ["TYPE",x,y,z,yaw,"name"]. */
    private static void addNpc(StringBuilder h, String type, org.bukkit.Location l, String name) {
        if (l == null) return;
        if (h.charAt(h.length() - 1) != '[') h.append(',');
        h.append("[\"").append(type).append("\",")
                .append(Math.round(l.getX() * 100.0) / 100.0).append(',')
                .append(Math.round(l.getY() * 100.0) / 100.0).append(',')
                .append(Math.round(l.getZ() * 100.0) / 100.0).append(',')
                .append(Math.round(l.getYaw())).append(",\"")
                .append(name).append("\"]");
    }

    private static String n(double v) {
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }

    private static String round(double v) {
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }
}
