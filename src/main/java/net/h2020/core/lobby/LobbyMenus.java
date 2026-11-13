package net.h2020.core.lobby;

import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameType;
import net.h2020.core.Core;
import net.h2020.core.HypixelFormat;
import net.h2020.api.gui.ItemBuilder;
import net.h2020.api.gui.Menu;
import net.h2020.core.profile.ProfileData;
import org.bukkit.ChatColor;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/** The 2020 lobby GUIs: Game Menu, Play Bed Wars / Play SkyWars, Lobby Selector, My Profile, Collectibles, Shop. */
public final class LobbyMenus {
    private static final String CONNECT = ChatColor.GREEN + "▸ Click to Connect";
    private final Core core;

    public LobbyMenus(Core core) { this.core = core; }

    private static void click(Player p) { p.playSound(p.getLocation(), Sound.CLICK, 1f, 1f); }

    private static Menu.Click unavailable(final String what) {
        return new Menu.Click() {
            @Override public void click(Player p, ClickType type) {
                p.sendMessage(ChatColor.RED + what + " is not available on this server!");
                p.closeInventory();
            }
        };
    }

    // ---------------- Game Menu ----------------

    public void openGameMenu(Player p) {
        Menu m = new Menu("Game Menu", 6);
        m.set(4, new ItemBuilder(Material.SKULL_ITEM, 1, 3).skullOwner(p.getName())
                .name(ChatColor.GREEN + "Hypixel Network")
                .lore(ChatColor.GRAY + "Players online: " + ChatColor.GREEN + HypixelFormat.commas(core.counts.network()), "",
                        ChatColor.GRAY + "Select a game below to connect", ChatColor.GRAY + "to its lobby!").build());

        m.set(11, gameIcon(new ItemBuilder(Material.BED), "Bed Wars", "Team Survival",
                "Protect your bed and destroy the enemy beds. Upgrade yourself and your team by collecting Iron, Gold, Emerald, and Diamond from generators to access powerful upgrades.",
                core.counts.count(GameFamily.BEDWARS), true), new Menu.Click() {
            @Override public void click(Player pl, ClickType type) { pl.closeInventory(); core.lobby.connectFamily(pl, GameFamily.BEDWARS); }
        });
        m.set(13, gameIcon(new ItemBuilder(Material.EYE_OF_ENDER), "SkyWars", "Solo/Team Survival",
                "Gather resources and equipment on your island in order to eliminate every other player. Go to the center island for special chests with special items!",
                core.counts.count(GameFamily.SKYWARS), true), new Menu.Click() {
            @Override public void click(Player pl, ClickType type) { pl.closeInventory(); core.lobby.connectFamily(pl, GameFamily.SKYWARS); }
        });
        m.set(15, deco(new ItemBuilder(Material.SAPLING, 1, 3), "SkyBlock", "Sandbox", "Collect resources, craft items, and grow your island in the brand-new SkyBlock world!", 31000, 1));

        m.set(19, deco(new ItemBuilder(Material.BOW), "Murder Mystery", "Social Deduction", "Can you find the Murderer before everyone is killed?", 12500, 2));
        m.set(20, deco(new ItemBuilder(Material.FISHING_ROD), "Duels", "1v1 Combat", "Fight your opponents in a variety of 1v1 and 2v2 modes.", 8200, 3));
        m.set(21, deco(new ItemBuilder(Material.WORKBENCH), "Build Battle", "Creative Building", "Build a masterpiece on a given theme and vote on everyone's builds!", 2600, 4));
        m.set(22, deco(new ItemBuilder(Material.GOLDEN_APPLE), "UHC Champions", "Survival PvP", "Gather resources, craft gear and fight to be the last one standing in the Ultra Hardcore world.", 900, 5));
        m.set(23, deco(new ItemBuilder(Material.DIAMOND_SWORD), "Blitz Survival Games", "Survival PvP", "Loot chests, choose a kit, and survive. Grab the Blitz Star to unleash a powerful ability!", 700, 6));
        m.set(24, deco(new ItemBuilder(Material.SOUL_SAND), "Mega Walls", "Team Survival", "Gather resources and prepare for the walls to fall. Protect your Wither and destroy the others!", 1400, 7));
        m.set(25, deco(new ItemBuilder(Material.SLIME_BALL), "Arcade Games", "Party Games", "Play a variety of fun mini games, including Party Games, Hide and Seek, Zombies and more!", 3300, 8));

        m.set(28, deco(new ItemBuilder(Material.TNT), "TNT Games", "Classic Minigames", "TNT Run, PVP Run, Bow Spleef, TNT Tag and TNT Wizards!", 1500, 9));
        m.set(29, deco(new ItemBuilder(Material.JUKEBOX), "Classic Games", "Retro Games", "Play the classics: VampireZ, Quakecraft, Paintball, The Walls, Arena Brawl and more!", 600, 10));
        m.set(30, deco(new ItemBuilder(Material.IRON_BARDING), "Cops and Crims", "Team Combat", "Defend the bomb site or plant the bomb in this team-based shooter!", 300, 11));
        m.set(31, deco(new ItemBuilder(Material.STONE_AXE), "Warlords", "Team Combat", "Choose a class and fight in a large scale battle to capture the flag!", 350, 12));
        m.set(32, deco(new ItemBuilder(Material.SKULL_ITEM, 1, 0), "Smash Heroes", "Arena Brawler", "Knock your opponents off the map using unique heroes and abilities!", 250, 13));
        m.set(33, deco(new ItemBuilder(Material.DIRT), "The Pit", "Survival PvP", "Jump in the Pit and fight! Earn gold, upgrades and prestige.", 2700, 14));
        m.set(34, deco(new ItemBuilder(Material.DARK_OAK_DOOR_ITEM), "Housing", "Creative", "Build your own house, invite your friends and visit others!", 1900, 15));

        // replays, when the bots module is loaded: the same browser as /h2020 replay list, as a menu
        net.h2020.api.game.ReplayService replays = net.h2020.api.H2020.find(net.h2020.api.game.ReplayService.class);
        if (replays != null) {
            m.set(16, new ItemBuilder(Material.BOOK_AND_QUILL)
                    .name(ChatColor.GREEN + "Replays")
                    .lore(ChatColor.DARK_GRAY + "Watch", "",
                            ChatColor.GRAY + "Replay recorded games: every bot",
                            ChatColor.GRAY + "match on the server is recorded.",
                            "", CONNECT)
                    .hideAll().build(), new Menu.Click() {
                @Override public void click(Player pl, ClickType type) {
                    pl.closeInventory();
                    replays.openBrowser(pl);
                }
            });
        }
        m.set(49, new ItemBuilder(Material.BOOKSHELF).name(ChatColor.GREEN + "Main Lobby")
                .lore(ChatColor.GRAY + "Return to the main lobby.", "", CONNECT).build(), new Menu.Click() {
            @Override public void click(Player pl, ClickType type) { pl.closeInventory(); core.lobby.connectFamily(pl, null); }
        });
        m.open(p);
        click(p);
    }

