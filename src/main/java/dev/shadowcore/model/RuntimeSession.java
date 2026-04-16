package dev.shadowcore.model;

import java.util.UUID;

/**
 * Transient runtime state for one connected player.
 * <p>
 * Persistent state (active profile, shadow target, conflict) lives in the database.
 * This class only tracks things that are meaningless after a restart:
 * <ul>
 *   <li>Phase — where in a multi-step operation (CHECKING_NAME, etc.)</li>
 *   <li>Pending — which profile/target is being resolved</li>
 *   <li>Connected — is the player currently online</li>
 *   <li>Applied keys — what the reconciler last applied (to detect drift)</li>
 * </ul>
 */
public final class RuntimeSession {
    public enum Phase { IDLE, CHECKING_NAME, MOUNTED }

    private final UUID baseUuid;
    private Phase phase = Phase.IDLE;
    private boolean connected;
    private String pendingProfileUuid;
    private String pendingSuffix;
    private String pendingTargetKey;

    // Reconciler tracking — what was last applied
    private String appliedRuntimeKey;
    private String appliedIdentityKey;
    private String appliedSpectatorKey;

    public RuntimeSession(final UUID baseUuid) {
        this.baseUuid = baseUuid;
    }

    public UUID baseUuid() { return baseUuid; }
    public Phase phase() { return phase; }
    public void phase(final Phase phase) { this.phase = phase; }
    public boolean connected() { return connected; }
    public void connected(final boolean connected) { this.connected = connected; }

    public String pendingProfileUuid() { return pendingProfileUuid; }
    public void pendingProfileUuid(final String v) { this.pendingProfileUuid = v; }
    public String pendingSuffix() { return pendingSuffix; }
    public void pendingSuffix(final String v) { this.pendingSuffix = v; }
    public String pendingTargetKey() { return pendingTargetKey; }
    public void pendingTargetKey(final String v) { this.pendingTargetKey = v; }

    public String appliedRuntimeKey() { return appliedRuntimeKey; }
    public void appliedRuntimeKey(final String v) { this.appliedRuntimeKey = v; }
    public String appliedIdentityKey() { return appliedIdentityKey; }
    public void appliedIdentityKey(final String v) { this.appliedIdentityKey = v; }
    public String appliedSpectatorKey() { return appliedSpectatorKey; }
    public void appliedSpectatorKey(final String v) { this.appliedSpectatorKey = v; }

    public boolean isIdle() { return phase == Phase.IDLE || phase == Phase.MOUNTED; }

    public void clearPending() {
        pendingProfileUuid = null;
        pendingSuffix = null;
        pendingTargetKey = null;
    }

    public void resetApplied() {
        appliedRuntimeKey = null;
        appliedIdentityKey = null;
        appliedSpectatorKey = null;
    }
}
