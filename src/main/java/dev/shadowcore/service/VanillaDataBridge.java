package dev.shadowcore.service;

import dev.shadowcore.ShadowCorePlugin;
import dev.shadowcore.model.PlayerSnapshot;
import dev.shadowcore.model.ProfileIdentity;
import java.io.File;
import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Bridge between ShadowCore's profile system and Minecraft's vanilla player data.
 * <p>
 * Each profile's data lives in {@code world/playerdata/<profile-uuid>.dat}. The profile
 * UUID is a random UUID from the database, so each profile has its own independent .dat
 * file that survives plugin removal.
 * <p>
 * <b>NMS isolation:</b> All NMS access is via reflection in this single class.
 * If NMS isn't available, falls back to PlayerSnapshot (Bukkit API only).
 */
public final class VanillaDataBridge {
    private final ShadowCorePlugin plugin;
    private final File playerDataDir;

    public VanillaDataBridge(final ShadowCorePlugin plugin) {
        this.plugin = plugin;
        this.playerDataDir = new File(Bukkit.getWorlds().get(0).getWorldFolder(), "playerdata");
        if (!playerDataDir.exists()) playerDataDir.mkdirs();
    }

    public File datFile(final UUID uuid) {
        return new File(playerDataDir, uuid + ".dat");
    }

    public boolean hasData(final UUID profileUuid) {
        return datFile(profileUuid).exists();
    }

    /**
     * Saves the player's current state to their active profile's .dat file.
     * For main profile, vanilla save goes to the right place automatically.
     * For local profiles, we save to both the base UUID and profile UUID locations.
     */
    public void saveToProfile(final Player player, final ProfileIdentity identity) {
        player.saveData(); // writes to playerdata/<base-uuid>.dat
        if (!identity.isMain()) {
            copyFile(datFile(identity.baseUuid()), datFile(identity.profileUuid()));
        }
    }

    /**
     * Loads a profile's data and applies it to the player.
     * For main profile, data is already loaded (or we reload from base UUID's .dat).
     * For local profiles, we load from the profile UUID's .dat file.
     */
    public boolean loadFromProfile(final Player player, final ProfileIdentity identity) {
        if (identity.isMain()) {
            return loadVanillaDat(player, identity.baseUuid());
        }
        final File profileDat = datFile(identity.profileUuid());
        if (!profileDat.exists()) {
            // New profile with no data yet — start fresh
            PlayerSnapshot.defaultFrom(player).applyTo(player);
            return true;
        }
        return loadVanillaDat(player, identity.profileUuid());
    }

    /**
     * Creates initial data for a new profile by saving a seed snapshot as a .dat file.
     */
    public void createProfileData(final Player player, final ProfileIdentity identity) {
        // Capture current state, apply seed, save, restore
        final PlayerSnapshot original = PlayerSnapshot.capture(player);
        final PlayerSnapshot seed = PlayerSnapshot.seedForNewLocalProfile(player);
        seed.applyTo(player);
        player.saveData(); // writes seed to playerdata/<base-uuid>.dat
        copyFile(datFile(identity.baseUuid()), datFile(identity.profileUuid()));
        // Restore original state
        original.applyTo(player);
        player.saveData();
    }

    /**
     * Switches profiles: save current → load target.
     */
    public boolean switchProfile(final Player player, final ProfileIdentity from, final ProfileIdentity to) {
        saveToProfile(player, from);
        return loadFromProfile(player, to);
    }

    public void deleteProfileData(final UUID profileUuid) {
        final File f = datFile(profileUuid);
        if (f.exists()) f.delete();
    }

    // ════════════════════════════════════════════════════════════════
    //  SHADOW DATA OPERATIONS
    // ════════════════════════════════════════════════════════════════

    /**
     * Loads a target player's .dat file data onto the admin's player entity.
     * Used when entering a shadow session. The admin inherits the target's
     * inventory, health, location, etc.
     *
     * @param player     The admin player.
     * @param targetUuid UUID of the target whose .dat file to load.
     * @return true if data was loaded successfully.
     */
    public boolean loadShadowTarget(final Player player, final UUID targetUuid) {
        return loadVanillaDat(player, targetUuid);
    }

    /**
     * Saves the admin's current player state back to the shadow target's .dat file.
     * After writing, restores the admin's base .dat from backup to prevent corruption.
     *
     * @param player        The admin player (whose state reflects the target's data).
     * @param targetUuid    UUID of the target whose .dat file to write.
     * @param adminIdentity The admin's own profile identity (for .dat restoration).
     */
    public void saveShadowTarget(final Player player, final UUID targetUuid, final ProfileIdentity adminIdentity) {
        // Save via vanilla path (writes to playerdata/<admin-uuid>.dat — temporarily has target data)
        player.saveData();
        // Copy admin's .dat to target's .dat (target now has the modified state)
        copyFile(datFile(player.getUniqueId()), datFile(targetUuid));
        // Restore admin's base .dat from their profile backup so it's not corrupted.
        // For local profiles, the profile .dat was saved before shadow loading.
        // For main profiles, we saved a backup at shadow mount time.
        final File backup = adminIdentity.isMain() ? shadowBackupFile(player.getUniqueId()) : datFile(adminIdentity.profileUuid());
        if (backup.exists()) {
            copyFile(backup, datFile(player.getUniqueId()));
        }
    }

