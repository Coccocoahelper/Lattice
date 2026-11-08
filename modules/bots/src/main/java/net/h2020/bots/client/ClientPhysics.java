package net.h2020.bots.client;

import net.minecraft.server.v1_8_R3.AxisAlignedBB;
import net.minecraft.server.v1_8_R3.Block;
import net.minecraft.server.v1_8_R3.BlockFluids;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.Blocks;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.IBlockData;
import net.minecraft.server.v1_8_R3.Material;
import net.minecraft.server.v1_8_R3.MathHelper;
import net.minecraft.server.v1_8_R3.Vec3D;
import net.minecraft.server.v1_8_R3.World;

import java.util.List;

/**
 * Port of the vanilla 1.8.9 client movement code (Entity.moveEntity, EntityLivingBase.onLivingUpdate /
 * moveEntityWithHeading / jump, EntityPlayer flying + jumpMovementFactor, EntityPlayerSP.pushOutOfBlocks, water /
 * lava / ladder / web / soul sand / slime / ice handling). It works on its own simulated position and box; the real
 * server entity is only passed to World.getCubes as the "entity" argument (to exclude itself and for world border).
 *
 * Field and method names follow MCP so the port can be compared line by line with the decompiled client.
 */
public final class ClientPhysics {
    public static final float WIDTH = 0.6F;
    public static final float HEIGHT = 1.8F;
    private static final double HALF_WIDTH = (double) (WIDTH / 2.0F);
    private static final double STEP_HEIGHT = (double) 0.6F;
    private static final float SPEED_IN_AIR = 0.02F;

    private final EntityPlayer entity;
    public World world;

    // ---- position / motion ----
    public double posX, posY, posZ;
    public double motionX, motionY, motionZ;
    public float rotationYaw, rotationPitch;
    final Box bb = new Box();

    // ---- flags ----
    public boolean onGround;
    public boolean isCollidedHorizontally, isCollidedVertically;
    public boolean isInWeb;
    public boolean inWater;
    public boolean noClip;
    public float fallDistance;

    // ---- living ----
    int jumpTicks;
    float jumpMovementFactor = SPEED_IN_AIR;
    float moveStrafing, moveForward;
    boolean isJumping;

    // ---- player state (set by the virtual client) ----
    public boolean sprinting, sneaking;
    public boolean flying, allowFlying, creative, spectator;
    public float flySpeed = 0.05F, walkSpeed = 0.1F;
    public int speedAmp = -1, slowAmp = -1, jumpAmp = -1;
    /** Bhop cheat: auto-jump and boosted horizontal speed. */
    public boolean speedHack;

    // ---- scratch (no per-tick allocation in the hot path except World.getCubes results) ----
    private final Box saved = new Box(), stepA = new Box(), stepB = new Box(), stretched = new Box(), beforeStep = new Box();
    private final BlockPosition.MutableBlockPosition mpos = new BlockPosition.MutableBlockPosition();
    private static final Vec3D ZERO = new Vec3D(0.0D, 0.0D, 0.0D);

    public ClientPhysics(EntityPlayer entity) {
        this.entity = entity;
        this.world = entity.world;
    }

    // ------------------------------------------------------------------ position helpers
    public void setPosition(double x, double y, double z) {
        posX = x; posY = y; posZ = z;
        bb.set(x - HALF_WIDTH, y, z - HALF_WIDTH, x + HALF_WIDTH, y + (double) HEIGHT, z + HALF_WIDTH);
    }

    private void resetPositionToBB() {
        posX = (bb.minX + bb.maxX) / 2.0D;
        posY = bb.minY;
        posZ = (bb.minZ + bb.maxZ) / 2.0D;
    }

    public double minY() { return bb.minY; }

    public float eyeHeight() {
        float f = 1.62F;
        if (sneaking) f -= 0.08F;
        return f;
    }

    public void resetMotion() {
        motionX = motionY = motionZ = 0.0D;
        fallDistance = 0.0F;
        jumpTicks = 0;
        isInWeb = false;
    }

