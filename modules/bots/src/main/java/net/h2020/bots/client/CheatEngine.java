package net.h2020.bots.client;

import net.h2020.api.H2020;
import net.h2020.api.bot.CheatProfile;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameState;
import net.h2020.api.game.GameTeam;
import net.h2020.bots.input.HumanMouse;
import net.h2020.bots.net.PacketCodec;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.EntityHuman;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.EnumDirection;
import net.minecraft.server.v1_8_R3.ItemBlock;
import net.minecraft.server.v1_8_R3.ItemStack;
import net.minecraft.server.v1_8_R3.Material;
import net.minecraft.server.v1_8_R3.MathHelper;

import java.util.List;
import java.util.Random;

/**
 * "Hacked client" modules of a 2020 cheater, implemented inside the virtual client by changing what the client does
 * and sends - exactly how real cheats work. Watchdog has no access to this class or the profile flag.
 *
 *  REACH      - client entity pick distance 3.6..4.2 instead of 3.0 (aim and clicks stay human)
 *  KILLAURA   - while fighting: silent exact rotations (no GCD) to every enemy within 4.2 incl. behind, switch
 *               between up to three targets at ~11-15 CPS, attack packet sent before the rotation packet
 *  AUTOCLICKER- see ClickScheduler (18-25 CPS metronome)
 *  FLY        - vanilla creative-style flight in survival once jump is pressed mid-air; slow glide when idle
 *  SPEED      - bhop (ClientPhysics.speedHack)
 *  NOFALL     - spoofs onGround=true while falling
 *  SCAFFOLD   - places blocks under/ahead of the feet every tick with snapped silent rotations, auto block switch
 *  FASTPLACE  - no 4-tick right click delay
 *  VELOCITY   - ignores knockback (S12 / explosion motion)
 */
public final class CheatEngine {
    private static final EnumDirection[] SIDES = { EnumDirection.NORTH, EnumDirection.SOUTH, EnumDirection.WEST, EnumDirection.EAST };

    private final VirtualClient c;
    private final Random rnd;
    private CheatProfile profile = CheatProfile.NONE;

    private double reach = 3.0D;
    boolean hackFlying;
    private double lastJumpHeld;

    private double nextAura;
    private int auraIndex;
    private final EntityPlayer[] cands = new EntityPlayer[8];
    private final double[] candDist = new double[8];

    private BlockPosition lastScaffold;
    private double lastScaffoldTime;

    CheatEngine(VirtualClient client, Random rnd) {
        this.c = client;
        this.rnd = rnd;
    }

    void onProfileChanged(CheatProfile p) {
        profile = p == null ? CheatProfile.NONE : p;
        reach = 3.6D + rnd.nextDouble() * 0.6D;
        hackFlying = false;
    }

    public CheatProfile profile() { return profile; }

    double entityReach() { return profile == CheatProfile.REACH ? reach : 3.0D; }

    boolean ignoresVelocity() { return profile == CheatProfile.VELOCITY; }

    boolean fastPlace() { return profile == CheatProfile.FASTPLACE || profile == CheatProfile.SCAFFOLD; }

    boolean groundFlag(boolean real) {
        if (profile == CheatProfile.NOFALL && !real && c.phys.motionY < 0.0D && c.phys.fallDistance > 1.0F && !c.phys.flying) {
            return true;
        }
        return real;
    }

    /** FLY: engage flight when jump is pressed in the air; glide down slowly when jump has not been held for a while. */
    void updateFly() {
        if (profile != CheatProfile.FLY) { hackFlying = false; return; }
        if (c.inJump) lastJumpHeld = c.clientTime;
        if (!c.phys.onGround && c.inJump && !c.dead && !c.phys.inWater) hackFlying = true;
        if (hackFlying && c.clientTime - lastJumpHeld > 1500.0D && !c.inSneak) {
            c.phys.motionY -= 0.035D;
        }
    }

    /** Runs after the human input of the tick, before movement. */
    void preMovement() {
        if (c.dead || c.gamemode == 3) return;
        switch (profile) {
            case KILLAURA: killAura(); break;
            case SCAFFOLD: scaffold(); break;
            default: break;
        }
    }

    // ------------------------------------------------------------------ kill aura
    private void killAura() {
        if (c.clientTime > c.combatUntil || c.clientTime < nextAura) return;
        Game game = null;
        GameTeam ownTeam = null;
        GameManager gm = H2020.find(GameManager.class);
        if (gm != null) {
            try {
                game = gm.gameOf(c.player);
                if (game != null) {
                    if (game.state() != GameState.PLAYING) return;
                    ownTeam = game.teamOf(c.player);
                }
            } catch (Throwable ignored) {}
        }
        ClientPhysics p = c.phys;
        double ex = p.posX, ey = p.posY + (double) p.eyeHeight(), ez = p.posZ;
        int n = 0;
        List<EntityHuman> players = p.world.players;
        for (int i = 0, size = players.size(); i < size && n < cands.length; i++) {
            EntityHuman h = players.get(i);
            if (h == c.ep || h.dead || !(h instanceof EntityPlayer) || h.getHealth() <= 0.0F) continue;
            EntityPlayer o = (EntityPlayer) h;
            if (o.isSpectator() || o.playerInteractManager.isCreative()) continue;
            double d = distanceToBox(o, ex, ey, ez);
            if (d > 4.2D) continue;
            if (game != null) {
                try {
                    if (game.isSpectator(o.getBukkitEntity())) continue;
                    GameTeam t = game.teamOf(o.getBukkitEntity());
                    if (ownTeam != null && t == ownTeam) continue;
                } catch (Throwable ignored) {}
            }
            // insertion sort by distance
            int j = n++;
            while (j > 0 && candDist[j - 1] > d) { cands[j] = cands[j - 1]; candDist[j] = candDist[j - 1]; j--; }
            cands[j] = o;
            candDist[j] = d;
        }
        if (n == 0) return;
        EntityPlayer target = cands[(auraIndex++) % Math.min(3, n)];
        double tx = target.locX, ty = target.locY + (double) target.length * 0.75D, tz = target.locZ;
        double yaw = HumanMouse.yawTo(ex, ez, tx, tz);
        double pitch = HumanMouse.pitchTo(ex, ey, ez, tx, ty, tz);
        c.rotOverride = true;
        c.rotOverrideYaw = (float) (c.mouse.yaw + HumanMouse.wrap(yaw - c.mouse.yaw));
        c.rotOverridePitch = (float) Math.max(-90.0D, Math.min(90.0D, pitch));
        c.swingItem();
        c.attackEntity(target);
        for (int i = 0; i < n; i++) cands[i] = null;
        nextAura = c.clientTime + 1000.0D / (11.0D + rnd.nextDouble() * 4.0D);
    }

