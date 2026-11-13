package net.h2020.bots;

import net.h2020.bots.ai.combat.CombatTuning;
import net.h2020.api.gui.ItemBuilder;
import net.h2020.api.gui.Menu;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/**
 * /bot settings: the in-game settings screen. Everything it changes is live except the average skill, which applies to
 * bots spawned from now on (the personas of bots already in a game are seeded at spawn).
 * <p>
 * Skill: left click +0.05, right click -0.05, shift +0.15 / -0.15 (click the top half for +, bottom half for -).
 * The other rows are on/off switches.
 */
final class BotSettingsMenu {
    private static final int SLOT_SKILL = 4;
    private static final int SLOT_LATENCY = 19;
    private static final int SLOT_BLOCKS = 21;
    private static final int SLOT_FIREBALL = 23;
    private static final int SLOT_BED = 25;
    private static final int SLOT_DIAG = 27;
    private static final int SLOT_RESET = 40;
    private static final int SLOT_ML = 31;
    private static final int SLOT_CLOSE = 36;

    private final BotSettings settings;
    private final BotManager bots;
    private final Menu menu;

    BotSettingsMenu(BotSettings settings, BotManager bots) {
        this.settings = settings;
        this.bots = bots;
        this.menu = new Menu(ChatColor.DARK_GRAY + "Bot settings", 5);
    }

    void open(Player p) {
        render();
        menu.open(p);
    }

    private void render() {
        menu.set(SLOT_SKILL, skillItem(), new Menu.Click() {
            @Override public void click(Player p, ClickType type) {
                double step = type.isShiftClick() ? 0.15 : 0.05;
                if (type.isRightClick()) step = -step;
                if (!type.isLeftClick() && !type.isRightClick()) return;
                double v = settings.averageSkill;
                if (v < 0) v = 0.5;                 // from the lobby mix: start at average
                v = Math.max(0.05, Math.min(0.97, v + step));
                settings.averageSkill = Math.round(v * 100.0) / 100.0;
                bots.setAverageSkill(settings.averageSkill);
                settings.save();
                render();
            }
        });
        menu.set(SLOT_LATENCY, toggle("Perceived latency", settings.perceivedLatency, Material.WATCH,
                "Bots only see where other players were",
                "half a round trip ago, delayed by",
                "(their ping + your ping) / 2, and react over",
                "that. Off = omniscient (the old behaviour)."), new Menu.Click() {
            @Override public void click(Player p, ClickType type) {
                settings.perceivedLatency = !settings.perceivedLatency;
                settings.save();
                settings.apply();
                render();
            }
        });
        menu.set(SLOT_BLOCKS, toggle("Combat block play", settings.blockPlay, Material.WOOL,
                "Stomps, 2-block duel pillars with the",
                "strafe around them, middle exits and",
                "head hitters in fights.",
                "Off = only the old tower / wall / cut."), new Menu.Click() {
            @Override public void click(Player p, ClickType type) {
                settings.blockPlay = !settings.blockPlay;
                settings.save();
                settings.apply();
                render();
            }
        });
        menu.set(SLOT_FIREBALL, toggle("Fireball play", settings.fireballPlay, Material.FIREBALL,
                "Punch incoming fireballs back (sword",
                "swing inside reach) or step off their",
                "line when that is not realistic."), new Menu.Click() {
            @Override public void click(Player p, ClickType type) {
                settings.fireballPlay = !settings.fireballPlay;
                settings.save();
                settings.apply();
                render();
            }
        });
        menu.set(SLOT_BED, toggle("Bed raid in fights", settings.bedRaid, Material.BED,
                "Rushers break the bed while they fight",
                "when the defender is busy, instead of",
                "waiting for the brain to send them."), new Menu.Click() {
            @Override public void click(Player p, ClickType type) {
                settings.bedRaid = !settings.bedRaid;
                settings.save();
                settings.apply();
                render();
            }
        });
        menu.set(SLOT_DIAG, toggle("Diagonal stance", settings.diagonalStance, Material.STRING,
                "Back down the bridge, S-key travel, two",
                "blocks a stance. Off: the straight bridge",
                "handles diagonals one block at a time."), new Menu.Click() {
            @Override public void click(Player p, ClickType type) {
                settings.diagonalStance = !settings.diagonalStance;
                settings.save();
                settings.apply();
                render();
            }
        });
        // the AI training switch: players always teach the models, our own bots only when asked
        final boolean botsTrain = net.h2020.bots.ai.ml.MlSettings.trainFromBots();
        menu.set(SLOT_ML, toggle("Learn from bots", botsTrain, Material.BOOK_AND_QUILL,
                "Players always train the behaviour, intent,",
                "shop, placement and role models.",
                "Our own bots only do when this is on.",
                "",
                "Leave it off: a hundred bots on guard duty",
                "make \"idle, wants to defend\" the answer to",
                "everything, because that is what they do.",
                "On is only for filling gaps in a fresh server."), new Menu.Click() {
            @Override public void click(Player p, ClickType type) {
                net.h2020.bots.ai.ml.MlSettings.setTrainFromBots(!botsTrain);
                p.sendMessage(net.h2020.api.core.Messages.get("bots.menu.training-from-bots", "state",
                        net.h2020.bots.ai.ml.MlSettings.trainFromBots() ? net.h2020.api.core.Messages.raw("bots.state.enabled") : net.h2020.api.core.Messages.raw("bots.state.disabled")));
                render();
            }
        });
        menu.set(SLOT_RESET, new ItemBuilder(Material.BARRIER)
                .name(ChatColor.RED + "Reset to defaults")
                .lore(ChatColor.GRAY + "2020 lobby skill mix, all switches on.")
                .build(), new Menu.Click() {
            @Override public void click(Player p, ClickType type) {
                settings.reset();
                bots.setAverageSkill(settings.averageSkill);
                render();
            }
        });
        menu.set(SLOT_CLOSE, new ItemBuilder(Material.ARROW)
                .name(ChatColor.GRAY + "Close")
                .build(), new Menu.Click() {
            @Override public void click(Player p, ClickType type) { p.closeInventory(); }
        });
    }

