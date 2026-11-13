package net.h2020.practice;

import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameMode;
import org.bukkit.Material;

/** One practice drill as a mode, so it shows up wherever modes are listed and can be created like any other game. */
public final class PracticeMode implements GameMode {

    private final String id;
    private final String display;
    private final String subtitle;
    private final String description;
    private final Material icon;

    private PracticeMode(String id, String display, String subtitle, String description, Material icon) {
        this.id = id;
        this.display = display;
        this.subtitle = subtitle;
        this.description = description;
        this.icon = icon;
    }

    public static final PracticeMode FIREBALL = new PracticeMode("practice_fireball",
            "Fireball Deflecting", "Deflect",
            "Incoming fireballs, a sword, and nothing else. Knock them back before they reach you.",
            Material.FIREBALL);
    public static final PracticeMode BOW = new PracticeMode("practice_bow",
            "Bow Aiming", "Aim",
            "Four targets from 10 to 34 blocks. Scores every shot, not just the hits.",
            Material.BOW);
    public static final PracticeMode SENSE = new PracticeMode("practice_gamesense",
            "Game Sense", "Decisions",
            "Bed trades, 2v1s, height fights and resource calls, graded with the reason.",
            Material.BOOK);
    public static final PracticeMode BRIDGE = new PracticeMode("practice_bridge",
            "Bridging", "Straight",
            "A gap that grows every crossing, timed, with the blocks you used counted.",
            Material.SANDSTONE);
    public static final PracticeMode BRIDGE_DIAGONAL = new PracticeMode("practice_bridge_diagonal",
            "Bridging", "Diagonal",
            "The same gap on the diagonal, where the stair-step is what keeps you on the line.",
            Material.SANDSTONE);

    public static final PracticeMode[] ALL = {FIREBALL, BOW, SENSE, BRIDGE, BRIDGE_DIAGONAL};

    @Override public String id() { return id; }
    @Override public GameFamily family() { return GameFamily.PRACTICE; }
    @Override public String displayName() { return display; }
    @Override public int teamSize() { return 1; }
    @Override public int teams() { return 1; }
    @Override public Material icon() { return icon; }
    @Override public String menuSubtitle() { return subtitle; }
    @Override public String description() { return description; }
    @Override public int menuOrder() { return 80 + ordinalOffset(); }

    private int ordinalOffset() {
        for (int i = 0; i < ALL.length; i++) if (ALL[i] == this) return i;
        return 0;
    }
}
