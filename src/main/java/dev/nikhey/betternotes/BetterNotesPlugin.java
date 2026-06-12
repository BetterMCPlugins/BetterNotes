package dev.nikhey.betternotes;

import dev.nikhey.betternotes.alert.DiscordAlerter;
import dev.nikhey.betternotes.api.NotesApi;
import dev.nikhey.betternotes.command.NotesCommand;
import dev.nikhey.betternotes.command.WatchlistCommand;
import dev.nikhey.betternotes.config.Settings;
import dev.nikhey.betternotes.listener.JoinListener;
import dev.nikhey.betternotes.storage.NoteStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class BetterNotesPlugin extends JavaPlugin {

    private volatile Settings settings;
    private NoteStore store;
    private NoteService service;
    private ScheduledExecutorService maintenance;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig());

        store = new NoteStore(new File(getDataFolder(), "notes.db"), getSLF4JLogger());
        try {
            store.init();
        } catch (Exception e) {
            getSLF4JLogger().error("Could not open the note database, disabling", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        service = new NoteService(this::settings, store, getSLF4JLogger());
        service.addSink(new DiscordAlerter(this::settings, getSLF4JLogger()));

        getServer().getPluginManager().registerEvents(new JoinListener(service), this);
        getServer().getServicesManager().register(NotesApi.class, service, this, ServicePriority.Normal);

        registerHooks();

        PluginCommand notes = getCommand("notes");
        if (notes != null) {
            NotesCommand executor = new NotesCommand(this, store, service);
            notes.setExecutor(executor);
            notes.setTabCompleter(executor);
        }
        PluginCommand watchlist = getCommand("watchlist");
        if (watchlist != null) {
            WatchlistCommand executor = new WatchlistCommand(store, service);
            watchlist.setExecutor(executor);
            watchlist.setTabCompleter(executor);
        }

        // Plain JDK scheduler keeps maintenance off server threads and Folia-safe.
        maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BetterNotes-Maintenance");
            t.setDaemon(true);
            return t;
        });
        maintenance.scheduleAtFixedRate(this::runMaintenance, 1, 60 * 12, TimeUnit.MINUTES);

        getSLF4JLogger().info("BetterNotes enabled - your staff team's institutional memory.");
    }

    /**
     * Optional integrations. Each hook class is only loaded (and its plugin
     * classes only touched) when the target plugin is actually installed, and
     * a failing hook is dropped with a warning instead of breaking the plugin.
     */
    private void registerHooks() {
        var pm = getServer().getPluginManager();
        if (pm.isPluginEnabled("PlaceholderAPI")) {
            tryHook("PlaceholderAPI", "registered %betternotes_*% placeholders", () ->
                    new dev.nikhey.betternotes.hook.NotesExpansion(this, service).register());
        }
        if (pm.isPluginEnabled("DiscordSRV")) {
            tryHook("DiscordSRV", "alerts route through its channels", () ->
                    service.addSink(new dev.nikhey.betternotes.hook.DiscordSrvSink(this::settings, getSLF4JLogger())));
        }
    }

    private void tryHook(String name, String what, Runnable registration) {
        try {
            registration.run();
            getSLF4JLogger().info("Hooked into {} - {}.", name, what);
        } catch (Throwable t) {
            getSLF4JLogger().warn("Could not enable the {} integration ({}). "
                    + "BetterNotes continues without it.", name, t.toString());
        }
    }

    private void runMaintenance() {
        store.watchlistPurgeExpired().whenComplete((removed, error) -> {
            if (error != null) {
                getSLF4JLogger().warn("Watchlist expiry sweep failed", error);
            } else if (removed != null && removed > 0) {
                getSLF4JLogger().info("Watchlist: removed {} expired entr{}",
                        removed, removed == 1 ? "y" : "ies");
            }
        });
        int days = settings.deletedRetentionDays();
        if (days <= 0) {
            return;
        }
        store.purgeDeletedOlderThan(days).whenComplete((deleted, error) -> {
            if (error != null) {
                getSLF4JLogger().warn("Retention purge failed", error);
            } else if (deleted != null && deleted > 0) {
                getSLF4JLogger().info("Retention: hard-deleted {} removed notes older than {} days",
                        deleted, days);
            }
        });
    }

    public Settings settings() {
        return settings;
    }

    public void reloadSettings() {
        reloadConfig();
        settings = Settings.load(getConfig());
    }

    @Override
    public void onDisable() {
        if (maintenance != null) {
            maintenance.shutdownNow();
        }
        if (store != null) {
            store.close();
        }
    }
}
