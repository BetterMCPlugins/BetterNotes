package dev.nikhey.betternotes.command;

import dev.nikhey.betternotes.NoteService;
import dev.nikhey.betternotes.config.Settings;
import dev.nikhey.betternotes.model.WatchlistEntry;
import dev.nikhey.betternotes.storage.NoteStore;
import dev.nikhey.betternotes.util.TimeText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.List;
import java.util.Locale;

public final class WatchlistCommand implements TabExecutor {

    private final NoteStore store;
    private final NoteService service;

    public WatchlistCommand(NoteStore store, NoteService service) {
        this.store = store;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            list(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> add(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender);
            default -> sender.sendMessage(NoteService.prefixed(
                    "Usage: /" + label + " <add|remove|list>", NamedTextColor.RED));
        }
        return true;
    }

    private void add(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Settings.PERM_ADD)) {
            sender.sendMessage(NoteService.prefixed("You may not edit the watchlist.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(NoteService.prefixed(
                    "Usage: /watchlist add <player> [duration like 7d] [reason]", NamedTextColor.RED));
            return;
        }
        OfflinePlayer target = NotesCommand.resolve(sender, args[1]);
        if (target == null) {
            return;
        }
        long duration = 0;
        int reasonFrom = 2;
        if (args.length > 2) {
            long parsed = TimeText.parseDuration(args[2]);
            if (parsed > 0) {
                duration = parsed;
                reasonFrom = 3;
            }
        }
        String reason = args.length > reasonFrom
                ? String.join(" ", List.of(args).subList(reasonFrom, args.length))
                : null;
        service.watchlistAdd(sender, target.getUniqueId(),
                NotesCommand.nameOf(target, args[1]), duration, reason);
    }

    private void remove(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Settings.PERM_ADD)) {
            sender.sendMessage(NoteService.prefixed("You may not edit the watchlist.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(NoteService.prefixed("Usage: /watchlist remove <player>", NamedTextColor.RED));
            return;
        }
        OfflinePlayer target = NotesCommand.resolve(sender, args[1]);
        if (target == null) {
            return;
        }
        service.watchlistRemove(sender, target.getUniqueId(), NotesCommand.nameOf(target, args[1]));
    }

    private void list(CommandSender sender) {
        store.watchlistAll().whenComplete((entries, error) -> {
            if (error != null || entries == null) {
                sender.sendMessage(NoteService.prefixed("Failed to load the watchlist.", NamedTextColor.RED));
                return;
            }
            if (entries.isEmpty()) {
                sender.sendMessage(NoteService.prefixed("The watchlist is empty.", NamedTextColor.GREEN));
                return;
            }
            sender.sendMessage(NoteService.prefixed("Watchlist — " + entries.size()
                    + " player" + (entries.size() == 1 ? "" : "s"), NamedTextColor.GOLD));
            for (WatchlistEntry entry : entries) {
                sender.sendMessage(line(entry));
            }
        });
    }

    private Component line(WatchlistEntry entry) {
        String reason = entry.reason() == null || entry.reason().isBlank() ? "" : " — " + entry.reason();
        String expiry = entry.expireTime() > 0 ? ", expires " + TimeText.in(entry.expireTime()) : "";
        return Component.text()
                .append(Component.text(" " + entry.targetName(), NamedTextColor.WHITE))
                .append(Component.text(reason, NamedTextColor.GRAY))
                .append(Component.text(" (by " + entry.authorName() + " "
                        + TimeText.ago(entry.time()) + expiry + ")", NamedTextColor.DARK_GRAY))
                .build()
                .clickEvent(ClickEvent.runCommand("/notes view " + entry.targetName()))
                .hoverEvent(Component.text("Click to view the notes on " + entry.targetName(),
                        NamedTextColor.AQUA));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("add", "remove", "list").stream()
                    .filter(sub -> sub.startsWith(prefix)).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
            return NotesCommand.onlineNames(args[1]);
        }
        return List.of();
    }
}
