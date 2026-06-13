package dev.nikhey.betternotes.hook;

import dev.nikhey.betternotes.NoteService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

/**
 * PlaceholderAPI expansion. Placeholders (per player):
 *   %betternotes_count%        active notes on the player
 *   %betternotes_count_info%   active info-severity notes
 *   %betternotes_count_warn%   active warn-severity notes
 *   %betternotes_count_alert%  active alert-severity notes
 *   %betternotes_watchlisted%  yes/no
 *
 * PlaceholderAPI calls are synchronous while the store is async, so values
 * come from the service's per-player cache, loaded on join and refreshed on
 * every note/watchlist mutation. Offline players resolve to empty.
 */
public final class NotesExpansion extends PlaceholderExpansion {

    private final Plugin plugin;
    private final NoteService service;

    public NotesExpansion(Plugin plugin, NoteService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public String getIdentifier() {
        return "betternotes";
    }

    @Override
    public String getAuthor() {
        return "Nikhey";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return "";
        }
        NoteService.CachedCounts counts = service.cached(player.getUniqueId());
        if (counts == null) {
            return "";
        }
        return switch (params.toLowerCase()) {
            case "count" -> String.valueOf(counts.total());
            case "count_info" -> String.valueOf(counts.info());
            case "count_warn" -> String.valueOf(counts.warn());
            case "count_alert" -> String.valueOf(counts.alert());
            case "watchlisted" -> counts.watchlisted() ? "yes" : "no";
            default -> null;
        };
    }
}
