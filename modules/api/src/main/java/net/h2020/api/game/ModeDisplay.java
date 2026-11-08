package net.h2020.api.game;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/** What a mode looks like in the lobby menu after menus.yml has had its say. */
public final class ModeDisplay {
    public Material icon;
    public short data;
    public int order;
    public String name;
    public String subtitle;
    public String description;

    public ModeDisplay(Material icon, short data, int order, String name, String subtitle, String description) {
        this.icon = icon; this.data = data; this.order = order;
        this.name = name; this.subtitle = subtitle; this.description = description;
    }

    /** The description lines, wrapped the way the menu wants them. */
    public List<String> lore() {
        List<String> out = new ArrayList<String>();
        if (description != null && !description.isEmpty()) out.add(description);
        return out;
    }
}
