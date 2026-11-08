package net.h2020.bots.profile;

import net.h2020.api.bot.BotProfile;
import net.h2020.api.bot.BotSkill;
import net.h2020.api.bot.CheatProfile;
import net.h2020.api.core.Rank;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Rolls realistic bot identities: 2020 usernames, a skin from the pool, the Hypixel rank mix of a public lobby
 * (~70% non, 12% VIP, 8% VIP+, 5% MVP, 4% MVP+, 1% MVP++), network level correlated with rank, log-normal ping and
 * a bell-curve skill profile (most players average, a few sweats, some complete beginners).
 */
public final class ProfileFactory {
    private static final ChatColor[] PLUS_COLORS = {
            ChatColor.RED, ChatColor.GOLD, ChatColor.GREEN, ChatColor.YELLOW, ChatColor.LIGHT_PURPLE, ChatColor.WHITE,
            ChatColor.BLUE, ChatColor.DARK_GREEN, ChatColor.DARK_RED, ChatColor.DARK_AQUA, ChatColor.DARK_PURPLE,
            ChatColor.DARK_GRAY, ChatColor.BLACK
    };
    private static final int[] PLUS_WEIGHTS = { 40, 10, 8, 8, 7, 5, 6, 4, 4, 3, 3, 1, 1 };

    private final Random rnd;
    private final NameGenerator names;
    private final SkinRepository skins;
    private final Set<String> reservedNames;
    /** -1 keeps the 2020 lobby mix; 0..1 centres the whole skill curve there (/bot settings). */
    private volatile double averageSkill = -1;

    public ProfileFactory(Random rnd, SkinRepository skins, Set<String> reservedNames) {
        this.rnd = rnd;
        this.names = new NameGenerator(rnd);
        this.skins = skins;
        this.reservedNames = reservedNames;
    }

    public void setAverageSkill(double v) { this.averageSkill = v; }

    public double averageSkill() { return averageSkill; }

    public BotProfile random(CheatProfile cheat) {
        return create(uniqueName(), cheat);
    }

    public BotProfile create(String name, CheatProfile cheat) {
        Rank rank = rollRank();
        ChatColor plus = (rank == Rank.MVP_PLUS || rank == Rank.MVP_PLUS_PLUS) ? rollPlusColor()
                : (rank == Rank.VIP_PLUS ? ChatColor.GOLD : ChatColor.RED);
        SkinRepository.Skin skin = skins == null ? null : skins.random(rnd);
        BotSkill skill = rollSkill(rank);
        return new BotProfile(name, UUID.randomUUID(), skin == null ? null : skin.value, skin == null ? null : skin.signature,
                rank, plus, rollLevel(rank), rollPing(), skill, cheat == null ? CheatProfile.NONE : cheat);
    }

    public String uniqueName() {
        for (int i = 0; i < 100; i++) {
            String n = names.next();
            if (isFree(n)) return n;
        }
        String n;
        do { n = "Player" + (1000 + rnd.nextInt(90000)); } while (!isFree(n));
        return n;
    }

    public boolean isFree(String name) {
        return Bukkit.getPlayerExact(name) == null && !reservedNames.contains(name.toLowerCase(Locale.ROOT));
    }

    private Rank rollRank() {
        int r = rnd.nextInt(100);
        if (r < 70) return Rank.NONE;
        if (r < 82) return Rank.VIP;
        if (r < 90) return Rank.VIP_PLUS;
        if (r < 95) return Rank.MVP;
        if (r < 99) return Rank.MVP_PLUS;
        return Rank.MVP_PLUS_PLUS;
    }

    private ChatColor rollPlusColor() {
        int total = 0;
        for (int w : PLUS_WEIGHTS) total += w;
        int r = rnd.nextInt(total);
        for (int i = 0; i < PLUS_WEIGHTS.length; i++) {
            r -= PLUS_WEIGHTS[i];
            if (r < 0) return PLUS_COLORS[i];
        }
        return ChatColor.RED;
    }

