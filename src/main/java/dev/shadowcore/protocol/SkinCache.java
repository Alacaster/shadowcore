package dev.shadowcore.protocol;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches resolved player skin data from Mojang's session servers.
 * <p>
 * Deliberately free of ProtocolLib types — stores raw property strings that the
 * {@link ShadowProtocolLayer} converts to {@code WrappedGameProfile} on demand.
 * This keeps the cache usable even if ProtocolLib isn't loaded.
 * <p>
 * Populated as a side-effect of {@link dev.shadowcore.service.RealAccountNameResolver}'s
 * Mojang profile lookups — no redundant API calls.
 */
public final class SkinCache {

    /**
     * Resolved skin data for a player.
     *
     * @param uuid              The player's Mojang UUID.
     * @param name              The player's current Mojang username.
     * @param textureValue      Base64-encoded texture data (the "textures" property value).
     * @param textureSignature  Mojang's signature for the texture data.
     * @param resolvedAt        Epoch millis when this entry was resolved.
     */
    public record CachedSkin(UUID uuid, String name, String textureValue, String textureSignature, long resolvedAt) {
        /** Returns true if this profile has a signed skin texture. */
        public boolean hasSkin() {
            return textureValue != null && !textureValue.isBlank();
        }
    }

    private final Map<String, CachedSkin> cache = new ConcurrentHashMap<>();
    private final long ttlMs;

    public SkinCache(final long ttlMinutes) {
        this.ttlMs = ttlMinutes * 60_000L;
    }

    /**
     * Store resolved skin data. Called by the name resolver after a successful Mojang lookup.
     */
    public void put(final String playerName, final UUID uuid, final String name,
                    final String textureValue, final String textureSignature) {
        cache.put(playerName.toLowerCase(), new CachedSkin(uuid, name, textureValue, textureSignature, System.currentTimeMillis()));
    }

    /**
     * Retrieve cached skin data. Returns empty if not cached or expired.
     */
    public Optional<CachedSkin> get(final String playerName) {
        final CachedSkin cached = cache.get(playerName.toLowerCase());
        if (cached == null) return Optional.empty();
        if ((System.currentTimeMillis() - cached.resolvedAt()) > ttlMs) {
            cache.remove(playerName.toLowerCase());
            return Optional.empty();
        }
        return Optional.of(cached);
    }
}
