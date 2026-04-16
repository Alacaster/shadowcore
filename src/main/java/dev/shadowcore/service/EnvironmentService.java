package dev.shadowcore.service;

import dev.shadowcore.ShadowCorePlugin;
import dev.shadowcore.model.PlayerSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

public final class EnvironmentService {
    private final ShadowCorePlugin plugin;

    public EnvironmentService(final ShadowCorePlugin plugin) {
        this.plugin = plugin;
    }

    public PlayerSnapshot captureCurrent(final Player player) {
        return PlayerSnapshot.capture(player);
    }

    public void applySnapshot(final Player player, final PlayerSnapshot snapshot) {
        snapshot.applyTo(player);
    }

    public void restoreMain(final Player player, final PlayerSnapshot main) {
        main.applyTo(player);
    }

    public void forceSpectator(final Player player) {
        player.setGameMode(GameMode.SPECTATOR);
    }

    public Player onlinePlayer(final java.util.UUID uuid) {
        return Bukkit.getPlayer(uuid);
    }

    public void runNextTick(final Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }
}
