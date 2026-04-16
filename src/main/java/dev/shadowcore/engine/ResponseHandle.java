package dev.shadowcore.engine;

import org.bukkit.command.CommandSender;

@FunctionalInterface
public interface ResponseHandle {
    ResponseHandle NONE = message -> {};

    void reply(String message);

    static ResponseHandle of(final CommandSender sender) {
        return message -> sender.sendRichMessage(message);
    }
}
