package net.h2020.bots;

import io.netty.channel.embedded.EmbeddedChannel;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotBrain;
import net.h2020.api.bot.BotController;
import net.h2020.api.bot.BotProfile;
import net.h2020.api.bot.CheatProfile;
import net.h2020.bots.client.VirtualClient;
import net.h2020.bots.net.FakeNetworkManager;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.PlayerConnection;
import org.bukkit.entity.Player;

import java.net.SocketAddress;
import java.util.Random;
import java.util.logging.Level;

/** A logged-in fake player: NMS EntityPlayer + fake connection + virtual client + optional brain. */
final class FakeBot implements Bot {
    private final BotManager manager;
    private volatile BotProfile profile;
    final EntityPlayer ep;
    final Player player;
    final VirtualClient client;
    final FakeNetworkManager network;
    private final IdleBehaviour idle;
    private BotBrain brain;
    volatile boolean removed;
    boolean removing;
    private int errorCount;
    private long lastErrorLog;

    FakeBot(BotManager manager, BotProfile profile, EntityPlayer ep, SocketAddress address, Random rnd) {
        this.manager = manager;
        this.profile = profile;
        this.ep = ep;
        this.player = ep.getBukkitEntity();
        this.client = new VirtualClient(this, ep, profile, rnd);
        this.network = new FakeNetworkManager(client, address);
        this.idle = new IdleBehaviour(rnd);
    }

    // ------------------------------------------------------------------ Bot
    @Override public Player player() { return player; }
    @Override public BotProfile profile() { return profile; }
    @Override public BotController controller() { return client; }
    @Override public BotBrain brain() { return brain; }
    @Override public boolean isRemoved() { return removed; }

    @Override
    public void setBrain(BotBrain newBrain) {
        BotBrain old = this.brain;
        if (old == newBrain) return;
        this.brain = null;
        if (old != null) {
            try { old.stop(this); } catch (Throwable t) { logError("brain stop", t); }
        }
        resetInputs();
        this.brain = newBrain;
        if (newBrain != null && !removed) {
            try { newBrain.start(this); } catch (Throwable t) { logError("brain start", t); }
        }
    }

    @Override
    public void remove() { manager.remove(this); }

    /** Runtime cheat switch (/bot cheat). The profile object is replaced so profile().cheat stays truthful. */
    void setCheat(CheatProfile cheat) {
        BotProfile p = profile;
        profile = new BotProfile(p.name, p.uuid, p.skinValue, p.skinSignature, p.rank, p.plusColor, p.networkLevel,
                p.pingMs, p.skill, cheat);
        client.setCheat(cheat);
    }

    private void resetInputs() {
        client.setMovement(0.0F, 0.0F);
        client.setJumping(false);
        client.setSneaking(false);
        client.setSprinting(false);
        client.setLeftHeld(false, 0.0D);
        client.setRightHeld(false);
        client.clearAim();
    }

    // ------------------------------------------------------------------ tick
    /** One server tick: packets in -> brain -> client simulation -> packets out -> connection tick. */
    void tick() {
        if (removed) return;
        client.receive();
        if (removed) return;
        BotBrain b = brain;
        try {
            if (b != null) b.tick(this);
            else idle.tick(this, client);
        } catch (Throwable t) {
            logError("brain tick", t);
        }
        if (removed) return;
        try {
            client.tick();
        } catch (Throwable t) {
            logError("client tick", t);
        }
        client.flushToServer();
        if (removed) return;
        PlayerConnection pc = ep.playerConnection;
        if (pc != null) {
            try { pc.c(); } catch (Throwable t) { logError("connection tick", t); }
        }
        if (network.channel instanceof EmbeddedChannel) {
            try { ((EmbeddedChannel) network.channel).runPendingTasks(); } catch (Throwable ignored) {}
        }
    }

    /** Called once when the bot has left the server (quit event or forced cleanup). */
    void onRemoved() {
        BotBrain b = brain;
        brain = null;
        if (b != null) {
            try { b.stop(this); } catch (Throwable t) { logError("brain stop", t); }
        }
        client.closeConnection();
        network.close(null);
    }

    private void logError(String what, Throwable t) {
        errorCount++;
        long now = System.currentTimeMillis();
        if (now - lastErrorLog > 10000L) {
            lastErrorLog = now;
            manager.log().log(Level.WARNING, "[bots] " + profile.name + ": error in " + what + " (" + errorCount + " total)", t);
        }
    }
}
