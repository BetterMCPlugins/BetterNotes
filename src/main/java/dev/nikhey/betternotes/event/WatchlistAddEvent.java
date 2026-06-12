package dev.nikhey.betternotes.event;

import dev.nikhey.betternotes.model.WatchlistEntry;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired after a player was added to (or refreshed on) the watchlist.
 * Usually asynchronous - check {@link #isAsynchronous()}.
 */
public final class WatchlistAddEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final WatchlistEntry entry;

    public WatchlistAddEvent(WatchlistEntry entry) {
        super(!Bukkit.isPrimaryThread());
        this.entry = entry;
    }

    public WatchlistEntry entry() {
        return entry;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
