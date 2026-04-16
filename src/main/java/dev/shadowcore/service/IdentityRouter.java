package dev.shadowcore.service;

import dev.shadowcore.model.ProfileIdentity;
import dev.shadowcore.store.Database;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central UUID routing service.
 * <p>
 * Tracks each player's active profile identity in memory and provides the correct
 * UUID for any given context. On startup, identities are reconstructed from the
 * database's session table.
 */
public final class IdentityRouter {

    public enum UuidContext {
        /** Permissions, op, bans, whitelist → always base UUID. */
        AUTHORITY,
        /** Player data (.dat files), advancements, statistics → profile UUID. */
        PLAYER_DATA,
        /** Entity ownership (pets, ender pearls) → profile UUID. */
        WORLD_ENTITY,
        /** Network/visual (tab list, entity spawn) → profile UUID. */
        NETWORK
    }

    private final Map<UUID, ProfileIdentity> active = new ConcurrentHashMap<>();

    public void set(final UUID baseUuid, final ProfileIdentity identity) {
        active.put(baseUuid, identity);
    }

    public ProfileIdentity get(final UUID baseUuid) {
        return active.getOrDefault(baseUuid, ProfileIdentity.main(baseUuid));
    }

    public UUID resolve(final UUID baseUuid, final UuidContext context) {
        return switch (context) {
            case AUTHORITY -> baseUuid;
            case PLAYER_DATA, WORLD_ENTITY, NETWORK -> get(baseUuid).profileUuid();
        };
    }

    public void clear(final UUID baseUuid) {
        active.remove(baseUuid);
    }

    public boolean isProfileActive(final UUID profileUuid) {
        return active.values().stream().anyMatch(id -> id.profileUuid().equals(profileUuid));
    }

    /**
     * Rebuilds active identities from persisted sessions on startup.
     */
    public void bootstrap(final Database db) {
        active.clear();
        for (final Database.SessionRecord session : db.loadAllSessions()) {
            if (session.activeProfileUuid() != null) {
                db.findProfileByUuid(session.activeProfileUuid()).ifPresent(profile ->
                    active.put(session.baseUuid(),
                        ProfileIdentity.local(session.baseUuid(), profile.profileUuid(), profile.suffix()))
                );
            }
            // Shadow state doesn't change the identity — it's a visual overlay.
            // The active profile underneath the shadow is what matters for data routing.
        }
    }
}
