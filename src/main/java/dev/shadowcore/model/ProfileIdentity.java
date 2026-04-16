package dev.shadowcore.model;

import java.util.UUID;

/**
 * Runtime identity context for a player.
 * <p>
 * Carries the mapping between a player's Mojang UUID and their active profile UUID.
 * The profile UUID comes from the database (generated via {@code UUID.randomUUID()}
 * at profile creation time) — not derived deterministically.
 * <p>
 * The main profile uses the player's real Mojang UUID as the profile UUID.
 * Local profiles have a separate random UUID that maps to their own .dat file.
 */
public record ProfileIdentity(
    /** The player's real Mojang UUID. Used for permissions, op, bans. */
    UUID baseUuid,
    /** The active profile's UUID. Used for player data (.dat files). */
    UUID profileUuid,
    /** The profile suffix, or null for the main profile. */
    String suffix,
    /** Whether this is the main (real account) profile. */
    boolean isMain
) {
    /** Main profile: profileUuid equals baseUuid. */
    public static ProfileIdentity main(final UUID baseUuid) {
        return new ProfileIdentity(baseUuid, baseUuid, null, true);
    }

    /** Local profile: profileUuid is a separate random UUID from the database. */
    public static ProfileIdentity local(final UUID baseUuid, final UUID profileUuid, final String suffix) {
        return new ProfileIdentity(baseUuid, profileUuid, suffix, false);
    }
}
