package dev.nikhey.betternotes.storage;

import dev.nikhey.betternotes.model.Note;
import dev.nikhey.betternotes.model.Severity;
import dev.nikhey.betternotes.model.WatchlistEntry;
import org.slf4j.Logger;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SQLite-backed store. All access is funneled through a single worker thread,
 * so the plugin never touches the database from a server thread.
 *
 * The schema is a versioned contract (schema_meta.version) - external readers
 * such as BetterPanel pin supported ranges against it.
 */
public final class NoteStore {

    /** Bump on any breaking schema change; external readers check this. */
    public static final int SCHEMA_VERSION = 1;

    /** Per-target counts of active notes by severity, plus removed-note count. */
    public record Counts(int info, int warn, int alert, int deleted) {
        public int active() {
            return info + warn + alert;
        }
    }

    private final File file;
    private final Logger logger;
    private final ExecutorService io;
    private Connection conn;

    public NoteStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "BetterNotes-DB");
            t.setDaemon(true);
            return t;
        });
    }

    public void init() throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create data folder " + parent);
        }
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("CREATE TABLE IF NOT EXISTS schema_meta (version INTEGER NOT NULL)");
            try (ResultSet rs = st.executeQuery("SELECT version FROM schema_meta LIMIT 1")) {
                if (!rs.next()) {
                    st.execute("INSERT INTO schema_meta (version) VALUES (" + SCHEMA_VERSION + ")");
                }
            }
            st.execute("""
                    CREATE TABLE IF NOT EXISTS notes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        time INTEGER NOT NULL,
                        target_uuid TEXT NOT NULL,
                        target_name TEXT NOT NULL,
                        author_uuid TEXT,
                        author_name TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        pinned INTEGER NOT NULL DEFAULT 0,
                        text TEXT NOT NULL,
                        deleted_time INTEGER NOT NULL DEFAULT 0,
                        deleted_by_uuid TEXT,
                        deleted_by_name TEXT
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_notes_target ON notes(target_uuid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_notes_author ON notes(author_uuid)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS watchlist (
                        target_uuid TEXT PRIMARY KEY,
                        target_name TEXT NOT NULL,
                        time INTEGER NOT NULL,
                        author_uuid TEXT,
                        author_name TEXT NOT NULL,
                        reason TEXT,
                        expire_time INTEGER NOT NULL DEFAULT 0
                    )""");
        }
    }

    /** Inserts a new note and returns its id. */
    public CompletableFuture<Long> create(Note draft) {
        CompletableFuture<Long> future = new CompletableFuture<>();
        io.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO notes (time, target_uuid, target_name, author_uuid, author_name, severity, pinned, text)
                    VALUES (?,?,?,?,?,?,0,?)""", Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, draft.time());
                ps.setString(2, draft.targetUuid().toString());
                ps.setString(3, draft.targetName());
                ps.setString(4, draft.authorUuid() == null ? null : draft.authorUuid().toString());
                ps.setString(5, draft.authorName());
                ps.setString(6, draft.severity().name());
                ps.setString(7, draft.text());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("No generated key for note insert");
                    }
                    future.complete(keys.getLong(1));
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Optional<Note>> byId(long id) {
        return queryOne("SELECT * FROM notes WHERE id = ?", ps -> ps.setLong(1, id));
    }

    /** Active notes for a target - pinned first, then newest first. */
    public CompletableFuture<List<Note>> notesFor(UUID target, int limit, int offset) {
        return query("SELECT * FROM notes WHERE target_uuid = ? AND deleted_time = 0 "
                        + "ORDER BY pinned DESC, time DESC, id DESC LIMIT ? OFFSET ?",
                ps -> {
                    ps.setString(1, target.toString());
                    ps.setInt(2, limit);
                    ps.setInt(3, offset);
                });
    }

    public CompletableFuture<Counts> countsFor(UUID target) {
        CompletableFuture<Counts> future = new CompletableFuture<>();
        io.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT SUM(CASE WHEN deleted_time = 0 AND severity = 'INFO' THEN 1 ELSE 0 END) info,
                           SUM(CASE WHEN deleted_time = 0 AND severity = 'WARN' THEN 1 ELSE 0 END) warn,
                           SUM(CASE WHEN deleted_time = 0 AND severity = 'ALERT' THEN 1 ELSE 0 END) alert,
                           SUM(CASE WHEN deleted_time > 0 THEN 1 ELSE 0 END) deleted
                    FROM notes WHERE target_uuid = ?""")) {
                ps.setString(1, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    future.complete(rs.next()
                            ? new Counts(rs.getInt("info"), rs.getInt("warn"), rs.getInt("alert"),
                                    rs.getInt("deleted"))
                            : new Counts(0, 0, 0, 0));
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** Marks a note deleted but keeps the row - removals stay accountable. */
    public CompletableFuture<Boolean> softDelete(long id, UUID by, String byName, long time) {
        return update("UPDATE notes SET deleted_time = ?, deleted_by_uuid = ?, deleted_by_name = ? "
                        + "WHERE id = ? AND deleted_time = 0",
                ps -> {
                    ps.setLong(1, time);
                    ps.setString(2, by == null ? null : by.toString());
                    ps.setString(3, byName);
                    ps.setLong(4, id);
                }).thenApply(rows -> rows > 0);
    }

    /** Pins a note; any previously pinned note of the same target is unpinned. */
    public CompletableFuture<Boolean> pin(long id) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        io.execute(() -> {
            try {
                String target = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT target_uuid FROM notes WHERE id = ? AND deleted_time = 0")) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            target = rs.getString("target_uuid");
                        }
                    }
                }
                if (target == null) {
                    future.complete(false);
                    return;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE notes SET pinned = 0 WHERE target_uuid = ? AND pinned = 1")) {
                    ps.setString(1, target);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE notes SET pinned = 1 WHERE id = ?")) {
                    ps.setLong(1, id);
                    future.complete(ps.executeUpdate() > 0);
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Boolean> unpin(long id) {
        return update("UPDATE notes SET pinned = 0 WHERE id = ? AND pinned = 1",
                ps -> ps.setLong(1, id)).thenApply(rows -> rows > 0);
    }

    /** Full-text LIKE search across active notes, newest first. */
    public CompletableFuture<List<Note>> search(String text, int limit) {
        String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return query("SELECT * FROM notes WHERE deleted_time = 0 AND text LIKE ? ESCAPE '\\' "
                        + "ORDER BY time DESC, id DESC LIMIT ?",
                ps -> {
                    ps.setString(1, "%" + escaped + "%");
                    ps.setInt(2, limit);
                });
    }

    /** Newest active notes server-wide - the staff catch-up view. */
    public CompletableFuture<List<Note>> recent(int limit) {
        return query("SELECT * FROM notes WHERE deleted_time = 0 ORDER BY time DESC, id DESC LIMIT ?",
                ps -> ps.setInt(1, limit));
    }

    /** Hard-deletes all notes and the watchlist entry of a player (the GDPR answer). */
    public CompletableFuture<Integer> purgePlayer(UUID target) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        io.execute(() -> {
            try {
                int deleted;
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM notes WHERE target_uuid = ?")) {
                    ps.setString(1, target.toString());
                    deleted = ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM watchlist WHERE target_uuid = ?")) {
                    ps.setString(1, target.toString());
                    ps.executeUpdate();
                }
                future.complete(deleted);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Hard-deletes all notes (active and deleted) older than the given number of
     * days. Guards days &lt; 1 defensively: a 0/negative cutoff would match every
     * row and wipe the table (the command layer also rejects it).
     */
    public CompletableFuture<Integer> purgeOlderThan(int days) {
        if (days < 1) {
            return CompletableFuture.completedFuture(0);
        }
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        return update("DELETE FROM notes WHERE time < ?", ps -> ps.setLong(1, cutoff));
    }

    /** Hard-deletes soft-deleted notes whose deletion is older than the given number of days. */
    public CompletableFuture<Integer> purgeDeletedOlderThan(int days) {
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        return update("DELETE FROM notes WHERE deleted_time > 0 AND deleted_time < ?",
                ps -> ps.setLong(1, cutoff));
    }

    /** Adds or refreshes a watchlist entry. Returns true when it was newly created. */
    public CompletableFuture<Boolean> watchlistPut(WatchlistEntry entry) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        io.execute(() -> {
            try {
                boolean existed;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT 1 FROM watchlist WHERE target_uuid = ?")) {
                    ps.setString(1, entry.targetUuid().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        existed = rs.next();
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO watchlist (target_uuid, target_name, time, author_uuid, author_name, reason, expire_time)
                        VALUES (?,?,?,?,?,?,?)
                        ON CONFLICT(target_uuid) DO UPDATE SET
                            target_name = excluded.target_name,
                            time = excluded.time,
                            author_uuid = excluded.author_uuid,
                            author_name = excluded.author_name,
                            reason = excluded.reason,
                            expire_time = excluded.expire_time""")) {
                    ps.setString(1, entry.targetUuid().toString());
                    ps.setString(2, entry.targetName());
                    ps.setLong(3, entry.time());
                    ps.setString(4, entry.authorUuid() == null ? null : entry.authorUuid().toString());
                    ps.setString(5, entry.authorName());
                    ps.setString(6, entry.reason());
                    ps.setLong(7, entry.expireTime());
                    ps.executeUpdate();
                }
                future.complete(!existed);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Boolean> watchlistRemove(UUID target) {
        return update("DELETE FROM watchlist WHERE target_uuid = ?",
                ps -> ps.setString(1, target.toString())).thenApply(rows -> rows > 0);
    }

    /** The non-expired watchlist entry for a target, if any. */
    public CompletableFuture<Optional<WatchlistEntry>> watchlistGet(UUID target) {
        CompletableFuture<Optional<WatchlistEntry>> future = new CompletableFuture<>();
        long now = System.currentTimeMillis();
        io.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM watchlist WHERE target_uuid = ? AND (expire_time = 0 OR expire_time > ?)")) {
                ps.setString(1, target.toString());
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    future.complete(rs.next() ? Optional.of(readWatchlist(rs)) : Optional.empty());
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** All non-expired watchlist entries, newest first. */
    public CompletableFuture<List<WatchlistEntry>> watchlistAll() {
        CompletableFuture<List<WatchlistEntry>> future = new CompletableFuture<>();
        long now = System.currentTimeMillis();
        io.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM watchlist WHERE expire_time = 0 OR expire_time > ? ORDER BY time DESC")) {
                ps.setLong(1, now);
                try (ResultSet rs = ps.executeQuery()) {
                    List<WatchlistEntry> entries = new ArrayList<>();
                    while (rs.next()) {
                        entries.add(readWatchlist(rs));
                    }
                    future.complete(entries);
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** Removes expired watchlist entries; called by the maintenance scheduler. */
    public CompletableFuture<Integer> watchlistPurgeExpired() {
        long now = System.currentTimeMillis();
        return update("DELETE FROM watchlist WHERE expire_time > 0 AND expire_time <= ?",
                ps -> ps.setLong(1, now));
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private CompletableFuture<Integer> update(String sql, Binder binder) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        io.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                binder.bind(ps);
                future.complete(ps.executeUpdate());
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private CompletableFuture<Optional<Note>> queryOne(String sql, Binder binder) {
        return query(sql, binder).thenApply(list -> list.isEmpty()
                ? Optional.empty()
                : Optional.of(list.getFirst()));
    }

    private CompletableFuture<List<Note>> query(String sql, Binder binder) {
        CompletableFuture<List<Note>> future = new CompletableFuture<>();
        io.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Note> notes = new ArrayList<>();
                    while (rs.next()) {
                        notes.add(read(rs));
                    }
                    future.complete(notes);
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private static Note read(ResultSet rs) throws SQLException {
        Severity severity = Severity.parse(rs.getString("severity")).orElse(Severity.INFO);
        return new Note(
                rs.getLong("id"),
                rs.getLong("time"),
                UUID.fromString(rs.getString("target_uuid")),
                rs.getString("target_name"),
                optionalUuid(rs.getString("author_uuid")),
                rs.getString("author_name"),
                severity,
                rs.getInt("pinned") != 0,
                rs.getString("text"),
                rs.getLong("deleted_time"),
                optionalUuid(rs.getString("deleted_by_uuid")),
                rs.getString("deleted_by_name"));
    }

    private static WatchlistEntry readWatchlist(ResultSet rs) throws SQLException {
        return new WatchlistEntry(
                UUID.fromString(rs.getString("target_uuid")),
                rs.getString("target_name"),
                rs.getLong("time"),
                optionalUuid(rs.getString("author_uuid")),
                rs.getString("author_name"),
                rs.getString("reason"),
                rs.getLong("expire_time"));
    }

    private static UUID optionalUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (SQLException e) {
            logger.warn("Failed to close note database cleanly", e);
        }
    }
}
