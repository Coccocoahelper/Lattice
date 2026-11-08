package net.h2020.bots.ai.world;

import org.bukkit.Material;

/**
 * Collision classification of a 1.8 block for the walkability model. Values are bytes so they can be cached cheaply.
 * Heights are the top surface relative to the block's own cell.
 */
public final class Shape {
    private Shape() {}

    /** Not loaded / outside the world: treated as an impassable wall. */
    public static final byte UNKNOWN = 0;
    /** Air and anything without collision (flowers, grass, torches, signs, buttons, rails, redstone). */
    public static final byte OPEN = 1;
    /** Full collision cube (or close enough: stairs, top slabs, double slabs, glass, leaves). */
    public static final byte FULL = 2;
    /** Solid with top below 1.0 but above 0.6 (chest 0.875, enchanting table 0.75, soul sand, farmland, portal frame). */
    public static final byte HIGH = 3;
    /** Low floor inside its own cell (bottom slab 0.5, bed 0.5625, carpet/snow layer, daylight sensor, trapdoor). */
    public static final byte LOW = 4;
    /** 1.5 tall: fences, walls, closed gates. Cannot be jumped onto or over. */
    public static final byte TALL = 5;
    /** Ladder / vine. Passable, climbable. */
    public static final byte CLIMB = 6;
    /** Water: passable, swimmable, cancels fall damage. */
    public static final byte WATER = 7;
    /** Lava / fire: never path through, high danger nearby. */
    public static final byte LAVA = 8;
    /** Cobweb: passable in theory, avoided. */
    public static final byte WEB = 9;
    /** Cactus: hurts. */
    public static final byte CACTUS = 10;
    /** Walls with thin collision: panes, iron bars, doors. Not standable. */
    public static final byte THIN = 11;

    /** Body can occupy this cell. */
    public static boolean passable(byte s) { return s == OPEN || s == CLIMB || s == WATER; }

    /** The top of this cell supports standing at y+1 (full) or slightly below (high). */
    public static boolean floor(byte s) { return s == FULL || s == HIGH || s == CACTUS; }

    public static boolean solid(byte s) {
        return s == FULL || s == HIGH || s == LOW || s == TALL || s == THIN || s == CACTUS || s == UNKNOWN;
    }

    /**
     * Generic solids whose walkability can be read from their real collision boxes. Everything with behaviour beyond
     * its shape keeps the table: liquids, ladders, webs, cactus, trapdoors (open ones are hatches) and gates.
     */
    public static boolean fromBoxes(byte s, Material m) {
        if (s != FULL && s != HIGH && s != LOW && s != THIN && s != TALL) return false;
        switch (m) {
            case TRAP_DOOR: case IRON_TRAPDOOR: case FENCE_GATE: case SPRUCE_FENCE_GATE: case BIRCH_FENCE_GATE:
            case JUNGLE_FENCE_GATE: case DARK_OAK_FENCE_GATE: case ACACIA_FENCE_GATE:
            case WOODEN_DOOR: case IRON_DOOR_BLOCK: case SPRUCE_DOOR: case BIRCH_DOOR: case JUNGLE_DOOR: case ACACIA_DOOR:
            case DARK_OAK_DOOR: case CACTUS: case BED_BLOCK:
                return false;
            default:
                return true;
        }
    }

    public static double topHeight(Material m, int data) {
        switch (m) {
            case CHEST: case TRAPPED_CHEST: case ENDER_CHEST: case SOUL_SAND: return 0.875;
            case SOIL: return 0.9375;
            case ENCHANTMENT_TABLE: return 0.75;
            case ENDER_PORTAL_FRAME: return 0.8125;
            case STEP: case WOOD_STEP: case STONE_SLAB2: return (data & 8) != 0 ? 1.0 : 0.5;
            case BED_BLOCK: return 0.5625;
            case CARPET: return 0.0625;
            case SNOW: return ((data & 7) + 1) / 8.0;
            case DAYLIGHT_DETECTOR: case DAYLIGHT_DETECTOR_INVERTED: return 0.375;
            case TRAP_DOOR: case IRON_TRAPDOOR: return (data & 4) != 0 ? 0.0 : ((data & 8) != 0 ? 1.0 : 0.1875);
            case CAKE_BLOCK: return 0.5;
            case WATER_LILY: return 0.015625;
            default: return 1.0;
        }
    }