    private ItemStack gameIcon(ItemBuilder b, String name, String sub, String desc, int count, boolean available) {
        b.name(ChatColor.GREEN + name).lore(ChatColor.DARK_GRAY + sub, "").wrap(ChatColor.GRAY.toString(), desc);
        b.lore("", CONNECT, ChatColor.GRAY + HypixelFormat.commas(count) + " currently playing!");
        return b.hideAll().build();
    }

    private ItemStack deco(ItemBuilder b, String name, String sub, String desc, int base, int salt) {
        int c = core.counts.decorative(base, salt);
        b.name(ChatColor.GREEN + name).lore(ChatColor.DARK_GRAY + sub, "").wrap(ChatColor.GRAY.toString(), desc);
        b.lore("", ChatColor.RED + "Not available on this server", ChatColor.GRAY + HypixelFormat.commas(c) + " currently playing!");
        return b.hideAll().build();
    }

    // ---------------- Play Bed Wars / Play SkyWars ----------------

    public void openModeSelect(Player p, GameFamily family) {
        String title = family == GameFamily.BEDWARS ? "Play Bed Wars" : "Play SkyWars";
        Menu m = new Menu(title, 4);
        // every registered mode of this family, laid out left to right by its menu order: a new mode appears here
        // without editing this method
        int[] slots = {10, 12, 14, 16, 22};
        List<net.h2020.api.game.GameMode> modes = net.h2020.api.game.GameModes.of(family);
        for (int i = 0; i < modes.size() && i < slots.length; i++) {
            net.h2020.api.game.GameMode mode = modes.get(i);
            net.h2020.api.game.ModeDisplay d = net.h2020.api.game.GameModes.display(mode);
            mode(m, slots[i], mode, new ItemBuilder(d.icon, 1, d.data), d.name, d.subtitle, d.description);
        }
        close(m, 31);
        m.open(p);
    }