    private static double distanceToBox(EntityPlayer o, double x, double y, double z) {
        double hw = (double) o.width / 2.0D;
        double cx = clamp(x, o.locX - hw, o.locX + hw);
        double cy = clamp(y, o.locY, o.locY + (double) o.length);
        double cz = clamp(z, o.locZ - hw, o.locZ + hw);
        double dx = x - cx, dy = y - cy, dz = z - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double clamp(double v, double a, double b) {
        return v < a ? a : (v > b ? b : v);
    }

    // ------------------------------------------------------------------ scaffold
    private void scaffold() {
        if (c.inForward == 0.0F && c.inStrafe == 0.0F && !c.inJump) return;
        ItemStack[] items = c.ep.inventory.items;
        ItemStack held = c.heldStack();
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            int slot = -1;
            for (int i = 0; i < 9; i++) {
                if (items[i] != null && items[i].getItem() instanceof ItemBlock && items[i].count > 0) { slot = i; break; }
            }
            if (slot < 0) return;
            c.currentItem = slot;
            c.syncCurrentPlayItem();
            held = items[slot];
        }
        ClientPhysics p = c.phys;
        int tx = MathHelper.floor(p.posX + p.motionX);
        int ty = MathHelper.floor(p.posY) - 1;
        int tz = MathHelper.floor(p.posZ + p.motionZ);
        BlockPosition target = new BlockPosition(tx, ty, tz);
        if (!free(target)) return;
        if (target.equals(lastScaffold) && c.clientTime - lastScaffoldTime < c.pingMs() + 150.0D) return;

        BlockPosition against = null;
        EnumDirection face = null;
        BlockPosition below = target.down();
        if (solid(below)) { against = below; face = EnumDirection.UP; }
        else {
            for (EnumDirection d : SIDES) {
                BlockPosition nb = target.shift(d);
                if (solid(nb)) { against = nb; face = d.opposite(); break; }
            }
        }
        if (against == null) {
            // diagonal step: no direct support, first fill a neighbouring block that has one
            outer:
            for (EnumDirection d : SIDES) {
                BlockPosition mid = target.shift(d);
                if (!free(mid)) continue;
                if (solid(mid.down())) { target = mid; against = mid.down(); face = EnumDirection.UP; break; }
                for (EnumDirection d2 : SIDES) {
                    BlockPosition nb = mid.shift(d2);
                    if (!nb.equals(target) && solid(nb)) { target = mid; against = nb; face = d2.opposite(); break outer; }
                }
            }
        }
        if (against == null) return;
        ItemBlock ib = (ItemBlock) held.getItem();
        if (!c.canPlaceBlockOnSide(ib, against, face, held)) return;

        double hx = against.getX() + 0.5D + face.getAdjacentX() * 0.5D;
        double hy = against.getY() + 0.5D + face.getAdjacentY() * 0.5D;
        double hz = against.getZ() + 0.5D + face.getAdjacentZ() * 0.5D;
        double ex = p.posX, ey = p.posY + (double) p.eyeHeight(), ez = p.posZ;
        double yaw = HumanMouse.yawTo(ex, ez, hx, hz);
        double pitch = HumanMouse.pitchTo(ex, ey, ez, hx, hy, hz);
        c.rotOverride = true;
        c.rotOverrideYaw = (float) (c.mouse.yaw + HumanMouse.wrap(yaw - c.mouse.yaw));
        c.rotOverridePitch = (float) Math.max(-90.0D, Math.min(90.0D, pitch));

        c.send(PacketCodec.place(against, face.a(), held, (float) (hx - against.getX()), (float) (hy - against.getY()),
                (float) (hz - against.getZ())));
        c.swingItem();
        c.predictPlacement(ib, against, face);
        lastScaffold = target;
        lastScaffoldTime = c.clientTime;
    }

    private boolean solid(BlockPosition pos) {
        if (c.phys.isPredicted(pos.getX(), pos.getY(), pos.getZ())) return true;
        Material m = c.phys.world.getType(pos).getBlock().getMaterial();
        return m.isSolid() && !m.isReplaceable();
    }

    private boolean free(BlockPosition pos) {
        return !c.phys.isPredicted(pos.getX(), pos.getY(), pos.getZ())
                && c.phys.world.getType(pos).getBlock().getMaterial().isReplaceable();
    }
}
