package net.h2020.bots.ai;

import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotController;
import net.h2020.bots.ai.chat.Chatter;
import net.h2020.bots.ai.combat.Combat;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.nav.Navigator;
import net.h2020.bots.ai.perception.Gaze;
import net.h2020.bots.ai.perception.Perception;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Random;

/** Everything one bot's AI components share: body handles, personality, and the component instances. */
public final class BotContext {
    public final Bot bot;
    public final Player player;
    public final BotController c;
    public final Persona persona;
    public final Random rng;
    public final Motor motor;
    public final Perception perception;
    public final Navigator nav;
    public final Combat combat;
    public final Chatter chat;
    public final Gaze gaze;
    private WorldView wv;
    private World wvWorld;
    /** Brain-local tick counter. */
    public long tick;

    public BotContext(Bot bot) {
        this.bot = bot;
        this.player = bot.player();
        this.c = bot.controller();
        this.persona = BotAI.persona(bot);
        this.rng = new Random(persona.seed ^ System.nanoTime());
        this.motor = new Motor(c);
        this.perception = new Perception(this);
        this.nav = new Navigator(this);
        this.combat = new Combat(this);
        this.chat = new Chatter(this);
        this.gaze = new Gaze(this);
    }

    /** Non-caching live world view of the bot's current world. */
    public WorldView world() {
        World w = player.getWorld();
        if (wv == null || wvWorld != w) { wv = new WorldView(w, false); wvWorld = w; }
        return wv;
    }

    public Location pos() { return c.position(); }

    public Location eye() {
        Location p = c.position();
        return p.clone().add(0, player.isSneaking() ? 1.54 : 1.62, 0);
    }

    public double health() { return player.getHealth(); }

    /**
     * The bot's own simulated ping in ms (profile.pingMs, what tab shows and what its client's packet stream runs at).
     */
    public int pingMs() {
        if (c instanceof net.h2020.bots.client.VirtualClient) return ((net.h2020.bots.client.VirtualClient) c).pingMs();
        try { return ((org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer) player).getHandle().ping; }
        catch (Throwable t) { return 50; }
    }

    /**
     * Where another player is as far as this client can tell: delayed by (our ping + their ping) / 2, like a real
     * client that only has the movement packets that reached it. Everything that decides something about somebody
     * else's position (range, pathing, aim assist, threat) uses this instead of Player.getLocation().
     */
    public Location posOf(Player p) { return net.h2020.bots.ai.perception.LagView.at(p, pingMs()); }

    /**
     * The client's own water/lava state (Entity.inWater / isInLava of the simulated body). In a liquid the jump key
     * swims up (+0.04/tick) instead of jumping, so anything that wants to get anywhere holds it, like a player does.
     */
    public boolean inLiquid() {
        if (c instanceof net.h2020.bots.client.VirtualClient) {
            net.h2020.bots.client.ClientPhysics p = ((net.h2020.bots.client.VirtualClient) c).phys;
            return p.inWater || p.isInLava();
        }
        org.bukkit.Material m = c.position().getBlock().getType();
        return m == org.bukkit.Material.WATER || m == org.bukkit.Material.STATIONARY_WATER
                || m == org.bukkit.Material.LAVA || m == org.bukkit.Material.STATIONARY_LAVA;
    }

    /** Server tick time in ms of this context (tick * 50). */
    public long nowMs() { return tick * 50L; }
}
