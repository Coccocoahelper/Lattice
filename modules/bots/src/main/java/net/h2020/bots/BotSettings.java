package net.h2020.bots;

import net.h2020.bots.ai.combat.CombatTuning;
import net.h2020.bots.ai.perception.LagView;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The bots module's own settings (config section "bots"), the ones an admin actually fiddles with mid-video:
 * <ul>
 * <li>{@link #averageSkill}: where the skill bell curve of newly spawned bots is centred (0 = complete beginner,
 *   1 = sweat). -1 keeps the 2020 public-lobby mix (most players average, a few sweats, a few beginners).</li>
 * <li>{@link #perceivedLatency}: bots see other players delayed by half a round trip (see
 *   {@link net.h2020.bots.ai.perception.LagView}) instead of the server's current position.</li>
 * <li>{@link #blockPlay}, {@link #fireballPlay}, {@link #bedRaid}: the block-placement / fireball / bed-raid
 *   behaviours of the fight layer, for A/B runs.</li>
 * </ul>
 * Saved back to config.yml by {@link #save()} so a restart keeps the menu state.
 */
public final class BotSettings {
    public static final double DEFAULT_SKILL = -1.0;

    private final JavaPlugin plugin;

    public double averageSkill = DEFAULT_SKILL;
    public boolean perceivedLatency = true;
    public boolean blockPlay = true;
    public boolean fireballPlay = true;
    public boolean bedRaid = true;
    /** Use the dedicated diagonal stance (back-down-the-bridge, two cells per stance). */
    public boolean diagonalStance = false;

    public BotSettings(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("bots");
        if (c == null) return;
        averageSkill = clamp(c.getDouble("average-skill", DEFAULT_SKILL));
        perceivedLatency = c.getBoolean("perceived-latency", true);
        blockPlay = c.getBoolean("combat-block-play", true);
        fireballPlay = c.getBoolean("fireball-play", true);
        bedRaid = c.getBoolean("bed-raid", true);
        diagonalStance = c.getBoolean("diagonal-stance", false);
        apply();
    }

    public void save() {
        plugin.getConfig().set("bots.average-skill", round(averageSkill));
        plugin.getConfig().set("bots.perceived-latency", perceivedLatency);
        plugin.getConfig().set("bots.combat-block-play", blockPlay);
        plugin.getConfig().set("bots.fireball-play", fireballPlay);
        plugin.getConfig().set("bots.bed-raid", bedRaid);
        plugin.getConfig().set("bots.diagonal-stance", diagonalStance);
        plugin.saveConfig();
    }

    /** Push the switches into the behaviour classes. */
    public void apply() {
        LagView.enabled = perceivedLatency;
        CombatTuning.blockPlay = blockPlay;
        CombatTuning.fireballPlay = fireballPlay;
        CombatTuning.bedRaid = bedRaid;
        net.h2020.bots.ai.motor.BridgeTuning.diagonalStance = diagonalStance;
    }

    public void reset() {
        averageSkill = DEFAULT_SKILL;
        perceivedLatency = true;
        blockPlay = true;
        fireballPlay = true;
        bedRaid = true;
        diagonalStance = false;
        save();
        apply();
    }

    /** 0..1 skill badge for the menu ("beginner", "average", "sweat"...). */
    public String skillLabel() {
        if (averageSkill < 0) return "2020 lobby mix";
        if (averageSkill < 0.25) return "beginners";
        if (averageSkill < 0.45) return "below average";
        if (averageSkill < 0.62) return "average";
        if (averageSkill < 0.8) return "good";
        return "sweats";
    }

    private static double clamp(double v) {
        if (v < 0) return DEFAULT_SKILL;
        return Math.max(0.05, Math.min(0.97, v));
    }

    private static double round(double v) {
        return v < 0 ? -1 : Math.round(v * 100.0) / 100.0;
    }
}
