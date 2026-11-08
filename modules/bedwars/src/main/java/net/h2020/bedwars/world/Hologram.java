package net.h2020.bedwars.world;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;

/** One floating text line: an invisible marker armor stand with a visible custom name. */
public final class Hologram {
    /** Marker stands render their name tag ~0.5 above their position; callers pass the desired text height. */
    private static final double NAME_OFFSET = 0.5;

    private final ArmorStand stand;
    private String text;

    public Hologram(Location textAt, String text) {
        Location at = textAt.clone().subtract(0, NAME_OFFSET, 0);
        stand = (ArmorStand) at.getWorld().spawnEntity(at, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setMarker(true);
        stand.setSmall(true);
        stand.setBasePlate(false);
        stand.setCustomNameVisible(true);
        stand.setCustomName(text);
        stand.setRemoveWhenFarAway(false);
        this.text = text;
    }

    public void setText(String t) {
        if (t.equals(text)) return;
        text = t;
        if (stand.isValid()) stand.setCustomName(t);
    }

    public ArmorStand entity() { return stand; }

    public void remove() { stand.remove(); }
}
