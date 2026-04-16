package dev.shadowcore;

import dev.shadowcore.command.LProfileCommand;
import dev.shadowcore.command.ShadowCommand;
import dev.shadowcore.engine.EventEngine;
import dev.shadowcore.listener.PlayerLifecycleListener;
import dev.shadowcore.protocol.ShadowProtocolLayer;
import dev.shadowcore.protocol.SkinCache;
import dev.shadowcore.service.EnvironmentService;
import dev.shadowcore.service.IdentityRouter;
import dev.shadowcore.service.PresentationService;
import dev.shadowcore.service.RealAccountNameResolver;
import dev.shadowcore.service.SessionAuthority;
import dev.shadowcore.service.VanillaDataBridge;
import dev.shadowcore.store.Database;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class ShadowCorePlugin extends JavaPlugin {
    private EventEngine eventEngine;
    private SessionAuthority authority;
    private Database database;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        final var data = getDataFolder();
        if (!data.exists() && !data.mkdirs()) throw new IllegalStateException("Cannot create data folder");

        database = new Database(data, getLogger());

        final ShadowProtocolLayer protocol;
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
            protocol = new ShadowProtocolLayer(this);
            getLogger().info("ProtocolLib detected — shadow disguise enabled.");
        } else {
            protocol = null;
            getLogger().warning("ProtocolLib not found. Shadow sessions unavailable.");
        }

        final SkinCache skinCache = new SkinCache(getConfig().getLong("name-check-cache-minutes", 30L));
        final IdentityRouter router = new IdentityRouter();
        final VanillaDataBridge dataBridge = new VanillaDataBridge(this);
        final EnvironmentService env = new EnvironmentService(this);
        final PresentationService presentation = new PresentationService(this);
        final RealAccountNameResolver nameResolver = new RealAccountNameResolver(this, database, skinCache);

        authority = new SessionAuthority(this, database, dataBridge, router, skinCache, env, presentation, nameResolver, protocol);
        eventEngine = new EventEngine(this, authority);
        authority.attachEngine(eventEngine);
        authority.bootstrap();

        final PluginCommand lprofile = Objects.requireNonNull(getCommand("lprofile"), "lprofile missing");
        final LProfileCommand lpc = new LProfileCommand(this, eventEngine);
        lprofile.setExecutor(lpc); lprofile.setTabCompleter(lpc);

        final PluginCommand shadow = Objects.requireNonNull(getCommand("shadow"), "shadow missing");
        final ShadowCommand sc = new ShadowCommand(this, eventEngine);
        shadow.setExecutor(sc); shadow.setTabCompleter(sc);

        getServer().getPluginManager().registerEvents(new PlayerLifecycleListener(eventEngine), this);
        getLogger().info("ShadowCore v3 enabled.");
    }

    @Override
    public void onDisable() {
        if (authority != null) authority.shutdown();
        if (database != null) database.close();
    }

    public EventEngine eventEngine() { return eventEngine; }
    public SessionAuthority authority() { return authority; }
}
