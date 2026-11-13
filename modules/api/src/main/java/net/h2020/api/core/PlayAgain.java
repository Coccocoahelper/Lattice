package net.h2020.api.core;

import net.h2020.api.H2020;
import net.h2020.api.game.GameManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import net.h2020.api.stats.CombatStats;

/** "/playagain" and the end-screen item: queue for the mode that just finished. */
public final class PlayAgain {
    private PlayAgain() {}

    /** The end-screen item: paper in the last hotbar slot, right-clicked to queue again. */
    public static void giveItem(Player p) {
        if (p == null) return;
        p.getInventory().setItem(8, new net.h2020.api.gui.ItemBuilder(org.bukkit.Material.PAPER)
                .name(ChatColor.GREEN + "Play Again")
                .lore(ChatColor.GRAY + "Click to queue for another game").build());
    }

    /**
     * The Hypixel end-of-game line, as one message made of four parts:
     * <pre>
     *   win     §aYou won!
     *   loss    §cYou lost!   (Bed Wars)   §cYou died!   (SkyWars)
     *           §eWant to play again? §b§lClick here!     <- clickable, runs /playagain
     * </pre>
     * {@link #build(boolean, boolean)} is the single source of both the component that is sent and the legacy string
     * used for logs and the fallback, so what is written down is what the player receives.
     */
    public static net.md_5.bungee.api.chat.BaseComponent[] parts(boolean win, boolean bedWars) {
        net.md_5.bungee.api.chat.TextComponent result = new net.md_5.bungee.api.chat.TextComponent(
                win ? Messages.raw("end.won") : Messages.raw(bedWars ? "end.lost" : "end.died"));
        result.setColor(win ? net.md_5.bungee.api.ChatColor.GREEN : net.md_5.bungee.api.ChatColor.RED);
        net.md_5.bungee.api.chat.TextComponent ask = new net.md_5.bungee.api.chat.TextComponent(
                " " + Messages.raw("end.play-again") + " ");
        ask.setColor(net.md_5.bungee.api.ChatColor.YELLOW);
        net.md_5.bungee.api.chat.TextComponent click =
                new net.md_5.bungee.api.chat.TextComponent(Messages.raw("end.click-here"));
        click.setColor(net.md_5.bungee.api.ChatColor.AQUA);
        click.setBold(true);
        click.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/playagain"));
        click.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                new net.md_5.bungee.api.chat.ComponentBuilder(net.md_5.bungee.api.ChatColor.YELLOW
                        + Messages.raw("end.click-hover")).create()));
        return new net.md_5.bungee.api.chat.BaseComponent[]{result, ask, click};
    }

    /** The same three parts as one legacy string, for logs, the probe and the fallback path. */
    public static String legacyPrompt(boolean win, boolean bedWars) {
        return net.md_5.bungee.api.chat.BaseComponent.toLegacyText(parts(win, bedWars));
    }

    public static void sendPrompt(Player p, boolean win, boolean bedWars) {
        if (p == null) return;
        try {
            p.spigot().sendMessage(parts(win, bedWars));
            CombatStats.inc("playagain.prompt");
            if (win) CombatStats.inc("playagain.prompt.win"); else CombatStats.inc("playagain.prompt.loss");
        } catch (Throwable t) {
            p.sendMessage(net.h2020.api.core.Messages.get("api.playagain.playagain","p1",legacyPrompt(win, bedWars)));
        }
    }

    public static void requeue(Player p) {
        LastGame.Entry e = LastGame.of(p);
        if (e == null) { net.h2020.api.core.Messages.send(p, "api.playagain.you-have-not-finished"); return; }
        int cd = LastGame.cooldown(p);
        if (cd > 0) { p.sendMessage(ChatColor.RED + Messages.raw("end.requeue-wait", "seconds", cd)); return; }
        GameManager gm = H2020.find(GameManager.class);
        if (gm == null) return;
        if (gm.gameOf(p) != null) { net.h2020.api.core.Messages.send(p, "api.playagain.you-are-already-in"); return; }
        LastGame.stamp(p);
        gm.queue(p, e.mode);
        p.sendMessage(ChatColor.GREEN + Messages.raw("end.playing-again", "mode", e.mode.displayName()));
    }
}
