package dev.shadowcore.command;

import dev.shadowcore.ShadowCorePlugin;
import dev.shadowcore.engine.EngineEvent;
import dev.shadowcore.engine.EventEngine;
import dev.shadowcore.engine.ResponseHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class ShadowCommand implements CommandExecutor, TabCompleter {
    private final ShadowCorePlugin plugin;
    private final EventEngine engine;

    public ShadowCommand(final ShadowCorePlugin plugin, final EventEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        final ResponseHandle response = ResponseHandle.of(sender);
        if (!(sender instanceof Player player)) { response.reply("<red>Requires a player.</red>"); return true; }
        if (!player.hasPermission("shadowcore.admin")) { response.reply("<red>No permission.</red>"); return true; }
        if (args.length == 0) { response.reply("<yellow>Usage:</yellow> /shadow <target|logout|status>"); return true; }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "logout" -> {
                final boolean resetLoc = args.length > 1 && args[1].equalsIgnoreCase("resetlocation");
                engine.submit(new EngineEvent.ShadowLogout(player.getUniqueId(), resetLoc, response));
            }
            case "status" -> response.reply(plugin.authority().describeShadowStatus(player.getUniqueId(), player.getName()));
            default -> engine.submit(new EngineEvent.ShadowMount(player.getUniqueId(), player.getName(), args[0], response));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias, final String[] args) {
        final List<String> sug = new ArrayList<>();
        if (args.length == 1) sug.addAll(List.of("logout", "status"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("logout")) sug.add("resetlocation");
        final String input = args[args.length - 1].toLowerCase(Locale.ROOT);
        return sug.stream().filter(s -> s.startsWith(input)).toList();
    }
}