    private void mode(Menu m, int slot, final net.h2020.api.game.GameMode type, ItemBuilder b, String name, String sub, String desc) {
        mode(m, slot, type, b, name, sub, desc, new String[0]);
    }

    private void mode(Menu m, int slot, final net.h2020.api.game.GameMode type, ItemBuilder b, String name, String sub, String desc,
                      String... extra) {
        if (extra.length > 0) {
            String[] all = new String[extra.length + 1];
            all[0] = desc;
            System.arraycopy(extra, 0, all, 1, extra.length);
            desc = String.join(" ", all);
        }
        b.name(ChatColor.GREEN + name).lore(ChatColor.DARK_GRAY + sub, "").wrap(ChatColor.GRAY.toString(), desc);
        b.lore("", ChatColor.YELLOW + "Click to play!", ChatColor.GRAY + HypixelFormat.commas(core.counts.count(type)) + " currently playing!");
        m.set(slot, b.hideAll().build(), new Menu.Click() {
            @Override public void click(Player p, ClickType ct) {
                p.closeInventory();
                if (!(type instanceof GameType)) { net.h2020.api.core.Messages.send(p, "core.lobbymenus.that-mode-has-no"); return; }
                core.games.queue(p, (GameType) type);
            }
        });
    }

    private static void close(Menu m, int slot) {
        m.set(slot, new ItemBuilder(Material.BARRIER).name(ChatColor.RED + "Close").build(), new Menu.Click() {
            @Override public void click(Player p, ClickType type) { p.closeInventory(); }
        });
    }

    // ---------------- Lobby Selector ----------------

    public void openLobbySelector(final Player p) {
        final GameFamily family = core.lobby.familyOf(p.getWorld());
        boolean main = core.lobby.isMainLobby(p.getWorld());
        String title = main ? "Lobby Selector" : (family == GameFamily.BEDWARS ? "Bed Wars Lobby Selector" : "SkyWars Lobby Selector");
        int count = core.config.lobbyCount;
        int rows = Math.min(6, 2 + (count + 6) / 7);
        Menu m = new Menu(title, rows);
        ProfileData d = core.profiles.data(p);
        int real = p.getWorld().getPlayers().size();
        for (int i = 0; i < count; i++) {
            final int number = i + 1;
            int slot = 10 + (i / 7) * 9 + (i % 7);
            if (slot >= rows * 9) break;
            boolean current = number == d.lobbyNumber;
            int players = current ? real : fakeLobbyPlayers(number, family);
            if (current) {
                m.set(slot, new ItemBuilder(Material.STAINED_CLAY, number, 14).name(ChatColor.RED + "Lobby #" + number)
                        .lore(ChatColor.GRAY + "Players: " + players + "/100", "", ChatColor.RED + "Already connected!").build(),
                        new Menu.Click() {
                            @Override public void click(Player pl, ClickType type) { net.h2020.api.core.Messages.send(pl, "core.lobbymenus.you-are-already-connected"); }
                        });
            } else if (players >= 100) {
                m.set(slot, new ItemBuilder(Material.STAINED_CLAY, number, 14).name(ChatColor.RED + "Lobby #" + number)
                        .lore(ChatColor.GRAY + "Players: 100/100", "", ChatColor.RED + "This lobby is full!").build());
            } else {
                m.set(slot, new ItemBuilder(Material.QUARTZ_BLOCK, number, 0).name(ChatColor.GREEN + "Lobby #" + number)
                        .lore(ChatColor.GRAY + "Players: " + players + "/100", "", ChatColor.YELLOW + "Click to connect!").build(),
                        new Menu.Click() {
                            @Override public void click(Player pl, ClickType type) {
                                pl.closeInventory();
                                core.profiles.data(pl).lobbyNumber = number;
                                core.lobby.connectFamily(pl, core.lobby.familyOf(pl.getWorld()));
                            }
                        });
            }
        }
        m.open(p);
        click(p);
    }

    private static int fakeLobbyPlayers(int number, GameFamily family) {
        long seed = (System.currentTimeMillis() / 60000L) * 31 + number * 7 + (family == null ? 0 : family.ordinal() + 1);
        java.util.Random r = new java.util.Random(seed);
        return 35 + r.nextInt(66);
    }

    // ---------------- My Profile ----------------

