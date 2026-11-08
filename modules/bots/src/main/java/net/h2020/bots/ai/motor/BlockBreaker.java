package net.h2020.bots.ai.motor;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.world.Shape;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;

/**
 * Breaks a block like a player: picks the right tool (shears for wool, pickaxe for stone/end stone/clay/obsidian,
 * axe for wood), aims at the visible face, holds left mouse. With tunnel mode it digs whatever the crosshair
 * actually hits on the way (that is how bed defenses get dug through).
 */
public final class BlockBreaker {
    public enum Result { RUNNING, DONE, FAILED }

    private final BotContext ctx;
    private int tx, ty, tz;
    private boolean tunnel, active;
    private int ticks, maxTicks;
    private Location aimPoint;
    private Block current;

    public BlockBreaker(BotContext ctx) { this.ctx = ctx; }

    private boolean failed;

    public void start(int x, int y, int z, boolean tunnel) {
        // never mine the block you are standing on: the support disappears, the bot drops, and on a bridge or a
        // tower that is a void death with nothing else to blame
        org.bukkit.Location me = ctx.pos();
        if (me != null && me.getBlockX() == x && me.getBlockZ() == z
                && (me.getBlockY() - 1 == y || me.getBlockY() - 2 == y)) {
            net.h2020.api.stats.CombatStats.inc("break.selfsupport");
            active = false;
            failed = true;
            return;
        }
        tx = x; ty = y; tz = z; this.tunnel = tunnel; active = true; ticks = 0; aimPoint = null; current = null;
        maxTicks = 20 * 25;
    }

    public boolean active() { return active; }

    /** Start refused (it would have removed the bot's own footing). */
    public boolean failed() { return failed; }

    public static Inv.Pred toolFor(Material m) {
        switch (m) {
            case WOOL: case LEAVES: case LEAVES_2: case CARPET: return Inv.SHEARS;
            case WEB: return Inv.SWORD;
            case WOOD: case LOG: case LOG_2: case LADDER: case FENCE: case WORKBENCH: case BOOKSHELF: case WOOD_STEP:
            case WOOD_STAIRS: case SPRUCE_WOOD_STAIRS: case BIRCH_WOOD_STAIRS: case JUNGLE_WOOD_STAIRS:
            case ACACIA_STAIRS: case DARK_OAK_STAIRS: case CHEST: case TRAP_DOOR: case WOODEN_DOOR:
                return Inv.AXE;
            case STONE: case COBBLESTONE: case ENDER_STONE: case STAINED_CLAY: case HARD_CLAY: case OBSIDIAN:
            case SANDSTONE: case BRICK: case SMOOTH_BRICK: case IRON_ORE: case GOLD_ORE: case DIAMOND_ORE:
            case COAL_ORE: case EMERALD_ORE: case LAPIS_ORE: case REDSTONE_ORE: case GLOWING_REDSTONE_ORE:
            case IRON_BLOCK: case GOLD_BLOCK: case DIAMOND_BLOCK: case QUARTZ_BLOCK: case PRISMARINE: case NETHERRACK:
            case NETHER_BRICK: case MOSSY_COBBLESTONE: case STEP: case DOUBLE_STEP: case COBBLESTONE_STAIRS:
            case MONSTER_EGGS: case FURNACE:
                return Inv.PICKAXE;
            default:
                return null;
        }
    }

    public static void equipToolFor(BotContext ctx, Material m) {
        Inv.Pred p = toolFor(m);
        if (p != null && Inv.has(ctx.player, p)) Inv.equip(ctx.player, ctx.c, p, -1);
    }

    public Result tick() {
        if (!active) return Result.FAILED;
        if (++ticks > maxTicks) { stopDig(); active = false; return Result.FAILED; }
        byte s = ctx.world().shape(tx, ty, tz);
        if (Shape.passable(s)) { stopDig(); active = false; return Result.DONE; }
        Location eye = ctx.eye();
        if (aimPoint == null || ticks % 15 == 0) aimPoint = visiblePoint(eye);
        double d = eye.distance(aimPoint);
        if (d > 4.6) { stopDig(); active = false; return Result.FAILED; }
        ctx.motor.aimPoint(Motor.P_TASK, aimPoint, AimIntent.PRECISE);
        Block tb = ctx.c.targetedBlock();
        if (tb == null) return Result.RUNNING;
        boolean onTarget = tb.getX() == tx && tb.getY() == ty && tb.getZ() == tz;
        if (onTarget || (tunnel && tb.getType() != Material.BEDROCK && !Shape.passable(ctx.world().shape(tb.getX(), tb.getY(), tb.getZ())))) {
            if (current == null || !current.equals(tb)) {
                current = tb;
                equipToolFor(ctx, tb.getType());
            }
            ctx.motor.holdLeft(0);
        }
        return Result.RUNNING;
    }

    private Location visiblePoint(Location eye) {
        double cx = tx + 0.5, cy = ty + 0.5, cz = tz + 0.5;
        double dx = eye.getX() - cx, dy = eye.getY() - cy, dz = eye.getZ() - cz;
        // aim slightly toward the face nearest the eye, with a little human offset
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        double ox = 0, oy = 0, oz = 0;
        if (ax >= ay && ax >= az) ox = Math.signum(dx) * 0.45;
        else if (ay >= az) oy = Math.signum(dy) * 0.45;
        else oz = Math.signum(dz) * 0.45;
        double j = 0.25;
        return new Location(eye.getWorld(), cx + ox + (ox == 0 ? (ctx.rng.nextDouble() - 0.5) * j : 0),
                cy + oy + (oy == 0 ? (ctx.rng.nextDouble() - 0.5) * j : 0), cz + oz + (oz == 0 ? (ctx.rng.nextDouble() - 0.5) * j : 0));
    }

    private void stopDig() { current = null; }

    public void cancel() { active = false; current = null; }
}
