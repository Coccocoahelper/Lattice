package net.h2020.bedwars.shop;

import org.bukkit.Material;

/** Base traps (max 3 queued, triggered one at a time when an enemy enters the base). */
public enum Trap {
    IT_IS_A_TRAP("trap_it_is_a_trap", "It's a Trap!", Material.TRIPWIRE_HOOK,
            "Inflicts Blindness and Slowness for 8 seconds."),
    COUNTER_OFFENSIVE("trap_counter_offensive", "Counter-Offensive Trap", Material.FEATHER,
            "Grants Speed I and Jump Boost II for 15 seconds to allied players near your base."),
    ALARM("trap_alarm", "Alarm Trap", Material.REDSTONE_TORCH_ON,
            "Reveals invisible players as well as their name and team."),
    MINER_FATIGUE("trap_miner_fatigue", "Miner Fatigue Trap", Material.IRON_PICKAXE,
            "Inflict Mining Fatigue for 10 seconds.");

    public static final int MAX_QUEUE = 3;

    public final String id;
    public final String displayName;
    public final Material icon;
    public final String description;

    Trap(String id, String displayName, Material icon, String description) {
        this.id = id; this.displayName = displayName; this.icon = icon; this.description = description;
    }

    /** 2020 pricing: 1, 2, 4 diamonds depending on how many traps are already queued. */
    public static int cost(int queued) {
        return queued <= 0 ? 1 : queued == 1 ? 2 : 4;
    }

    public static Trap byId(String id) {
        for (Trap t : values()) if (t.id.equalsIgnoreCase(id)) return t;
        return null;
    }
}
