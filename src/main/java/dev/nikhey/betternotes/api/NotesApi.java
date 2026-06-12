package dev.nikhey.betternotes.api;

import dev.nikhey.betternotes.model.Severity;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Cross-plugin API, registered in Bukkit's ServicesManager. Other plugins
 * (BetterReports, BetterPanel, ...) use this for note actions instead of
 * touching the database - writes always go through the owning plugin.
 *
 * All methods are safe to call from any thread.
 */
public interface NotesApi {

    /**
     * Adds a note and returns its id. {@code authorUuid} may be null for
     * non-player authors (console, web panel); {@code authorName} is what
     * staff will see as the source.
     */
    CompletableFuture<Long> addNote(UUID targetUuid, String targetName,
                                    UUID authorUuid, String authorName,
                                    Severity severity, String text);

    /** Soft-deletes a note. Completes with false when it does not exist or is already deleted. */
    CompletableFuture<Boolean> removeNote(long noteId, UUID removerUuid, String removerName);

    /** Number of active (non-deleted) notes on a player. */
    CompletableFuture<Integer> noteCount(UUID target);

    /** Whether the player has a non-expired watchlist entry. */
    CompletableFuture<Boolean> isWatchlisted(UUID target);
}
