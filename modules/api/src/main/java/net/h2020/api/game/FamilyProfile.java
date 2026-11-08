package net.h2020.api.game;

import org.bukkit.ChatColor;
import org.bukkit.Material;

/**
 * How one family presents itself: the name on the lobby NPC, the menu title, the sidebar header and the icon.
 * Held in a registry so a server can rename a family or add one without editing the lobby classes; the enum constant
 * is still the key, which is the one piece of the family system Java forces into code.
 */
public final class FamilyProfile {
    public final GameFamily family;
    public String displayName;
    public String menuTitle;
    public String sidebarTitle;
    public Material icon;

    public FamilyProfile(GameFamily family, String displayName, String menuTitle, String sidebarTitle, Material icon) {
        this.family = family;
        this.displayName = displayName;
        this.menuTitle = menuTitle;
        this.sidebarTitle = sidebarTitle;
        this.icon = icon;
    }

    /** "&e&lBED WARS" style header, from the plain display name. */
    public String colouredSidebarTitle() {
        return ChatColor.YELLOW + "" + ChatColor.BOLD + sidebarTitle;
    }

    public String colouredMenuTitle() {
        return ChatColor.DARK_GRAY + menuTitle;
    }
}