    private int rollLevel(Rank rank) {
        double median;
        switch (rank) {
            case VIP: median = 28; break;
            case VIP_PLUS: median = 42; break;
            case MVP: median = 58; break;
            case MVP_PLUS: median = 82; break;
            case MVP_PLUS_PLUS: median = 135; break;
            default: median = 11; break;
        }
        double lvl = median * Math.exp(rnd.nextGaussian() * 0.55D);
        return (int) Math.max(1, Math.min(300, Math.round(lvl)));
    }

    private int rollPing() {
        double p = 62.0D * Math.exp(rnd.nextGaussian() * 0.5D);
        return (int) Math.max(20, Math.min(180, Math.round(p)));
    }

    private BotSkill rollSkill(Rank rank) {
        double sweatChance = 0.06D + (rank == Rank.MVP_PLUS || rank == Rank.MVP_PLUS_PLUS ? 0.08D : 0.0D);
        double roll = rnd.nextDouble();
        double overall;
        if (averageSkill >= 0) {
            // configured curve: a bell around the chosen mean, with the same spread the lobby mix has for its middle
            overall = averageSkill + rnd.nextGaussian() * 0.13D;
            // a couple of better players per game keep it from being a flat line
            if (rnd.nextDouble() < 0.08D) overall += 0.12D + rnd.nextDouble() * 0.1D;
            overall = clamp(overall, 0.03D, 0.98D);
        } else {
            boolean sweat = roll < sweatChance;
            boolean beginner = !sweat && roll < sweatChance + 0.12D;
            if (sweat) overall = 0.85D + rnd.nextGaussian() * 0.06D;
            else if (beginner) overall = 0.18D + rnd.nextGaussian() * 0.07D;
            else overall = 0.50D + rnd.nextGaussian() * 0.13D;
            overall = clamp(overall, 0.03D, 0.98D);
        }
        boolean sweat = overall > 0.82D;

        double aim = clamp(overall + rnd.nextGaussian() * 0.10D, 0.02D, 0.99D);
        int reaction = (int) Math.round(clamp(330.0D - 150.0D * overall + rnd.nextGaussian() * 28.0D, 150.0D, 450.0D));
        double cps = clamp(5.5D + 6.0D * overall + rnd.nextGaussian() * 1.1D, 3.5D, 14.0D);
        if (sweat && rnd.nextDouble() < 0.4D) cps = Math.min(16.5D, cps + 2.5D + rnd.nextDouble() * 3.0D); // jitter/butterfly
        float sens = (float) clamp(0.42D - 0.08D * overall + rnd.nextGaussian() * 0.13D, 0.08D, 0.95D);

        BotSkill.BridgeStyle style;
        double s = rnd.nextDouble();
        if (overall < 0.22D) style = s < 0.8D ? BotSkill.BridgeStyle.NOOB : BotSkill.BridgeStyle.SHIFT;
        else if (overall < 0.55D) style = s < 0.85D ? BotSkill.BridgeStyle.SHIFT : BotSkill.BridgeStyle.SPEED;
        else if (overall < 0.75D) style = s < 0.35D ? BotSkill.BridgeStyle.SHIFT : (s < 0.8D ? BotSkill.BridgeStyle.SPEED : BotSkill.BridgeStyle.NINJA);
        else style = s < 0.3D ? BotSkill.BridgeStyle.SPEED : (s < 0.65D ? BotSkill.BridgeStyle.NINJA : BotSkill.BridgeStyle.BREEZILY);

        double aggression = clamp(0.45D + 0.25D * overall + rnd.nextGaussian() * 0.2D, 0.0D, 1.0D);
        double awareness = clamp(0.25D + 0.6D * overall + rnd.nextGaussian() * 0.1D, 0.0D, 1.0D);
        double chattiness = clamp(Math.pow(rnd.nextDouble(), 1.6D) * 0.9D + 0.05D, 0.0D, 1.0D);
        return new BotSkill(overall, aim, reaction, cps, sens, style, aggression, awareness, chattiness);
    }

    private static double clamp(double v, double a, double b) {
        return v < a ? a : (v > b ? b : v);
    }
}
