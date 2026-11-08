package net.h2020.bedwars.shop;

import net.h2020.bedwars.BedwarsGame;
import net.h2020.bedwars.BwPlayer;
import net.h2020.bedwars.BwTeam;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** The registered trap effects, one class each. */
public final class TrapActions {
    private TrapActions() {}

    public static final TrapAction IT_IS_A_TRAP = (game, team, intruder) -> {
        Player ip = intruder.player;
        ip.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 8 * 20, 0), true);
        ip.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 8 * 20, 0), true);
    };

    public static final TrapAction COUNTER_OFFENSIVE = (game, team, intruder) -> {
        for (Player m : team.members) {
            BwPlayer bm = game.data(m);
            if (bm != null && bm.playing() && team.inBase(m.getLocation())) {
                m.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 15 * 20, 0), true);
                m.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, 15 * 20, 1), true);
            }
        }
    };

    public static final TrapAction ALARM = (game, team, intruder) ->
            intruder.player.removePotionEffect(PotionEffectType.INVISIBILITY);

    public static final TrapAction MINER_FATIGUE = (game, team, intruder) ->
            intruder.player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_DIGGING, 10 * 20, 0), true);

    public static TrapAction of(Trap trap) {
        switch (trap) {
            case IT_IS_A_TRAP: return IT_IS_A_TRAP;
            case COUNTER_OFFENSIVE: return COUNTER_OFFENSIVE;
            case ALARM: return ALARM;
            case MINER_FATIGUE: return MINER_FATIGUE;
            default: return (game, team, intruder) -> { };
        }
    }
}
