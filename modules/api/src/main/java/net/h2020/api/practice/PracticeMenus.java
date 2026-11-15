package net.h2020.api.practice;

import org.bukkit.entity.Player;

/**
 * The practice drill select menu, owned by the practice plugin and reachable from the lobby's game menu.
 *
 * <p>Same shape as {@link PracticeArenas}: the core does not depend on the practice plugin, the practice plugin tells
 * the core how to open its menu, and a menu entry appears only when that plugin is loaded.
 */
public final class PracticeMenus {

    public interface Opener { void open(Player p); }

    private static Opener opener;

    private PracticeMenus() { }

    public static void install(Opener o) { opener = o; }

    public static boolean available() { return opener != null; }

    public static void open(Player p) { if (opener != null) opener.open(p); }
}
