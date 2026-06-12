package dev.nikhey.betternotes.config;

import org.bukkit.configuration.file.FileConfiguration;

public final class Settings {

    public static final String PERM_STAFF = "betternotes.staff";
    public static final String PERM_ADD = "betternotes.add";
    public static final String PERM_REMOVE_OWN = "betternotes.remove.own";
    public static final String PERM_REMOVE_ANY = "betternotes.remove.any";
    public static final String PERM_NOTIFY = "betternotes.notify";
    public static final String PERM_ADMIN = "betternotes.admin";

    private final int maxNoteLength;
    private final int recentLimit;
    private final boolean watchlistJoinAlert;
    private final boolean alertNotesOnJoin;
    private final boolean notifyIngame;
    private final boolean discordEnabled;
    private final String webhookUrl;
    private final boolean discordSrvEnabled;
    private final String discordSrvChannel;
    private final boolean sendWatchlistJoins;
    private final boolean sendAlertNotes;
    private final int deletedRetentionDays;

    private Settings(FileConfiguration c) {
        this.maxNoteLength = Math.max(16, c.getInt("notes.max-length", 256));
        this.recentLimit = Math.max(1, c.getInt("notes.recent-limit", 10));
        this.watchlistJoinAlert = c.getBoolean("notify.watchlist-join", true);
        this.alertNotesOnJoin = c.getBoolean("notify.alert-notes-on-join", false);
        this.notifyIngame = c.getBoolean("notify.ingame", true);
        this.discordEnabled = c.getBoolean("discord.enabled", false);
        this.webhookUrl = c.getString("discord.webhook-url", "");
        this.discordSrvEnabled = c.getBoolean("discord.discordsrv.enabled", true);
        this.discordSrvChannel = c.getString("discord.discordsrv.channel", "");
        this.sendWatchlistJoins = c.getBoolean("discord.send.watchlist-joins", true);
        this.sendAlertNotes = c.getBoolean("discord.send.alert-notes", true);
        this.deletedRetentionDays = c.getInt("retention.deleted-days", 30);
    }

    public static Settings load(FileConfiguration config) {
        return new Settings(config);
    }

    public int maxNoteLength() {
        return maxNoteLength;
    }

    public int recentLimit() {
        return recentLimit;
    }

    public boolean watchlistJoinAlert() {
        return watchlistJoinAlert;
    }

    public boolean alertNotesOnJoin() {
        return alertNotesOnJoin;
    }

    public boolean notifyIngame() {
        return notifyIngame;
    }

    public boolean discordEnabled() {
        return discordEnabled && webhookUrl != null && !webhookUrl.isBlank();
    }

    public String webhookUrl() {
        return webhookUrl;
    }

    public boolean discordSrvEnabled() {
        return discordSrvEnabled;
    }

    public String discordSrvChannel() {
        return discordSrvChannel;
    }

    public boolean sendWatchlistJoins() {
        return sendWatchlistJoins;
    }

    public boolean sendAlertNotes() {
        return sendAlertNotes;
    }

    public int deletedRetentionDays() {
        return deletedRetentionDays;
    }
}
