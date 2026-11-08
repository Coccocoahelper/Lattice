package net.h2020.api.bot;

/**
 * Bots can be configured as "blatant cheaters" for the video so Watchdog visibly catches them.
 * The bot's virtual client produces genuinely cheating packets; Watchdog has no knowledge of this flag.
 */
public enum CheatProfile {
    NONE, REACH, KILLAURA, AUTOCLICKER, FLY, SPEED, NOFALL, SCAFFOLD, FASTPLACE, VELOCITY
}
