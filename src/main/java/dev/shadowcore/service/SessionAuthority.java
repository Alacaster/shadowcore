package dev.shadowcore.service;

import dev.shadowcore.ShadowCorePlugin;
import dev.shadowcore.engine.EngineEvent;
import dev.shadowcore.engine.EventEngine;
import dev.shadowcore.engine.ResponseHandle;
import dev.shadowcore.model.PlayerSnapshot;
import dev.shadowcore.model.ProfileIdentity;
import dev.shadowcore.model.RuntimeSession;
import dev.shadowcore.protocol.ShadowProtocolLayer;
import dev.shadowcore.protocol.SkinCache;
import dev.shadowcore.store.Database;
import dev.shadowcore.store.Database.ProfileRecord;
import dev.shadowcore.store.Database.SessionRecord;
import dev.shadowcore.util.Naming;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Authoritative coordinator for all session state transitions.
 * <p>
 * Persistent state lives in the {@link Database}. This class holds only transient
 * runtime state in {@link RuntimeSession} objects (phase, pending ops, connected flag).
 * On restart, runtime state resets to defaults — persistent state is read from the DB.
 */
public final class SessionAuthority {
    private final ShadowCorePlugin plugin;
    private final Database db;
    private final VanillaDataBridge dataBridge;
    private final IdentityRouter router;
    private final SkinCache skinCache;
    private final EnvironmentService env;
    private final PresentationService presentation;
    private final RealAccountNameResolver nameResolver;
    private final ShadowProtocolLayer protocol; // nullable
    private final Map<UUID, RuntimeSession> runtimes = new HashMap<>();
    private final Map<String, UUID> shadowReservations = new HashMap<>();
    private EventEngine engine;

    public SessionAuthority(
        final ShadowCorePlugin plugin, final Database db, final VanillaDataBridge dataBridge,
        final IdentityRouter router, final SkinCache skinCache, final EnvironmentService env,
        final PresentationService presentation, final RealAccountNameResolver nameResolver,
        final ShadowProtocolLayer protocol
    ) {
        this.plugin = plugin; this.db = db; this.dataBridge = dataBridge;
        this.router = router; this.skinCache = skinCache; this.env = env;
        this.presentation = presentation; this.nameResolver = nameResolver;
        this.protocol = protocol;
    }

    public void attachEngine(final EventEngine engine) { this.engine = engine; }

    public void bootstrap() {
        runtimes.clear();
        shadowReservations.clear();
        router.bootstrap(db);
        // Rebuild shadow reservations from persisted sessions
        for (final SessionRecord s : db.loadAllSessions()) {
            if (s.shadowTarget() != null) {
                shadowReservations.put(Naming.normalizeKey(s.shadowTarget()), s.baseUuid());
            }
        }
    }

