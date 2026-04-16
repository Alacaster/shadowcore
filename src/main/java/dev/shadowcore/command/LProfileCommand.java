package dev.shadowcore.command;

import dev.shadowcore.ShadowCorePlugin;
import dev.shadowcore.engine.EngineEvent;
import dev.shadowcore.engine.EventEngine;
import dev.shadowcore.engine.ResponseHandle;
import dev.shadowcore.store.Database;
import dev.shadowcore.util.Naming;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class LProfileCommand implements CommandExecutor, TabCompleter {
    private final ShadowCorePlugin plugin;
    private final EventEngine engine;

    public LProfileCommand(final ShadowCorePlugin plugin, final EventEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        final ResponseHandle response = ResponseHandle.of(sender);
        if (!(sender instanceof Player player)) { response.reply("<red>Requires a player.</red>"); return true; }
        if (args.length == 0) { listProfiles(player, response); return true; }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                if (args.length < 2) response.reply("<red>Usage: /lprofile create <suffix></red>");
                else engine.submit(new EngineEvent.ProfileCreate(player.getUniqueId(), player.getName(), args[1], response));
            }
            case "switch" -> {
                if (args.length < 2) response.reply("<red>Usage: /lprofile switch <suffix></red>");
                else engine.submit(new EngineEvent.ProfileSwitch(player.getUniqueId(), player.getName(), args[1], response));
            }
            case "list" -> listProfiles(player, response);
            case "rename" -> {
                if (args.length < 3) response.reply("<red>Usage: /lprofile rename <old> <new></red>");
                else engine.submit(new EngineEvent.ProfileRename(player.getUniqueId(), args[1], args[2], response));
            }
            case "delete" -> {
                if (args.length < 2) response.reply("<red>Usage: /lprofile delete <suffix></red>");
                else engine.submit(new EngineEvent.ProfileDelete(player.getUniqueId(), args[1], response));
            }
            case "status" -> response.reply(plugin.authority().describeStatus(player.getUniqueId(), player.getName()));
            case "logout", "main" -> engine.submit(new EngineEvent.ProfileReturnToMain(player.getUniqueId(), response));
            case "setlimit" -> {
                if (!player.hasPermission("shadowcore.admin")) { response.reply("<red>No permission.</red>"); return true; }
                if (args.length < 3) { response.reply("<red>Usage: /lprofile setlimit <player> <limit></red>"); return true; }
                final Optional<UUID> target = plugin.authority().resolveKnownOwnerUuid(args[1]);
                if (target.isEmpty()) { response.reply("<red>Player not found.</red>"); return true; }
                try { engine.submit(new EngineEvent.ProfileSetLimit(player.getUniqueId(), target.get(), Integer.parseInt(args[2]), response)); }
                catch (final NumberFormatException ex) { response.reply("<red>Limit must be a number.</red>"); }
            }
            case "admindelete" -> {
                if (!player.hasPermission("shadowcore.admin")) { response.reply("<red>No permission.</red>"); return true; }
                if (args.length < 3) { response.reply("<red>Usage: /lprofile admindelete <player> <suffix></red>"); return true; }
                final Optional<UUID> target = plugin.authority().resolveKnownOwnerUuid(args[1]);
                if (target.isEmpty()) { response.reply("<red>Player not found.</red>"); return true; }
                engine.submit(new EngineEvent.ProfileAdminDelete(player.getUniqueId(), target.get(), args[2], response));
            }
            default -> engine.submit(new EngineEvent.ProfileSwitch(player.getUniqueId(), player.getName(), args[0], response));
        }
        return true;
    }

    private void listProfiles(final Player player, final ResponseHandle response) {
        final List<Database.ProfileRecord> profiles = plugin.authority().listProfiles(player.getUniqueId());
        if (profiles.isEmpty()) { response.reply("<yellow>No local profiles.</yellow>"); return; }
        final List<String> names = new ArrayList<>();
        for (final Database.ProfileRecord p : profiles) {
            names.add(Naming.fullLocalName(player.getName(), p.suffix()));
        }
        response.reply("<gray>Profiles:</gray> <yellow>" + String.join("<gray>,</gray> ", names) + "</yellow>");
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias, final String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        final List<String> sug = new ArrayList<>();
        final boolean admin = player.hasPermission("shadowcore.admin");
        if (args.length == 1) {
            sug.addAll(List.of("create", "switch", "list", "rename", "delete", "status", "logout", "main"));
            if (admin) sug.addAll(List.of("setlimit", "admindelete"));
        } else if (args.length == 2) {
            final String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "switch", "delete", "rename" -> plugin.authority().listProfiles(player.getUniqueId()).forEach(p -> sug.add(p.suffix()));
                case "setlimit", "admindelete" -> { if (admin) Bukkit.getOnlinePlayers().forEach(p -> sug.add(p.getName())); }
            }
        } else if (args.length == 3 && "admindelete".equalsIgnoreCase(args[0]) && admin) {
            plugin.authority().resolveKnownOwnerUuid(args[1]).ifPresent(uuid ->
                plugin.authority().listProfiles(uuid).forEach(p -> sug.add(p.suffix())));
        }
        final String input = args[args.length - 1].toLowerCase(Locale.ROOT);
        return sug.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(input)).toList();
    }
}
