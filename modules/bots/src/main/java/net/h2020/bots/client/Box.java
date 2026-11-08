package net.h2020.bots.client;

import net.minecraft.server.v1_8_R3.AxisAlignedBB;

/**
 * Mutable axis-aligned box used by the client physics so the per-tick collision code does not allocate. The offset
 * methods are exact ports of AxisAlignedBB.calculate{X,Y,Z}Offset with the roles of "this" (a block box) and the
 * moving entity box kept as in vanilla.
 */
final class Box {
    double minX, minY, minZ, maxX, maxY, maxZ;

    Box set(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        return this;
    }

    Box set(Box o) { return set(o.minX, o.minY, o.minZ, o.maxX, o.maxY, o.maxZ); }

    Box offset(double x, double y, double z) {
        minX += x; minY += y; minZ += z; maxX += x; maxY += y; maxZ += z;
        return this;
    }

    /** NMS AABB of this box offset by (x,y,z) (World.getCubes needs a real AABB). */
    AxisAlignedBB toNmsOffset(double x, double y, double z) {
        return new AxisAlignedBB(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
    }

    /** NMS AABB of this box stretched by the movement vector (Entity.getEntityBoundingBox().addCoord(x,y,z)). */
    AxisAlignedBB toNmsAddCoord(double x, double y, double z) {
        double a = minX, b = minY, c = minZ, d = maxX, e = maxY, f = maxZ;
        if (x < 0.0D) a += x; else if (x > 0.0D) d += x;
        if (y < 0.0D) b += y; else if (y > 0.0D) e += y;
        if (z < 0.0D) c += z; else if (z > 0.0D) f += z;
        return new AxisAlignedBB(a, b, c, d, e, f);
    }

    /** blockBox.calculateXOffset(entityBox, offsetX) */
    static double xOffset(AxisAlignedBB c, Box o, double offsetX) {
        if (o.maxY > c.b && o.minY < c.e && o.maxZ > c.c && o.minZ < c.f) {
            if (offsetX > 0.0D && o.maxX <= c.a) {
                double d1 = c.a - o.maxX;
                if (d1 < offsetX) offsetX = d1;
            } else if (offsetX < 0.0D && o.minX >= c.d) {
                double d0 = c.d - o.minX;
                if (d0 > offsetX) offsetX = d0;
            }
        }
        return offsetX;
    }

    /** blockBox.calculateYOffset(entityBox, offsetY) */
    static double yOffset(AxisAlignedBB c, Box o, double offsetY) {
        if (o.maxX > c.a && o.minX < c.d && o.maxZ > c.c && o.minZ < c.f) {
            if (offsetY > 0.0D && o.maxY <= c.b) {
                double d1 = c.b - o.maxY;
                if (d1 < offsetY) offsetY = d1;
            } else if (offsetY < 0.0D && o.minY >= c.e) {
                double d0 = c.e - o.minY;
                if (d0 > offsetY) offsetY = d0;
            }
        }
        return offsetY;
    }

    /** blockBox.calculateZOffset(entityBox, offsetZ) */
    static double zOffset(AxisAlignedBB c, Box o, double offsetZ) {
        if (o.maxX > c.a && o.minX < c.d && o.maxY > c.b && o.minY < c.e) {
            if (offsetZ > 0.0D && o.maxZ <= c.c) {
                double d1 = c.c - o.maxZ;
                if (d1 < offsetZ) offsetZ = d1;
            } else if (offsetZ < 0.0D && o.minZ >= c.f) {
                double d0 = c.f - o.minZ;
                if (d0 > offsetZ) offsetZ = d0;
            }
        }
        return offsetZ;
    }
}
