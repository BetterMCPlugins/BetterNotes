# BetterNotes — Concept

*The third free plugin of the BetterMCPlugins suite. Status: concept, 2026-06-12.*

## One-line pitch

> Institutional memory for your staff team: notes on players, a watchlist with join alerts, and a full history of who wrote what — searchable, accountable, and free.

## Why this niche

Same playbook as BetterAudit: strong demand, dead competition.

- **Staff Notes** (SpigotMC) — last touched 2017
- **Notes** (Bukkit) — officially abandoned
- The only living option is **Staff++**, a kitchen-sink moderation bundle. Servers that already have reports/bans/staff-mode plugins don't want a second bundle just for notes — they want one focused plugin that does notes well.

Strategically, BetterNotes is the glue of the suite. Notes + BetterReports + BetterAudit = a complete **player dossier**, which becomes the flagship page of BetterPanel: one profile view per player merging all three data sources. Every install of any free plugin makes the panel more valuable.

It is also the cheapest of the candidates to build: no evidence buffers, no anti-abuse mechanics, no GUI required for v1. Architecture is a straight copy of BetterReports.

## Feature set (v1.0)

**Notes**
- `/notes add <player> [severity] <text>` — severities `info` (default), `warn`, `alert`; works for offline players who have joined before (OfflinePlayer cache lookup, async)
- `/notes view <player>` — paginated, newest first, pinned note on top; hover shows full timestamp + author
- `/notes pin <id>` / `/notes unpin <id>` — one pinned note per player (e.g. "confirmed alt of X")
- `/notes remove <id>` — soft delete: the row stays, marked deleted-by-whom-when (accountability is the suite's brand; hard removal only via admin purge)
- `/notes search <text>` — full-text LIKE search across all notes
- `/notes recent` — last N notes server-wide (staff catch-up after time off)

**Watchlist**
- `/watchlist add <player> [duration] [reason]` — optional auto-expiry (`7d`, `30d`)
- `/watchlist remove <player>`, `/watchlist list`
- Join alert to everyone with `betternotes.notify`: `⚠ Watchlisted player Steve joined (reason: …, 3 notes)` — clickable, runs `/notes view`
- Watchlist joins optionally mirrored to Discord via DiscordSRV (same warn-once sink pattern as BetterReports)

**Join summary (config-off by default)**
- Optional quiet notice to notify-staff when a player with ≥1 `alert`-severity note joins. Watchlist alerts stay the loud channel; this avoids alert fatigue.

**Anti-clutter / data hygiene**
- `/notes purge <player|olderthan <time>>` — admin-only hard delete (also the GDPR answer)
- Config: max note length, default `/notes recent` size

## Cross-plugin integration

- **Custom Bukkit events**: `NoteAddEvent`, `NoteRemoveEvent`, `WatchlistAddEvent`, `WatchlistRemoveEvent` — fired so BetterAudit can log note activity in a future release without a compile-time dependency (tryHook pattern, same as everywhere else in the suite).
- **ServicesManager API**: a tiny `NotesService` (note count + watchlist status by UUID) registered on enable. BetterReports' next release can show "target has 4 notes ⚠" in the report detail view and offer a clickable "[add note]" after resolve. The panel uses the same service for write actions.
- **PlaceholderAPI**: `%betternotes_count%`, `%betternotes_count_<severity>%`, `%betternotes_watchlisted%`.
- **DiscordSRV**: optional channel for watchlist joins and `alert` notes.

Hooks ship in v1.0: PlaceholderAPI, DiscordSRV (mirroring BetterReports exactly). LuckPerms etc. not needed — permissions are plain Bukkit.

## Architecture

Identical to BetterReports, deliberately: Paper API 1.21.4, Java 21, Maven, single-worker-thread SQLite (WAL), Folia-safe via player schedulers + JDK executors, tryHook graceful degradation, MIT license, repo under github.com/BetterMCPlugins.

### Schema (the panel contract, from day one)

BetterNotes is the first suite plugin to ship `schema_meta` in its **initial** release — BetterAudit/BetterReports retrofit it in their next ones.

```sql
CREATE TABLE schema_meta (
    version INTEGER NOT NULL          -- starts at 1; panel pins supported ranges
);

CREATE TABLE notes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    time INTEGER NOT NULL,            -- epoch millis, like the sibling plugins
    target_uuid TEXT NOT NULL,
    target_name TEXT NOT NULL,        -- name at write time (display without lookups)
    author_uuid TEXT,                 -- NULL = console
    author_name TEXT NOT NULL,
    severity TEXT NOT NULL,           -- INFO | WARN | ALERT
    pinned INTEGER NOT NULL DEFAULT 0,
    text TEXT NOT NULL,
    deleted_time INTEGER NOT NULL DEFAULT 0,   -- soft delete
    deleted_by_uuid TEXT,
    deleted_by_name TEXT
);
CREATE INDEX idx_notes_target ON notes(target_uuid);
CREATE INDEX idx_notes_author ON notes(author_uuid);

CREATE TABLE watchlist (
    target_uuid TEXT PRIMARY KEY,
    target_name TEXT NOT NULL,
    time INTEGER NOT NULL,
    author_uuid TEXT,
    author_name TEXT NOT NULL,
    reason TEXT,
    expire_time INTEGER NOT NULL DEFAULT 0     -- 0 = never
);
```

### Commands & permissions (plugin.yml sketch)

```yaml
commands:
  notes:
    usage: /notes <add|view|pin|unpin|remove|search|recent|purge|reload> [args]
    aliases: [note, bnotes]
    permission: betternotes.staff
  watchlist:
    usage: /watchlist <add|remove|list>
    aliases: [wl]
    permission: betternotes.staff

permissions:
  betternotes.staff:        # view notes, watchlist list        (default: op)
  betternotes.add:          # add notes / watchlist entries     (default: op)
  betternotes.remove.own:   # soft-delete own notes             (default: op)
  betternotes.remove.any:   # soft-delete anyone's notes        (default: op)
  betternotes.notify:       # watchlist join + alert-note pings (default: op)
  betternotes.admin:        # purge, reload                     (default: op)
```

## Explicitly not in v1

- GUI menu (text components first; GUI in v1.1 reusing the ReportsMenu pattern)
- Note templates / categories beyond the three severities
- Alt detection (separate problem; note text + pin covers the manual workflow)
- Velocity/multi-server sync (suite-wide v2 topic, same as the panel)
- bStats (consistent with the other plugins — deliberately skipped for now)

## Launch plan

1. Build + smoke test (local Paper 1.21.4 with JDK 21, `-DPaper.DisableSpark=true`; Folia boot test), deploy to survivalLegends-test alongside the siblings.
2. CI workflow (same mvn-package action as the other two repos), GitHub issue forms, docs/listing.md.
3. Publish github.com/BetterMCPlugins/BetterNotes, release v1.0.0 — ideally **together with** the Modrinth/Hangar listings of all three plugins (one "suite launch" moment beats three trickles).
4. README roadmap mentions the shared dashboard, like the siblings.

## Open questions (decide before code)

1. Name final? **BetterNotes** — checked 2026-06-12: no plugin of that name on Modrinth/SpigotMC (closest is "JustNote", a different concept).
2. Severity names: `info/warn/alert` vs `low/medium/high`? Leaning info/warn/alert (matches log-level intuition).
3. Should the report-resolve "[add note]" prompt live in BetterNotes (listening to a future BetterReports event) or in BetterReports (querying NotesService)? Leaning BetterReports-side via NotesService — no new event needed in v1.
