package dev.nikhey.betternotes;

import dev.nikhey.betternotes.alert.AlertSink;
import dev.nikhey.betternotes.api.NotesApi;
import dev.nikhey.betternotes.config.Settings;
import dev.nikhey.betternotes.event.NoteAddEvent;
import dev.nikhey.betternotes.event.NoteRemoveEvent;
import dev.nikhey.betternotes.event.WatchlistAddEvent;
import dev.nikhey.betternotes.event.WatchlistRemoveEvent;
import dev.nikhey.betternotes.model.Note;
import dev.nikhey.betternotes.model.Severity;
import dev.nikhey.betternotes.model.WatchlistEntry;
import dev.nikhey.betternotes.storage.NoteStore;
import dev.nikhey.betternotes.util.TimeText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Note and watchlist lifecycle: adding, soft-deleting, pinning, watchlist
 * management, join alerts and the per-player count cache that backs the
 * PlaceholderAPI expansion. All storage work happens off the server threads;
 * player messages are sent via Adventure, which is thread-safe on Paper.
 */
public final class NoteService implements NotesApi {

    public static final UUID CONSOLE_UUID = new UUID(0, 0);

    /** Cached per-player state for online players (PAPI is synchronous, the store is not). */
    public record CachedCounts(int total, int alerts, boolean watchlisted) {
    }

    private final Supplier<Settings> settings;
    private final NoteStore store;
    private final Logger logger;
    private final java.util.List<AlertSink> sinks = new CopyOnWriteArrayList<>();
    private final Map<UUID, CachedCounts> cache = new ConcurrentHashMap<>();

    public NoteService(Supplier<Settings> settings, NoteStore store, Logger logger) {
        this.settings = settings;
        this.store = store;
        this.logger = logger;
    }

    public void addSink(AlertSink sink) {
        sinks.add(sink);
    }

    // --- notes ---

    public void addNote(CommandSender author, OfflinePlayer target, String targetName,
                        Severity severity, String text) {
        Settings s = settings.get();
        if (text.length() > s.maxNoteLength()) {
            author.sendMessage(prefixed("Notes are limited to " + s.maxNoteLength()
                    + " characters (yours has " + text.length() + ").", NamedTextColor.RED));
            return;
        }
        addNote(target.getUniqueId(), targetName, uuidOf(author), nameOf(author), severity, text)
                .thenAccept(id -> author.sendMessage(Component.text()
                        .append(prefix())
                        .append(Component.text("Note ", NamedTextColor.GREEN))
                        .append(Component.text("#" + id, NamedTextColor.YELLOW))
                        .append(Component.text(" (" + severity.display() + ") added on "
                                + targetName + ".", NamedTextColor.GREEN))
                        .build()))
                .exceptionally(error -> {
                    logger.error("Failed to add a note on {}", targetName, error);
                    author.sendMessage(prefixed("Something went wrong saving the note.", NamedTextColor.RED));
                    return null;
                });
    }

    @Override
    public CompletableFuture<Long> addNote(UUID targetUuid, String targetName,
                                           UUID authorUuid, String authorName,
                                           Severity severity, String text) {
        long now = System.currentTimeMillis();
        Note draft = new Note(0, now, targetUuid, targetName, authorUuid, authorName,
                severity, false, text, 0, null, null);
        return store.create(draft).thenApply(id -> {
            Note note = new Note(id, now, targetUuid, targetName, authorUuid, authorName,
                    severity, false, text, 0, null, null);
            if (severity == Severity.ALERT) {
                notifyStaff(Component.text()
                        .append(prefix())
                        .append(Component.text("Alert note ", NamedTextColor.RED))
                        .append(Component.text("#" + id, NamedTextColor.YELLOW))
                        .append(Component.text(" on ", NamedTextColor.GRAY))
                        .append(noteTargetLink(targetName))
                        .append(Component.text(" by " + authorName + ": " + text, NamedTextColor.GRAY))
                        .build(), targetUuid);
                if (settings.get().sendAlertNotes()) {
                    alert("Alert note #" + id + " — " + targetName,
                            "By " + authorName + "\n" + text, severity.discordColor());
                }
            }
            callEvent(new NoteAddEvent(note));
            refreshCache(targetUuid);
            return id;
        });
    }

