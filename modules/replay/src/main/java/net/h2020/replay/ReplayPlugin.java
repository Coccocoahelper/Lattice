package net.h2020.replay;

import net.h2020.api.H2020;
import net.h2020.api.core.AdminHooks;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Replays and the training harness: the recorder, the viewer, the league and the gate. It depends on the bots plugin
 * for the models it scores, and on nothing else beyond the shared API.
 */
public final class ReplayPlugin extends JavaPlugin {
    @Override public void onEnable() {
        Ledger.install(this);
        League.install(this);
        Arena.install(this);
        Hindsight.install(this);
        Recorder.install(this);
        ReplayViewer.install(this);
        // the bot runtime reports deaths and kills through the api; the training arena is the listener
        net.h2020.api.core.TrainSignals.onDeath = Arena::noteDeath;
        net.h2020.api.core.TrainSignals.onKill = Arena::noteKill;
        org.bukkit.Bukkit.getScheduler().runTaskTimer(this, League::saveAll, 600L, 600L);
        AdminHooks.register("train", (sender, args) -> { TrainCommand.run(sender, args); return true; });
        AdminHooks.register("replay", (sender, args) -> { ReplayViewer.command(sender, args); return true; });
        getLogger().info("Replays online");
    }

    @Override public void onDisable() {
        Recorder.shutdown();
        Replay.shutdown();
    }
}
