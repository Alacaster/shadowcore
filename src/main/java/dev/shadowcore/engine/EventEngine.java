package dev.shadowcore.engine;

import dev.shadowcore.ShadowCorePlugin;
import dev.shadowcore.service.SessionAuthority;
import java.util.ArrayDeque;
import java.util.Queue;
import org.bukkit.Bukkit;

public final class EventEngine {
    private final ShadowCorePlugin plugin;
    private final SessionAuthority authority;
    private final Queue<EngineEvent> queue = new ArrayDeque<>();
    private boolean scheduled;
    private boolean draining;

    public EventEngine(final ShadowCorePlugin plugin, final SessionAuthority authority) {
        this.plugin = plugin;
        this.authority = authority;
    }

    public void submit(final EngineEvent event) {
        boolean runNow = false;
        synchronized (queue) {
            queue.add(event);
            if (!scheduled) {
                scheduled = true;
                runNow = Bukkit.isPrimaryThread() && !draining;
                if (!runNow) {
                    Bukkit.getScheduler().runTask(plugin, this::drainOnMainThread);
                }
            }
        }
        if (runNow) {
            drainOnMainThread();
        }
    }

    private void drainOnMainThread() {
        if (draining) {
            return;
        }
        draining = true;
        try {
            while (true) {
                final EngineEvent next;
                synchronized (queue) {
                    next = queue.poll();
                    if (next == null) {
                        break;
                    }
                }
                try {
                    authority.handle(next);
                } catch (final Exception exception) {
                    plugin.getLogger().severe("Unhandled engine event failure for " + next + ": " + exception.getMessage());
                    exception.printStackTrace();
                }
            }
            try {
                authority.reconcileAll();
            } catch (final Exception exception) {
                plugin.getLogger().severe("Reconcile-all failure: " + exception.getMessage());
                exception.printStackTrace();
            }
        } finally {
            synchronized (queue) {
                scheduled = !queue.isEmpty();
                if (scheduled) {
                    Bukkit.getScheduler().runTask(plugin, this::drainOnMainThread);
                }
            }
            draining = false;
        }
    }
}