    public void removeNote(CommandSender remover, long id) {
        store.byId(id).thenCompose(found -> {
            if (found.isEmpty() || found.get().deleted()) {
                remover.sendMessage(prefixed("Note #" + id + " does not exist or was already removed.",
                        NamedTextColor.RED));
                return CompletableFuture.completedFuture(null);
            }
            Note note = found.get();
            boolean own = uuidOf(remover).equals(note.authorUuid());
            if (!remover.hasPermission(Settings.PERM_REMOVE_ANY)
                    && !(own && remover.hasPermission(Settings.PERM_REMOVE_OWN))) {
                remover.sendMessage(prefixed(own
                        ? "You may not remove notes."
                        : "You may only remove your own notes.", NamedTextColor.RED));
                return CompletableFuture.completedFuture(null);
            }
            return store.softDelete(id, uuidOf(remover), nameOf(remover), System.currentTimeMillis())
                    .thenAccept(ok -> {
                        if (!ok) {
                            remover.sendMessage(prefixed("Note #" + id + " was already removed.",
                                    NamedTextColor.RED));
                            return;
                        }
                        remover.sendMessage(prefixed("Note #" + id + " on " + note.targetName()
                                + " removed (kept in the record).", NamedTextColor.GREEN));
                        callEvent(new NoteRemoveEvent(note, uuidOf(remover), nameOf(remover)));
                        refreshCache(note.targetUuid());
                    });
        }).exceptionally(error -> {
            logger.error("Failed to remove note {}", id, error);
            remover.sendMessage(prefixed("Something went wrong removing the note.", NamedTextColor.RED));
            return null;
        });
    }

    @Override
    public CompletableFuture<Boolean> removeNote(long noteId, UUID removerUuid, String removerName) {
        return store.byId(noteId).thenCompose(found -> {
            if (found.isEmpty() || found.get().deleted()) {
                return CompletableFuture.completedFuture(false);
            }
            Note note = found.get();
            return store.softDelete(noteId, removerUuid, removerName, System.currentTimeMillis())
                    .thenApply(ok -> {
                        if (ok) {
                            callEvent(new NoteRemoveEvent(note, removerUuid, removerName));
                            refreshCache(note.targetUuid());
                        }
                        return ok;
                    });
        });
    }

    public void pin(CommandSender staff, long id) {
        store.pin(id).thenAccept(ok -> staff.sendMessage(ok
                ? prefixed("Note #" + id + " is now the pinned note of that player.", NamedTextColor.GREEN)
                : prefixed("Note #" + id + " does not exist or was removed.", NamedTextColor.RED))
        ).exceptionally(error -> {
            logger.error("Failed to pin note {}", id, error);
            return null;
        });
    }

    public void unpin(CommandSender staff, long id) {
        store.unpin(id).thenAccept(ok -> staff.sendMessage(ok
                ? prefixed("Note #" + id + " is no longer pinned.", NamedTextColor.GREEN)
                : prefixed("Note #" + id + " is not pinned.", NamedTextColor.RED))
        ).exceptionally(error -> {
            logger.error("Failed to unpin note {}", id, error);
            return null;
        });
    }

    // --- watchlist ---

    public void watchlistAdd(CommandSender author, UUID targetUuid, String targetName,
                             long durationMillis, String reason) {
        long now = System.currentTimeMillis();
        long expire = durationMillis > 0 ? now + durationMillis : 0;
        WatchlistEntry entry = new WatchlistEntry(targetUuid, targetName, now,
                uuidOf(author), nameOf(author), reason, expire);
        store.watchlistPut(entry).thenAccept(created -> {
            String until = expire > 0 ? " until " + TimeText.absolute(expire) : "";
            author.sendMessage(prefixed(targetName + (created ? " added to" : " updated on")
                    + " the watchlist" + until + ".", NamedTextColor.GREEN));
            notifyStaff(Component.text()
                    .append(prefix())
                    .append(noteTargetLink(targetName))
                    .append(Component.text(" was " + (created ? "added to" : "updated on")
                            + " the watchlist by " + nameOf(author)
                            + (reason == null || reason.isBlank() ? "" : ": " + reason)
                            + until + ".", NamedTextColor.GRAY))
                    .build(), targetUuid);
            callEvent(new WatchlistAddEvent(entry));
            refreshCache(targetUuid);
        }).exceptionally(error -> {
            logger.error("Failed to watchlist {}", targetName, error);
            author.sendMessage(prefixed("Something went wrong updating the watchlist.", NamedTextColor.RED));
            return null;
        });
    }

    public void watchlistRemove(CommandSender remover, UUID targetUuid, String targetName) {
        store.watchlistRemove(targetUuid).thenAccept(removed -> {
            if (!removed) {
                remover.sendMessage(prefixed(targetName + " is not on the watchlist.", NamedTextColor.RED));
                return;
            }
            remover.sendMessage(prefixed(targetName + " removed from the watchlist.", NamedTextColor.GREEN));
            notifyStaff(prefixed(targetName + " was removed from the watchlist by "
                    + nameOf(remover) + ".", NamedTextColor.GRAY), targetUuid);
            callEvent(new WatchlistRemoveEvent(targetUuid, targetName, uuidOf(remover), nameOf(remover)));
            refreshCache(targetUuid);
        }).exceptionally(error -> {
            logger.error("Failed to un-watchlist {}", targetName, error);
            return null;
        });
    }

