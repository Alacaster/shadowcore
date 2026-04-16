package dev.shadowcore.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

public final class PlayerSnapshot {
    private String worldName;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private String gameMode = GameMode.SURVIVAL.name();
    private double health = 20.0D;
    private int foodLevel = 20;
    private float saturation = 5.0F;
    private float exhaustion;
    private int level;
    private float exp;
    private int selectedSlot;
    private boolean allowFlight;
    private boolean flying;
    private ItemStack[] inventoryContents = new ItemStack[36];
    private ItemStack[] armorContents = new ItemStack[4];
    private ItemStack offhand;
    private ItemStack[] enderChestContents = new ItemStack[27];
    private final List<PotionEffect> potionEffects = new ArrayList<>();
    private final Map<Attribute, Double> attributeBaseValues = new LinkedHashMap<>();

    public static PlayerSnapshot capture(final Player player) {
        final PlayerSnapshot snapshot = new PlayerSnapshot();
        final Location location = player.getLocation();
        snapshot.worldName = location.getWorld() == null ? null : location.getWorld().getName();
        snapshot.x = location.getX();
        snapshot.y = location.getY();
        snapshot.z = location.getZ();
        snapshot.yaw = location.getYaw();
        snapshot.pitch = location.getPitch();
        snapshot.gameMode = player.getGameMode().name();
        snapshot.health = player.getHealth();
        snapshot.foodLevel = player.getFoodLevel();
        snapshot.saturation = player.getSaturation();
        snapshot.exhaustion = player.getExhaustion();
        snapshot.level = player.getLevel();
        snapshot.exp = player.getExp();
        snapshot.selectedSlot = player.getInventory().getHeldItemSlot();
        snapshot.allowFlight = player.getAllowFlight();
        snapshot.flying = player.isFlying();
        snapshot.inventoryContents = cloneContents(player.getInventory().getStorageContents());
        snapshot.armorContents = cloneContents(player.getInventory().getArmorContents());
        snapshot.offhand = cloneItem(player.getInventory().getItemInOffHand());
        snapshot.enderChestContents = cloneContents(player.getEnderChest().getContents());
        snapshot.potionEffects.addAll(player.getActivePotionEffects());
        for (final Attribute attribute : Registry.ATTRIBUTE) {
            final AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                snapshot.attributeBaseValues.put(attribute, instance.getBaseValue());
            }
        }
        return snapshot;
    }

    public static PlayerSnapshot defaultFrom(final Player player) {
        final PlayerSnapshot snapshot = new PlayerSnapshot();
        final World world = player.getRespawnLocation() != null
            ? player.getRespawnLocation().getWorld()
            : player.getWorld();
        final Location location = player.getRespawnLocation() != null
            ? player.getRespawnLocation()
            : world == null ? player.getLocation() : world.getSpawnLocation();
        snapshot.worldName = location.getWorld() == null ? null : location.getWorld().getName();
        snapshot.x = location.getX();
        snapshot.y = location.getY();
        snapshot.z = location.getZ();
        snapshot.yaw = location.getYaw();
        snapshot.pitch = location.getPitch();
        snapshot.gameMode = GameMode.SURVIVAL.name();
        snapshot.health = 20.0D;
        snapshot.foodLevel = 20;
        snapshot.saturation = 5.0F;
        snapshot.exhaustion = 0.0F;
        snapshot.level = 0;
        snapshot.exp = 0.0F;
        snapshot.selectedSlot = 0;
        snapshot.allowFlight = false;
        snapshot.flying = false;
        return snapshot;
    }

    public static PlayerSnapshot seedForNewLocalProfile(final Player player) {
        final PlayerSnapshot snapshot = capture(player);
        if (player.getGameMode() == GameMode.SURVIVAL) {
            snapshot.inventoryContents(new ItemStack[36]);
            snapshot.armorContents(new ItemStack[4]);
            snapshot.offhand(null);
            snapshot.health(20.0D);
            snapshot.potionEffects(List.of());
        }
        return snapshot;
    }

    public void applyTo(final Player player) {
        if (worldName != null) {
            final World world = Bukkit.getWorld(worldName);
            if (world != null) {
                player.teleport(new Location(world, x, y, z, yaw, pitch));
            }
        }
        for (final Map.Entry<Attribute, Double> entry : attributeBaseValues.entrySet()) {
            final AttributeInstance instance = player.getAttribute(entry.getKey());
            if (instance != null) {
                instance.setBaseValue(entry.getValue());
            }
        }
        player.setGameMode(GameMode.valueOf(gameMode));
        final Attribute maxHealthAttribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.max_health"));
        final double maxHealth = maxHealthAttribute == null ? 20.0D : Objects.requireNonNull(player.getAttribute(maxHealthAttribute)).getValue();
        player.setHealth(Math.max(0.01D, Math.min(health, maxHealth)));
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);
        player.setLevel(level);
        player.setExp(exp);
        player.setAllowFlight(allowFlight);
        player.setFlying(allowFlight && flying);
        player.getInventory().setStorageContents(cloneContents(inventoryContents));
        player.getInventory().setArmorContents(cloneContents(armorContents));
        player.getInventory().setItemInOffHand(cloneItem(offhand));
        player.getInventory().setHeldItemSlot(selectedSlot);
        player.getEnderChest().setContents(cloneContents(enderChestContents));
        for (final PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        for (final PotionEffect effect : potionEffects) {
            player.addPotionEffect(effect, true);
        }
        player.updateInventory();
    }

    public void saveTo(final ConfigurationSection section) {
        section.set("location.world", worldName);
        section.set("location.x", x);
        section.set("location.y", y);
        section.set("location.z", z);
        section.set("location.yaw", yaw);
        section.set("location.pitch", pitch);
        section.set("gameMode", gameMode);
        section.set("health", health);
        section.set("foodLevel", foodLevel);
        section.set("saturation", saturation);
        section.set("exhaustion", exhaustion);
        section.set("level", level);
        section.set("exp", exp);
        section.set("selectedSlot", selectedSlot);
        section.set("allowFlight", allowFlight);
        section.set("flying", flying);
        section.set("inventoryContents", inventoryContents);
        section.set("armorContents", armorContents);
        section.set("offhand", offhand);
        section.set("enderChestContents", enderChestContents);
        section.set("potionEffects", potionEffects);
        final ConfigurationSection attrs = section.createSection("attributes");
        for (final Map.Entry<Attribute, Double> entry : attributeBaseValues.entrySet()) {
            if (entry.getKey().getKey() != null) {
                attrs.set(entry.getKey().getKey().toString(), entry.getValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static PlayerSnapshot loadFrom(final ConfigurationSection section) {
        final PlayerSnapshot snapshot = new PlayerSnapshot();
        snapshot.worldName = section.getString("location.world");
        snapshot.x = section.getDouble("location.x");
        snapshot.y = section.getDouble("location.y");
        snapshot.z = section.getDouble("location.z");
        snapshot.yaw = (float) section.getDouble("location.yaw");
        snapshot.pitch = (float) section.getDouble("location.pitch");
        snapshot.gameMode = section.getString("gameMode", GameMode.SURVIVAL.name());
        snapshot.health = section.getDouble("health", 20.0D);
        snapshot.foodLevel = section.getInt("foodLevel", 20);
        snapshot.saturation = (float) section.getDouble("saturation", 5.0D);
        snapshot.exhaustion = (float) section.getDouble("exhaustion", 0.0D);
        snapshot.level = section.getInt("level", 0);
        snapshot.exp = (float) section.getDouble("exp", 0.0D);
        snapshot.selectedSlot = section.getInt("selectedSlot", 0);
        snapshot.allowFlight = section.getBoolean("allowFlight", false);
        snapshot.flying = section.getBoolean("flying", false);
        snapshot.inventoryContents = normalize(section.getList("inventoryContents", List.of()).toArray(new ItemStack[0]), 36);
        snapshot.armorContents = normalize(section.getList("armorContents", List.of()).toArray(new ItemStack[0]), 4);
        final Object off = section.get("offhand");
        snapshot.offhand = off instanceof ItemStack itemStack ? itemStack : null;
        snapshot.enderChestContents = normalize(section.getList("enderChestContents", List.of()).toArray(new ItemStack[0]), 27);
        snapshot.potionEffects.addAll((List<PotionEffect>) section.getList("potionEffects", List.of()));
        final ConfigurationSection attrs = section.getConfigurationSection("attributes");
        if (attrs != null) {
            for (final String key : attrs.getKeys(false)) {
                final NamespacedKey namespacedKey = NamespacedKey.fromString(key);
                if (namespacedKey == null) {
                    continue;
                }
                final Attribute attribute = Registry.ATTRIBUTE.get(namespacedKey);
                if (attribute != null) {
                    snapshot.attributeBaseValues.put(attribute, attrs.getDouble(key));
                }
            }
        }
        return snapshot;
    }

    public String worldName() { return worldName; }
    public void worldName(final String worldName) { this.worldName = worldName; }
    public double x() { return x; }
    public void x(final double x) { this.x = x; }
    public double y() { return y; }
    public void y(final double y) { this.y = y; }
    public double z() { return z; }
    public void z(final double z) { this.z = z; }
    public float yaw() { return yaw; }
    public void yaw(final float yaw) { this.yaw = yaw; }
    public float pitch() { return pitch; }
    public void pitch(final float pitch) { this.pitch = pitch; }
    public String gameMode() { return gameMode; }
    public void gameMode(final String gameMode) { this.gameMode = gameMode; }
    public double health() { return health; }
    public void health(final double health) { this.health = health; }
    public int foodLevel() { return foodLevel; }
    public void foodLevel(final int foodLevel) { this.foodLevel = foodLevel; }
    public float saturation() { return saturation; }
    public void saturation(final float saturation) { this.saturation = saturation; }
    public float exhaustion() { return exhaustion; }
    public void exhaustion(final float exhaustion) { this.exhaustion = exhaustion; }
    public int level() { return level; }
    public void level(final int level) { this.level = level; }
    public float exp() { return exp; }
    public void exp(final float exp) { this.exp = exp; }
    public int selectedSlot() { return selectedSlot; }
    public void selectedSlot(final int selectedSlot) { this.selectedSlot = selectedSlot; }
    public boolean allowFlight() { return allowFlight; }
    public void allowFlight(final boolean allowFlight) { this.allowFlight = allowFlight; }
    public boolean flying() { return flying; }
    public void flying(final boolean flying) { this.flying = flying; }
    public ItemStack[] inventoryContents() { return cloneContents(inventoryContents); }
    public void inventoryContents(final ItemStack[] inventoryContents) { this.inventoryContents = normalize(cloneContents(inventoryContents), 36); }
    public ItemStack[] armorContents() { return cloneContents(armorContents); }
    public void armorContents(final ItemStack[] armorContents) { this.armorContents = normalize(cloneContents(armorContents), 4); }
    public ItemStack offhand() { return cloneItem(offhand); }
    public void offhand(final ItemStack offhand) { this.offhand = cloneItem(offhand); }
    public ItemStack[] enderChestContents() { return cloneContents(enderChestContents); }
    public void enderChestContents(final ItemStack[] enderChestContents) { this.enderChestContents = normalize(cloneContents(enderChestContents), 27); }
    public List<PotionEffect> potionEffects() { return new ArrayList<>(potionEffects); }
    public void potionEffects(final List<PotionEffect> effects) { this.potionEffects.clear(); this.potionEffects.addAll(effects); }

    private static ItemStack cloneItem(final ItemStack item) {
        return item == null ? null : item.clone();
    }

    private static ItemStack[] cloneContents(final ItemStack[] items) {
        if (items == null) {
            return new ItemStack[0];
        }
        final ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = cloneItem(items[i]);
        }
        return copy;
    }

    private static ItemStack[] normalize(final ItemStack[] items, final int size) {
        final ItemStack[] normalized = new ItemStack[size];
        if (items != null) {
            System.arraycopy(items, 0, normalized, 0, Math.min(items.length, size));
        }
        return normalized;
    }
}