    public void openProfile(Player p) {
        ProfileData d = core.profiles.data(p);
        Menu m = new Menu("My Profile", 6);
        int need = HypixelFormat.networkExpForLevel(d.networkLevel);
        int toGo = (int) Math.round(need * (1 - d.levelProgress));
        m.set(13, new ItemBuilder(Material.SKULL_ITEM, 1, 3).skullOwner(p.getName())
                .name(ChatColor.GREEN + "Character Information")
                .lore(ChatColor.GRAY + "Rank: " + HypixelFormat.rankName(d.rank, d.plusColor),
                        ChatColor.GRAY + "Hypixel Level: " + ChatColor.GOLD + d.networkLevel,
                        ChatColor.GRAY + "Experience until next Level: " + ChatColor.GOLD + HypixelFormat.commas(toGo),
                        ChatColor.GRAY + "Achievement Points: " + ChatColor.YELLOW + HypixelFormat.commas(d.achievementPoints),
                        ChatColor.GRAY + "Quests Completed: " + ChatColor.GOLD + HypixelFormat.commas(d.achievementPoints / 7),
                        ChatColor.GRAY + "Karma: " + ChatColor.LIGHT_PURPLE + HypixelFormat.commas(d.karma)).build());
        m.set(28, new ItemBuilder(Material.PAPER).name(ChatColor.GREEN + "Bed Wars Stats")
                .lore(ChatColor.GRAY + "Level: " + HypixelFormat.bedwarsStar(d.bwStar),
                        ChatColor.GRAY + "Wins: " + ChatColor.GREEN + HypixelFormat.commas(d.bwWins),
                        ChatColor.GRAY + "Kills: " + ChatColor.GREEN + HypixelFormat.commas(d.bwKills),
                        ChatColor.GRAY + "Final Kills: " + ChatColor.GREEN + HypixelFormat.commas(d.bwFinalKills),
                        ChatColor.GRAY + "Beds Broken: " + ChatColor.GREEN + HypixelFormat.commas(d.bwBedsBroken),
                        ChatColor.GRAY + "Coins: " + ChatColor.GOLD + HypixelFormat.commas(d.bwCoins)).build());
        m.set(29, new ItemBuilder(Material.PAPER).name(ChatColor.GREEN + "SkyWars Stats")
                .lore(ChatColor.GRAY + "Level: " + HypixelFormat.skywarsLevel(d.swLevel),
                        ChatColor.GRAY + "Wins: " + ChatColor.GREEN + HypixelFormat.commas(d.swWins),
                        ChatColor.GRAY + "Kills: " + ChatColor.GREEN + HypixelFormat.commas(d.swKills),
                        ChatColor.GRAY + "Coins: " + ChatColor.GOLD + HypixelFormat.commas(d.swCoins),
                        ChatColor.GRAY + "Souls: " + ChatColor.AQUA + HypixelFormat.commas(d.swSouls)).build());
        m.set(30, new ItemBuilder(Material.DIAMOND).name(ChatColor.GREEN + "Achievements")
                .lore(ChatColor.GRAY + "Track your progress as you unlock", ChatColor.GRAY + "achievements across the network.", "",
                        ChatColor.GRAY + "Points: " + ChatColor.YELLOW + HypixelFormat.commas(d.achievementPoints)).build(), unavailable("Achievements"));
        m.set(31, new ItemBuilder(Material.BOOK).name(ChatColor.GREEN + "Quests & Challenges")
                .lore(ChatColor.GRAY + "Complete quests and challenges to", ChatColor.GRAY + "earn Hypixel Experience and coins.").build(), unavailable("Quests"));
        m.set(32, new ItemBuilder(Material.SKULL_ITEM, 1, 3).name(ChatColor.GREEN + "Social")
                .lore(ChatColor.GRAY + "Manage your friends, party and guild.").build(), unavailable("Social"));
        m.set(33, new ItemBuilder(Material.POTION, 1, 8227).name(ChatColor.GREEN + "Network Boosters")
                .lore(ChatColor.GRAY + "Activate Network Boosters to give", ChatColor.GRAY + "everyone in a game triple coins!").build(), unavailable("Network Boosters"));
        net.h2020.api.bot.BotSettingsService botSettings =
                net.h2020.api.H2020.find(net.h2020.api.bot.BotSettingsService.class);
        if (botSettings != null) {
            m.set(22, new ItemBuilder(Material.MONSTER_EGG, 1, 50)
                    .name(ChatColor.GREEN + "Bot Settings")
                    .lore(ChatColor.DARK_GRAY + "Configuration", "",
                            ChatColor.GRAY + "Skill level, perceived latency,",
                            ChatColor.GRAY + "combat block play, fireballs, bed raids",
                            ChatColor.GRAY + "and whether the AI may learn from bots.",
                            "", CONNECT)
                    .hideAll().build(), new Menu.Click() {
                @Override public void click(Player pl, ClickType type) {
                    pl.closeInventory();
                    botSettings.open(pl);
                }
            });
        }
        m.set(34, new ItemBuilder(Material.REDSTONE_COMPARATOR).name(ChatColor.GREEN + "Settings & Visibility")
                .lore(ChatColor.GRAY + "Customise your Hypixel experience.").build(), new Menu.Click() {
            @Override public void click(Player pl, ClickType type) {
                new SettingsMenu(core).open(pl);
            }
        });
        close(m, 49);
        m.open(p);
        click(p);
    }