    // --- join handling & cache ---

    public void handleJoin(Player player) {
        UUID uuid = player.getUniqueId();
        store.countsFor(uuid).thenCombine(store.watchlistGet(uuid), (counts, watch) -> {
            cache.put(uuid, new CachedCounts(counts.active(), counts.alerts(), watch.isPresent()));
            Settings s = settings.get();
            if (watch.isPresent() && s.watchlistJoinAlert()) {
                WatchlistEntry entry = watch.get();
                String reason = entry.reason() == null || entry.reason().isBlank()
                        ? "" : " — " + entry.reason();
                notifyStaff(Component.text()
                        .append(prefix())
                        .append(Component.text("Watchlisted player ", NamedTextColor.GOLD))
                        .append(noteTargetLink(player.getName()))
                        .append(Component.text(" joined" + reason
                                + " (listed by " + entry.authorName() + " " + TimeText.ago(entry.time())
                                + (counts.active() > 0 ? ", " + counts.active() + " note"
                                        + (counts.active() == 1 ? "" : "s") : "")
                                + ")", NamedTextColor.GRAY))
                        .build(), uuid);
                if (s.sendWatchlistJoins()) {
                    alert("Watchlisted player joined — " + player.getName(),
                            (entry.reason() == null || entry.reason().isBlank()
                                    ? "No reason recorded" : entry.reason())
                                    + "\nListed by " + entry.authorName()
                                    + (counts.active() > 0 ? "\nNotes: " + counts.active() : ""),
                            0xE67E22);
                }
            } else if (counts.alerts() > 0 && s.alertNotesOnJoin()) {
                notifyStaff(Component.text()
                        .append(prefix())
                        .append(noteTargetLink(player.getName()))
                        .append(Component.text(" joined and has " + counts.alerts() + " alert note"
                                + (counts.alerts() == 1 ? "" : "s") + ".", NamedTextColor.GRAY))
                        .build(), uuid);
            }
            return null;
        }).exceptionally(error -> {
            logger.warn("Failed to load notes for joining player {}", player.getName(), error);
            return null;
        });
    }

    public void evict(UUID uuid) {
        cache.remove(uuid);
    }

    public CachedCounts cached(UUID uuid) {
        return cache.get(uuid);
    }

    private void refreshCache(UUID uuid) {
        if (Bukkit.getPlayer(uuid) == null) {
            return;
        }
        store.countsFor(uuid).thenCombine(store.watchlistGet(uuid), (counts, watch) ->
                cache.put(uuid, new CachedCounts(counts.active(), counts.alerts(), watch.isPresent()))
        ).exceptionally(error -> {
            logger.warn("Failed to refresh the note cache", error);
            return null;
        });
    }

    // --- api reads ---

    @Override
    public CompletableFuture<Integer> noteCount(UUID target) {
        return store.countsFor(target).thenApply(NoteStore.Counts::active);
    }

    @Override
    public CompletableFuture<Boolean> isWatchlisted(UUID target) {
        return store.watchlistGet(target).thenApply(java.util.Optional::isPresent);
    }

    // --- plumbing ---

    /** Staff broadcast; the player the message is about never sees it. */
    private void notifyStaff(Component message, UUID about) {
        if (!settings.get().notifyIngame()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(Settings.PERM_NOTIFY) && !player.getUniqueId().equals(about)) {
                player.sendMessage(message);
            }
        }
    }

    private static Component noteTargetLink(String targetName) {
        return Component.text(targetName, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.runCommand("/notes view " + targetName))
                .hoverEvent(Component.text("Click to view the notes on " + targetName, NamedTextColor.AQUA));
    }

    private void alert(String title, String detail, int color) {
        for (AlertSink sink : sinks) {
            try {
                sink.send(title, detail, color);
            } catch (Throwable t) {
                logger.warn("Alert sink failed: {}", t.toString());
            }
        }
    }

    /** Our events are declared asynchronous, so firing from the DB worker is safe. */
    private void callEvent(Event event) {
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (Throwable t) {
            logger.warn("An event listener failed: {}", t.toString());
        }
    }

    private static UUID uuidOf(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : CONSOLE_UUID;
    }

    private static String nameOf(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : "Console";
    }

    public static Component prefix() {
        return Component.text()
                .append(Component.text("[", NamedTextColor.DARK_GRAY))
                .append(Component.text("Notes", NamedTextColor.AQUA))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .build();
    }

    public static Component prefixed(String message, NamedTextColor color) {
        return Component.text()
                .append(prefix())
                .append(Component.text(message, color))
                .build();
    }
}
