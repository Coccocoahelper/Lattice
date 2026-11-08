package net.h2020.bedwars;

import net.h2020.api.game.GameState;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.projectiles.ProjectileSource;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Damage rules: no friendly fire, attacker attribution for kill credit, explosion scaling, death interception. */
final class Combat {
    private Combat() {}

    static void onPlayerDamage(final BedwarsGame g, final Player p, EntityDamageEvent e) {
        BwPlayer v = g.data(p);
        DamageCause cause = e.getCause();
        if (v == null || g.state != GameState.PLAYING || !v.playing() || v.team == null) {
            e.setCancelled(true);
            if (cause == DamageCause.VOID) {
                boolean lobby = g.state == GameState.WAITING || g.state == GameState.STARTING;
                p.setFallDistance(0);
                p.teleport(lobby ? g.waitingLobby : g.spectatorSpawn);
            }
            return;
        }
        if (e instanceof EntityDamageByEntityEvent && !attribute(g, v, (EntityDamageByEntityEvent) e)) {
            e.setCancelled(true);
            return;
        }
        if (cause == DamageCause.VOID) {
            e.setCancelled(true);
            g.handleDeath(v, DamageCause.VOID);
            return;
        }
        if (p.getHealth() - e.getFinalDamage() <= 0.0) {
            e.setCancelled(true);
            g.handleDeath(v, cause);
        }
    }

    /** @return false if the hit must be cancelled. Records the combat tag otherwise. */
    private static boolean attribute(final BedwarsGame g, BwPlayer v, EntityDamageByEntityEvent e) {
        Entity d = e.getDamager();
        Player attacker = null;
        BwPlayer.DamageKind kind = BwPlayer.DamageKind.MELEE;
        String mobName = null;
        boolean fireball = false, tnt = false;

        if (d instanceof Player) {
            attacker = (Player) d;
        } else if (d instanceof Projectile) {
            ProjectileSource s = ((Projectile) d).getShooter();
            if (s instanceof Player) attacker = (Player) s;
            if (d instanceof Arrow) kind = BwPlayer.DamageKind.BOW;
            else if (d instanceof Fireball) { kind = BwPlayer.DamageKind.EXPLOSION; fireball = true; }
            else kind = BwPlayer.DamageKind.OTHER;
        } else if (d instanceof TNTPrimed) {
            attacker = g.gadgets.tntOwner(d);
            kind = BwPlayer.DamageKind.EXPLOSION;
            tnt = true;
        } else {
            BwTeam dragonTeam = g.dragons.teamOf(d);
            if (dragonTeam != null) {
                if (dragonTeam == v.team) return false;
                kind = BwPlayer.DamageKind.MOB;
                mobName = dragonTeam.color.chat + dragonTeam.color.displayName + " Dragon";
            } else {
                Gadgets.OwnedMob m = g.gadgets.mob(d);
                if (m != null) {
                    if (m.team == v.team) return false;
                    attacker = m.owner;
                    kind = BwPlayer.DamageKind.MOB;
                    mobName = m.label;
                    if (d instanceof IronGolem) e.setDamage(Math.min(e.getDamage(), 4.5));
                }
            }
        }

        Player victim = v.player;
        if (attacker == victim) {
            // own fireball / TNT: jump-friendly reduced damage
            if (fireball) e.setDamage(Math.min(e.getDamage() * 0.25, 2.5));
            else if (tnt) e.setDamage(Math.min(e.getDamage() * 0.4, 6.0));
            return true;
        }
        if (attacker != null) {
            BwPlayer a = g.data(attacker);
            if (a == null || a.team == null || a.eliminated) {
                if (kind == BwPlayer.DamageKind.MOB && a == null) attacker = null; // owner left: mob still fights
                else return false;
            } else {
                if (kind == BwPlayer.DamageKind.MELEE && !a.playing()) return false;
                if (a.team == v.team) return false; // no friendly fire
            }
        }
        if (fireball) e.setDamage(Math.min(e.getDamage(), 6.0));
        else if (tnt) e.setDamage(Math.min(e.getDamage(), 10.0));

        if (attacker != null || mobName != null) {
            v.lastDamager = attacker;
            v.lastDamageAt = System.currentTimeMillis();
            v.lastDamageKind = kind;
            v.lastDamageMobName = mobName;
        }
        if (kind == BwPlayer.DamageKind.BOW && attacker != null) {
            final Player shooter = attacker;
            final BwPlayer target = v;
            Bukkit.getScheduler().runTask(g.module.plugin(), new Runnable() {
                @Override public void run() {
                    if (!target.playing() || !shooter.isOnline()) return;
                    String hp = new BigDecimal(target.player.getHealth()).setScale(1, RoundingMode.HALF_UP).toPlainString();
                    shooter.sendMessage(g.feedName(target.player) + ChatColor.GRAY + " is on " + ChatColor.RED + hp
                            + ChatColor.GRAY + " HP!");
                }
            });
        }
        return true;
    }
}
