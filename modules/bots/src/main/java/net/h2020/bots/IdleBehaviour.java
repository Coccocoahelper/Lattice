package net.h2020.bots;

import net.h2020.api.bot.AimIntent;
import net.h2020.api.bot.Bot;
import net.h2020.bots.client.VirtualClient;
import net.h2020.bots.input.HumanMouse;

import java.util.Random;

/** What a bot does without a brain: stands still and looks around now and then, like an AFK player. */
final class IdleBehaviour {
    private final Random rnd;
    private int ticksUntilGlance;
    private float baseYaw = Float.NaN;

    IdleBehaviour(Random rnd) {
        this.rnd = rnd;
        this.ticksUntilGlance = 40 + rnd.nextInt(100);
    }

    void tick(Bot bot, VirtualClient c) {
        if (!c.loggedIn() || c.isDead()) return;
        if (Float.isNaN(baseYaw)) baseYaw = c.yaw();
        // someone (a command, a game) aimed us deliberately: don't fight it
        if (c.mouse.mode != HumanMouse.Mode.NONE && c.mouse.intent != AimIntent.GLANCE) return;
        if (c.leftHeld()) return;
        if (--ticksUntilGlance > 0) return;
        ticksUntilGlance = 60 + rnd.nextInt(140);
        float yaw;
        float pitch;
        if (rnd.nextInt(4) == 0 && Math.abs(HumanMouse.wrap(baseYaw - c.yaw())) < 90.0D) {
            // return roughly to where we were looking before
            yaw = baseYaw + (float) (rnd.nextGaussian() * 10.0D);
            pitch = (float) (rnd.nextGaussian() * 6.0D + 4.0D);
        } else {
            baseYaw = c.yaw();
            yaw = c.yaw() + (float) (rnd.nextGaussian() * 45.0D);
            pitch = (float) Math.max(-40.0D, Math.min(55.0D, rnd.nextGaussian() * 14.0D + 6.0D));
        }
        c.aimRotation(yaw, pitch, AimIntent.GLANCE);
    }
}
