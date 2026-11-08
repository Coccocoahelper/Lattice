package net.h2020.bots.ai.nav;

import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;

import java.util.ArrayList;
import java.util.List;

/**
 * String-pulling post-process: removes intermediate walk nodes wherever a straight walk between two nodes is safe
 * (every sample along the line is standable at the same height, the player's 0.6-wide body fits, and no side sample
 * overhangs a void). Action nodes and their take-off nodes are kept as anchors. The steering's look-ahead then turns
 * the remaining polyline into natural curves.
 */
public final class PathSmoother {
    private PathSmoother() {}

    public static List<PathPoint> smooth(List<PathPoint> pts, WorldView wv) {
        int n = pts.size();
        if (n <= 2) return new ArrayList<>(pts);
        boolean[] anchor = new boolean[n];
        anchor[0] = true;
        anchor[n - 1] = true;
        for (int j = 1; j < n; j++) {
            if (pts.get(j).special()) { anchor[j] = true; anchor[j - 1] = true; }
        }
        List<PathPoint> out = new ArrayList<>();
        out.add(pts.get(0));
        int i = 0;
        while (i < n - 1) {
            if (anchor[i + 1] || pts.get(i).special() && pts.get(i).move != MoveType.DROP) {
                out.add(pts.get(i + 1));
                i++;
                continue;
            }
            int j = i + 1;
            for (int k = i + 2; k < n && k - i <= 14; k++) {
                PathPoint pk = pts.get(k);
                if (pk.move != MoveType.WALK) break;
                if (walkable(pts.get(i), pk, wv)) j = k; else break;
                if (anchor[k]) break;
            }
            out.add(pts.get(j));
            i = j;
        }
        return out;
    }

    static boolean walkable(PathPoint a, PathPoint b, WorldView wv) {
        if (Math.abs(a.y - b.y) > 0.6) return false;
        double dx = b.x - a.x, dz = b.z - a.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-3) return true;
        double ux = dx / len, uz = dz / len;
        double px = -uz * 0.3, pz = ux * 0.3;
        int steps = (int) Math.ceil(len / 0.25);
        double prevY = a.y;
        for (int s = 1; s < steps; s++) {
            double t = s / (double) steps;
            double cx = a.x + dx * t, cz = a.z + dz * t;
            double y = prevY;
            int fy = (int) Math.floor(y + 0.01);
            double st = standNear(wv, floor(cx), fy, floor(cz));
            if (Double.isNaN(st) || Math.abs(st - y) > 0.6) return false;
            prevY = st;
            for (int side = -1; side <= 1; side += 2) {
                int sx = floor(cx + px * side), sz = floor(cz + pz * side);
                int sy = (int) Math.floor(st + 0.01);
                if (!wv.passable(sx, sy, sz) && wv.shape(sx, sy, sz) != Shape.LOW) return false;
                if (!wv.passable(sx, sy + 1, sz)) return false;
                if (wv.shape(sx, sy - 1, sz) == Shape.TALL) return false; // fence/wall top pokes 0.5 into this cell
                if (!wv.standable(sx, sy, sz) && wv.voidBelow(sx, sy, sz, 5)) return false;
                if (wv.shape(sx, sy, sz) == Shape.LAVA || wv.shape(sx, sy - 1, sz) == Shape.LAVA) return false;
            }
        }
        return true;
    }

    private static double standNear(WorldView wv, int x, int y, int z) {
        double s = wv.stand(x, y, z);
        if (!Double.isNaN(s)) return s;
        s = wv.stand(x, y + 1, z);
        if (!Double.isNaN(s)) return s;
        return wv.stand(x, y - 1, z);
    }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }
}
