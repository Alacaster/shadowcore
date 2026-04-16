package dev.shadowcore.store;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * SQLite-backed persistent store for all ShadowCore state.
 * <p>
 * <b>Design principle:</b> If the server crashes and restarts, the database alone
 * (plus vanilla .dat files in playerdata/) contains everything needed to reconstruct
 * the complete system state. No YAML, no in-memory-only state that matters.
 * <p>
 * <b>What's NOT in the database:</b>
 * <ul>
 *   <li>Session phase (CHECKING_NAME, etc.) — transient, always IDLE on startup</li>
 *   <li>Pending operations — transient, always cleared on startup</li>
 *   <li>Connected flag — always false on startup, set true when player joins</li>
 *   <li>Player inventory/health/location — stored in vanilla .dat files</li>
 * </ul>
 * <p>
 * <b>What IS derived (no column needed):</b>
 * <ul>
 *   <li>Session mode: shadow_target != null → SHADOW; active_profile_uuid != null → LOCAL; else → MAIN</li>
 *   <li>Display name: account_name + "_" + suffix (computed by Naming utility)</li>
 *   <li>Shadow reservations: rebuilt from sessions table on startup</li>
 * </ul>
 */
public final class Database implements AutoCloseable {
    private final Connection connection;
    private final Logger logger;

