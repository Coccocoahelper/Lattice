package net.h2020.bots.ai.stats;

import net.h2020.api.stats.CombatStats;

/** How long a bed defence actually takes to finish, from the first block down to the last gap closed. */
public final class DefenceStats {
    private static long firstBlockTick = -1, lastCompleteTick = -1;
    private static int walls, runs, blocks;
    private static double totalTicks;

    private DefenceStats() {}

    /** One defence block placed (any bot). The clock starts at the first one. */
    public static void block(long tick) {
        blocks++;
        if (firstBlockTick < 0) firstBlockTick = tick;
    }

    /** A DefendTask was started: a wall that needs several attempts is the thing being measured. */
    public static void run() { runs++; }

    /** The defence reached full integrity. */
    public static void completed(long tick) {
        walls++;
        if (firstBlockTick > 0) {
            totalTicks += tick - firstBlockTick;
            CombatStats.add("defence.wall.seconds", (tick - firstBlockTick) / 20.0);
            firstBlockTick = -1;
        }
        lastCompleteTick = tick;
    }

    public static String line() {
        if (walls == 0) return "§6[defence] §7no completed wall yet (" + blocks + " blocks, " + runs + " attempts)";
        return String.format(java.util.Locale.ROOT,
                "§6[defence] §f%d§7 walls, avg §f%.1fs§7 to complete, §f%.1f§7 attempts per wall, §f%d§7 blocks",
                walls, totalTicks / walls / 20.0, runs / (double) walls, blocks);
    }
}
