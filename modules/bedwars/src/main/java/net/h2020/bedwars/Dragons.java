package net.h2020.bedwars;

import net.minecraft.server.v1_8_R3.EntityEnderDragon;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftEnderDragon;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/**
 * Sudden Death ender dragons: one per surviving team (two with Dragon Buff). Vanilla 1.8 dragons wander around the
 * world origin, so their NMS target point / target entity is steered towards enemies of their team around mid.
 */
final class Dragons {
    private final BedwarsGame g;
    private final Map<EnderDragon, BwTeam> dragons = new HashMap<>();
    private final Random random = new Random();

    Dragons(BedwarsGame g) { this.g = g; }

    void spawnAll() {
        for (BwTeam t : g.teams) {
            if (!t.participated || !t.alive()) continue;
            int n = t.upgrades.level(net.h2020.bedwars.shop.Upgrade.DRAGON_BUFF) > 0 ? 2 : 1;
            for (int i = 0; i < n; i++) spawn(t);
        }
    }

    private void spawn(BwTeam t) {
        Location at = g.mid.clone().add(random.nextInt(41) - 20, 30 + random.nextInt(10), random.nextInt(41) - 20);
        EnderDragon d = g.world.spawn(at, EnderDragon.class);
        d.setCustomName(t.color.chat + t.color.displayName + " Dragon");
        d.setCustomNameVisible(false);
        d.setRemoveWhenFarAway(false);
        dragons.put(d, t);
    }

    BwTeam teamOf(Entity e) {
        if (e instanceof org.bukkit.entity.ComplexEntityPart) e = ((org.bukkit.entity.ComplexEntityPart) e).getParent();
        return e instanceof EnderDragon ? dragons.get(e) : null;
    }

    boolean isDragon(Entity e) { return teamOf(e) != null; }

    void tick() {
        if (dragons.isEmpty() || g.tick % 10 != 0) return;
        Iterator<Map.Entry<EnderDragon, BwTeam>> it = dragons.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<EnderDragon, BwTeam> e = it.next();
            EnderDragon d = e.getKey();
            if (!d.isValid()) { it.remove(); continue; }
            EntityEnderDragon nms = ((CraftEnderDragon) d).getHandle();
            Player best = null;
            double bestD = 90 * 90;
            for (BwPlayer bp : g.playingPlayers()) {
                if (bp.team == e.getValue()) continue;
                double dist = bp.player.getLocation().distanceSquared(d.getLocation());
                if (dist < bestD) { bestD = dist; best = bp.player; }
            }
            if (best != null && random.nextInt(3) != 0) {
                nms.target = ((CraftPlayer) best).getHandle();
            } else {
                nms.target = null;
                Location l = d.getLocation();
                double dx = l.getX() - g.mid.getX(), dz = l.getZ() - g.mid.getZ();
                double tx = nms.a - g.mid.getX(), tz = nms.c - g.mid.getZ();
                // re-pick a cruise point near mid when the current one is far from the map or reached
                if (tx * tx + tz * tz > 60 * 60 || dx * dx + dz * dz > 70 * 70 || random.nextInt(8) == 0) {
                    nms.a = g.mid.getX() + random.nextInt(61) - 30;
                    nms.b = g.mid.getY() + 5 + random.nextInt(20);
                    nms.c = g.mid.getZ() + random.nextInt(61) - 30;
                }
            }
        }
    }

    void clear() {
        for (EnderDragon d : dragons.keySet()) d.remove();
        dragons.clear();
    }
}
