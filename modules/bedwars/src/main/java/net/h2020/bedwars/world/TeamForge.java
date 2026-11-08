package net.h2020.bedwars.world;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Random;

/**
 * A team's island forge: iron + gold, emeralds from Emerald Forge on. Forge levels speed up spawning
 * (+50% / +100% / +100% & emeralds / +200%). Items carry {@link #META} so pickups can be split between teammates.
 */
public final class TeamForge {
    public static final String META = "bw_forge";

    private static final int IRON_CAP = 48, GOLD_CAP = 16, EMERALD_CAP = 4;

    private final Plugin plugin;
    private final String teamKey;
    private final Location at;
    private final boolean bigMode;
    private final Random random = new Random();
    private double ironProgress, goldProgress, emeraldProgress;
    private int level;

    public TeamForge(Plugin plugin, String teamKey, Location at, boolean bigMode) {
        this.plugin = plugin; this.teamKey = teamKey; this.at = at; this.bigMode = bigMode;
    }

    public Location location() { return at.clone(); }
    public void setLevel(int level) { this.level = level; }

    private double multiplier() {
        switch (level) {
            case 1: return 1.5;
            case 2: case 3: return 2.0;
            case 4: return 3.0;
            default: return 1.0;
        }
    }

    /** Called every tick. */
    public void tick() {
        double m = multiplier();
        // base: 1 iron / ~1s (0.6s in 3s/4s), 1 gold / ~6s (4s in 3s/4s), 1 emerald / 60s (40s)
        ironProgress += m / (bigMode ? 12.0 : 20.0);
        goldProgress += m / (bigMode ? 80.0 : 120.0);
        if (level >= 3) emeraldProgress += 1.0 / (bigMode ? 800.0 : 1200.0);
        if (ironProgress >= 1) {
            ironProgress -= 1;
            // Hypixel's forge occasionally drops two at once.
            drop(Material.IRON_INGOT, random.nextInt(8) == 0 ? 2 : 1, IRON_CAP);
        }
        if (goldProgress >= 1) { goldProgress -= 1; drop(Material.GOLD_INGOT, 1, GOLD_CAP); }
        if (emeraldProgress >= 1) { emeraldProgress -= 1; drop(Material.EMERALD, 1, EMERALD_CAP); }
    }

    private void drop(Material m, int amount, int cap) {
        int present = 0;
        for (Entity e : at.getWorld().getNearbyEntities(at, 1.6, 1.5, 1.6)) {
            if (e instanceof Item && ((Item) e).getItemStack().getType() == m) present += ((Item) e).getItemStack().getAmount();
        }
        if (present >= cap) return;
        amount = Math.min(amount, cap - present);
        Item it = at.getWorld().dropItem(at.clone().add(0, 0.2, 0), new ItemStack(m, amount));
        it.setVelocity(new Vector(0, 0, 0));
        it.setMetadata(META, new FixedMetadataValue(plugin, teamKey));
    }
}
