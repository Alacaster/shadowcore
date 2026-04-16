package dev.shadowcore.service;

import dev.shadowcore.ShadowCorePlugin;
import dev.shadowcore.engine.EngineEvent;
import dev.shadowcore.engine.EventEngine;
import dev.shadowcore.engine.ResponseHandle;
import dev.shadowcore.protocol.SkinCache;
import dev.shadowcore.store.Database;
import java.time.Duration;
import java.util.UUID;
import org.bukkit.Bukkit;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;

/**
 * Resolves player names against Mojang session servers.
 * Uses the database as a persistent cache and populates the SkinCache as a side effect.
 */
public final class RealAccountNameResolver {
    private final ShadowCorePlugin plugin;
    private final Database db;
    private final SkinCache skinCache;

    public RealAccountNameResolver(final ShadowCorePlugin plugin, final Database db, final SkinCache skinCache) {
        this.plugin = plugin;
        this.db = db;
        this.skinCache = skinCache;
    }

    public void resolveForProfileSwitch(
        final EventEngine engine, final UUID actor, final String actorName,
        final UUID profileUuid, final String suffix, final ResponseHandle response
    ) {
        // Names > 16 chars can't be real Mojang accounts
        if (suffix.length() > 16) {
            engine.submit(new EngineEvent.NameResolved(actor, actorName, profileUuid, suffix, false, response));
            return;
        }
        final String displayName = dev.shadowcore.util.Naming.fullLocalName(actorName, suffix);
        if (displayName.length() > 16) {
            engine.submit(new EngineEvent.NameResolved(actor, actorName, profileUuid, suffix, false, response));
            return;
        }
        // Check DB cache
        final long ttl = Duration.ofMinutes(plugin.getConfig().getLong("name-check-cache-minutes", 30L)).toMillis();
        final var cached = db.lookupName(displayName);
        if (cached.isPresent() && (System.currentTimeMillis() - cached.get().resolvedAt()) < ttl) {
            engine.submit(new EngineEvent.NameResolved(actor, actorName, profileUuid, suffix, cached.get().isReal(), response));
            return;
        }
        // Async Mojang lookup
        final PlayerProfile profile = Bukkit.getServer().createProfile(displayName);
        profile.update().whenComplete((updated, throwable) -> {
            final boolean real = throwable == null && updated != null && updated.getId() != null;
            final UUID mojangUuid = real ? updated.getId() : null;
            db.cacheNameResolution(displayName, mojangUuid, real);
            if (real && updated != null) cacheSkin(updated);
            engine.submit(new EngineEvent.NameResolved(actor, actorName, profileUuid, suffix, real, response));
        });
    }

    public void resolveForShadowMount(
        final EventEngine engine, final UUID actor, final String actorName,
        final String targetName, final ResponseHandle response
    ) {
        if (targetName.length() > 16) {
            engine.submit(new EngineEvent.ShadowNameResolved(actor, actorName, targetName, false, response));
            return;
        }
        final long ttl = Duration.ofMinutes(plugin.getConfig().getLong("name-check-cache-minutes", 30L)).toMillis();
        final var cached = db.lookupName(targetName);
        if (cached.isPresent() && (System.currentTimeMillis() - cached.get().resolvedAt()) < ttl) {
            engine.submit(new EngineEvent.ShadowNameResolved(actor, actorName, targetName, cached.get().isReal(), response));
            return;
        }
        final PlayerProfile profile = Bukkit.getServer().createProfile(targetName);
        profile.update().whenComplete((updated, throwable) -> {
            final boolean real = throwable == null && updated != null && updated.getId() != null;
            final UUID mojangUuid = real ? updated.getId() : null;
            db.cacheNameResolution(targetName, mojangUuid, real);
            if (real && updated != null) cacheSkin(updated);
            engine.submit(new EngineEvent.ShadowNameResolved(actor, actorName, targetName, real, response));
        });
    }

    private void cacheSkin(final PlayerProfile profile) {
        if (profile.getId() == null || profile.getName() == null) return;
        String texValue = null, texSig = null;
        for (final ProfileProperty prop : profile.getProperties()) {
            if ("textures".equals(prop.getName())) { texValue = prop.getValue(); texSig = prop.getSignature(); break; }
        }
        skinCache.put(profile.getName(), profile.getId(), profile.getName(), texValue, texSig);
    }
}
