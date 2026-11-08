package net.h2020.maps;

import net.h2020.api.game.GameFamily;
import net.h2020.maps.gen.BedwarsGenerator;
import net.h2020.maps.gen.GeneratedMap;
import net.h2020.maps.gen.SkywarsGenerator;
import net.h2020.maps.gen.Themes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The built-in procedural map rotation (original names, Hypixel-like themes). */
public final class DefaultMaps {
    /** Bump when generator output changes: generated templates with an older version are rebuilt on start. */
    public static final int VERSION = 1;

    public static final class Def {
        public final String name, theme;
        public final GameFamily family;
        public final int teams;

        Def(String name, GameFamily family, int teams, String theme) {
            this.name = name; this.family = family; this.teams = teams; this.theme = theme;
        }

        public String folder() { return name.toLowerCase().replaceAll("[^a-z0-9]+", "_"); }

        public long seed() { return ((long) ("h2020:" + name).hashCode() << 16) ^ (teams * 7919L) ^ VERSION; }

        public GeneratedMap generate() {
            if (family == GameFamily.BEDWARS) return BedwarsGenerator.generate(name, Themes.byId(theme), teams, seed());
            return SkywarsGenerator.generate(name, Themes.byId(theme), teams, seed());
        }
    }

    private static final List<Def> ALL;

    static {
        // No procedural maps: the rotation is the 20 maps that shipped with the project, and nothing else. The
        // generator classes stay because /lattice regen and the map painter still use them on demand; this list is
        // what used to fill every family/team-count group with a generated map on start.
        List<Def> l = new ArrayList<Def>();
        ALL = Collections.unmodifiableList(l);
    }

    private DefaultMaps() {}

    public static List<Def> all() { return ALL; }

    public static Def byName(String name) {
        for (Def d : ALL) if (d.name.equalsIgnoreCase(name)) return d;
        return null;
    }
}
