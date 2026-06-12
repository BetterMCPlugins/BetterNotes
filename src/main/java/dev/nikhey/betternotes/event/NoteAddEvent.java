package dev.nikhey.betternotes.event;

import dev.nikhey.betternotes.model.Note;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired after a note was stored. Usually asynchronous (storage completes off
 * the server threads), so listeners must not assume the main thread - check
 * {@link #isAsynchronous()}.
 */
public final class NoteAddEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Note note;

    public NoteAddEvent(Note note) {
        super(!Bukkit.isPrimaryThread());
        this.note = note;
    }

    public Note note() {
        return note;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
