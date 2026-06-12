package dev.nikhey.betternotes.event;

import dev.nikhey.betternotes.model.Note;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Fired after a note was soft-deleted. Usually asynchronous - check {@link #isAsynchronous()}. */
public final class NoteRemoveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Note note;
    private final UUID removerUuid;
    private final String removerName;

    public NoteRemoveEvent(Note note, UUID removerUuid, String removerName) {
        super(!Bukkit.isPrimaryThread());
        this.note = note;
        this.removerUuid = removerUuid;
        this.removerName = removerName;
    }

    /** The note as it was before deletion. */
    public Note note() {
        return note;
    }

    public UUID removerUuid() {
        return removerUuid;
    }

    public String removerName() {
        return removerName;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