    public void shutdown() {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            final UUID uuid = p.getUniqueId();
            final SessionRecord session = db.loadSession(uuid).orElse(null);
            if (session != null && session.isShadowing() && !session.shadowSelf() && session.shadowTargetUuid() != null) {
                dataBridge.saveShadowTarget(p, session.shadowTargetUuid(), router.get(uuid));
            } else {
                dataBridge.saveToProfile(p, router.get(uuid));
            }
            if (protocol != null) protocol.disengage(p);
            presentation.forgetPlayer(uuid);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  EVENT DISPATCH
    // ════════════════════════════════════════════════════════════════

    public void handle(final EngineEvent event) {
        try {
            switch (event) {
                case EngineEvent.ProfileCreate e -> onProfileCreate(e);
                case EngineEvent.ProfileSwitch e -> onProfileSwitch(e);
                case EngineEvent.ProfileDelete e -> onProfileDelete(e);
                case EngineEvent.ProfileReturnToMain e -> onReturnToMain(e);
                case EngineEvent.ProfileSetLimit e -> onSetLimit(e);
                case EngineEvent.ProfileAdminDelete e -> onAdminDelete(e);
                case EngineEvent.ProfileRename e -> onProfileRename(e);
                case EngineEvent.ShadowMount e -> onShadowMount(e);
                case EngineEvent.ShadowLogout e -> onShadowLogout(e);
                case EngineEvent.NameResolved e -> onNameResolved(e);
                case EngineEvent.ShadowNameResolved e -> onShadowNameResolved(e);
                case EngineEvent.PlayerJoined e -> onPlayerJoined(e);
                case EngineEvent.PlayerQuit e -> onPlayerQuit(e.uuid(), e.name());
                case EngineEvent.PlayerKicked e -> onPlayerQuit(e.uuid(), e.name());
            }
        } catch (final Exception ex) {
            plugin.getLogger().severe("Event failure: " + event + ": " + ex.getMessage());
            ex.printStackTrace();
            // Reset transient state to prevent stuck sessions
            final UUID failedActor = extractActor(event);
            if (failedActor != null) {
                final RuntimeSession rt = runtimes.get(failedActor);
                if (rt != null && !rt.isIdle()) { rt.phase(RuntimeSession.Phase.IDLE); rt.clearPending(); }
            }
            if (event instanceof EngineEvent.ProfileCreate e) e.response().reply("<red>Internal error.</red>");
            else if (event instanceof EngineEvent.ProfileSwitch e) e.response().reply("<red>Internal error.</red>");
            else if (event instanceof EngineEvent.ShadowMount e) e.response().reply("<red>Internal error.</red>");
            else if (event instanceof EngineEvent.NameResolved e) e.response().reply("<red>Internal error.</red>");
            else if (event instanceof EngineEvent.ShadowNameResolved e) e.response().reply("<red>Internal error.</red>");
        }
    }

    /** Reconcile all connected players after event drain. */
    public void reconcileAll() {
        for (final RuntimeSession rt : runtimes.values()) {
            if (!rt.connected()) continue;
            final Player player = Bukkit.getPlayer(rt.baseUuid());
            if (player == null) continue;
            try { reconcile(player, rt); } catch (final Exception ex) {
                plugin.getLogger().severe("Reconcile failure for " + rt.baseUuid() + ": " + ex.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  PROFILE HANDLERS
    // ════════════════════════════════════════════════════════════════

    private void onProfileCreate(final EngineEvent.ProfileCreate e) {
        final Player player = online(e.actor(), e.response()); if (player == null) return;
        final String suffix = Naming.normalizeSuffix(e.suffix());
        if (!Naming.isValidSuffix(suffix)) { e.response().reply("<red>Invalid suffix. Use 1-" + Naming.MAX_SUFFIX_LENGTH + " chars: a-z, 0-9, _ or -.</red>"); return; }
        if (db.findProfileBySuffix(e.actor(), suffix).isPresent()) { e.response().reply("<red>Suffix already in use.</red>"); return; }
        final String accountName = accountName(e.actor(), e.actorName());
        final String displayName = Naming.fullLocalName(accountName, suffix);
        if (db.findProfileByDisplayName(displayName).isPresent()) { e.response().reply("<red>That identity is reserved.</red>"); return; }
        final int limit = db.getMaxProfiles(e.actor(), plugin.getConfig().getInt("normal-profile-limit", 2));
        if (!player.hasPermission("shadowcore.opbypass") && db.listProfiles(e.actor()).size() >= limit) { e.response().reply("<red>Profile limit reached.</red>"); return; }
        final UUID profileUuid = db.createProfile(e.actor(), suffix);
        if (profileUuid == null) { e.response().reply("<red>Failed to create profile.</red>"); return; }
        final ProfileIdentity identity = ProfileIdentity.local(e.actor(), profileUuid, suffix);
        dataBridge.createProfileData(player, identity);
        e.response().reply("<green>Created</green> <yellow>" + displayName + "</yellow><green>.</green>");
    }

    private void onProfileSwitch(final EngineEvent.ProfileSwitch e) {
        final Player player = online(e.actor(), e.response()); if (player == null) return;
        final RuntimeSession rt = runtime(e.actor());
        if (!rt.isIdle()) { e.response().reply("<red>Session busy.</red>"); return; }
        final SessionRecord session = db.loadSession(e.actor()).orElse(null);
        if (session != null && session.isShadowing()) { e.response().reply("<red>Unshadow first.</red>"); return; }
        final Optional<ProfileRecord> opt = db.findProfileBySuffix(e.actor(), e.suffix());
        if (opt.isEmpty()) { e.response().reply("<red>No profile with that suffix.</red>"); return; }
        final ProfileRecord profile = opt.get();
        rt.phase(RuntimeSession.Phase.CHECKING_NAME);
        rt.pendingProfileUuid(profile.profileUuid().toString());
        rt.pendingSuffix(profile.suffix());
        nameResolver.resolveForProfileSwitch(engine, e.actor(), e.actorName(), profile.profileUuid(), profile.suffix(), e.response());
        e.response().reply("<gray>Checking name safety...</gray>");
    }

    private void onNameResolved(final EngineEvent.NameResolved e) {
        final Player player = online(e.actor(), e.response()); if (player == null) return;
        final RuntimeSession rt = runtime(e.actor());
        if (!e.profileUuid().toString().equals(rt.pendingProfileUuid())) return;
        final String accountName = accountName(e.actor(), e.actorName());
        final String displayName = Naming.fullLocalName(accountName, e.suffix());
        if (e.isReal() && !displayName.equalsIgnoreCase(accountName)) {
            rt.phase(RuntimeSession.Phase.IDLE); rt.clearPending();
            e.response().reply("<red>Name matches a real account. Use a different suffix.</red>"); return;
        }
        // Save current profile, load new one
        final ProfileIdentity currentId = router.get(e.actor());
        final ProfileIdentity newId = ProfileIdentity.local(e.actor(), e.profileUuid(), e.suffix());
        dataBridge.switchProfile(player, currentId, newId);
        router.set(e.actor(), newId);
        db.setActiveProfile(e.actor(), e.profileUuid());
        db.touchProfileMounted(e.profileUuid());
        presentation.applyLocalIdentity(player, displayName, e.suffix());
        rt.phase(RuntimeSession.Phase.MOUNTED);
        rt.clearPending();
        rt.appliedRuntimeKey(null); // force reconciler to re-check
        rt.appliedIdentityKey(null);
        e.response().reply("<green>Mounted</green> <yellow>" + displayName + "</yellow><green>.</green>");
    }

    private void onProfileDelete(final EngineEvent.ProfileDelete e) {
        final Player player = online(e.actor(), e.response()); if (player == null) return;
        final Optional<ProfileRecord> opt = db.findProfileBySuffix(e.actor(), e.suffix());
        if (opt.isEmpty()) { e.response().reply("<red>No profile with that suffix.</red>"); return; }
        final ProfileRecord profile = opt.get();
        // Can't delete the active profile
        final SessionRecord session = db.loadSession(e.actor()).orElse(null);
        if (session != null && profile.profileUuid().equals(session.activeProfileUuid())) {
            e.response().reply("<red>Can't delete the active profile. Switch first.</red>"); return;
        }
        db.deleteProfile(profile.profileUuid());
        dataBridge.deleteProfileData(profile.profileUuid());
        e.response().reply("<green>Deleted</green> <yellow>" + profile.displayName(accountName(e.actor(), player.getName())) + "</yellow><green>.</green>");
    }

    private void onProfileRename(final EngineEvent.ProfileRename e) {
        final Player player = online(e.actor(), e.response()); if (player == null) return;
        final Optional<ProfileRecord> opt = db.findProfileBySuffix(e.actor(), e.oldSuffix());
        if (opt.isEmpty()) { e.response().reply("<red>No profile with that suffix.</red>"); return; }
        final String newSuffix = Naming.normalizeSuffix(e.newSuffix());
        if (!Naming.isValidSuffix(newSuffix)) { e.response().reply("<red>Invalid suffix.</red>"); return; }
        if (db.findProfileBySuffix(e.actor(), newSuffix).isPresent()) { e.response().reply("<red>Suffix already in use.</red>"); return; }
        db.updateProfileSuffix(opt.get().profileUuid(), newSuffix);
        e.response().reply("<green>Renamed to suffix</green> <yellow>" + newSuffix + "</yellow><green>.</green>");
    }

    private void onReturnToMain(final EngineEvent.ProfileReturnToMain e) {
        final Player player = online(e.actor(), e.response()); if (player == null) return;
        final SessionRecord session = db.loadSession(e.actor()).orElse(null);
        if (session == null || session.isMain()) { e.response().reply("<yellow>Already on main.</yellow>"); return; }
        if (session.isShadowing()) {
            doShadowLogout(player, e.actor(), false);
        }
        final ProfileIdentity currentId = router.get(e.actor());
        final ProfileIdentity mainId = ProfileIdentity.main(e.actor());
        dataBridge.switchProfile(player, currentId, mainId);
        router.set(e.actor(), mainId);
        db.setActiveProfile(e.actor(), null);
        presentation.applyRealIdentity(player);
        final RuntimeSession rt = runtime(e.actor());
        rt.appliedRuntimeKey(null);
        rt.appliedIdentityKey(null);
        e.response().reply("<green>Returned to main profile.</green>");
    }

    private void onSetLimit(final EngineEvent.ProfileSetLimit e) {
        if (!isAdmin(e.admin())) { e.response().reply("<red>No permission.</red>"); return; }
        db.setMaxProfiles(e.target(), e.limit());
        final int count = db.listProfiles(e.target()).size();
        if (count > e.limit()) {
            e.response().reply("<green>Limit set to</green> <yellow>" + e.limit() + "</yellow><green>.</green> <red>Warning: player has " + count + " profiles.</red>");
        } else {
            e.response().reply("<green>Limit set to</green> <yellow>" + e.limit() + "</yellow><green>.</green>");
        }
    }

    private void onAdminDelete(final EngineEvent.ProfileAdminDelete e) {
        if (!isAdmin(e.admin())) { e.response().reply("<red>No permission.</red>"); return; }
        final Optional<ProfileRecord> opt = db.findProfileBySuffix(e.owner(), e.suffix());
        if (opt.isEmpty()) { e.response().reply("<red>No profile with that suffix.</red>"); return; }
        final ProfileRecord profile = opt.get();
        // Check if active
        if (router.isProfileActive(profile.profileUuid())) {
            e.response().reply("<red>Profile is currently active. Have the player switch first.</red>"); return;
        }
        db.deleteProfile(profile.profileUuid());
        dataBridge.deleteProfileData(profile.profileUuid());
        e.response().reply("<green>Admin deleted profile</green> <yellow>" + profile.suffix() + "</yellow><green>.</green>");
    }

    // ════════════════════════════════════════════════════════════════
    //  SHADOW HANDLERS
    // ════════════════════════════════════════════════════════════════

    private void onShadowMount(final EngineEvent.ShadowMount e) {
        final Player actor = online(e.actor(), e.response()); if (actor == null) return;
        if (!actor.hasPermission("shadowcore.admin")) { e.response().reply("<red>No permission.</red>"); return; }
        if (protocol == null) { e.response().reply("<red>ProtocolLib required.</red>"); return; }
        final RuntimeSession rt = runtime(e.actor());
        final String targetKey = Naming.normalizeKey(e.targetName());
        // Re-mount check
        final SessionRecord session = db.loadSession(e.actor()).orElse(null);
        if (session != null && session.isShadowing() && targetKey.equals(Naming.normalizeKey(session.shadowTarget()))) {
            e.response().reply("<yellow>Already shadowing that target.</yellow>"); return;
        }
        if (!rt.isIdle()) { e.response().reply("<red>Session busy.</red>"); return; }
        final UUID existing = shadowReservations.get(targetKey);
        if (existing != null && !existing.equals(e.actor())) { e.response().reply("<red>Target reserved by another shadow.</red>"); return; }
        final String actorBase = accountName(e.actor(), e.actorName());
        final boolean selfTarget = targetKey.equalsIgnoreCase(Naming.normalizeKey(actorBase));
        final Player onlineTarget = findOnlineByName(e.targetName());
        if (onlineTarget != null && !onlineTarget.getUniqueId().equals(e.actor()) && !selfTarget) {
            e.response().reply("<red>Target is online.</red>"); return;
        }
        rt.phase(RuntimeSession.Phase.CHECKING_NAME);
        rt.pendingTargetKey(targetKey);
        nameResolver.resolveForShadowMount(engine, e.actor(), actorBase, e.targetName(), e.response());
        e.response().reply("<gray>Resolving target identity...</gray>");
    }

    private void onShadowNameResolved(final EngineEvent.ShadowNameResolved e) {
        final Player actor = online(e.actor(), e.response()); if (actor == null) return;
        final RuntimeSession rt = runtime(e.actor());
        if (!e.targetName().equalsIgnoreCase(rt.pendingTargetKey())) return;
        final String targetKey = Naming.normalizeKey(e.targetName());
        final boolean selfTarget = targetKey.equalsIgnoreCase(Naming.normalizeKey(accountName(e.actor(), e.actorName())));

        // If already shadowing something else, write back and logout first
        final SessionRecord current = db.loadSession(e.actor()).orElse(null);
        if (current != null && current.isShadowing()) {
            doShadowLogout(actor, e.actor(), false);
        }

        // Save admin's current profile data before loading target data
        dataBridge.saveToProfile(actor, router.get(e.actor()));

        // Backup admin's base .dat before shadow overwrites it
        dataBridge.backupAdminDat(e.actor());

        // Resolve the target to a .dat file UUID
        UUID targetUuid = null;
        if (!selfTarget) {
            // Resolution chain: known player → local profile → Mojang cache
            targetUuid = db.resolveTargetUuid(e.targetName()).orElse(null);
            if (targetUuid == null && e.isReal()) {
                // Real Mojang account that we haven't seen before — use their Mojang UUID
                final var nameRecord = db.lookupName(e.targetName());
                if (nameRecord.isPresent() && nameRecord.get().mojangUuid() != null) {
                    targetUuid = nameRecord.get().mojangUuid();
                }
            }

            if (targetUuid != null) {
                // Load target's .dat onto the admin
                if (!dataBridge.hasData(targetUuid)) {
                    // Target has never joined — create fresh data for them
                    dataBridge.createFreshTargetData(actor, targetUuid);
                }
                dataBridge.loadShadowTarget(actor, targetUuid);
            }
            // If targetUuid is still null, admin keeps their own state — shadow is visual-only
        }

        // Get skin data
        final Optional<SkinCache.CachedSkin> skin = skinCache.get(e.targetName());
        final String texValue = skin.map(SkinCache.CachedSkin::textureValue).orElse(null);
        final String texSig = skin.map(SkinCache.CachedSkin::textureSignature).orElse(null);

        // Persist shadow state (including target UUID for write-back on logout)
        shadowReservations.put(targetKey, e.actor());
        db.setShadow(e.actor(), e.targetName(), targetUuid, selfTarget, texValue, texSig);

        // Engage visual puppet
        final boolean visual = engageProtocol(actor, e.targetName(), selfTarget, texValue, texSig);

        rt.phase(RuntimeSession.Phase.MOUNTED);
        rt.clearPending();
        rt.appliedRuntimeKey(null); // force reconciler to re-check

        if (selfTarget) {
            e.response().reply(visual
                ? "<green>Self-shadow engaged. You are invisible. Use</green> <yellow>/shadow logout</yellow><green>.</green>"
                : "<green>Self-shadow data loaded, visual failed. Use</green> <yellow>/shadow logout</yellow><green>.</green>");
        } else {
            final String dataStatus = targetUuid != null ? "(data loaded)" : "(visual only)";
            e.response().reply("<green>Shadow mounted:</green> <yellow>" + e.targetName() + "</yellow> "
                + (visual ? "<gray>(disguise active)</gray>" : "<red>(visual failed)</red>")
                + " <gray>" + dataStatus + ". Use</gray> <yellow>/shadow logout</yellow><green>.</green>");
        }
    }

    private void onShadowLogout(final EngineEvent.ShadowLogout e) {
        final Player actor = online(e.actor(), e.response()); if (actor == null) return;
        final SessionRecord session = db.loadSession(e.actor()).orElse(null);
        if (session == null || !session.isShadowing()) { e.response().reply("<yellow>Not shadowing.</yellow>"); return; }
        doShadowLogout(actor, e.actor(), e.resetLocation());
        e.response().reply(e.resetLocation()
            ? "<green>Shadow ended. Target location reset.</green>"
            : "<green>Shadow ended.</green>");
    }

    private void doShadowLogout(final Player actor, final UUID baseUuid, final boolean resetLocation) {
        final SessionRecord session = db.loadSession(baseUuid).orElse(null);
        if (session == null) return;
        final ProfileIdentity adminId = router.get(baseUuid);

        // Write back shadow changes to the target's .dat file
        if (!session.shadowSelf() && session.shadowTargetUuid() != null) {
            dataBridge.saveShadowTarget(actor, session.shadowTargetUuid(), adminId);
        }

        // Release shadow reservation
        if (session.shadowTarget() != null) {
            shadowReservations.remove(Naming.normalizeKey(session.shadowTarget()));
        }

        // Disengage visual puppet
        if (protocol != null) protocol.disengage(actor);

        // Clear shadow from database
        db.clearShadow(baseUuid);

        // Reload the admin's own profile data (the state they were in before shadowing)
        dataBridge.loadFromProfile(actor, adminId);

        // Clean up shadow backup now that admin's .dat is restored
        dataBridge.clearShadowBackup(baseUuid);

        // Force reconciler to re-apply identity
        final RuntimeSession rt = runtime(baseUuid);
        rt.appliedRuntimeKey(null);
        rt.appliedIdentityKey(null);
    }

    // ════════════════════════════════════════════════════════════════
    //  PLAYER LIFECYCLE
    // ════════════════════════════════════════════════════════════════

    private void onPlayerJoined(final EngineEvent.PlayerJoined e) {
        final Player player = Bukkit.getPlayer(e.uuid()); if (player == null) return;
        final RuntimeSession rt = runtime(e.uuid());
        rt.connected(true);
        rt.phase(RuntimeSession.Phase.MOUNTED);
        db.upsertPlayer(e.uuid(), e.name());
        // Restore persistent session state
        final SessionRecord session = db.loadSession(e.uuid()).orElse(null);
        if (session != null && session.activeProfileUuid() != null) {
            db.findProfileByUuid(session.activeProfileUuid()).ifPresent(profile -> {
                final ProfileIdentity id = ProfileIdentity.local(e.uuid(), profile.profileUuid(), profile.suffix());
                router.set(e.uuid(), id);
                dataBridge.loadFromProfile(player, id);
                presentation.applyLocalIdentity(player, profile.displayName(e.name()), profile.suffix());
            });
        }
        if (session != null && session.isShadowing()) {
            shadowReservations.put(Naming.normalizeKey(session.shadowTarget()), e.uuid());
            // Load shadow target data onto admin (overwrites whatever was just loaded)
            if (!session.shadowSelf() && session.shadowTargetUuid() != null) {
                if (dataBridge.hasData(session.shadowTargetUuid())) {
                    dataBridge.loadShadowTarget(player, session.shadowTargetUuid());
                }
            }
            engageProtocol(player, session.shadowTarget(), session.shadowSelf(),
                session.shadowTextureValue(), session.shadowTextureSig());
        }
        if (session != null && session.conflictFrozen()) {
            // Check if conflict is still valid
            if (session.conflictTarget() == null || !isIdentityActive(session.conflictTarget(), e.uuid())) {
                db.clearConflict(e.uuid());
            }
        }
        if (protocol != null) protocol.onObserverJoined(player);
        // Delayed reconcile to fix tab names after protocol's showPlayer
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) reconcileAll();
        }, 10L);
    }

    private void onPlayerQuit(final UUID uuid, final String name) {
        final Player player = Bukkit.getPlayer(uuid); if (player == null) return;
        // Check if shadowing — need to save to target's .dat, not admin's profile
        final SessionRecord session = db.loadSession(uuid).orElse(null);
        if (session != null && session.isShadowing() && !session.shadowSelf() && session.shadowTargetUuid() != null) {
            dataBridge.saveShadowTarget(player, session.shadowTargetUuid(), router.get(uuid));
            // Do NOT clear backup — shadow persists across logout, backup needed on rejoin
        } else {
            final ProfileIdentity id = router.get(uuid);
            dataBridge.saveToProfile(player, id);
        }
        if (protocol != null && protocol.hasOverride(uuid)) protocol.disengage(player);
        presentation.forgetPlayer(uuid);
        final RuntimeSession rt = runtime(uuid);
        rt.connected(false);
        rt.phase(RuntimeSession.Phase.IDLE);
        rt.resetApplied();
        router.clear(uuid);
    }

    // ════════════════════════════════════════════════════════════════
    //  RECONCILER
    // ════════════════════════════════════════════════════════════════

    private void reconcile(final Player player, final RuntimeSession rt) {
        final UUID baseUuid = player.getUniqueId();
        final SessionRecord session = db.loadSession(baseUuid).orElse(null);
        final ProfileIdentity id = router.get(baseUuid);
        // Runtime reconciliation — identity (tab name, display name)
        final String accountName = accountName(baseUuid, player.getName());
        final String desiredIdKey;
        if (session != null && session.activeProfileUuid() != null) {
            final Optional<ProfileRecord> prof = db.findProfileByUuid(session.activeProfileUuid());
            final String display = prof.map(p -> p.displayName(accountName)).orElse(accountName);
            final String suffix = prof.map(ProfileRecord::suffix).orElse("");
            desiredIdKey = "local:" + display;
            if (!desiredIdKey.equals(rt.appliedIdentityKey())) {
                presentation.applyLocalIdentity(player, display, suffix);
                rt.appliedIdentityKey(desiredIdKey);
            }
        } else {
            desiredIdKey = "main:" + accountName;
            if (!desiredIdKey.equals(rt.appliedIdentityKey())) {
                presentation.applyRealIdentity(player);
                rt.appliedIdentityKey(desiredIdKey);
            }
        }
        // Spectator conflict
        final boolean shouldBeSpectator = session != null && session.conflictFrozen();
        final String desiredSpec = shouldBeSpectator ? "spectator" : "normal";
        if (!desiredSpec.equals(rt.appliedSpectatorKey())) {
            if (shouldBeSpectator) env.forceSpectator(player);
            rt.appliedSpectatorKey(desiredSpec);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  PROTOCOL BRIDGE
    // ════════════════════════════════════════════════════════════════

    private boolean engageProtocol(final Player admin, final String targetName, final boolean selfShadow,
                                   final String texValue, final String texSig) {
        if (protocol == null) return false;
        try {
            if (selfShadow) {
                protocol.engageBlank(admin);
            } else if (texValue != null && !texValue.isBlank()) {
                protocol.engageWithSkin(admin, targetName, texValue, texSig);
            } else {
                protocol.engageWithSkin(admin, targetName, null, null);
            }
            return protocol.hasOverride(admin.getUniqueId());
        } catch (final Exception ex) {
            plugin.getLogger().warning("Protocol engage failed: " + ex.getMessage());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  QUERY API (used by commands)
    // ════════════════════════════════════════════════════════════════

    public List<ProfileRecord> listProfiles(final UUID ownerUuid) { return db.listProfiles(ownerUuid); }

    public Optional<UUID> resolveKnownOwnerUuid(final String playerName) {
        final Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) return Optional.of(online.getUniqueId());
        return db.resolvePlayerUuid(playerName);
    }

    public String describeStatus(final UUID baseUuid, final String name) {
        final SessionRecord session = db.loadSession(baseUuid).orElse(null);
        if (session == null || session.isMain()) return "<gray>State:</gray> <yellow>main</yellow>";
        if (session.isShadowing()) return "<gray>Shadowing:</gray> <yellow>" + session.shadowTarget() + "</yellow>";
        return db.findProfileByUuid(session.activeProfileUuid())
            .map(p -> "<gray>Profile:</gray> <yellow>" + p.displayName(accountName(baseUuid, name)) + "</yellow>")
            .orElse("<gray>State:</gray> <yellow>unknown</yellow>");
    }

    public String describeShadowStatus(final UUID baseUuid, final String name) {
        final SessionRecord session = db.loadSession(baseUuid).orElse(null);
        if (session == null || !session.isShadowing()) {
            if (session != null && session.conflictFrozen()) return "<gray>Not shadowing. Conflict:</gray> <yellow>" + session.conflictTarget() + "</yellow>";
            return "<gray>Not shadowing.</gray>";
        }
        return "<gray>Target:</gray> <yellow>" + session.shadowTarget() + "</yellow> <gray>self:</gray> <yellow>" + session.shadowSelf() + "</yellow>";
    }

    // ════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════

    private RuntimeSession runtime(final UUID uuid) { return runtimes.computeIfAbsent(uuid, RuntimeSession::new); }

    private String accountName(final UUID uuid, final String fallback) {
        return db.getAccountName(uuid).orElse(fallback);
    }

    private Player online(final UUID uuid, final ResponseHandle r) {
        final Player p = Bukkit.getPlayer(uuid);
        if (p == null) r.reply("<red>Must be online.</red>");
        return p;
    }

    private boolean isAdmin(final UUID uuid) {
        final Player p = Bukkit.getPlayer(uuid);
        return p != null && p.hasPermission("shadowcore.admin");
    }

    private Player findOnlineByName(final String name) {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    private boolean isIdentityActive(final String targetKey, final UUID exclude) {
        final Player online = findOnlineByName(targetKey);
        return online != null && !online.getUniqueId().equals(exclude);
    }

    private UUID extractActor(final EngineEvent event) {
        return switch (event) {
            case EngineEvent.ProfileCreate e -> e.actor();
            case EngineEvent.ProfileSwitch e -> e.actor();
            case EngineEvent.ProfileDelete e -> e.actor();
            case EngineEvent.ProfileReturnToMain e -> e.actor();
            case EngineEvent.ProfileRename e -> e.actor();
            case EngineEvent.ShadowMount e -> e.actor();
            case EngineEvent.ShadowLogout e -> e.actor();
            case EngineEvent.NameResolved e -> e.actor();
            case EngineEvent.ShadowNameResolved e -> e.actor();
            default -> null;
        };
    }
}
