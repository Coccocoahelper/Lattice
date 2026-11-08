package net.h2020.bots.data;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.v1_8_R3.EntityHuman;
import net.minecraft.server.v1_8_R3.IPlayerFileData;
import net.minecraft.server.v1_8_R3.MinecraftServer;
import net.minecraft.server.v1_8_R3.NBTTagCompound;
import net.minecraft.server.v1_8_R3.PlayerList;
import net.minecraft.server.v1_8_R3.UserCache;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Keeps bots off the disk: wraps PlayerList.playerFileData so bot player.dat files are never loaded or written,
 * removes their ServerStatisticManager from the stats map (so no stats/uuid.json is written) and strips them from the
 * user cache (usercache.json) again after login.
 */
public final class BotDataGuard implements IPlayerFileData {
    private final Set<UUID> bots = ConcurrentHashMap.newKeySet();
    private final IPlayerFileData delegate;
    private final PlayerList playerList;
    private final Logger log;

    private BotDataGuard(PlayerList list, IPlayerFileData delegate, Logger log) {
        this.playerList = list;
        this.delegate = delegate;
        this.log = log;
    }

    public static BotDataGuard install(MinecraftServer server, Logger log) {
        PlayerList list = server.getPlayerList();
        IPlayerFileData current = list.playerFileData;
        if (current instanceof BotDataGuard) return (BotDataGuard) current;
        BotDataGuard g = new BotDataGuard(list, current, log);
        list.playerFileData = g;
        return g;
    }

    public void uninstall() {
        if (playerList.playerFileData == this) playerList.playerFileData = delegate;
    }

    public void markBot(UUID id) { bots.add(id); }
    public void unmarkBot(UUID id) { bots.remove(id); }

    private boolean isBot(EntityHuman h) {
        return h != null && bots.contains(h.getUniqueID());
    }

    @Override
    public void save(EntityHuman human) {
        if (isBot(human)) return;
        if (delegate != null) delegate.save(human);
    }

    @Override
    public NBTTagCompound load(EntityHuman human) {
        if (isBot(human)) return null;
        return delegate == null ? null : delegate.load(human);
    }

    @Override
    public String[] getSeenPlayers() {
        return delegate == null ? new String[0] : delegate.getSeenPlayers();
    }

    // ------------------------------------------------------------------ stats + usercache
    private static Field statsField, cacheByName, cacheByUuid, cacheDeque;

    static {
        try {
            statsField = PlayerList.class.getDeclaredField("o");
            statsField.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            cacheByName = UserCache.class.getDeclaredField("c");
            cacheByName.setAccessible(true);
            cacheByUuid = UserCache.class.getDeclaredField("d");
            cacheByUuid.setAccessible(true);
            cacheDeque = UserCache.class.getDeclaredField("e");
            cacheDeque.setAccessible(true);
        } catch (Exception ignored) {}
    }

    /** Drop the stats manager the EntityPlayer constructor registered so savePlayerFile skips it. */
    public void forgetStats(UUID id) {
        if (statsField == null) return;
        try {
            Map<?, ?> m = (Map<?, ?>) statsField.get(playerList);
            m.remove(id);
        } catch (Exception e) {
            log.fine("[bots] could not drop stats manager: " + e);
        }
    }

    /** Remove the bot from usercache.json's in-memory maps (it was added by the login). */
    public void forgetUserCache(MinecraftServer server, GameProfile profile) {
        if (cacheByName == null) return;
        try {
            UserCache cache = server.getUserCache();
            synchronized (cache) {
                ((Map<?, ?>) cacheByName.get(cache)).remove(profile.getName().toLowerCase(Locale.ROOT));
                ((Map<?, ?>) cacheByUuid.get(cache)).remove(profile.getId());
                Collection<?> deque = (Collection<?>) cacheDeque.get(cache);
                deque.remove(profile);
            }
        } catch (Exception e) {
            log.fine("[bots] could not clean user cache: " + e);
        }
    }
}
