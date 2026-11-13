package net.h2020.practice.drill;

import net.h2020.practice.PracticeArena;
import net.h2020.practice.PracticeGame;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * One mechanic. A drill builds its arena, hands out its kit, decides what counts as success, and reports a line per
 * player. Everything it does not implement is a no-op, so a drill only writes the parts it cares about.
 */
public interface Drill {

    /** Stable id, the same string the command takes. */
    String id();

    /** Shown in the join message and the HUD title. */
    String display();

    /** One line telling the player what to do. */
    String hint();

    /** Build the arena. Called once, before the first player joins, on the main thread. */
    void build(PracticeArena arena);

    /** Where a player starts. */
    Location spawn();

    /** Where to watch from after a fall. */
    Location spectatorSpawn();

    /** Kit for one player. */
    void kit(Player p);

    /** The game is live. */
    default void start(PracticeGame g) { }

    /** Once per server tick while the game runs. */
    default void tick(PracticeGame g) { }

    default void onJoin(PracticeGame g, Player p) { }

    default void onQuit(PracticeGame g, Player p) { }

    /** A player attacked something (left click on an entity). */
    default void onAttack(PracticeGame g, Player p, Entity target) { }

    /** A player placed a block (used by the bridging drill to count what a crossing cost). */
    default void onPlace(PracticeGame g, Player p, org.bukkit.block.Block b) { }

    /** A player was hurt. Return true when the drill handled it and the game should not do anything else. */
    default boolean onDamage(PracticeGame g, Player p, EntityDamageEvent e) { return false; }

    /** A player died or fell out of the arena. */
    default void onFall(PracticeGame g, Player p) { }

    /** One line for the action bar: score, streak, whatever this drill counts. */
    String hud(PracticeGame g, Player p);

    /** Detail lines for /practice stats. */
    default java.util.List<String> stats(PracticeGame g, Player p) { return java.util.Collections.emptyList(); }

    /** The game is ending: remove entities, stop tasks. */
    default void cleanup(PracticeGame g) { }
}
