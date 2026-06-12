package dev.nikhey.betternotes.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Fired after a player was removed from the watchlist. Usually asynchronous - check {@link #isAsynchronous()}. */
public final class WatchlistRemoveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID targetUuid;
    private final String targetName;
    private final UUID removerUuid;
    private final String removerName;

    public WatchlistRemoveEvent(UUID targetUuid, String targetName, UUID removerUuid, String removerName) {
        super(!Bukkit.isPrimaryThread());
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.removerUuid = removerUuid;
        this.removerName = removerName;
    }

    public UUID targetUuid() {
        return targetUuid;
    }

    public String targetName() {
        return targetName;
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
