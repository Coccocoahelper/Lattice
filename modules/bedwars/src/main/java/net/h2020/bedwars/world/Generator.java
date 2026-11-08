package net.h2020.bedwars.world;

import net.h2020.bedwars.util.Text;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

/**
 * Mid-map diamond / emerald generator with the 2020 display: a spinning, bobbing block on an invisible armor stand
 * and three hologram lines ("Tier II", "Diamond", "Spawns in 12 seconds!").
 */
public final class Generator {
    public enum Kind {
        DIAMOND(Material.DIAMOND, Material.DIAMOND_BLOCK, ChatColor.AQUA + "" + ChatColor.BOLD + "Diamond",
                new int[]{30, 23, 12}, 4, 8),
        EMERALD(Material.EMERALD, Material.EMERALD_BLOCK, ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Emerald",
                new int[]{65, 50, 30}, 2, 5);

        public final Material drop, block;
        public final String label;
        final int[] intervals;
        /** Max resources lying on the generator: Solo/Doubles vs 3v3v3v3/4v4v4v4 (wiki 2020-05). */
        final int cap, bigCap;

        Kind(Material drop, Material block, String label, int[] intervals, int cap, int bigCap) {
            this.drop = drop; this.block = block; this.label = label; this.intervals = intervals;
            this.cap = cap; this.bigCap = bigCap;
        }
    }

    private final Kind kind;
    private final Location dropAt;
    private final int cap;
    private int tier = 1;
    private int secondsLeft;
    private ArmorStand display;
    private Hologram tierLine, nameLine, timerLine;
    private double angle;
    private final double phase = Math.random() * Math.PI * 2;

    public Generator(Kind kind, Location dropAt, boolean bigMode) {
        this.kind = kind;
        this.dropAt = dropAt;
        this.cap = bigMode ? kind.bigCap : kind.cap;
        this.secondsLeft = kind.intervals[0];
    }

    public Kind kind() { return kind; }
    public Location location() { return dropAt.clone(); }
    public int tier() { return tier; }

    public void spawnDisplay() {
        Location base = dropAt.clone().add(0, 0.9, 0);
        display = (ArmorStand) base.getWorld().spawnEntity(base, EntityType.ARMOR_STAND);
        display.setVisible(false);
        display.setGravity(false);
        display.setBasePlate(false);
        display.setRemoveWhenFarAway(false);
        display.setHelmet(new ItemStack(kind.block));
        tierLine = new Hologram(dropAt.clone().add(0, 4.05, 0), tierText());
        nameLine = new Hologram(dropAt.clone().add(0, 3.75, 0), kind.label);
        timerLine = new Hologram(dropAt.clone().add(0, 3.45, 0), timerText());
    }

    private String tierText() { return ChatColor.YELLOW + "Tier " + ChatColor.RED + Text.roman(tier); }

    private String timerText() {
        return ChatColor.YELLOW + "Spawns in " + ChatColor.RED + secondsLeft + ChatColor.YELLOW
                + (secondsLeft == 1 ? " second!" : " seconds!");
    }

    public void setTier(int t) {
        tier = Math.max(1, Math.min(3, t));
        int interval = kind.intervals[tier - 1];
        if (secondsLeft > interval) secondsLeft = interval;
        if (tierLine != null) tierLine.setText(tierText());
    }

    /** Called every tick: spin/bob the block. */
    public void animate(long tick) {
        if (display == null || !display.isValid()) return;
        // Hypixel's blocks spin with a slow ease in/out rather than a constant speed.
        angle += 0.06 + 0.05 * Math.sin(tick / 30.0 + phase);
        display.setHeadPose(new EulerAngle(0, angle, 0));
        double bob = Math.sin(tick / 18.0 + phase) * 0.18;
        Location l = dropAt.clone().add(0, 0.9 + bob, 0);
        l.setYaw(0);
        display.teleport(l);
    }

    /** Called once per second. */
    public void second() {
        secondsLeft--;
        if (secondsLeft <= 0) {
            spawn();
            secondsLeft = kind.intervals[tier - 1];
        }
        if (timerLine != null) timerLine.setText(timerText());
    }

    private void spawn() {
        int present = 0;
        for (Entity e : dropAt.getWorld().getNearbyEntities(dropAt, 2.0, 2.0, 2.0)) {
            if (e instanceof Item && ((Item) e).getItemStack().getType() == kind.drop) {
                present += ((Item) e).getItemStack().getAmount();
            }
        }
        if (present >= cap) return;
        Item it = dropAt.getWorld().dropItem(dropAt.clone().add(0, 0.25, 0), new ItemStack(kind.drop));
        it.setVelocity(new Vector(0, 0, 0));
    }

    public void remove() {
        if (display != null) display.remove();
        if (tierLine != null) tierLine.remove();
        if (nameLine != null) nameLine.remove();
        if (timerLine != null) timerLine.remove();
    }
}
