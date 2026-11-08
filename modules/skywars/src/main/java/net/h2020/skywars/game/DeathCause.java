package net.h2020.skywars.game;

/** How a player was eliminated; drives the 2020 default death messages. */
enum DeathCause {
    MELEE, BOW, PROJECTILE, VOID, FALL, FIRE, EXPLOSION, DRAGON, DISCONNECT, OTHER;

    /**
     * Hypixel SkyWars 2020 default kill messages. v / k are already-coloured names.
     * Returns null when nothing is broadcast.
     */
    String message(String v, String k) {
        String e = "§e";
        switch (this) {
            case MELEE: return k != null ? v + e + " was slain by " + k + e + "." : v + e + " died.";
            case BOW: return k != null ? v + e + " was shot by " + k + e + "." : v + e + " was shot.";
            case PROJECTILE: return k != null ? v + e + " was hit by " + k + e + "." : v + e + " died.";
            case VOID: return k != null ? v + e + " was thrown into the void by " + k + e + "." : v + e + " fell into the void.";
            case FALL: return k != null ? v + e + " was knocked off a cliff by " + k + e + "." : v + e + " fell to their death.";
            case FIRE: return k != null ? v + e + " was burned to death while fighting " + k + e + "." : v + e + " burned to death.";
            case EXPLOSION: return k != null ? v + e + " was blown up by " + k + e + "." : v + e + " exploded.";
            case DRAGON: return v + e + " was killed by the §5Ender Dragon" + e + ".";
            case DISCONNECT: return k != null ? v + e + " disconnected while fighting " + k + e + "." : null;
            default: return k != null ? v + e + " was killed by " + k + e + "." : v + e + " died.";
        }
    }
}