    private org.bukkit.inventory.ItemStack skillItem() {
        String value = settings.averageSkill < 0 ? "default mix" : String.format("%.2f", settings.averageSkill);
        return new ItemBuilder(Material.EXP_BOTTLE)
                .name(ChatColor.GOLD + "Average bot skill: " + ChatColor.WHITE + value
                        + ChatColor.GRAY + " (" + settings.skillLabel() + ")")
                .lore(ChatColor.GRAY + "Where new bots are rolled on the 0-1 skill curve:",
                        ChatColor.GRAY + "aim, CPS, reaction time, bridging, and how",
                        ChatColor.GRAY + "often they use blocks all follow from it.",
                        ChatColor.GRAY + "Applies to bots spawned from now on.",
                        "",
                        ChatColor.YELLOW + "Left click " + ChatColor.GRAY + "+0.05   "
                                + ChatColor.YELLOW + "Right click " + ChatColor.GRAY + "-0.05",
                        ChatColor.YELLOW + "Shift + click " + ChatColor.GRAY + "+/-0.15",
                        ChatColor.DARK_GRAY + "(-1 = the 2020 public lobby distribution)")
                .build();
    }

    private org.bukkit.inventory.ItemStack toggle(String name, boolean on, Material m, String... about) {
        ItemBuilder b = new ItemBuilder(on ? m : Material.GLASS)
                .name((on ? ChatColor.GREEN + name + ": ON" : ChatColor.RED + name + ": OFF"));
        b.lore(about);
        b.lore("", (on ? ChatColor.GREEN + "Click to turn off" : ChatColor.YELLOW + "Click to turn on"));
        if (on) b.glow();
        return b.build();
    }
}
