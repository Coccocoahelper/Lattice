package net.h2020.bots.client;

import net.minecraft.server.v1_8_R3.AxisAlignedBB;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.Entity;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.EnumDirection;
import net.minecraft.server.v1_8_R3.MathHelper;
import net.minecraft.server.v1_8_R3.MovingObjectPosition;
import net.minecraft.server.v1_8_R3.Vec3D;
import net.minecraft.server.v1_8_R3.World;

import java.util.List;

/**
 * Port of EntityRenderer.getMouseOver (client objectMouseOver): block ray trace to the block reach distance, then the
 * entity pick using hitboxes expanded by the collision border, with the survival 3.0 entity reach cut-off.
 */
public final class MouseOver {
    public enum Type { MISS, BLOCK, ENTITY }

    public Type type = Type.MISS;
    public Entity entity;
    public BlockPosition blockPos;
    public EnumDirection face;
    public Vec3D hitVec;
    /** Distance from the eyes to the hit (or reach when missing). */
    public double distance;

    /**
     * @param entityReach survival 3.0 (a REACH cheat raises this)
     * @param blockReach  4.5 survival, 5.0 creative
     */
    public void compute(World world, EntityPlayer self, ClientPhysics p, float yaw, float pitch,
                        double entityReach, double blockReach, boolean creative) {
        double ex = p.posX, ey = p.posY + (double) p.eyeHeight(), ez = p.posZ;
        Vec3D eye = new Vec3D(ex, ey, ez);
        // Entity.getVectorForRotation(pitch, yaw)
        float f = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float f1 = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float f2 = -MathHelper.cos(-pitch * 0.017453292F);
        float f3 = MathHelper.sin(-pitch * 0.017453292F);
        double lx = (double) (f1 * f2), ly = (double) f3, lz = (double) (f * f2);

        double d0 = blockReach;
        Vec3D end = new Vec3D(ex + lx * d0, ey + ly * d0, ez + lz * d0);
        MovingObjectPosition mop = world.rayTrace(eye, end, false, false, true);

        double d1 = d0;
        boolean limitTo3 = false;
        if (creative) { d0 = 6.0D; d1 = 6.0D; }
        else if (d0 > entityReach) limitTo3 = true;
        if (entityReach > d0) d0 = entityReach; // reach cheat: trace entities further than block reach
        if (mop != null) d1 = dist(mop.pos, ex, ey, ez);

        Vec3D far = new Vec3D(ex + lx * d0, ey + ly * d0, ez + lz * d0);
        Entity pointed = null;
        Vec3D pointedVec = null;
        double d2 = d1;
        AxisAlignedBB search = new AxisAlignedBB(p.bb.minX, p.bb.minY, p.bb.minZ, p.bb.maxX, p.bb.maxY, p.bb.maxZ)
                .a(lx * d0, ly * d0, lz * d0).grow(1.0D, 1.0D, 1.0D);
        List<Entity> list = world.getEntities(self, search);
        for (int i = 0, n = list.size(); i < n; i++) {
            Entity e = list.get(i);
            if (!e.ad()) continue; // canBeCollidedWith
            if (e instanceof EntityPlayer && ((EntityPlayer) e).isSpectator()) continue;
            if (e == self.vehicle) continue;
            float border = e.ao();
            AxisAlignedBB box = e.getBoundingBox().grow((double) border, (double) border, (double) border);
            MovingObjectPosition hit = box.a(eye, far);
            if (box.a(eye)) {
                if (d2 >= 0.0D) {
                    pointed = e;
                    pointedVec = hit == null ? eye : hit.pos;
                    d2 = 0.0D;
                }
            } else if (hit != null) {
                double d3 = dist(hit.pos, ex, ey, ez);
                if (d3 < d2 || d2 == 0.0D) {
                    pointed = e;
                    pointedVec = hit.pos;
                    d2 = d3;
                }
            }
        }

        if (pointed != null && limitTo3 && dist(pointedVec, ex, ey, ez) > entityReach) {
            pointed = null;
            mop = new MovingObjectPosition(MovingObjectPosition.EnumMovingObjectType.MISS, pointedVec, null, new BlockPosition(pointedVec));
        }

        if (pointed != null && (d2 < d1 || mop == null)) {
            type = Type.ENTITY;
            entity = pointed;
            hitVec = pointedVec;
            blockPos = null;
            face = null;
            distance = d2;
            return;
        }
        entity = null;
        if (mop != null && mop.type == MovingObjectPosition.EnumMovingObjectType.BLOCK) {
            type = Type.BLOCK;
            blockPos = mop.a();
            face = mop.direction;
            hitVec = mop.pos;
            distance = d1;
        } else {
            type = Type.MISS;
            blockPos = null;
            face = null;
            hitVec = mop != null ? mop.pos : end;
            distance = blockReach;
        }
    }

    public void clear() {
        type = Type.MISS;
        entity = null;
        blockPos = null;
        face = null;
        hitVec = null;
    }

    static double dist(Vec3D v, double x, double y, double z) {
        double dx = v.a - x, dy = v.b - y, dz = v.c - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
