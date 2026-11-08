package net.h2020.api.core;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which games are being replayed and who is watching, shared so the game plugins can skip a replay host's world and
 * hold back a watcher's damage without linking against the replay code.
 */
public final class ReplayState {
    private static final Set<String> HOSTS = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> WATCHING = ConcurrentHashMap.newKeySet();

    private ReplayState() {}

    public static void setHost(String gameId, boolean host) {
        if (gameId == null) return;
        if (host) HOSTS.add(gameId); else HOSTS.remove(gameId);
    }
    public static boolean isHost(String gameId) { return gameId != null && HOSTS.contains(gameId); }

    public static void watching(UUID player, boolean on) {
        if (player == null) return;
        if (on) WATCHING.add(player); else WATCHING.remove(player);
    }
    public static boolean isWatching(UUID player) { return player != null && WATCHING.contains(player); }
}