    // ---------------- Collectibles / Shop ----------------

    public void openCollectibles(Player p) {
        Menu m = new Menu("Collectibles", 6);
        String[][] cats = {
                {"BONE", "Pets", "Cute pets that follow you around the lobby."},
                {"SADDLE", "Mounts", "Ride around the lobby in style."},
                {"PISTON_BASE", "Gadgets", "Fun gadgets to use in the lobby."},
                {"BLAZE_POWDER", "Particle Packs", "Particles that trail behind you."},
                {"GOLD_HELMET", "Hats", "Show off a hat on your head."},
                {"LEATHER_CHESTPLATE", "Clothing", "Dress up in outfits and armor."},
                {"BANNER", "Banners", "Wear a banner as a cape."},
                {"MONSTER_EGG", "Morphs", "Disguise yourself as a mob."},
                {"FIREWORK", "Emotes", "Express yourself with emotes."},
                {"ENDER_CHEST", "Mystery Vault", "Open Mystery Boxes to unlock cosmetics!"}};
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 21, 22, 23};
        for (int i = 0; i < cats.length; i++) {
            Material mat = Material.matchMaterial(cats[i][0]);
            if (mat == null) mat = Material.CHEST;
            int unlocked = ThreadLocalRandom.current().nextInt(3, 40);
            m.set(slots[i], new ItemBuilder(mat).name(ChatColor.GREEN + cats[i][1])
                    .lore(ChatColor.GRAY + cats[i][2], "",
                            ChatColor.GRAY + "Unlocked: " + ChatColor.GREEN + unlocked + "/" + (unlocked + ThreadLocalRandom.current().nextInt(20, 120)),
                            "", ChatColor.YELLOW + "Click to view!").hideAll().build(), unavailable(cats[i][1]));
        }
        close(m, 49);
        m.open(p);
        click(p);
    }

    public void openShop(Player p, GameFamily family) {
        boolean bw = family == GameFamily.BEDWARS;
        ProfileData d = core.profiles.data(p);
        Menu m = new Menu(bw ? "Bed Wars Shop" : "SkyWars Shop", 5);
        String[][] items = bw
                ? new String[][]{{"MONSTER_EGG", "Shopkeeper Skins"}, {"BOW", "Projectile Trails"}, {"DIAMOND_SWORD", "Kill Effects"},
                {"GOLD_SWORD", "Kill Messages"}, {"STAINED_GLASS", "Glyphs"}, {"BED", "Bed Destroys"}, {"FIREWORK", "Victory Dances"}, {"NOTE_BLOCK", "Sprays"}}
                : new String[][]{{"IRON_SWORD", "Kits"}, {"BLAZE_POWDER", "Perks"}, {"GLASS", "Cages"}, {"DIAMOND_SWORD", "Kill Effects"},
                {"GOLD_SWORD", "Kill Messages"}, {"ARROW", "Projectile Trails"}, {"FIREWORK", "Victory Dances"}, {"NOTE_BLOCK", "Sprays"}};
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        for (int i = 0; i < items.length; i++) {
            Material mat = Material.matchMaterial(items[i][0]);
            if (mat == null) mat = Material.CHEST;
            int data = mat == Material.MONSTER_EGG ? 120 : 0;
            m.set(slots[i], new ItemBuilder(mat, 1, data).name(ChatColor.GREEN + items[i][1])
                    .lore(ChatColor.GRAY + "Browse " + items[i][1] + ".", "", ChatColor.YELLOW + "Click to browse!").hideAll().build(), unavailable(items[i][1]));
        }
        m.set(40, new ItemBuilder(Material.EMERALD).name(ChatColor.GRAY + "Total Coins: " + ChatColor.GOLD + HypixelFormat.commas(bw ? d.bwCoins : d.swCoins))
                .lore(ChatColor.GOLD + "https://store.hypixel.net").build());
        m.open(p);
        click(p);
    }
}