    // ------------------------------------------------------------------ client-side block prediction
    // A vanilla client places a block in its own world the moment it right-clicks (ItemBlock.onItemUse), long
    // before the server confirms it. We keep those predicted full cubes for collision until the server world shows
    // the block or the prediction times out (server rejected it -> the client would get a block change back).
    private static final int MAX_PRED = 8;
    private final int[] predX = new int[MAX_PRED], predY = new int[MAX_PRED], predZ = new int[MAX_PRED];
    private final double[] predUntil = new double[MAX_PRED];
    private int predCount;
    /** Client clock in ms, set by the virtual client every tick. */
    public double nowMs;

    public void predictBlock(int x, int y, int z, double lifetimeMs) {
        if (predCount == MAX_PRED) removePred(0);
        predX[predCount] = x; predY[predCount] = y; predZ[predCount] = z;
        predUntil[predCount] = nowMs + lifetimeMs;
        predCount++;
    }

    private void removePred(int i) {
        for (int j = i + 1; j < predCount; j++) {
            predX[j - 1] = predX[j]; predY[j - 1] = predY[j]; predZ[j - 1] = predZ[j]; predUntil[j - 1] = predUntil[j];
        }
        predCount--;
    }

    public boolean isPredicted(int x, int y, int z) {
        for (int i = 0; i < predCount; i++) if (predX[i] == x && predY[i] == y && predZ[i] == z) return true;
        return false;
    }

    // ------------------------------------------------------------------ world queries
    List<AxisAlignedBB> cubes(AxisAlignedBB area) {
        List<AxisAlignedBB> list = world.getCubes(entity, area);
        if (predCount > 0) {
            for (int i = predCount - 1; i >= 0; i--) {
                if (predUntil[i] < nowMs || !block(predX[i], predY[i], predZ[i]).getMaterial().isReplaceable()) {
                    removePred(i); // expired, or the real block is there now
                    continue;
                }
                int x = predX[i], y = predY[i], z = predZ[i];
                if (area.d > x && area.a < x + 1 && area.e > y && area.b < y + 1 && area.f > z && area.c < z + 1) {
                    list.add(new AxisAlignedBB(x, y, z, x + 1, y + 1, z + 1));
                }
            }
        }
        return list;
    }

    private IBlockData state(int x, int y, int z) {
        return world.getType(mpos.c(x, y, z));
    }

    private Block block(int x, int y, int z) {
        return state(x, y, z).getBlock();
    }

    private boolean loaded(int x, int z) {
        return world.isLoaded(mpos.c(x, 64, z));
    }

    public boolean isChunkLoadedHere() {
        return loaded(MathHelper.floor(posX), MathHelper.floor(posZ));
    }

    private boolean areaLoaded(int x0, int z0, int x1, int z1) {
        return loaded(x0, z0) && loaded(x1, z1) && loaded(x0, z1) && loaded(x1, z0);
    }

    // ------------------------------------------------------------------ Entity.onEntityUpdate part
    /** Entity.onEntityUpdate: water state and lava fall-distance halving. Called at the start of the tick. */
    public void entityUpdate() {
        noClip = spectator;
        if (spectator) onGround = false;
        handleWaterMovement();
        if (isInLava()) fallDistance *= 0.5F;
    }

    /** Entity.handleWaterMovement */
    public boolean handleWaterMovement() {
        double a = bb.minX + 0.001D, b = bb.minY + 0.4000000059604645D + 0.001D, c = bb.minZ + 0.001D;
        double d = bb.maxX - 0.001D, e = bb.maxY - 0.4000000059604645D - 0.001D, f = bb.maxZ - 0.001D;
        if (handleMaterialAcceleration(a, b, c, d, e, f)) {
            fallDistance = 0.0F;
            inWater = true;
        } else {
            inWater = false;
        }
        return inWater;
    }

