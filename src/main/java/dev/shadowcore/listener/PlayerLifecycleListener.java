package dev.shadowcore.listener;

import dev.shadowcore.engine.EngineEvent;
import dev.shadowcore.engine.EventEngine;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerLifecycleListener implements Listener {
    private final EventEngine engine;

    public PlayerLifecycleListener(final EventEngine engine) {
        this.engine = engine;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        engine.submit(new EngineEvent.PlayerJoined(event.getPlayer().getUniqueId(), event.getPlayer().getName()));
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        engine.submit(new EngineEvent.PlayerQuit(event.getPlayer().getUniqueId(), event.getPlayer().getName()));
    }

    @EventHandler
    public void onKick(final PlayerKickEvent event) {
        engine.submit(new EngineEvent.PlayerKicked(event.getPlayer().getUniqueId(), event.getPlayer().getName()));
    }
}
