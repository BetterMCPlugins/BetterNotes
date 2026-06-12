# Listing copy for Modrinth / Hangar

Summary line (Modrinth "description" field, max ~250 chars):

> Staff notes and a watchlist with join alerts — institutional memory for your staff team. Severities, pinned notes, full-text search, auto-expiring watchlist, Discord alerts, accountable soft-delete. Lightweight, free.

Suggested tags: `admin-tools`, `moderation`, `utility`. Loaders: Paper, Folia. Versions: 1.21+.

---

## Your best moderator just logged off. Did their knowledge leave with them?

Every staff team keeps notes somewhere — a Discord channel, a Google doc, someone's head. None of that is in the game when the player in question joins at 3am and the only moderator online is the one hired last week.

BetterNotes puts your team's memory where the action is:

**Notes on players, in game, forever.** `/notes add Steve alert confirmed alt of Alex` — three severities (`info`, `warn`, `alert`), author and timestamp on every note, one pinnable note per player for the thing everyone must see first. Works for offline players too.

**A watchlist that watches for you.** Watchlisted players trigger a staff alert the moment they join — with the reason, who listed them, and how many notes they carry. One click opens the full record. Temporary entries (`/watchlist add Steve 7d suspected xray`) expire on their own.

**Catch-up in one command.** `/notes recent` shows what your team wrote while you were away; `/notes search xray` finds every note that ever mentioned it.

**Accountability built in.** Removing a note doesn't erase it — the record keeps what was removed, by whom and when, visible as a count in the player's history. Hard deletion exists exactly once: the admin purge command, which is also your GDPR answer. Same philosophy as [BetterAudit](https://modrinth.com/plugin/betteraudit), same workflow DNA as [BetterReports](https://modrinth.com/plugin/betterreports).

### Integrations (all optional, auto-detected)

PlaceholderAPI (`%betternotes_count%`, `%betternotes_watchlisted%` for staff scoreboards) · DiscordSRV (watchlist joins and alert notes in your existing channels, zero setup) · raw Discord webhooks (no other plugin needed)

For developers: a `NotesApi` Bukkit service + events (`NoteAddEvent`, `WatchlistAddEvent`, ...) for your own integrations.

### Built the way you'd want it built

- Async SQLite storage, never on the main thread; ~60KB jar, zero dependencies
- Folia supported, Paper 1.21+
- Free, open source (MIT), part of the BetterMCPlugins suite
