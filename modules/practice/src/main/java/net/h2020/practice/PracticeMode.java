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

    public static final PracticeMode BED_DEFENCE = new PracticeMode("practice_beddefence",
            "Bed Defence", "Build",
            "You get the blocks and a model of the defence on your left. Build it as fast as you can.",
            Material.BED);

    public static final PracticeMode BLOCK_IN = new PracticeMode("practice_blockin",
            "Block In", "Seal",
            "Break into the defence, then seal the hole you made. The first hole a player closes becomes the drill.",
            Material.WOOL);

    public static final PracticeMode FIREBALL_JUMP = new PracticeMode("practice_fireballjump",
            "Fireball Jumping", "Movement",
            "A gap, a fireball and your own feet. Ride the blast across before it rides you off the map.",
            Material.FIREBALL);

    public static final PracticeMode BLOCK_CLUTCH = new PracticeMode("practice_blockclutch",
            "Block Clutching", "Survival",
            "Get knocked off the platform and save yourself: place blocks under your feet mid-fall and land on one. "
                    + "A hit that would have killed you counts the same as a save over the void.",
            Material.WOOL);

    public static final PracticeMode[] ALL = {FIREBALL, FIREBALL_JUMP, BOW, SENSE, BRIDGE, BRIDGE_DIAGONAL,
            BED_DEFENCE, BLOCK_IN, BLOCK_CLUTCH};

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
