package dev.shadowcore.service;

import dev.shadowcore.ShadowCorePlugin;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

/**
 * Manages the <em>server-side</em> display identity for local profiles.
 * <p>
 * Shadow session identity is handled entirely by {@link dev.shadowcore.protocol.ShadowProtocolLayer}
 * at the packet level — this class doesn't touch shadow sessions at all.
 * <p>
 * For local profiles, we set the player's tab list name, display name, and nametag suffix
 * using Bukkit's API. These are real server-side changes because local profiles are a
 * legitimate identity the player has chosen to present.
 */
public final class PresentationService {
    private final ShadowCorePlugin plugin;
    private final Map<UUID, Component> baselineTabNames = new HashMap<>();
    private final Map<UUID, Component> baselineDisplayNames = new HashMap<>();
    private final Map<UUID, String> baselinePlayerNames = new HashMap<>();
    private final Map<UUID, String> baselineTeamNames = new HashMap<>();
    private final Map<UUID, String> managedTeamNames = new HashMap<>();

    public PresentationService(final ShadowCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void cacheBaseline(final Player player) {
        final UUID uuid = player.getUniqueId();
        baselineTabNames.putIfAbsent(uuid, player.playerListName());
        baselineDisplayNames.putIfAbsent(uuid, player.displayName());
        baselinePlayerNames.putIfAbsent(uuid, player.getName());
        baselineTeamNames.putIfAbsent(uuid, currentTeamName(player.getName()));
    }

    public void forgetPlayer(final UUID uuid) {
        cleanupManagedTeam(uuid);
        baselineTabNames.remove(uuid);
        baselineDisplayNames.remove(uuid);
        baselinePlayerNames.remove(uuid);
        baselineTeamNames.remove(uuid);
        managedTeamNames.remove(uuid);
    }

    /** Restore the player's real server-side identity (tab name, display name, team). */
    public void applyRealIdentity(final Player player) {
        cacheBaseline(player);
        final UUID uuid = player.getUniqueId();
        cleanupManagedTeam(uuid);
        restoreBaselineTeam(uuid);
        // Null-safe: if cache is stale/missing, fall back to the player's actual name.
        // This prevents intermittent tab issues (bug 3) when the baseline wasn't cached
        // correctly after a reconnect cycle.
        final Component tabName = baselineTabNames.getOrDefault(uuid, net.kyori.adventure.text.Component.text(player.getName()));
        final Component displayName = baselineDisplayNames.getOrDefault(uuid, net.kyori.adventure.text.Component.text(player.getName()));
        player.playerListName(tabName);
        player.displayName(displayName);
    }

    /** Set the player's server-side identity to their local profile name. */
    public void applyLocalIdentity(final Player player, final String fullAltName, final String suffix) {
        cacheBaseline(player);
        final String display = nonBlank(fullAltName, baselinePlayerNames.get(player.getUniqueId()), player.getName());
        player.playerListName(Component.text(display));
        player.displayName(Component.text(display));
        applySuffixNameTag(player, "_" + nonBlank(suffix, "local"));
    }

    // ── Internal ────────────────────────────────────────────────────

    private void restoreBaselineTeam(final UUID uuid) {
        final String name = baselinePlayerNames.get(uuid);
        final String teamName = baselineTeamNames.get(uuid);
        if (name == null || teamName == null) return;
        final Scoreboard sb = mainScoreboard();
        if (sb == null) return;
        final Team team = sb.getTeam(teamName);
        if (team != null && !team.hasEntry(name)) team.addEntry(name);
    }

    private void cleanupManagedTeam(final UUID uuid) {
        final String name = baselinePlayerNames.get(uuid);
        final String managedName = managedTeamNames.get(uuid);
        if (name == null || managedName == null) return;
        final Scoreboard sb = mainScoreboard();
        if (sb == null) return;
        final Team team = sb.getTeam(managedName);
        if (team != null) {
            team.removeEntry(name);
            if (team.getEntries().isEmpty()) team.unregister();
        }
    }

    private void applySuffixNameTag(final Player player, final String suffix) {
        final Team team = managedTeam(player);
        team.setPrefix("");
        team.setSuffix(nonBlank(suffix, ""));
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
    }

    private Team managedTeam(final Player player) {
        final Scoreboard sb = mainScoreboard();
        if (sb == null) throw new IllegalStateException("Main scoreboard is unavailable");
        final String baseName = baselinePlayerNames.computeIfAbsent(player.getUniqueId(), ignored -> player.getName());
        final String baseTeam = baselineTeamNames.computeIfAbsent(player.getUniqueId(), uuid -> currentTeamName(baseName));
        if (baseTeam != null) {
            final Team bt = sb.getTeam(baseTeam);
            if (bt != null) bt.removeEntry(baseName);
        }
        final String teamName = managedTeamNames.computeIfAbsent(player.getUniqueId(), this::teamNameFor);
        Team team = sb.getTeam(teamName);
        if (team == null) team = sb.registerNewTeam(teamName);
        if (!team.hasEntry(baseName)) team.addEntry(baseName);
        return team;
    }

    private String currentTeamName(final String playerName) {
        final Scoreboard sb = mainScoreboard();
        if (sb == null) return null;
        final Team team = sb.getEntryTeam(playerName);
        return team == null ? null : team.getName();
    }

    private Scoreboard mainScoreboard() {
        final ScoreboardManager mgr = Bukkit.getScoreboardManager();
        return mgr == null ? null : mgr.getMainScoreboard();
    }

    private String teamNameFor(final UUID uuid) {
        return "sc" + uuid.toString().replace("-", "").substring(0, 14);
    }

    private static String nonBlank(final String... candidates) {
        for (final String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return "ㅤ";
    }
}
