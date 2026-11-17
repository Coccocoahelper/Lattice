package net.h2020.duels;

import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameMode;
import org.bukkit.Material;

/**
 * One duels mode. Everything that makes a mode different lives here: the kit it hands out, how a round is won, and
 * whether the border closes in. The game reads these fields instead of switching on the mode, so a new mode is one
 * entry in {@link #ALL} and one kit, not a new branch in the round logic.
 *
 * <p>The kits are the ones Hypixel ran in 2018-2020 (forums and the wiki's mode pages), not approximations: a Classic
 * duel is full iron, an iron sword, a bow, a rod and five arrows, and a NoDebuff duel is Prot II diamond with 28
 * splash heals.
 */
public final class DuelsMode implements GameMode {

    /** How a round is decided. */
    public enum Win {
        /** Kill the opponent. */
        KILL,
        /** Knock them off the platform; falling is the only loss. */
        KNOCKBACK,
        /** First to the hit target, or `mercy` hits ahead. No damage is dealt at all. */
        HITS,
        /** Like KILL, but the border closes in on both players after `borderAt` seconds. */
        BORDER
    }

    private final String id;
    private final String display;
    private final String subtitle;
    private final String description;
    private final Material icon;
    private final short iconData;
    private final int order;
    private final Win win;
    /** HITS: hits needed to win. */
    public final int hitTarget;
    /** HITS: a lead this large also wins ("mercy"). */
    public final int mercy;
    /** Seconds before the border starts closing (BORDER modes), 0 for none. */
    public final int borderAt;
    /** Total round length in seconds; a draw when nobody has won by then. */
    public final int roundSeconds;
    /** Rounds needed to take the duel. Hypixel's duels are one round. */
    public final int rounds;
    /** No natural regeneration (UHC rules). */
    public final boolean uhc;
    /** Melee damage is disabled (Bow duels). */
    public final boolean rangedOnly;
    /** A sumo platform instead of a walled arena. */
    public final boolean sumo;

    private DuelsMode(Builder b) {
        this.id = b.id;
        this.display = b.display;
        this.subtitle = b.subtitle;
        this.description = b.description;
        this.icon = b.icon;
        this.iconData = b.iconData;
        this.order = b.order;
        this.win = b.win;
        this.hitTarget = b.hitTarget;
        this.mercy = b.mercy;
        this.borderAt = b.borderAt;
        this.roundSeconds = b.roundSeconds;
        this.rounds = b.rounds;
        this.uhc = b.uhc;
        this.rangedOnly = b.rangedOnly;
        this.sumo = b.sumo;
    }

    private static final class Builder {
        String id, display, subtitle, description;
        Material icon = Material.IRON_SWORD;
        short iconData;
        int order = 50;
        Win win = Win.KILL;
        int hitTarget, mercy, borderAt, roundSeconds = 480, rounds = 1;
        boolean uhc, rangedOnly, sumo;

        Builder(String id, String display, String subtitle, String description, Material icon) {
            this.id = id; this.display = display; this.subtitle = subtitle;
            this.description = description; this.icon = icon;
        }
        Builder order(int v) { order = v; return this; }
        Builder win(Win v) { win = v; return this; }
        Builder hits(int target, int mercyLead) { hitTarget = target; mercy = mercyLead; return this; }
        Builder border(int at) { borderAt = at; return this; }
        Builder seconds(int v) { roundSeconds = v; return this; }
        Builder rounds(int v) { rounds = v; return this; }
        Builder uhc() { uhc = true; return this; }
        Builder rangedOnly() { rangedOnly = true; return this; }
        Builder sumo() { sumo = true; return this; }
        DuelsMode build() { return new DuelsMode(this); }
    }

    public static final DuelsMode CLASSIC = new Builder("duels_classic", "Classic", "Duel",
            "Iron armour, an iron sword, a bow, a rod and five arrows. The oldest duel there is.", Material.FISHING_ROD)
            .order(1).border(120).seconds(480).build();

    public static final DuelsMode SUMO = new Builder("duels_sumo", "Sumo", "Duel",
            "No items, no damage. Punch your opponent off the platform.", Material.SLIME_BALL)
            .order(2).win(Win.KNOCKBACK).sumo().seconds(120).build();

    public static final DuelsMode BOXING = new Builder("duels_boxing", "Boxing", "Duel",
            "100 hits, or 50 ahead for a mercy win. Nobody dies.", Material.RAW_FISH)
            .order(3).win(Win.HITS).hits(100, 50).seconds(600).build();

    public static final DuelsMode NODEBUFF = new Builder("duels_nodebuff", "NoDebuff", "Duel",
            "Prot II diamond, Fire Aspect, 28 splash heals and pearls. The pot PvP duel.", Material.POTION)
            .order(4).win(Win.BORDER).border(120).seconds(900).build();

    public static final DuelsMode COMBO = new Builder("duels_combo", "Combo", "Duel",
            "Prot V diamond, Sharpness II, notch apples, Strength and Speed, pearls. No border - it ends in a combo.",
            Material.GOLDEN_APPLE)
            .order(5).seconds(900).build();

    public static final DuelsMode UHC = new Builder("duels_uhc", "UHC", "Duel",
            "Diamond, Sharpness III, rods, lava and water, golden heads. No natural regeneration.", Material.GOLDEN_APPLE)
            .order(6).uhc().seconds(480).build();

    public static final DuelsMode OP = new Builder("duels_op", "OP", "Duel",
            "Prot III diamond, Sharpness V, Power IV, gapples and regen. Everything at once.", Material.DIAMOND_SWORD)
            .order(7).seconds(480).build();

    public static final DuelsMode BOW = new Builder("duels_bow", "Bow", "Duel",
            "Chainmail, Punch I Infinity bow, one pearl. Melee does nothing.", Material.BOW)
            .order(8).rangedOnly().seconds(480).build();

    public static final DuelsMode[] ALL = {CLASSIC, SUMO, BOXING, NODEBUFF, COMBO, UHC, OP, BOW};

    public static DuelsMode byId(String id) {
        for (DuelsMode m : ALL) if (m.id.equalsIgnoreCase(id) || m.id.equalsIgnoreCase("duels_" + id)) return m;
        return null;
    }

    /** The mode a menu click or a command named, by id or by its display name ("NoDebuff" -> NODEBUFF). */
    public static DuelsMode byName(String s) {
        if (s == null) return null;
        DuelsMode m = byId(s);
        if (m != null) return m;
        String want = s.replace("_", "").replace(" ", "").toLowerCase(java.util.Locale.ROOT);
        for (DuelsMode x : ALL)
            if (x.display.replace(" ", "").toLowerCase(java.util.Locale.ROOT).equals(want)) return x;
        return null;
    }

    // ---- GameMode ----
    @Override public String id() { return id; }
    @Override public GameFamily family() { return GameFamily.DUELS; }
    @Override public String displayName() { return display; }
    @Override public int teamSize() { return 1; }
    @Override public int teams() { return 2; }
    @Override public int maxPlayers() { return 2; }
    @Override public Material icon() { return icon; }
    @Override public short iconData() { return iconData; }
    @Override public String menuSubtitle() { return subtitle; }
    @Override public String description() { return description; }
    @Override public int menuOrder() { return order; }

    public Win win() { return win; }
}
