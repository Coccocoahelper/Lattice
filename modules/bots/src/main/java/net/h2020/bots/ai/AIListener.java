package net.h2020.bots.ai;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.game.events.GameKillEvent;
import net.h2020.api.game.events.GameStateChangeEvent;
import net.h2020.bots.ai.brain.BaseBrain;
import net.h2020.bots.ai.brain.GameBrain;
import net.h2020.bots.ai.perception.PlayerStats;
import net.h2020.bots.ai.perception.SoundBus;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Feeds the AI: sounds, combat events, kills, game state changes, and brain (re)assignment triggers. */
final class AIListener implements Listener {

    /**
     * Team callouts ("inc", "void", "drop", "split") are part of what a teammate says; the brains read them through
     * TeamTalk. Chat is async, the ring buffer is synchronized, everything else stays on the main thread.
     */
    /**
     * Pickups are information: an enemy picking up emeralds or diamonds in front of us is something the bots would
     * notice and the scout model remembers. Only bots that could see it (close, or with the picker in sight) count.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(org.bukkit.event.player.PlayerPickupItemEvent e) {
        org.bukkit.Material m = e.getItem().getItemStack().getType();
        if (m != org.bukkit.Material.EMERALD && m != org.bukkit.Material.DIAMOND) return;
        Player picker = e.getPlayer();
        net.h2020.bots.ai.perception.PlayerStats.picked(picker);
        if (!net.h2020.bots.ai.BotAI.isBotPlayer(picker)) {
            net.h2020.bots.ai.ml.BehaviourModel.observeEvent(picker, net.h2020.bots.ai.ml.BehaviourModel.GATHERING,
                    net.h2020.bots.ai.perception.SoundBus.now());
        }
        for (BaseBrain b : BotAI.brains()) {
            if (b instanceof net.h2020.bots.ai.brain.BedwarsBrain) {
                ((net.h2020.bots.ai.brain.BedwarsBrain) b).sawPickup(picker, m);
            }
        }
    }

    /** A real player opening the shop / upgrades menu: a free label for the intent model. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onShopOpen(org.bukkit.event.inventory.InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        Player p = (Player) e.getPlayer();
        if (BotAI.isBotPlayer(p)) return;                                  // people only
        if (!(e.getInventory().getHolder() instanceof net.h2020.api.gui.Menu)) return;
        net.h2020.bots.ai.ml.ShopWatch.observed(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        net.h2020.bots.ai.perception.TeamTalk.say(e.getPlayer(), e.getMessage());
        // a yes/no answer to a behaviour question is a training label
        String raw = e.getMessage() == null ? "" : e.getMessage().trim();
        // a number answers the server's open multiple-choice question
        if (net.h2020.bots.ai.ml.ServerQuestions.answerTyped(e.getPlayer(), raw)) return;
        String m = raw.toLowerCase();
        boolean yes = m.equals("y") || m.equals("yes") || m.equals("ye") || m.equals("yea") || m.equals("yeah")
                || m.equals("yep") || m.equals("true") || m.equals("im") || m.equals("ye");
        boolean no = m.equals("n") || m.equals("no") || m.equals("nah") || m.equals("nope") || m.equals("false");
        if (!yes && !no) return;
        // whichever module has a question open for this player gets the label
        if (net.h2020.bots.ai.ml.QuestionBook.hasPending(e.getPlayer())) {
            net.h2020.bots.ai.ml.QuestionBook.answer(e.getPlayer(), yes);
        } else {
            net.h2020.bots.ai.ml.BehaviourModel.answer(e.getPlayer(), yes);
        }
    }

    /** Block placements are behaviour labels: over the void = bridging, on solid ground = placing. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (p == null || p.getWorld() == null) return;
        net.h2020.bots.ai.perception.PlayerStats.placed(p, e.getBlockPlaced().getLocation());
        // people only: what a human builds when somebody is on him is the prior the bots learn from
        if (!net.h2020.bots.ai.BotAI.isBotPlayer(p)) {
            org.bukkit.entity.Player foe = null;
            double bd = 8;
            for (org.bukkit.entity.Player o : p.getWorld().getPlayers()) {
                if (o == p || o.isDead()) continue;
                if (net.h2020.api.H2020.find(net.h2020.bots.ai.BotAI.class) == null) break;
                double d = o.getLocation().distance(p.getLocation());
                if (d < bd) { bd = d; foe = o; }
            }
            boolean hurt = net.h2020.bots.ai.perception.PlayerStats.recentHits(p) > 0;
            if (foe != null && (hurt || bd < 5)) {
                net.h2020.bots.ai.ml.HumanPlacements.observe(p, foe, e.getBlockPlaced().getLocation());
            }
        }
        SoundBus.emit(SoundBus.Kind.PLACE, e.getBlock().getLocation().add(0.5, 0.5, 0.5), p);
        if (net.h2020.bots.ai.BotAI.isBotPlayer(p)) return;          // learn from people, not from our own bots
        org.bukkit.Location below = e.getBlockPlaced().getLocation().add(0, -1, 0);
        boolean over = !below.getBlock().getType().isSolid();
        net.h2020.bots.ai.ml.BehaviourModel.observeEvent(p,
                over ? net.h2020.bots.ai.ml.BehaviourModel.BRIDGING : net.h2020.bots.ai.ml.BehaviourModel.PLACING,
                net.h2020.bots.ai.perception.SoundBus.now());
    }

    private static Player attackerOf(Entity damager) {
        if (damager instanceof Player) return (Player) damager;
        if (damager instanceof Projectile) {
            Object s = ((Projectile) damager).getShooter();
            if (s instanceof Player) return (Player) s;
        }
        return null;
    }

    /** A rod that hooked a player: the pull is the combo start, and it never damages, so no damage event fires. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onFish(org.bukkit.event.player.PlayerFishEvent e) {
        if (e.getState() != org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_ENTITY) return;
        if (!(e.getCaught() instanceof Player)) return;
        Player shooter = e.getPlayer();
        BaseBrain sb = BotAI.brainOf(shooter);
        if (sb != null && sb.context() != null && e.getCaught() != shooter)
            sb.context().combat.onRangedHit((Player) e.getCaught());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player victim = (Player) e.getEntity();
        // a rod or snowball of ours connecting is the classic combo opener, and it never reaches the melee path
        if (!(e.getDamager() instanceof Player) && e.getDamager() instanceof org.bukkit.entity.Projectile) {
            org.bukkit.entity.Projectile pr = (org.bukkit.entity.Projectile) e.getDamager();
            if (pr.getShooter() instanceof Player) {
                Player shooter = (Player) pr.getShooter();
                BaseBrain sb = BotAI.brainOf(shooter);
                if (sb != null && sb.context() != null && shooter != victim) sb.context().combat.onRangedHit(victim);
            }
        }
        Player attacker = attackerOf(e.getDamager());
        if (attacker == null || attacker == victim) return;
        if (e.getDamager() instanceof Player) {
            PlayerStats.hit(attacker, attacker.getEyeLocation().distance(victim.getLocation().add(0, 0.9, 0)) - 0.3);
            net.h2020.bots.ai.perception.PlayerStats.hitBy(victim, attacker);
        }
        SoundBus.emit(SoundBus.Kind.HIT, victim.getLocation(), attacker);
        PlayerStats.tookHit(victim);
        if (e.getDamager() instanceof Player) {
            PlayerStats.dealtHit(attacker);
            if (!BotAI.isBotPlayer(attacker)) {
                net.h2020.bots.ai.ml.BehaviourModel.observeEvent(attacker, net.h2020.bots.ai.ml.BehaviourModel.FIGHTING,
                        net.h2020.bots.ai.perception.SoundBus.now());
            }
            if (!BotAI.isBotPlayer(victim)) {
                net.h2020.bots.ai.ml.BehaviourModel.observeEvent(victim, net.h2020.bots.ai.ml.BehaviourModel.FIGHTING,
                        net.h2020.bots.ai.perception.SoundBus.now());
            }
            double dist = attacker.getEyeLocation().distance(victim.getLocation().add(0, 0.9, 0));
            net.h2020.bots.ai.ml.HumanFights.engaged(attacker, victim, dist);
        }
        BaseBrain vb = BotAI.brainOf(victim);
        if (vb != null) vb.onHurt(attacker, e.getFinalDamage());
        BaseBrain ab = BotAI.brainOf(attacker);
        if (ab != null) ab.onHitLanded(victim);
        if (ab != null && ab.context() != null && e.getDamager() instanceof Projectile)
            ab.context().combat.onProjectileHit(e.getDamager().getType().name().toLowerCase());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent || !(e.getEntity() instanceof Player)) return;
        BaseBrain vb = BotAI.brainOf((Player) e.getEntity());
        if (vb != null) vb.onHurt(null, e.getFinalDamage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent e) { PlayerStats.swing(e.getPlayer()); }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        SoundBus.emit(SoundBus.Kind.BREAK, e.getBlock().getLocation().add(0.5, 0.5, 0.5), e.getPlayer());
        net.h2020.bots.ai.ml.PlacementPolicy.noteBroken(e.getBlock().getX(), e.getBlock().getY(),
                e.getBlock().getZ(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBow(EntityShootBowEvent e) {
        if (e.getEntity() instanceof Player) SoundBus.emit(SoundBus.Kind.BOW, e.getEntity().getLocation(), (Player) e.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof EnderPearl) {
            Object s = e.getEntity().getShooter();
            if (s instanceof Player) SoundBus.emit(SoundBus.Kind.PEARL, ((Player) s).getLocation(), (Player) s);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { SoundBus.emit(SoundBus.Kind.EXPLOSION, e.getLocation(), null); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) { SoundBus.emit(SoundBus.Kind.EAT, e.getPlayer().getLocation(), e.getPlayer()); }

    private static boolean bot(Player p) { return BotAI.isBotPlayer(p); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(GameKillEvent e) {
        Player victim = e.victim();
        boolean voided = victim != null && (victim.getLocation().getY() < 2
                || (victim.getLastDamageCause() != null && victim.getLastDamageCause().getCause() == EntityDamageEvent.DamageCause.VOID));
        BaseBrain vb = BotAI.brainOf(victim);
        if (voided) {
            // a knock-off is not a melee kill: count it on its own line so the two skills can be measured apart
            Player tapper = net.h2020.bots.ai.perception.PlayerStats.attackerWithin(victim, 60);
            if (tapper != null && tapper != victim) {
                net.h2020.api.stats.CombatStats.inc("tap.knockoff");
                net.h2020.api.stats.CombatStats.add("tap.knockoff.power",
                        Math.max(0, 20 - victim.getHealth()));
                if (bot(victim)) net.h2020.api.stats.CombatStats.inc("tap.knocked");
            } else {
                net.h2020.api.stats.CombatStats.inc("fall.void.unassisted");
            }
        }
        if (vb != null && vb.context() != null) vb.context().combat.onDeath(voided);
        net.h2020.bots.ai.stats.DeathStats.record(victim, voided, e.killer());
        if (vb != null) vb.onDeath(e.killer(), voided);
        if (voided) {
            // credit the knockback to whoever was fighting them: the void finish happens after the hit window closed
            BaseBrain killerBrain = BotAI.brainOf(e.killer());
            if (killerBrain != null && killerBrain.context() != null && e.killer() != victim)
                killerBrain.context().combat.noteVoidKill(victim);
        }
        BaseBrain kb = BotAI.brainOf(e.killer());
        if (kb != null && e.killer() != victim) kb.onKill(victim, e.finalKill());
        try { net.h2020.bots.ai.ml.HumanFights.resolved(victim); } catch (Throwable t) { /* never break a death */ }
        try {
            if (net.h2020.bots.ai.BotAI.isBotPlayer(e.killer()) && net.h2020.bots.ai.BotAI.isBotPlayer(victim)) {
                net.h2020.api.core.TrainSignals.death();        // the victim's side lost the exchange
                net.h2020.api.core.TrainSignals.kill();         // and the killer's side won it
            }
        } catch (Throwable ignored) { }
        for (BaseBrain b : BotAI.brains()) {
            if (b instanceof GameBrain && ((GameBrain) b).game() == e.game()) ((GameBrain) b).onAnyKill(e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onState(GameStateChangeEvent e) {
        for (BaseBrain b : BotAI.brains()) {
            if (b instanceof GameBrain && ((GameBrain) b).game() == e.game()) {
                try { ((GameBrain) b).onGameState(e.from(), e.to()); } catch (Throwable ignored) {}
            }
        }
        BotAI.reconcileGame(e.game());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) { BotAI.reconcileSoon(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) { BotAI.reconcileSoon(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) { BotAI.reconcileSoon(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getFrom().getWorld() != e.getTo().getWorld()) return;
        if (e.getFrom().distanceSquared(e.getTo()) > 64) {
            BaseBrain b = BotAI.brainOf(e.getPlayer());
            if (b != null) b.onTeleported();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        PlayerStats.forget(e.getPlayer());
        net.h2020.bots.ai.perception.LagView.forget(e.getPlayer().getUniqueId());
        BotAI.forgetPersona(e.getPlayer().getUniqueId());
    }
}