    /** World.handleMaterialAcceleration for water, applying the flow push to the simulated motion. */
    private boolean handleMaterialAcceleration(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        int i = MathHelper.floor(minX);
        int j = MathHelper.floor(maxX + 1.0D);
        int k = MathHelper.floor(minY);
        int l = MathHelper.floor(maxY + 1.0D);
        int i1 = MathHelper.floor(minZ);
        int j1 = MathHelper.floor(maxZ + 1.0D);
        if (!areaLoaded(i, i1, j, j1)) return false;
        boolean flag = false;
        double vx = 0.0D, vy = 0.0D, vz = 0.0D;
        for (int x = i; x < j; ++x) {
            for (int y = k; y < l; ++y) {
                for (int z = i1; z < j1; ++z) {
                    IBlockData st = state(x, y, z);
                    Block blk = st.getBlock();
                    if (blk.getMaterial() == Material.WATER) {
                        double d0 = (double) ((float) (y + 1) - BlockFluids.b(((Integer) st.get(BlockFluids.LEVEL)).intValue()));
                        if ((double) l >= d0) {
                            flag = true;
                            Vec3D flow = blk.a(world, new BlockPosition(x, y, z), entity, ZERO);
                            vx += flow.a; vy += flow.b; vz += flow.c;
                        }
                    }
                }
            }
        }
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len > 0.0D && !flying) {
            if (len < 1.0E-4D) { vx = vy = vz = 0.0D; }
            else { vx /= len; vy /= len; vz /= len; }
            double d1 = 0.014D;
            motionX += vx * d1;
            motionY += vy * d1;
            motionZ += vz * d1;
        }
        return flag;
    }

    /** Entity.isInLava: material check on bb.expand(-0.1, -0.4, -0.1). */
    public boolean isInLava() {
        return isMaterialInBB(bb.minX + 0.10000000149011612D, bb.minY + 0.4000000059604645D, bb.minZ + 0.10000000149011612D,
                bb.maxX - 0.10000000149011612D, bb.maxY - 0.4000000059604645D, bb.maxZ - 0.10000000149011612D, Material.LAVA);
    }

    private boolean isMaterialInBB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, Material m) {
        int i = MathHelper.floor(minX), j = MathHelper.floor(maxX + 1.0D);
        int k = MathHelper.floor(minY), l = MathHelper.floor(maxY + 1.0D);
        int i1 = MathHelper.floor(minZ), j1 = MathHelper.floor(maxZ + 1.0D);
        for (int x = i; x < j; ++x)
            for (int y = k; y < l; ++y)
                for (int z = i1; z < j1; ++z)
                    if (block(x, y, z).getMaterial() == m) return true;
        return false;
    }

    /** EntityLivingBase.isOnLadder */
    public boolean isOnLadder() {
        if (spectator) return false;
        Block b = block(MathHelper.floor(posX), MathHelper.floor(bb.minY), MathHelper.floor(posZ));
        return b == Blocks.LADDER || b == Blocks.VINE;
    }

    // ------------------------------------------------------------------ EntityPlayerSP.pushOutOfBlocks
    public void pushOutOfBlocks() {
        if (noClip) return;
        double w = (double) WIDTH * 0.35D;
        double y = bb.minY + 0.5D;
        pushOutOfBlocks(posX - w, y, posZ + w);
        pushOutOfBlocks(posX - w, y, posZ - w);
        pushOutOfBlocks(posX + w, y, posZ - w);
        pushOutOfBlocks(posX + w, y, posZ + w);
    }

    private void pushOutOfBlocks(double x, double y, double z) {
        int bx = MathHelper.floor(x), by = MathHelper.floor(y), bz = MathHelper.floor(z);
        double d0 = x - (double) bx;
        double d1 = z - (double) bz;
        if (!isOpenBlockSpace(bx, by, bz)) {
            int i = -1;
            double d2 = 9999.0D;
            if (isOpenBlockSpace(bx - 1, by, bz) && d0 < d2) { d2 = d0; i = 0; }
            if (isOpenBlockSpace(bx + 1, by, bz) && 1.0D - d0 < d2) { d2 = 1.0D - d0; i = 1; }
            if (isOpenBlockSpace(bx, by, bz - 1) && d1 < d2) { d2 = d1; i = 4; }
            if (isOpenBlockSpace(bx, by, bz + 1) && 1.0D - d1 < d2) { i = 5; }
            float f = 0.1F;
            if (i == 0) motionX = (double) (-f);
            if (i == 1) motionX = (double) f;
            if (i == 4) motionZ = (double) (-f);
            if (i == 5) motionZ = (double) f;
        }
    }

    private boolean isOpenBlockSpace(int x, int y, int z) {
        return !block(x, y, z).isOccluding() && !block(x, y + 1, z).isOccluding();
    }

    // ------------------------------------------------------------------ EntityLivingBase.onLivingUpdate (+ EntityPlayer tail)
    /**
     * @param strafe/forward movement input (already sneak/use-item scaled)
     * @param movementBlocked dead / sleeping
     */
    public void livingUpdate(float strafe, float forward, boolean jumping, boolean movementBlocked) {
        if (jumpTicks > 0) --jumpTicks;
        if (Math.abs(motionX) < 0.005D) motionX = 0.0D;
        if (Math.abs(motionY) < 0.005D) motionY = 0.0D;
        if (Math.abs(motionZ) < 0.005D) motionZ = 0.0D;

        if (movementBlocked) {
            isJumping = false;
            moveStrafing = 0.0F;
            moveForward = 0.0F;
        } else {
            moveStrafing = strafe;
            moveForward = forward;
            isJumping = jumping;
        }

        if (speedHack && !movementBlocked && onGround && !inWater && !flying && (forward != 0.0F || strafe != 0.0F)) {
            isJumping = true; // bhop: always jump when moving on ground
        }

        if (isJumping) {
            if (inWater) motionY += 0.03999999910593033D;
            else if (isInLava()) motionY += 0.03999999910593033D;
            else if (onGround && jumpTicks == 0) {
                jump();
                jumpTicks = 10;
            }
        } else {
            jumpTicks = 0;
        }

        moveStrafing *= 0.98F;
        moveForward *= 0.98F;
        playerMoveEntityWithHeading(moveStrafing, moveForward);

        // EntityPlayer.onLivingUpdate tail (after super.onLivingUpdate): air control for next tick
        jumpMovementFactor = SPEED_IN_AIR;
        if (sprinting) jumpMovementFactor = (float) ((double) jumpMovementFactor + (double) SPEED_IN_AIR * 0.3D);
    }

    /** EntityLivingBase.jump (+ jump boost, sprint-jump boost along yaw). */
    void jump() {
        motionY = (double) 0.42F;
        if (jumpAmp >= 0) motionY += (double) ((float) (jumpAmp + 1) * 0.1F);
        if (sprinting) {
            float f = rotationYaw * 0.017453292F;
            motionX -= (double) (MathHelper.sin(f) * 0.2F);
            motionZ += (double) (MathHelper.cos(f) * 0.2F);
        }
        if (speedHack) {
            // blatant bhop: launch at ~0.62 b/t in the input direction
            float dir = moveDirectionYaw() * 0.017453292F;
            motionX = -MathHelper.sin(dir) * 0.68D;
            motionZ = MathHelper.cos(dir) * 0.68D;
            motionY = 0.40D;
        }
    }

    private float moveDirectionYaw() {
        float yaw = rotationYaw;
        float fwd = moveForward, str = moveStrafing;
        if (fwd < 0.0F) yaw += 180.0F;
        float mult = fwd < 0.0F ? -0.5F : (fwd > 0.0F ? 0.5F : 1.0F);
        if (str > 0.0F) yaw -= 90.0F * mult;
        if (str < 0.0F) yaw += 90.0F * mult;
        return yaw;
    }

    /** EntityPlayer.getAIMoveSpeed: movementSpeed attribute (walk speed, sprint +30%, speed/slowness potions). */
    float getAIMoveSpeed() {
        double v = (double) walkSpeed;
        if (sprinting) v *= 1.0D + 0.30000001192092896D;
        if (speedAmp >= 0) v *= 1.0D + 0.20000000298023224D * (double) (speedAmp + 1);
        if (slowAmp >= 0) v *= 1.0D + (-0.15000000596046448D) * (double) (slowAmp + 1);
        if (v < 0.0D) v = 0.0D;
        return (float) v;
    }

    /** EntityPlayer.moveEntityWithHeading (flying wrapper). */
    private void playerMoveEntityWithHeading(float strafe, float forward) {
        if (flying) {
            double d3 = motionY;
            float f = jumpMovementFactor;
            jumpMovementFactor = flySpeed * (float) (sprinting ? 2 : 1);
            moveEntityWithHeading(strafe, forward);
            motionY = d3 * 0.6D;
            jumpMovementFactor = f;
        } else {
            moveEntityWithHeading(strafe, forward);
        }
    }

    /** EntityLivingBase.moveEntityWithHeading */
    private void moveEntityWithHeading(float strafe, float forward) {
        if (!inWater || flying) {
            if (!isInLava() || flying) {
                float f4 = 0.91F;
                if (onGround) f4 = slipperinessBelow() * 0.91F;
                float f = 0.16277136F / (f4 * f4 * f4);
                float f5;
                if (onGround) f5 = getAIMoveSpeed() * f;
                else f5 = jumpMovementFactor;
                moveFlying(strafe, forward, f5);
                f4 = 0.91F;
                if (onGround) f4 = slipperinessBelow() * 0.91F;

                if (isOnLadder()) {
                    float f6 = 0.15F;
                    motionX = clamp(motionX, (double) (-f6), (double) f6);
                    motionZ = clamp(motionZ, (double) (-f6), (double) f6);
                    fallDistance = 0.0F;
                    if (motionY < -0.15D) motionY = -0.15D;
                    if (sneaking && motionY < 0.0D) motionY = 0.0D;
                }

                if (speedHack && !onGround) {
                    // keep bhop momentum: hold horizontal speed at >= 0.44 b/t while airborne
                    double h = Math.sqrt(motionX * motionX + motionZ * motionZ);
                    if (h > 0.01D && h < 0.44D && (forward != 0.0F || strafe != 0.0F)) {
                        double k = 0.44D / h;
                        motionX *= k;
                        motionZ *= k;
                    }
                }

                moveEntity(motionX, motionY, motionZ);

                if (isCollidedHorizontally && isOnLadder()) motionY = 0.2D;

                if (!isChunkLoadedHere()) {
                    if (posY > 0.0D) motionY = -0.1D;
                    else motionY = 0.0D;
                } else {
                    motionY -= 0.08D;
                }
                motionY *= 0.9800000190734863D;
                motionX *= (double) f4;
                motionZ *= (double) f4;
            } else {
                double d1 = posY;
                moveFlying(strafe, forward, 0.02F);
                moveEntity(motionX, motionY, motionZ);
                motionX *= 0.5D;
                motionY *= 0.5D;
                motionZ *= 0.5D;
                motionY -= 0.02D;
                if (isCollidedHorizontally && isOffsetPositionInLiquid(motionX, motionY + 0.6000000238418579D - posY + d1, motionZ)) {
                    motionY = 0.30000001192092896D;
                }
            }
        } else {
            double d0 = posY;
            float f1 = 0.8F;
            float f2 = 0.02F;
            float f3 = (float) depthStrider;
            if (f3 > 3.0F) f3 = 3.0F;
            if (!onGround) f3 *= 0.5F;
            if (f3 > 0.0F) {
                f1 += (0.54600006F - f1) * f3 / 3.0F;
                f2 += (getAIMoveSpeed() * 1.0F - f2) * f3 / 3.0F;
            }
            moveFlying(strafe, forward, f2);
            moveEntity(motionX, motionY, motionZ);
            motionX *= (double) f1;
            motionY *= 0.800000011920929D;
            motionZ *= (double) f1;
            motionY -= 0.02D;
            if (isCollidedHorizontally && isOffsetPositionInLiquid(motionX, motionY + 0.6000000238418579D - posY + d0, motionZ)) {
                motionY = 0.30000001192092896D;
            }
        }
    }

    /** Depth strider level of the boots (set by the client from the inventory). */
    public int depthStrider;

    private float slipperinessBelow() {
        return block(MathHelper.floor(posX), MathHelper.floor(bb.minY) - 1, MathHelper.floor(posZ)).frictionFactor;
    }

    /** Entity.moveFlying */
    private void moveFlying(float strafe, float forward, float friction) {
        float f = strafe * strafe + forward * forward;
        if (f >= 1.0E-4F) {
            f = MathHelper.c(f);
            if (f < 1.0F) f = 1.0F;
            f = friction / f;
            strafe = strafe * f;
            forward = forward * f;
            float f1 = MathHelper.sin(rotationYaw * (float) Math.PI / 180.0F);
            float f2 = MathHelper.cos(rotationYaw * (float) Math.PI / 180.0F);
            motionX += (double) (strafe * f2 - forward * f1);
            motionZ += (double) (forward * f2 + strafe * f1);
        }
    }

    /** Entity.isOffsetPositionInLiquid */
    private boolean isOffsetPositionInLiquid(double x, double y, double z) {
        AxisAlignedBB box = bb.toNmsOffset(x, y, z);
        return cubes(box).isEmpty() && !world.containsLiquid(box);
    }

    // ------------------------------------------------------------------ Entity.moveEntity
    /** Exact port of Entity.moveEntity(x, y, z) for an EntityPlayerSP. */
    public void moveEntity(double x, double y, double z) {
        if (noClip) {
            bb.offset(x, y, z);
            resetPositionToBB();
            return;
        }
        if (isInWeb) {
            isInWeb = false;
            x *= 0.25D;
            y *= 0.05000000074505806D;
            z *= 0.25D;
            motionX = 0.0D;
            motionY = 0.0D;
            motionZ = 0.0D;
        }

        double d3 = x;
        double d4 = y;
        double d5 = z;
        boolean flag = onGround && sneaking;

        if (flag) {
            double d6;
            for (d6 = 0.05D; x != 0.0D && cubes(bb.toNmsOffset(x, -1.0D, 0.0D)).isEmpty(); d3 = x) {
                if (x < d6 && x >= -d6) x = 0.0D;
                else if (x > 0.0D) x -= d6;
                else x += d6;
            }
            for (; z != 0.0D && cubes(bb.toNmsOffset(0.0D, -1.0D, z)).isEmpty(); d5 = z) {
                if (z < d6 && z >= -d6) z = 0.0D;
                else if (z > 0.0D) z -= d6;
                else z += d6;
            }
            for (; x != 0.0D && z != 0.0D && cubes(bb.toNmsOffset(x, -1.0D, z)).isEmpty(); d5 = z) {
                if (x < d6 && x >= -d6) x = 0.0D;
                else if (x > 0.0D) x -= d6;
                else x += d6;
                d3 = x;
                if (z < d6 && z >= -d6) z = 0.0D;
                else if (z > 0.0D) z -= d6;
                else z += d6;
            }
        }

        List<AxisAlignedBB> list1 = cubes(bb.toNmsAddCoord(x, y, z));
        saved.set(bb);
        for (int i = 0, n = list1.size(); i < n; i++) y = Box.yOffset(list1.get(i), bb, y);
        bb.offset(0.0D, y, 0.0D);
        boolean flag1 = onGround || d4 != y && d4 < 0.0D;
        for (int i = 0, n = list1.size(); i < n; i++) x = Box.xOffset(list1.get(i), bb, x);
        bb.offset(x, 0.0D, 0.0D);
        for (int i = 0, n = list1.size(); i < n; i++) z = Box.zOffset(list1.get(i), bb, z);
        bb.offset(0.0D, 0.0D, z);

        if (STEP_HEIGHT > 0.0D && flag1 && (d3 != x || d5 != z)) {
            double d11 = x;
            double d7 = y;
            double d8 = z;
            beforeStep.set(bb);
            bb.set(saved);
            y = STEP_HEIGHT;
            List<AxisAlignedBB> list = cubes(bb.toNmsAddCoord(d3, y, d5));

            // candidate A: step up using the stretched box, then move horizontally
            stepA.set(bb);
            stretched.set(stepA);
            if (d3 < 0.0D) stretched.minX += d3; else if (d3 > 0.0D) stretched.maxX += d3;
            if (d5 < 0.0D) stretched.minZ += d5; else if (d5 > 0.0D) stretched.maxZ += d5;
            double d9 = y;
            for (int i = 0, n = list.size(); i < n; i++) d9 = Box.yOffset(list.get(i), stretched, d9);
            stepA.offset(0.0D, d9, 0.0D);
            double d15 = d3;
            for (int i = 0, n = list.size(); i < n; i++) d15 = Box.xOffset(list.get(i), stepA, d15);
            stepA.offset(d15, 0.0D, 0.0D);
            double d16 = d5;
            for (int i = 0, n = list.size(); i < n; i++) d16 = Box.zOffset(list.get(i), stepA, d16);
            stepA.offset(0.0D, 0.0D, d16);

            // candidate B: step up with the plain box
            stepB.set(bb);
            double d17 = y;
            for (int i = 0, n = list.size(); i < n; i++) d17 = Box.yOffset(list.get(i), stepB, d17);
            stepB.offset(0.0D, d17, 0.0D);
            double d18 = d3;
            for (int i = 0, n = list.size(); i < n; i++) d18 = Box.xOffset(list.get(i), stepB, d18);
            stepB.offset(d18, 0.0D, 0.0D);
            double d19 = d5;
            for (int i = 0, n = list.size(); i < n; i++) d19 = Box.zOffset(list.get(i), stepB, d19);
            stepB.offset(0.0D, 0.0D, d19);

            double d20 = d15 * d15 + d16 * d16;
            double d10 = d18 * d18 + d19 * d19;
            if (d20 > d10) {
                x = d15;
                z = d16;
                y = -d9;
                bb.set(stepA);
            } else {
                x = d18;
                z = d19;
                y = -d17;
                bb.set(stepB);
            }
            for (int i = 0, n = list.size(); i < n; i++) y = Box.yOffset(list.get(i), bb, y);
            bb.offset(0.0D, y, 0.0D);

            if (d11 * d11 + d8 * d8 >= x * x + z * z) {
                x = d11;
                y = d7;
                z = d8;
                bb.set(beforeStep);
            }
        }

        resetPositionToBB();
        isCollidedHorizontally = d3 != x || d5 != z;
        isCollidedVertically = d4 != y;
        onGround = isCollidedVertically && d4 < 0.0D;

        int bx = MathHelper.floor(posX);
        int by = MathHelper.floor(posY - 0.20000000298023224D);
        int bz = MathHelper.floor(posZ);
        Block block1 = block(bx, by, bz);
        updateFallState(y, onGround, block1);

        if (d3 != x) motionX = 0.0D;
        if (d5 != z) motionZ = 0.0D;
        if (d4 != y) onLanded(block1);

        if (!flying && !flag && onGround) onEntityWalking(block1);

        doBlockCollisions();
    }

    /** EntityLivingBase.updateFallState -> Entity.updateFallState */
    private void updateFallState(double y, boolean onGroundIn, Block block) {
        if (!inWater) handleWaterMovement();
        if (onGroundIn) {
            if (fallDistance > 0.0F) {
                lastFallDistance = fallDistance;
                fallDistance = 0.0F;
            }
        } else if (y < 0.0D) {
            fallDistance = (float) ((double) fallDistance - y);
        }
    }

    /** Fall distance at the last landing (for debugging / nofall). */
    public float lastFallDistance;

    /** Block.onLanded (default stops vertical motion; slime bounces unless sneaking). */
    private void onLanded(Block block) {
        if (block == Blocks.SLIME && !sneaking) {
            if (motionY < 0.0D) motionY = -motionY;
        } else {
            motionY = 0.0D;
        }
    }

    /** Block.onEntityCollidedWithBlock(World, BlockPos, Entity) - walking on a block (slime slowdown). */
    private void onEntityWalking(Block block) {
        if (block == Blocks.SLIME && Math.abs(motionY) < 0.1D && !sneaking) {
            double d0 = 0.4D + Math.abs(motionY) * 0.2D;
            motionX *= d0;
            motionZ *= d0;
        }
    }

    /** Entity.doBlockCollisions: cobwebs and soul sand affect the client's motion. */
    private void doBlockCollisions() {
        int x0 = MathHelper.floor(bb.minX + 0.001D), y0 = MathHelper.floor(bb.minY + 0.001D), z0 = MathHelper.floor(bb.minZ + 0.001D);
        int x1 = MathHelper.floor(bb.maxX - 0.001D), y1 = MathHelper.floor(bb.maxY - 0.001D), z1 = MathHelper.floor(bb.maxZ - 0.001D);
        if (!areaLoaded(x0, z0, x1, z1)) return;
        for (int x = x0; x <= x1; ++x)
            for (int y = y0; y <= y1; ++y)
                for (int z = z0; z <= z1; ++z) {
                    Block b = block(x, y, z);
                    if (b == Blocks.WEB) isInWeb = true;
                    else if (b == Blocks.SOUL_SAND) { motionX *= 0.4D; motionZ *= 0.4D; }
                }
    }

    // ------------------------------------------------------------------ helpers for the client
    /** True if any block collision box intersects the player box offset by (x,y,z). */
    public boolean collidesAt(double x, double y, double z) {
        return !cubes(bb.toNmsOffset(x, y, z)).isEmpty();
    }

    /** MathHelper.clamp_double */
    private static double clamp(double num, double min, double max) {
        return num < min ? min : (num > max ? max : num);
    }

    public double motionHorizontal() {
        return Math.sqrt(motionX * motionX + motionZ * motionZ);
    }
}