    public Database(final File pluginFolder, final Logger logger) {
        this.logger = logger;
        try {
            final File dbFile = new File(pluginFolder, "shadowcore.db");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA foreign_keys=ON");
            }
            createSchema();
        } catch (final SQLException e) {
            throw new IllegalStateException("Failed to open SQLite database", e);
        }
    }

    private void createSchema() throws SQLException {
        try (Statement s = connection.createStatement()) {
            // ── Players ────────────────────────────────────────────
            // Every Mojang account that has ever joined.
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS players (
                    base_uuid    TEXT PRIMARY KEY,
                    account_name TEXT NOT NULL,
                    max_profiles INTEGER NOT NULL DEFAULT 2,
                    first_seen_at INTEGER NOT NULL,
                    last_seen_at  INTEGER NOT NULL
                )
            """);
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_players_name ON players(account_name COLLATE NOCASE)");

            // ── Profiles ───────────────────────────────────────────
            // Each local profile. profile_uuid is a random UUID used as the
            // .dat file key in world/playerdata/.
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS profiles (
                    profile_uuid TEXT PRIMARY KEY,
                    owner_uuid   TEXT NOT NULL,
                    suffix       TEXT NOT NULL,
                    created_at   INTEGER NOT NULL,
                    last_mounted_at INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(owner_uuid, suffix)
                )
            """);
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_profiles_owner ON profiles(owner_uuid)");

            // ── Sessions ───────────────────────────────────────────
            // One row per player. Captures the persistent part of their session:
            // which profile is active and whether a shadow is engaged.
            // Mode is DERIVED: shadow_target != null → SHADOW;
            //                  active_profile_uuid != null → LOCAL;
            //                  else → MAIN.
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sessions (
                    base_uuid            TEXT PRIMARY KEY,
                    active_profile_uuid  TEXT,
                    shadow_target        TEXT,
                    shadow_target_uuid   TEXT,
                    shadow_self          INTEGER NOT NULL DEFAULT 0,
                    shadow_texture_value TEXT,
                    shadow_texture_sig   TEXT,
                    conflict_frozen      INTEGER NOT NULL DEFAULT 0,
                    conflict_target      TEXT
                )
            """);

            // ── Known names ────────────────────────────────────────
            // Caches Mojang name resolution results so we don't re-query
            // the session servers on every profile switch.
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS known_names (
                    name_lower   TEXT PRIMARY KEY,
                    mojang_uuid  TEXT,
                    is_real      INTEGER NOT NULL,
                    resolved_at  INTEGER NOT NULL
                )
            """);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  PLAYERS
    // ════════════════════════════════════════════════════════════════

    /** Registers a player on first join, or updates their name and last-seen time. */
    public void upsertPlayer(final UUID baseUuid, final String accountName) {
        final long now = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO players (base_uuid, account_name, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(base_uuid) DO UPDATE SET account_name = excluded.account_name, last_seen_at = excluded.last_seen_at
            """)) {
            ps.setString(1, baseUuid.toString());
            ps.setString(2, accountName);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.executeUpdate();
        } catch (final SQLException e) {
            logger.severe("upsertPlayer failed: " + e.getMessage());
        }
    }

    public Optional<String> getAccountName(final UUID baseUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT account_name FROM players WHERE base_uuid = ?")) {
            ps.setString(1, baseUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getString("account_name"));
            }
        } catch (final SQLException e) {
            logger.severe("getAccountName failed: " + e.getMessage());
        }
        return Optional.empty();
    }

    public int getMaxProfiles(final UUID baseUuid, final int defaultLimit) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT max_profiles FROM players WHERE base_uuid = ?")) {
            ps.setString(1, baseUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("max_profiles");
            }
        } catch (final SQLException e) {
            logger.severe("getMaxProfiles failed: " + e.getMessage());
        }
        return defaultLimit;
    }

    public void setMaxProfiles(final UUID baseUuid, final int limit) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE players SET max_profiles = ? WHERE base_uuid = ?")) {
            ps.setInt(1, limit);
            ps.setString(2, baseUuid.toString());
            ps.executeUpdate();
        } catch (final SQLException e) {
            logger.severe("setMaxProfiles failed: " + e.getMessage());
        }
    }

    /** Resolves a player name to their base UUID. Checks online players first via caller. */
    public Optional<UUID> resolvePlayerUuid(final String accountName) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT base_uuid FROM players WHERE account_name = ? COLLATE NOCASE")) {
            ps.setString(1, accountName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(UUID.fromString(rs.getString("base_uuid")));
            }
        } catch (final SQLException e) {
            logger.severe("resolvePlayerUuid failed: " + e.getMessage());
        }
        return Optional.empty();
    }

    // ════════════════════════════════════════════════════════════════
    //  PROFILES
    // ════════════════════════════════════════════════════════════════

    /** Record carrying all profile data from a single DB row. */
    public record ProfileRecord(
        UUID profileUuid,
        UUID ownerUuid,
        String suffix,
        long createdAt,
        long lastMountedAt
    ) {
        /** Computes the display name from the owner's account name + suffix. */
        public String displayName(final String ownerAccountName) {
            return dev.shadowcore.util.Naming.fullLocalName(ownerAccountName, suffix);
        }
    }

    /** Creates a new profile with a random UUID. Returns the generated profile UUID. */
    public UUID createProfile(final UUID ownerUuid, final String suffix) {
        final UUID profileUuid = UUID.randomUUID();
        final long now = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO profiles (profile_uuid, owner_uuid, suffix, created_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, profileUuid.toString());
            ps.setString(2, ownerUuid.toString());
            ps.setString(3, suffix.toLowerCase());
            ps.setLong(4, now);
            ps.executeUpdate();
        } catch (final SQLException e) {
            logger.severe("createProfile failed: " + e.getMessage());
            return null;
        }
        return profileUuid;
    }

    public List<ProfileRecord> listProfiles(final UUID ownerUuid) {
        final List<ProfileRecord> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM profiles WHERE owner_uuid = ? ORDER BY suffix")) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(readProfile(rs));
            }
        } catch (final SQLException e) {
            logger.severe("listProfiles failed: " + e.getMessage());
        }
        return list;
    }

    public Optional<ProfileRecord> findProfileBySuffix(final UUID ownerUuid, final String suffix) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM profiles WHERE owner_uuid = ? AND suffix = ? COLLATE NOCASE")) {
            ps.setString(1, ownerUuid.toString());
            ps.setString(2, suffix.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(readProfile(rs));
            }
        } catch (final SQLException e) {
            logger.severe("findProfileBySuffix failed: " + e.getMessage());
        }
        return Optional.empty();
    }

    public Optional<ProfileRecord> findProfileByUuid(final UUID profileUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM profiles WHERE profile_uuid = ?")) {
            ps.setString(1, profileUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(readProfile(rs));
            }
        } catch (final SQLException e) {
            logger.severe("findProfileByUuid failed: " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Finds a profile by its computed display name (ownerAccountName + "_" + suffix).
     * Requires splitting the display name and matching both parts.
     */
    public Optional<ProfileRecord> findProfileByDisplayName(final String displayName) {
        final int lastUnderscore = displayName.lastIndexOf('_');
        if (lastUnderscore <= 0) return Optional.empty();
        final String baseName = displayName.substring(0, lastUnderscore);
        final String suffix = displayName.substring(lastUnderscore + 1);
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT p.* FROM profiles p
                JOIN players pl ON p.owner_uuid = pl.base_uuid
                WHERE pl.account_name = ? COLLATE NOCASE AND p.suffix = ? COLLATE NOCASE
            """)) {
            ps.setString(1, baseName);
            ps.setString(2, suffix);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(readProfile(rs));
            }
        } catch (final SQLException e) {
            logger.severe("findProfileByDisplayName failed: " + e.getMessage());
        }
        return Optional.empty();
    }

    public void deleteProfile(final UUID profileUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM profiles WHERE profile_uuid = ?")) {
            ps.setString(1, profileUuid.toString());
            ps.executeUpdate();
        } catch (final SQLException e) {
            logger.severe("deleteProfile failed: " + e.getMessage());
        }
    }

    public void updateProfileSuffix(final UUID profileUuid, final String newSuffix) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE profiles SET suffix = ? WHERE profile_uuid = ?")) {
            ps.setString(1, newSuffix.toLowerCase());
            ps.setString(2, profileUuid.toString());
            ps.executeUpdate();
        } catch (final SQLException e) {
            logger.severe("updateProfileSuffix failed: " + e.getMessage());
        }
    }

    public void touchProfileMounted(final UUID profileUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE profiles SET last_mounted_at = ? WHERE profile_uuid = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, profileUuid.toString());
            ps.executeUpdate();
        } catch (final SQLException e) {
            logger.severe("touchProfileMounted failed: " + e.getMessage());
        }
    }

    private ProfileRecord readProfile(final ResultSet rs) throws SQLException {
        return new ProfileRecord(
            UUID.fromString(rs.getString("profile_uuid")),
            UUID.fromString(rs.getString("owner_uuid")),
            rs.getString("suffix"),
            rs.getLong("created_at"),
            rs.getLong("last_mounted_at")
        );
    }

    // ════════════════════════════════════════════════════════════════
    //  SESSIONS
    // ════════════════════════════════════════════════════════════════

    /**
     * All persistent session state for one player.
     * Mode is derived: shadowTarget != null → SHADOW;
     * activeProfileUuid != null → LOCAL; else → MAIN.
     */
    public record SessionRecord(
        UUID baseUuid,
        UUID activeProfileUuid,
        String shadowTarget,
        UUID shadowTargetUuid,
        boolean shadowSelf,
        String shadowTextureValue,
        String shadowTextureSig,
        boolean conflictFrozen,
        String conflictTarget
    ) {
        public boolean isShadowing() { return shadowTarget != null; }
        public boolean isOnProfile() { return activeProfileUuid != null && !isShadowing(); }
        public boolean isMain() { return activeProfileUuid == null && !isShadowing(); }
    }

    /** Loads the persistent session for a player. Returns empty if no session exists. */
    public Optional<SessionRecord> loadSession(final UUID baseUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM sessions WHERE base_uuid = ?")) {
            ps.setString(1, baseUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(readSession(rs));
            }
        } catch (final SQLException e) {
            logger.severe("loadSession failed: " + e.getMessage());
        }
        return Optional.empty();
    }

    /** Loads all sessions. Used on startup to rebuild shadow reservations. */
    public List<SessionRecord> loadAllSessions() {
        final List<SessionRecord> list = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM sessions")) {
            while (rs.next()) list.add(readSession(rs));
        } catch (final SQLException e) {
            logger.severe("loadAllSessions failed: " + e.getMessage());
        }
        return list;
    }

    /** Sets the active profile. Pass null to return to main. */
    public void setActiveProfile(final UUID baseUuid, final UUID profileUuid) {
        ensureSession(baseUuid);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sessions SET active_profile_uuid = ? WHERE base_uuid = ?")) {
            ps.setString(1, profileUuid == null ? null : profileUuid.toString());
            ps.setString(2, baseUuid.toString());
            ps.executeUpdate();
        } catch (final SQLException e) {
            logger.severe("setActiveProfile failed: " + e.getMessage());
        }
    }

    /** Starts a shadow session. targetUuid is the UUID of the .dat file being operated on. */
    public void setShadow(final UUID baseUuid, final String target, final UUID targetUuid,
                          final boolean selfShadow, final String textureValue, final String textureSig) {
        ensureSession(baseUuid);
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE sessions SET shadow_target = ?, shadow_target_uuid = ?, shadow_self = ?,
                shadow_texture_value = ?, shadow_texture_sig = ? WHERE base_uuid = ?
            """)) {
            ps.setString(1, target);
            ps.setString(2, targetUuid == null ? null : targetUuid.toString());
            ps.setInt(3, selfShadow ? 1 : 0);
            ps.setString(4, textureValue);
            ps.setString(5, textureSig);
            ps.setString(6, baseUuid.toString());
            ps.executeUpdate();
        } catch (final SQLException e) {
            logger.severe("setShadow failed: " + e.getMessage());
        }
    }

    /** Clears shadow state without affecting the active profile. */
    public void clearShadow(final UUID baseUuid) {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE sessions SET shadow_target = NULL, shadow_target_uuid = NULL, shadow_self = 0,
                shadow_texture_value = NULL, shadow_texture_sig = NULL WHERE base_uuid = ?
            """)) {
            ps.setString(1, baseUuid.toString());
            ps.executeUpdate();
        } catch (final SQLException e) {
            logger.severe("clearShadow failed: " + e.getMessage());
        }
    }

    public void setConflict(final UUID baseUuid, final String conflictTarget) {
        ensureSession(baseUuid);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sessions SET conflict_frozen = 1, conflict_target = ? WHERE base_uuid = ?")) {
            ps.setString(1, conflictTarget);
            ps.setString(2, baseUuid.toString());
            ps.executeUpdate();
        } catch (final SQLException e) {
            logger.severe("setConflict failed: " + e.getMessage());
        }
    }

    public void clearConflict(final UUID baseUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sessions SET conflict_frozen = 0, conflict_target = NULL WHERE base_uuid = ?")) {
            ps.setString(1, baseUuid.toString());
            ps.executeUpdate();
        } catch (final SQLException e) {
            logger.severe("clearConflict failed: " + e.getMessage());
        }
    }

    /** Ensures a session row exists for this player. Idempotent. */
    private void ensureSession(final UUID baseUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO sessions (base_uuid) VALUES (?)")) {
            ps.setString(1, baseUuid.toString());
            ps.executeUpdate();
        } catch (final SQLException e) {
            logger.severe("ensureSession failed: " + e.getMessage());
        }
    }

    private SessionRecord readSession(final ResultSet rs) throws SQLException {
        final String profileUuidStr = rs.getString("active_profile_uuid");
        final String shadowTargetUuidStr = rs.getString("shadow_target_uuid");
        return new SessionRecord(
            UUID.fromString(rs.getString("base_uuid")),
            profileUuidStr == null ? null : UUID.fromString(profileUuidStr),
            rs.getString("shadow_target"),
            shadowTargetUuidStr == null ? null : UUID.fromString(shadowTargetUuidStr),
            rs.getInt("shadow_self") != 0,
            rs.getString("shadow_texture_value"),
            rs.getString("shadow_texture_sig"),
            rs.getInt("conflict_frozen") != 0,
            rs.getString("conflict_target")
        );
    }

    // ════════════════════════════════════════════════════════════════
    //  KNOWN NAMES (Mojang resolution cache)
    // ════════════════════════════════════════════════════════════════

    public record NameRecord(String name, UUID mojangUuid, boolean isReal, long resolvedAt) {}

    public void cacheNameResolution(final String name, final UUID mojangUuid, final boolean isReal) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO known_names (name_lower, mojang_uuid, is_real, resolved_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, name.toLowerCase());
            ps.setString(2, mojangUuid == null ? null : mojangUuid.toString());
            ps.setInt(3, isReal ? 1 : 0);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (final SQLException e) {
            logger.severe("cacheNameResolution failed: " + e.getMessage());
        }
    }

    public Optional<NameRecord> lookupName(final String name) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM known_names WHERE name_lower = ?")) {
            ps.setString(1, name.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    final String uuidStr = rs.getString("mojang_uuid");
                    return Optional.of(new NameRecord(
                        rs.getString("name_lower"),
                        uuidStr == null ? null : UUID.fromString(uuidStr),
                        rs.getInt("is_real") != 0,
                        rs.getLong("resolved_at")
                    ));
                }
            }
        } catch (final SQLException e) {
            logger.severe("lookupName failed: " + e.getMessage());
        }
        return Optional.empty();
    }

    // ════════════════════════════════════════════════════════════════
    //  TARGET RESOLUTION (for shadows)
    // ════════════════════════════════════════════════════════════════

    /**
     * Resolves a shadow target name to the UUID of the .dat file to operate on.
     * Resolution chain:
     * 1. Known player (players table) → their base_uuid
     * 2. Local profile display name → the profile_uuid
     * 3. Mojang name cache (known_names) → their mojang_uuid
     * 4. Not found → empty
     */
    public Optional<UUID> resolveTargetUuid(final String targetName) {
        // 1. Known player by account name
        final Optional<UUID> playerUuid = resolvePlayerUuid(targetName);
        if (playerUuid.isPresent()) return playerUuid;
        // 2. Local profile by display name
        final Optional<ProfileRecord> profile = findProfileByDisplayName(targetName);
        if (profile.isPresent()) return Optional.of(profile.get().profileUuid());
        // 3. Mojang name cache
        final Optional<NameRecord> nameRecord = lookupName(targetName);
        if (nameRecord.isPresent() && nameRecord.get().isReal() && nameRecord.get().mojangUuid() != null) {
            return Optional.of(nameRecord.get().mojangUuid());
        }
        return Optional.empty();
    }

    // ════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ════════════════════════════════════════════════════════════════

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (final SQLException e) {
            logger.severe("Failed to close database: " + e.getMessage());
        }
    }
}
