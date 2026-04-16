package dev.shadowcore.protocol;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.shadowcore.ShadowCorePlugin;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Puppet-based shadow identity system.
 * <p>
 * The admin's real tab entry is NEVER modified — they appear as themselves in the tab list.
 * A hidden (listed=false) puppet tab entry provides skin data so the client can render the
 * target's appearance on the admin's entity. The entity UUID is swapped in SPAWN_ENTITY so
 * the client maps the entity to the puppet profile instead of the admin's real profile.
 * <p>
 * Result: tab shows "FirstMage", world shows "Notch" with Notch's skin and nameplate.
 */
public final class ShadowProtocolLayer {
    private final ShadowCorePlugin plugin;
    private final ProtocolManager pm;
    private final Map<UUID, PuppetState> puppets = new ConcurrentHashMap<>();
    private final Map<UUID, String> realNames = new ConcurrentHashMap<>();

    public record PuppetState(UUID puppetUuid, WrappedGameProfile profile, boolean selfShadow) {}

    public ShadowProtocolLayer(final ShadowCorePlugin plugin) {
        this.plugin = plugin;
        this.pm = ProtocolLibrary.getProtocolManager();
        registerListeners();
    }

    // ════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ════════════════════════════════════════════════════════════════════

    public void engageBlank(final Player admin) {
        final UUID puppetUuid = derivePuppetUuid(admin.getUniqueId(), "");
        realNames.put(admin.getUniqueId(), admin.getName());
        puppets.put(admin.getUniqueId(), new PuppetState(puppetUuid, null, true));
        admin.addPotionEffect(new PotionEffect(
            PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        // Admin stays in tab list normally. Just hidden in the world.
        for (final Player observer : Bukkit.getOnlinePlayers()) {
            if (observer.getUniqueId().equals(admin.getUniqueId())) continue;
            observer.hidePlayer(plugin, admin);
        }
    }

    public void engageWithSkin(final Player admin, final String targetName,
                               final String textureValue, final String textureSignature) {
        final UUID puppetUuid = derivePuppetUuid(admin.getUniqueId(), targetName);
        final WrappedGameProfile profile = buildProfile(puppetUuid, targetName, textureValue, textureSignature);
        if (profile == null) { plugin.getLogger().warning("Puppet profile build failed for " + targetName); return; }

        // Clean up old puppet state if switching targets
        final PuppetState oldState = puppets.get(admin.getUniqueId());
        final UUID oldPuppetUuid = (oldState != null && !oldState.selfShadow()) ? oldState.puppetUuid() : null;
        if (oldState != null && oldState.selfShadow()) {
            // Transitioning from self-shadow → target shadow: remove invisibility
            admin.removePotionEffect(PotionEffectType.INVISIBILITY);
        }

        realNames.put(admin.getUniqueId(), admin.getName());
        puppets.put(admin.getUniqueId(), new PuppetState(puppetUuid, profile, false));
        for (final Player observer : Bukkit.getOnlinePlayers()) {
            if (observer.getUniqueId().equals(admin.getUniqueId())) continue;
            if (oldPuppetUuid != null && !oldPuppetUuid.equals(puppetUuid)) {
                sendPlayerInfoRemove(observer, oldPuppetUuid);
            }
            spawnPuppetForObserver(observer, admin, puppetUuid, profile);
        }
    }

    public void disengage(final Player admin) {
        final PuppetState state = puppets.remove(admin.getUniqueId());
        realNames.remove(admin.getUniqueId());
        if (state == null) return;
        if (state.selfShadow()) {
            admin.removePotionEffect(PotionEffectType.INVISIBILITY);
            for (final Player observer : Bukkit.getOnlinePlayers()) {
                if (observer.getUniqueId().equals(admin.getUniqueId())) continue;
                observer.showPlayer(plugin, admin);
            }
            restoreTabName(admin);
        } else {
            for (final Player observer : Bukkit.getOnlinePlayers()) {
                if (observer.getUniqueId().equals(admin.getUniqueId())) continue;
                despawnPuppetForObserver(observer, admin, state.puppetUuid());
            }
        }
    }

    public void onObserverJoined(final Player newPlayer) {
        for (final Map.Entry<UUID, PuppetState> entry : puppets.entrySet()) {
            final Player admin = Bukkit.getPlayer(entry.getKey());
            if (admin == null || admin.getUniqueId().equals(newPlayer.getUniqueId())) continue;
            final PuppetState state = entry.getValue();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!newPlayer.isOnline() || !admin.isOnline()) return;
                if (state.selfShadow()) {
                    newPlayer.hidePlayer(plugin, admin);
                } else {
                    spawnPuppetForObserver(newPlayer, admin, state.puppetUuid(), state.profile());
                }
            }, 5L);
        }
    }

    public boolean hasOverride(final UUID uuid) { return puppets.containsKey(uuid); }

    public void shutdown() {
        for (final UUID uuid : new ArrayList<>(puppets.keySet())) {
            final Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
        puppets.clear();
        realNames.clear();
        pm.removePacketListeners(plugin);
    }

    // ════════════════════════════════════════════════════════════════════
    //  PUPPET SPAWN / DESPAWN
    // ════════════════════════════════════════════════════════════════════

    private void spawnPuppetForObserver(final Player observer, final Player admin,
                                        final UUID puppetUuid, final WrappedGameProfile profile) {
        sendPuppetTabEntry(observer, puppetUuid, profile, admin);
        if (observer.canSee(admin)) observer.hidePlayer(plugin, admin);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!observer.isOnline() || !admin.isOnline()) return;
            observer.showPlayer(plugin, admin);
            // showPlayer sends PLAYER_INFO ADD which resets the tab display to the real
            // profile name. Re-apply the current server-side tab name (e.g. local profile)
            // so the tab list is unaffected by shadowing.
            restoreTabName(admin);
        }, 4L);
    }

    private void despawnPuppetForObserver(final Player observer, final Player admin, final UUID puppetUuid) {
        sendPlayerInfoRemove(observer, puppetUuid);
        if (observer.canSee(admin)) observer.hidePlayer(plugin, admin);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!observer.isOnline() || !admin.isOnline()) return;
            observer.showPlayer(plugin, admin);
            restoreTabName(admin);
        }, 4L);
    }

    /**
     * Re-triggers the tab display name packet. After showPlayer sends PLAYER_INFO ADD
     * (which carries the real profile name), this re-sets the server-side playerListName
     * to its current value, which makes Paper send an UPDATE_DISPLAY_NAME packet that
     * overrides the ADD_PLAYER's profile name in the client's tab list.
     */
    private void restoreTabName(final Player admin) {
        try {
            final net.kyori.adventure.text.Component current = admin.playerListName();
            // Force Paper to re-send by toggling the value
            admin.playerListName(null);
            admin.playerListName(current);
        } catch (final Exception ignored) {}
    }

    // ════════════════════════════════════════════════════════════════════
    //  PACKET LISTENERS
    // ════════════════════════════════════════════════════════════════════

    private void registerListeners() {
        // Entity spawn: swap admin's UUID with puppet UUID for observers
        pm.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL,
                PacketType.Play.Server.SPAWN_ENTITY) {
            @Override
            public void onPacketSending(final PacketEvent event) { onSpawnEntity(event); }
        });

        // Scoreboard teams: suppress admin's real name from team membership
        try {
            pm.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL,
                    PacketType.Play.Server.SCOREBOARD_TEAM) {
                @Override
                public void onPacketSending(final PacketEvent event) { onTeam(event); }
            });
        } catch (final Exception e) {
            plugin.getLogger().warning("SCOREBOARD_TEAM listener unavailable: " + e.getMessage());
        }

        // Signed chat: cancel for shadowed admins to prevent secure chat errors
        try {
            pm.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                    PacketType.Play.Server.CHAT) {
                @Override
                public void onPacketSending(final PacketEvent event) { onChat(event); }
            });
        } catch (final Exception e) {
            plugin.getLogger().warning("CHAT listener unavailable: " + e.getMessage());
        }
    }

    /** Swap admin's UUID with puppet UUID in entity spawn packets for observers. */
    private void onSpawnEntity(final PacketEvent event) {
        final Player receiver = event.getPlayer();
        try {
            final UUID entityUuid = event.getPacket().getUUIDs().read(0);
            final PuppetState state = puppets.get(entityUuid);
            if (state != null && !state.selfShadow() && !receiver.getUniqueId().equals(entityUuid)) {
                event.getPacket().getUUIDs().write(0, state.puppetUuid());
            }
        } catch (final Exception ignored) {}
    }

    /** Suppress admin's real name from team membership so nameplate uses puppet profile. */
    @SuppressWarnings("unchecked")
    private void onTeam(final PacketEvent event) {
        final Player receiver = event.getPlayer();
        final Set<String> suppress = ConcurrentHashMap.newKeySet();
        for (final Map.Entry<UUID, String> entry : realNames.entrySet()) {
            if (!entry.getKey().equals(receiver.getUniqueId())) suppress.add(entry.getValue());
        }
        if (suppress.isEmpty()) return;
        try {
            final var mod = event.getPacket().getSpecificModifier(Collection.class);
            for (int i = 0; i < mod.size(); i++) {
                final Collection<String> players = (Collection<String>) mod.read(i);
                if (players == null || players.isEmpty()) continue;
                final List<String> filtered = new ArrayList<>(players);
                if (filtered.removeIf(suppress::contains)) mod.write(i, filtered);
            }
        } catch (final Exception ignored) {}
    }

    /** Cancel signed chat from shadowed admins for observers. */
    private void onChat(final PacketEvent event) {
        try {
            final UUID sender = event.getPacket().getUUIDs().read(0);
            if (puppets.containsKey(sender) && !event.getPlayer().getUniqueId().equals(sender)) {
                event.setCancelled(true);
            }
        } catch (final Exception ignored) {}
    }

    // ════════════════════════════════════════════════════════════════════
    //  TAB ENTRY MANAGEMENT
    // ════════════════════════════════════════════════════════════════════

    /**
     * Sends a hidden puppet tab entry. listed=false means the entry doesn't appear in
     * the tab list UI, but the client still caches the profile internally. When a
     * SPAWN_ENTITY arrives with this UUID, the client looks up the cached profile and
     * renders the entity with the target's skin and nameplate.
     */
    private void sendPuppetTabEntry(final Player observer, final UUID puppetUuid,
                                    final WrappedGameProfile profile, final Player admin) {
        try {
            final PlayerInfoData entry = new PlayerInfoData(
                puppetUuid, 0,
                false, // listed=false → invisible in tab UI, but profile is cached for skin
                EnumWrappers.NativeGameMode.fromBukkit(admin.getGameMode()),
                profile, null
            );
            final PacketContainer packet = pm.createPacket(PacketType.Play.Server.PLAYER_INFO);
            packet.getPlayerInfoActions().write(0,
                EnumSet.of(EnumWrappers.PlayerInfoAction.ADD_PLAYER, EnumWrappers.PlayerInfoAction.UPDATE_LISTED));
            packet.getPlayerInfoDataLists().write(1, List.of(entry));
            pm.sendServerPacket(observer, packet);
        } catch (final Exception e) {
            plugin.getLogger().warning("Puppet tab entry failed: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  PROFILE BUILDING (Paper API, no authlib)
    // ════════════════════════════════════════════════════════════════════

    private WrappedGameProfile buildProfile(final UUID uuid, final String name,
                                            final String textureValue, final String textureSignature) {
        try {
            final String safeName = (name == null || name.isEmpty()) ? "ㅤ" : name;
            final var paper = Bukkit.createProfile(uuid, safeName);
            if (textureValue != null && !textureValue.isBlank()) {
                paper.setProperty(new ProfileProperty("textures", textureValue, textureSignature));
            }
            final Object handle = extractHandle(paper);
            return handle != null ? WrappedGameProfile.fromHandle(handle) : null;
        } catch (final Exception e) {
            plugin.getLogger().warning("Profile build failed: " + e.getMessage());
            return null;
        }
    }

    private Object extractHandle(final Object paperProfile) {
        for (final String name : new String[]{"getGameProfile", "buildGameProfile"}) {
            try {
                final Method m = paperProfile.getClass().getMethod(name);
                m.setAccessible(true);
                return m.invoke(paperProfile);
            } catch (final NoSuchMethodException ignored) {
            } catch (final Exception e) {
                plugin.getLogger().warning("extractHandle(" + name + "): " + e.getMessage());
            }
        }
        try {
            for (final Method m : paperProfile.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().contains("GameProfile")) {
                    m.setAccessible(true);
                    return m.invoke(paperProfile);
                }
            }
        } catch (final Exception ignored) {}
        return null;
    }

    // ════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════

    private UUID derivePuppetUuid(final UUID adminUuid, final String targetName) {
        return UUID.nameUUIDFromBytes(("shadowcore-puppet:" + adminUuid + ":" + targetName).getBytes(StandardCharsets.UTF_8));
    }

    private void sendPlayerInfoRemove(final Player observer, final UUID uuid) {
        try {
            final PacketContainer packet = pm.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
            packet.getModifier().write(0, List.of(uuid));
            pm.sendServerPacket(observer, packet);
        } catch (final Exception e) {
            plugin.getLogger().warning("PLAYER_INFO_REMOVE failed: " + e.getMessage());
        }
    }
}