    @SuppressWarnings("deprecation")
    public static byte classify(Material m, int data) {
        if (m == null) return UNKNOWN;
        switch (m) {
            case AIR: case LONG_GRASS: case DEAD_BUSH: case YELLOW_FLOWER: case RED_ROSE: case BROWN_MUSHROOM:
            case RED_MUSHROOM: case TORCH: case REDSTONE_TORCH_ON: case REDSTONE_TORCH_OFF: case REDSTONE_WIRE:
            case SIGN_POST: case WALL_SIGN: case STONE_BUTTON: case WOOD_BUTTON: case LEVER: case RAILS:
            case POWERED_RAIL: case DETECTOR_RAIL: case ACTIVATOR_RAIL: case SAPLING: case CROPS: case CARROT:
            case POTATO: case SUGAR_CANE_BLOCK: case DOUBLE_PLANT: case NETHER_WARTS: case TRIPWIRE:
            case TRIPWIRE_HOOK: case STONE_PLATE: case WOOD_PLATE: case GOLD_PLATE: case IRON_PLATE:
            case STANDING_BANNER: case WALL_BANNER: case PORTAL: case ENDER_PORTAL: case PUMPKIN_STEM:
            case MELON_STEM: case DIODE_BLOCK_OFF: case DIODE_BLOCK_ON: case REDSTONE_COMPARATOR_OFF:
            case REDSTONE_COMPARATOR_ON:
                return OPEN;
            case WATER: case STATIONARY_WATER: return WATER;
            case LAVA: case STATIONARY_LAVA: case FIRE: return LAVA;
            case LADDER: case VINE: return CLIMB;
            case WEB: return WEB;
            case CACTUS: return CACTUS;
            case FENCE: case NETHER_FENCE: case COBBLE_WALL: case SPRUCE_FENCE: case BIRCH_FENCE: case JUNGLE_FENCE:
            case DARK_OAK_FENCE: case ACACIA_FENCE:
                return TALL;
            case FENCE_GATE: case SPRUCE_FENCE_GATE: case BIRCH_FENCE_GATE: case JUNGLE_FENCE_GATE:
            case DARK_OAK_FENCE_GATE: case ACACIA_FENCE_GATE:
                return (data & 4) != 0 ? OPEN : TALL;
            case THIN_GLASS: case STAINED_GLASS_PANE: case IRON_FENCE: case WOODEN_DOOR: case IRON_DOOR_BLOCK:
            case SPRUCE_DOOR: case BIRCH_DOOR: case JUNGLE_DOOR: case ACACIA_DOOR: case DARK_OAK_DOOR:
            case BREWING_STAND: case FLOWER_POT: case SKULL: case ANVIL: case HOPPER: case CAULDRON:
                return THIN;
            case STEP: case WOOD_STEP: case STONE_SLAB2:
                return (data & 8) != 0 ? FULL : LOW;
            case BED_BLOCK: case CARPET: case SNOW: case DAYLIGHT_DETECTOR: case DAYLIGHT_DETECTOR_INVERTED:
            case CAKE_BLOCK: case WATER_LILY:
                return LOW;
            case TRAP_DOOR: case IRON_TRAPDOOR:
                if ((data & 4) != 0) return OPEN;
                return (data & 8) != 0 ? FULL : LOW;
            case CHEST: case TRAPPED_CHEST: case ENDER_CHEST: case SOUL_SAND: case SOIL: case ENCHANTMENT_TABLE:
            case ENDER_PORTAL_FRAME:
                return HIGH;
            default:
                return m.isSolid() ? FULL : OPEN;
        }
    }
}