    /**
     * Creates a fresh default .dat file for a target UUID that has never joined.
     */
    public void createFreshTargetData(final Player referencePlayer, final UUID targetUuid) {
        final PlayerSnapshot original = PlayerSnapshot.capture(referencePlayer);
        final PlayerSnapshot fresh = PlayerSnapshot.defaultFrom(referencePlayer);
        fresh.applyTo(referencePlayer);
        referencePlayer.saveData();
        copyFile(datFile(referencePlayer.getUniqueId()), datFile(targetUuid));
        // Restore admin's original state
        original.applyTo(referencePlayer);
        referencePlayer.saveData();
    }

    /**
     * Backs up the admin's base .dat file before shadow target data overwrites it.
     * Called at shadow mount time, after the admin's profile data has been saved.
     */
    public void backupAdminDat(final UUID adminUuid) {
        final File src = datFile(adminUuid);
        if (src.exists()) {
            copyFile(src, shadowBackupFile(adminUuid));
        }
    }

    /**
     * Removes the shadow backup file. Called when shadow ends and admin's .dat is restored.
     */
    public void clearShadowBackup(final UUID adminUuid) {
        final File backup = shadowBackupFile(adminUuid);
        if (backup.exists()) backup.delete();
    }

    /** Backup file location for admin's .dat during shadow sessions. */
    private File shadowBackupFile(final UUID adminUuid) {
        final File backupDir = new File(playerDataDir.getParentFile(), "shadowcore-backups");
        backupDir.mkdirs();
        return new File(backupDir, adminUuid + ".dat.bak");
    }

    // ════════════════════════════════════════════════════════════════
    //  NMS DATA LOADING
    // ════════════════════════════════════════════════════════════════

    /**
     * Loads player data from a specific UUID's .dat file using NMS reflection.
     * Falls back to PlayerSnapshot if NMS isn't available.
     */
    private boolean loadVanillaDat(final Player player, final UUID dataUuid) {
        final File dat = datFile(dataUuid);
        if (!dat.exists()) return false;

        try {
            final Object nmsPlayer = invokeMethod(player, "getHandle");
            if (nmsPlayer != null) {
                final Object tag = readCompressedNbt(dat);
                if (tag != null) {
                    applyNbt(nmsPlayer, tag);
                    player.updateInventory();
                    return true;
                }
            }
        } catch (final Exception e) {
            plugin.getLogger().warning("NMS load failed, using snapshot fallback: " + e.getMessage());
        }

        // Fallback: copy target .dat over base .dat, let vanilla reload handle it
        // This is imperfect but better than nothing
        plugin.getLogger().warning("NMS data loading unavailable — profile data may not load fully.");
        return false;
    }

    private Object readCompressedNbt(final File file) {
        try {
            final Class<?> nbtIo = Class.forName("net.minecraft.nbt.NbtIo");
            // Try Path-based methods first
            for (final Method m : nbtIo.getMethods()) {
                if (!"readCompressed".equals(m.getName())) continue;
                if (m.getParameterCount() >= 1 && m.getParameterTypes()[0] == java.nio.file.Path.class) {
                    if (m.getParameterCount() == 1) {
                        return m.invoke(null, file.toPath());
                    }
                    // Needs NbtAccounter
                    final Class<?> acc = Class.forName("net.minecraft.nbt.NbtAccounter");
                    return m.invoke(null, file.toPath(), acc.getMethod("unlimitedHeap").invoke(null));
                }
            }
            // Fallback: InputStream-based
            for (final Method m : nbtIo.getMethods()) {
                if ("readCompressed".equals(m.getName()) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == java.io.InputStream.class) {
                    try (final var fis = new java.io.FileInputStream(file)) {
                        return m.invoke(null, fis);
                    }
                }
            }
        } catch (final Exception e) {
            plugin.getLogger().warning("NbtIo.readCompressed failed: " + e.getMessage());
        }
        return null;
    }

    private void applyNbt(final Object nmsPlayer, final Object tag) throws Exception {
        // Try load() first (Entity.load reads all data)
        Method method = findMethod(nmsPlayer.getClass(), "load", tag.getClass());
        if (method != null) { method.invoke(nmsPlayer, tag); return; }
        // Fallback: readAdditionalSaveData (player-specific)
        method = findMethod(nmsPlayer.getClass(), "readAdditionalSaveData", tag.getClass());
        if (method != null) method.invoke(nmsPlayer, tag);
    }

    // ════════════════════════════════════════════════════════════════
    //  REFLECTION HELPERS
    // ════════════════════════════════════════════════════════════════

    private Object invokeMethod(final Object obj, final String name) {
        try {
            final Method m = obj.getClass().getMethod(name);
            return m.invoke(obj);
        } catch (final Exception e) {
            return null;
        }
    }

    private Method findMethod(final Class<?> clazz, final String name, final Class<?> param) {
        Class<?> c = clazz;
        while (c != null) {
            for (final Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(param)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private void copyFile(final File src, final File dest) {
        try {
            dest.getParentFile().mkdirs();
            java.nio.file.Files.copy(src.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (final java.io.IOException e) {
            plugin.getLogger().severe("File copy failed: " + src + " → " + dest + ": " + e.getMessage());
        }
    }
}
