package net.h2020.api.game;

import org.bukkit.Material;

/**
 * One playable mode of a family, as data rather than as an enum constant. The built-ins are still {@link GameType}
 * values, but a network only has to call {@code GameModes.register(...)} with any other implementation to put a new
 * mode in the menus, the counters and the admin commands: nothing in the lobby or the command tree knows the
 * difference.
 */
public interface GameMode {
    String id();
    GameFamily family();
    String displayName();
    int teamSize();
    int teams();
    default int maxPlayers() { return teamSize() * teams(); }

    /** Menu icon. A bed for Bed Wars, an eye for SkyWars, whatever the mode's family uses. */
    Material icon();
    default short iconData() { return 0; }
    /** Full title shown in the menu, e.g. "Bed Wars (Solo)". */
    default String menuName() { return displayName() + " (" + menuSubtitle() + ")"; }
    /** Line under the title in the mode menu, e.g. "Solo". */
    String menuSubtitle();
    /** Wrapped grey description under the icon. */
    String description();
    /** SkyWars' "insane" modifier, false for every other mode. Games ask this instead of casting to GameType. */
    default boolean insane() { return false; }

    /** Left-to-right order in the mode menu; lower first. */
    default int menuOrder() { return 50; }
}
