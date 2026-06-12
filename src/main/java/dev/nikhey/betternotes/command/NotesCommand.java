package dev.nikhey.betternotes.command;

import dev.nikhey.betternotes.BetterNotesPlugin;
import dev.nikhey.betternotes.NoteService;
import dev.nikhey.betternotes.config.Settings;
import dev.nikhey.betternotes.model.Note;
import dev.nikhey.betternotes.model.Severity;
import dev.nikhey.betternotes.storage.NoteStore;
import dev.nikhey.betternotes.util.TimeText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class NotesCommand implements TabExecutor {

    private static final int PAGE_SIZE = 8;
    private static final int SEARCH_LIMIT = 10;
    private static final List<String> SUBCOMMANDS = List.of(
            "add", "view", "pin", "unpin", "remove", "search", "recent", "purge", "reload");

    private final BetterNotesPlugin plugin;
    private final NoteStore store;
    private final NoteService service;

    public NotesCommand(BetterNotesPlugin plugin, NoteStore store, NoteService service) {
        this.plugin = plugin;
        this.store = store;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> add(sender, args);
            case "view" -> view(sender, args);
            case "pin" -> withId(sender, args, service::pin);
            case "unpin" -> withId(sender, args, service::unpin);
            case "remove" -> withId(sender, args, service::removeNote);
            case "search" -> search(sender, args);
            case "recent" -> recent(sender, args);
            case "purge" -> purge(sender, args);
            case "reload" -> reload(sender);
            default -> usage(sender, label);
        }
        return true;
    }

    private static void usage(CommandSender sender, String label) {
        sender.sendMessage(NoteService.prefixed(
                "Usage: /" + label + " <add|view|pin|unpin|remove|search|recent|purge|reload>",
                NamedTextColor.RED));
    }

    private void add(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Settings.PERM_ADD)) {
            sender.sendMessage(NoteService.prefixed("You may not add notes.", NamedTextColor.RED));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(NoteService.prefixed(
                    "Usage: /notes add <player> [info|warn|alert] <text>", NamedTextColor.RED));
            return;
        }
        OfflinePlayer target = resolve(sender, args[1]);
        if (target == null) {
            return;
        }
        Severity severity = Severity.INFO;
        int textFrom = 2;
        if (args.length > 3) {
            var parsed = Severity.parse(args[2]);
            if (parsed.isPresent()) {
                severity = parsed.get();
                textFrom = 3;
            }
        }
        String text = String.join(" ", List.of(args).subList(textFrom, args.length));
        service.addNote(sender, target, nameOf(target, args[1]), severity, text);
    }

    private void view(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(NoteService.prefixed("Usage: /notes view <player> [page]", NamedTextColor.RED));
            return;
        }
        OfflinePlayer target = resolve(sender, args[1]);
        if (target == null) {
            return;
        }
        String targetName = nameOf(target, args[1]);
        int page = args.length > 2 ? parsePage(args[2]) : 1;
        store.notesFor(target.getUniqueId(), PAGE_SIZE, (page - 1) * PAGE_SIZE)
                .thenCombine(store.countsFor(target.getUniqueId()), (notes, counts) -> {
                    if (counts.active() == 0) {
                        sender.sendMessage(NoteService.prefixed("No notes on " + targetName + "."
                                + (counts.deleted() > 0 ? " (" + counts.deleted() + " removed)" : ""),
                                NamedTextColor.GREEN));
                        return null;
                    }
                    sender.sendMessage(NoteService.prefixed("Notes on " + targetName + " — "
                            + counts.active() + " total"
                            + (counts.alerts() > 0 ? ", " + counts.alerts() + " alert" : "")
                            + (page > 1 ? " (page " + page + ")" : ""), NamedTextColor.GOLD));
                    for (Note note : notes) {
                        sender.sendMessage(noteLine(note));
                    }
                    if (counts.deleted() > 0) {
                        sender.sendMessage(Component.text(" (+" + counts.deleted()
                                + " removed note" + (counts.deleted() == 1 ? "" : "s")
                                + " kept in the record)", NamedTextColor.DARK_GRAY));
                    }
                    return null;
                }).exceptionally(error -> {
                    sender.sendMessage(NoteService.prefixed("Failed to load the notes.", NamedTextColor.RED));
                    return null;
                });
    }

    private Component noteLine(Note note) {
        var line = Component.text()
                .append(Component.text(" #" + note.id() + " ", NamedTextColor.YELLOW));
        if (note.pinned()) {
            line.append(Component.text("[pinned] ", NamedTextColor.GOLD));
        }
        line.append(Component.text("[" + note.severity().display() + "] ", note.severity().color()))
                .append(Component.text(note.text(), NamedTextColor.WHITE))
                .append(Component.text(" — " + note.authorName() + ", " + TimeText.ago(note.time()),
                        NamedTextColor.DARK_GRAY));
        return line.build().hoverEvent(Component.text(
                TimeText.absolute(note.time()) + " by " + note.authorName(), NamedTextColor.AQUA));
    }

    private interface IdAction {
        void run(CommandSender sender, long id);
    }

    private void withId(CommandSender sender, String[] args, IdAction action) {
        if (args.length < 2) {
            sender.sendMessage(NoteService.prefixed("Usage: /notes " + args[0] + " <id>", NamedTextColor.RED));
            return;
        }
        if (args[0].equalsIgnoreCase("pin") || args[0].equalsIgnoreCase("unpin")) {
            if (!sender.hasPermission(Settings.PERM_ADD)) {
                sender.sendMessage(NoteService.prefixed("You may not pin notes.", NamedTextColor.RED));
                return;
            }
        }
        long id;
        try {
            id = Long.parseLong(args[1].replace("#", ""));
        } catch (NumberFormatException e) {
            sender.sendMessage(NoteService.prefixed("'" + args[1] + "' is not a note id.", NamedTextColor.RED));
            return;
        }
        action.run(sender, id);
    }

    private void search(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(NoteService.prefixed("Usage: /notes search <text>", NamedTextColor.RED));
            return;
        }
        String text = String.join(" ", List.of(args).subList(1, args.length));
        store.search(text, SEARCH_LIMIT).whenComplete((notes, error) -> {
            if (error != null || notes == null) {
                sender.sendMessage(NoteService.prefixed("Search failed.", NamedTextColor.RED));
                return;
            }
            if (notes.isEmpty()) {
                sender.sendMessage(NoteService.prefixed("No notes match '" + text + "'.", NamedTextColor.GRAY));
                return;
            }
            sender.sendMessage(NoteService.prefixed("Notes matching '" + text + "'"
                    + (notes.size() == SEARCH_LIMIT ? " (first " + SEARCH_LIMIT + ")" : ""),
                    NamedTextColor.GOLD));
            for (Note note : notes) {
                sender.sendMessage(serverWideLine(note));
            }
        });
    }

    private void recent(CommandSender sender, String[] args) {
        int limit = plugin.settings().recentLimit();
        if (args.length > 1) {
            try {
                limit = Math.max(1, Math.min(50, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
            }
        }
        store.recent(limit).whenComplete((notes, error) -> {
            if (error != null || notes == null) {
                sender.sendMessage(NoteService.prefixed("Failed to load recent notes.", NamedTextColor.RED));
                return;
            }
            if (notes.isEmpty()) {
                sender.sendMessage(NoteService.prefixed("No notes yet.", NamedTextColor.GRAY));
                return;
            }
            sender.sendMessage(NoteService.prefixed("Recent notes", NamedTextColor.GOLD));
            for (Note note : notes) {
                sender.sendMessage(serverWideLine(note));
            }
        });
    }

    /** A note line in server-wide lists, where the target name matters. */
    private Component serverWideLine(Note note) {
        return Component.text()
                .append(Component.text(" #" + note.id() + " ", NamedTextColor.YELLOW))
                .append(Component.text("[" + note.severity().display() + "] ", note.severity().color()))
                .append(Component.text(note.targetName(), NamedTextColor.WHITE)
                        .decoration(TextDecoration.BOLD, false))
                .append(Component.text(": " + note.text(), NamedTextColor.GRAY))
                .append(Component.text(" — " + note.authorName() + ", " + TimeText.ago(note.time()),
                        NamedTextColor.DARK_GRAY))
                .build()
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                        "/notes view " + note.targetName()))
                .hoverEvent(Component.text("Click to view all notes on " + note.targetName(),
                        NamedTextColor.AQUA));
    }

    private void purge(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Settings.PERM_ADMIN)) {
            sender.sendMessage(NoteService.prefixed("You may not purge notes.", NamedTextColor.RED));
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("player")) {
            OfflinePlayer target = resolve(sender, args[2]);
            if (target == null) {
                return;
            }
            String targetName = nameOf(target, args[2]);
            store.purgePlayer(target.getUniqueId()).whenComplete((deleted, error) -> {
                if (error != null) {
                    sender.sendMessage(NoteService.prefixed("Purge failed.", NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(NoteService.prefixed("Hard-deleted " + deleted + " note"
                        + (deleted == 1 ? "" : "s") + " and the watchlist entry of "
                        + targetName + ".", NamedTextColor.GREEN));
            });
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("olderthan")) {
            int days;
            try {
                days = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(NoteService.prefixed("'" + args[2] + "' is not a number of days.",
                        NamedTextColor.RED));
                return;
            }
            store.purgeOlderThan(days).whenComplete((deleted, error) -> {
                if (error != null) {
                    sender.sendMessage(NoteService.prefixed("Purge failed.", NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(NoteService.prefixed("Hard-deleted " + deleted + " note"
                        + (deleted == 1 ? "" : "s") + " older than " + days + " days.",
                        NamedTextColor.GREEN));
            });
            return;
        }
        sender.sendMessage(NoteService.prefixed(
                "Usage: /notes purge <player <name>|olderthan <days>> - hard-deletes, unlike /notes remove.",
                NamedTextColor.RED));
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission(Settings.PERM_ADMIN)) {
            sender.sendMessage(NoteService.prefixed("You may not reload the config.", NamedTextColor.RED));
            return;
        }
        plugin.reloadSettings();
        sender.sendMessage(NoteService.prefixed("Configuration reloaded.", NamedTextColor.GREEN));
    }

    /**
     * Resolves a player name without ever blocking: exact online match first,
     * then Paper's offline-player cache (players who joined before).
     */
    static OfflinePlayer resolve(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached == null) {
            sender.sendMessage(NoteService.prefixed("'" + name + "' has never played on this server.",
                    NamedTextColor.RED));
        }
        return cached;
    }

    static String nameOf(OfflinePlayer player, String typed) {
        return player.getName() != null ? player.getName() : typed;
    }

    private static int parsePage(String arg) {
        try {
            return Math.max(1, Integer.parseInt(arg));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(sub -> sub.startsWith(prefix)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("add") || sub.equals("view"))) {
            return onlineNames(args[1]);
        }
        if (args.length == 2 && sub.equals("purge")) {
            return List.of("player", "olderthan");
        }
        if (args.length == 3 && sub.equals("purge") && args[1].equalsIgnoreCase("player")) {
            return onlineNames(args[2]);
        }
        if (args.length == 3 && sub.equals("add")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return List.of("info", "warn", "alert").stream()
                    .filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }

    static List<String> onlineNames(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }
}
