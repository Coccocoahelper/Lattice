package net.h2020.core.arena;

import net.h2020.api.stats.CombatStats;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * A flat world for testing melee mechanics where nobody can die.
 * <p>
 * Damage still applies - hit counts, knockback and combos behave exactly as in a real fight - but a lethal hit is
 * cancelled, health is restored and the player goes back on the pad. That is the difference between testing a combo and
 * testing a respawn timer.
 */
public final class PvpArena implements Listener {
    public static final String WORLD = "pvp_arena";
    private static final int RADIUS = 22;
    private static final int FLOOR_Y = 64;

    private static JavaPlugin plugin;

    private PvpArena() {}

    public static void install(JavaPlugin p) {
        plugin = p;
        Bukkit.getPluginManager().registerEvents(new PvpArena(), p);
        // kept applied rather than granted once: a respawn, a world change or a milk bucket would otherwise drop it
        Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
            @Override public void run() { keepBuff(); }
        }, 20L, 20L);
    }

    /** Resistance 20 (total immunity) and everyone visible to everyone, for everybody on the pad. */
    public static volatile long buffRuns;

    private static void keepBuff() {
        buffRuns++;
        World w = Bukkit.getWorld(WORLD);
        if (w == null) return;
        java.util.List<Player> in = new java.util.ArrayList<Player>();
        for (Player p : w.getPlayers()) {
            if (p.isDead()) continue;
            in.add(p);
            // a bot that came from a game can still be a spectator or under an invisibility potion: both read as
            // "there is nobody there" to a player standing on the pad
            if (p.getGameMode() != org.bukkit.GameMode.SURVIVAL) p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            p.removePotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
            int amp = -1, sat = -1;
            for (org.bukkit.potion.PotionEffect e : p.getActivePotionEffects()) {
                if (e.getType().getName().equals("DAMAGE_RESISTANCE")) amp = e.getAmplifier();
                if (e.getType().getName().equals("SATURATION")) sat = e.getAmplifier();
            }
            if (amp < 20) {
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.DAMAGE_RESISTANCE, 60, 20, true, false), true);
            }
            // saturation 20: no hunger drain, and natural regeneration stays topped up between hits
            if (sat < 20) {
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.SATURATION, 60, 20, true, false), true);
            }
            p.setFoodLevel(20);
        }
        // the lobby's player-visibility toggle leaves people hidden across worlds: undo it here, and re-send the
        // spawn for anyone whose entity the client never received (hide+show forces the player-info and spawn packets)
        for (final Player a : in) {
            for (final Player b : in) {
                if (a == b) continue;
                if (!a.canSee(b)) {
                    a.hidePlayer(b);
                    Bukkit.getScheduler().runTask(plugin, new Runnable() {
                        @Override public void run() { if (a.isOnline() && b.isOnline()) a.showPlayer(b); }
                    });
                }
                if (!b.canSee(a)) {
                    b.hidePlayer(a);
                    Bukkit.getScheduler().runTask(plugin, new Runnable() {
                        @Override public void run() { if (a.isOnline() && b.isOnline()) b.showPlayer(a); }
                    });
                }
            }
        }
    }

    public static World world() {
        net.h2020.api.core.Worlds.arena(WORLD);
        World w = Bukkit.getWorld(WORLD);
        if (w != null) return w;
        WorldCreator wc = new WorldCreator(WORLD);
        wc.type(WorldType.FLAT);
        wc.generateStructures(false);
        w = wc.createWorld();
        if (w == null) return null;
        w.setSpawnLocation(0, FLOOR_Y + 1, 0);
        w.setDifficulty(org.bukkit.Difficulty.EASY);
        w.setGameRuleValue("doDaylightCycle", "false");
        w.setGameRuleValue("doMobSpawning", "false");
        w.setGameRuleValue("keepInventory", "true");
        w.setGameRuleValue("naturalRegeneration", "true");
        w.setGameRuleValue("doFireTick", "false");
        w.setTime(6000);
        build(w);
        return w;
    }

    /** Stone pad, a wall around it, clear air above: something to fight on and nowhere to fall. */
    private static void build(World w) {
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                Block floor = w.getBlockAt(x, FLOOR_Y, z);
                boolean edge = Math.abs(x) == RADIUS || Math.abs(z) == RADIUS;
                floor.setType(edge ? Material.BEDROCK : Material.STONE);
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 7; y++) w.getBlockAt(x, y, z).setType(Material.AIR);
                if (edge) for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 4; y++) w.getBlockAt(x, y, z).setType(Material.GLASS);
            }
        }
        w.setSpawnLocation(0, FLOOR_Y + 1, 0);
    }

    public static boolean isArena(World w) { return w != null && WORLD.equals(w.getName()); }

    public static Location spawn() {
        World w = world();
        return w == null ? null : new Location(w, 0.5, FLOOR_Y + 1, 0.5, 0f, 0f);
    }

    /** The uniform: sword, blocks, gapples, rod, diamond armour. Bots get it too, or they fight bare-handed. */
    public static void giveKit(Player p) {
        p.getInventory().clear();
        p.getInventory().setHeldItemSlot(0);
        p.getInventory().setItem(0, new org.bukkit.inventory.ItemStack(Material.DIAMOND_SWORD));
        p.getInventory().setItem(1, new org.bukkit.inventory.ItemStack(Material.WOOL, 64));
        p.getInventory().setItem(2, new org.bukkit.inventory.ItemStack(Material.GOLDEN_APPLE, 64));
        p.getInventory().setItem(3, new org.bukkit.inventory.ItemStack(Material.FISHING_ROD));
        p.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[]{
                new org.bukkit.inventory.ItemStack(Material.DIAMOND_BOOTS),
                new org.bukkit.inventory.ItemStack(Material.DIAMOND_LEGGINGS),
                new org.bukkit.inventory.ItemStack(Material.DIAMOND_CHESTPLATE),
                new org.bukkit.inventory.ItemStack(Material.DIAMOND_HELMET)});
    }

    /** Put a player on the pad in survival, fed and full, with what a melee test needs. */
    public static void join(Player p) {
        Location s = spawn();
        if (s == null) { net.h2020.api.core.Messages.send(p, "core.pvparena.could-not-create-the"); return; }
        p.teleport(s);
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(20.0);
        p.setFoodLevel(20);
        giveKit(p);
        int bots = populate(3);
        p.sendMessage(ChatColor.GREEN + "PvP arena: nobody dies here, damage still counts."
                + (bots > 0 ? ChatColor.GRAY + " (" + bots + " bots joined)" : ""));
    }

    /**
     * Bring bots along. An arena you walk into alone is an empty room: when a player joins, the closest idle bots
     * follow, up to `n`, so there is something to fight without a second command.
     */
    public static int populate(int n) {
        Location spawn = spawn();
        if (spawn == null) return 0;
        net.h2020.api.bot.BotService svc = net.h2020.api.H2020.find(net.h2020.api.bot.BotService.class);
        if (svc == null) return 0;
        int moved = 0;
        java.util.List<net.h2020.api.bot.Bot> pool = new java.util.ArrayList<net.h2020.api.bot.Bot>(svc.bots());
        java.util.Collections.shuffle(pool);
        for (net.h2020.api.bot.Bot b : pool) {
            if (moved >= n) break;
            Player p = b.player();
            if (p == null || !p.isOnline() || isArena(p.getWorld())) continue;
            double ang = ctxAngle(moved);
            Location at = spawn.clone().add(Math.cos(ang) * 4, 0, Math.sin(ang) * 4);
            at.setY(spawn.getY());
            p.teleport(at);
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(20.0);
            p.setFoodLevel(20);
            p.removePotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
            giveKit(p);
            moved++;
        }
        return moved;
    }

    private static double ctxAngle(int i) { return i * (Math.PI * 2.0 / 3.0) + 0.6; }

    /** Entering the arena re-sends everyone to the arriving client: a stale hide from the lobby follows you through. */
    @EventHandler
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent e) {
        if (!isArena(e.getPlayer().getWorld())) return;
        final Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() {
                if (!p.isOnline()) return;
                for (Player o : p.getWorld().getPlayers()) {
                    if (o == p) continue;
                    o.hidePlayer(p);
                    p.hidePlayer(o);
                }
                Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                    @Override public void run() {
                        for (Player o : p.getWorld().getPlayers()) {
                            if (o == p) continue;
                            o.showPlayer(p);
                            p.showPlayer(o);
                        }
                    }
                }, 2L);
            }
        }, 1L);
    }

    /** Death is refused: back to full, back on the pad, everything kept. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        final Player p = e.getEntity();
        if (!isArena(p.getWorld())) return;
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        e.getDrops().clear();
        e.setDeathMessage(null);
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                if (!p.isOnline()) return;
                p.spigot().respawn();
                p.teleport(spawn());
                p.setHealth(20.0);
                p.setFoodLevel(20);
                p.setFireTicks(0);
            }
        });
    }

    /**
     * Only knockback, no health loss.
     * <p>
     * The hit itself must not be cancelled: knockback, hit counting and the sprint reset are all consequences of a
     * damage event that actually applies, so cancelling it would make the arena useless for testing combos. Instead a
     * lethal hit is trimmed to leave half a heart, and the health is put back straight after the damage resolves.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onLethal(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        if (!isArena(p.getWorld()) || e.isCancelled()) return;
        if (e.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.VOID
                || e.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL) {
            e.setCancelled(true);
            if (p.getLocation().getY() < FLOOR_Y - 3) p.teleport(spawn());
            return;
        }
        if (p.getHealth() - e.getFinalDamage() <= 0) {
            e.setDamage(Math.max(0.5, p.getHealth() - 0.5));
            net.h2020.api.stats.CombatStats.inc("arena.trimmed");
        }
    }

    /** After the hit has applied (knockback included): health back to full, every time. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAfterDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player) || e.isCancelled()) return;
        Player p = (Player) e.getEntity();
        if (!isArena(p.getWorld())) return;
        p.setHealth(20.0);
        net.h2020.api.stats.CombatStats.inc("arena.nodamage");
    }
}
