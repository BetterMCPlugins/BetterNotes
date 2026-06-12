# BetterNotes

[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Paper](https://img.shields.io/badge/Paper-1.21%2B-blue.svg)](https://papermc.io)
[![Folia](https://img.shields.io/badge/Folia-supported-blue.svg)](https://papermc.io/software/folia)
[![Discord](https://img.shields.io/badge/support-Discord-5865F2.svg)](https://discord.gg/UfnyJgbY4P)

**Staff notes and a watchlist — institutional memory for your staff team.** The moderator who handled the incident logs off; the knowledge shouldn't. BetterNotes keeps notes on players, alerts staff when a watchlisted player joins, and keeps a full record of who wrote — and who removed — what.

## Why this exists

Every staff team keeps notes somewhere: a Discord channel, a Google doc, someone's memory. None of that is there when it matters — in the game, the moment the player joins. The plugins that solved this died years ago, and the alternatives bundle notes into 50-feature moderation suites you don't want.

BetterNotes does one thing well:

1. **Notes where the action is.** `/notes add Steve alert confirmed alt of Alex` — visible to every staff member, forever, with author and timestamp.
2. **The watchlist watches for you.** Watchlisted players trigger a staff alert on join — with the reason, who listed them and how many notes they carry. Optional auto-expiry (`7d`) for "keep an eye on them this week" cases.
3. **Accountability built in.** Removing a note doesn't erase it — the record keeps what was removed, by whom, when. Same philosophy as [BetterAudit](https://github.com/BetterMCPlugins/BetterAudit).
4. **Catch-up in one command.** `/notes recent` shows what the team wrote while you were away.

## Features

- **Severities**: `info`, `warn`, `alert` — alert notes notify online staff the moment they are written.
- **Pinned note**: one per player, always on top (`confirmed alt of X` belongs above everything else).
- **Watchlist with join alerts**: optional duration (`30m`, `12h`, `7d`, `2w`), auto-expiring entries, clickable alerts that open the player's notes.
- **Full-text search** across all notes, plus a server-wide recent feed.
- **Soft delete**: removed notes stay in the record (shown as a count, hard-deleted after a configurable retention period). Hard delete only via the admin purge — which is also your GDPR answer.
- **Discord alerts** for watchlist joins and alert notes — webhook or DiscordSRV.
- **Works for offline players** — anyone who has ever joined can be noted or watchlisted.
- **Zero hard dependencies, async everywhere**: SQLite storage on a dedicated thread, never on the main thread. Folia-supported.

## Integrations (all optional, auto-detected)

| Plugin | What you get |
|---|---|
| **PlaceholderAPI** | `%betternotes_count%`, `%betternotes_count_alert%`, `%betternotes_watchlisted%` — e.g. for staff scoreboards |
| **DiscordSRV** | Alerts route through your existing DiscordSRV channels — no webhook setup needed |

For plugin developers: BetterNotes registers a `NotesApi` service in Bukkit's ServicesManager (add note, remove note, note count, watchlist status) and fires `NoteAddEvent`, `NoteRemoveEvent`, `WatchlistAddEvent`, `WatchlistRemoveEvent`.

## Commands

| Command | Description | Permission |
|---|---|---|
| `/notes add <player> [info\|warn\|alert] <text>` | Add a note (severity defaults to `info`) | `betternotes.add` |
| `/notes view <player> [page]` | All notes on a player, pinned first | `betternotes.staff` |
| `/notes pin <id>` / `unpin <id>` | Pin a note to the top (one per player) | `betternotes.add` |
| `/notes remove <id>` | Soft-delete a note (kept in the record) | `betternotes.remove.own` / `.any` |
| `/notes search <text>` | Full-text search across all notes | `betternotes.staff` |
| `/notes recent [count]` | Newest notes server-wide | `betternotes.staff` |
| `/notes purge <player <name>\|olderthan <days>>` | Hard delete (GDPR) | `betternotes.admin` |
| `/notes reload` | Reload the configuration | `betternotes.admin` |
| `/watchlist add <player> [duration] [reason]` | Watch a player, optionally temporary | `betternotes.add` |
| `/watchlist remove <player>` | Remove from the watchlist | `betternotes.add` |
| `/watchlist list` | All watchlisted players | `betternotes.staff` |

Aliases: `/note`, `/bnotes`, `/wl`.

## Permissions

- `betternotes.staff` — view notes and the watchlist (default: op)
- `betternotes.add` — add notes/watchlist entries, pin notes (default: op)
- `betternotes.remove.own` / `betternotes.remove.any` — soft-delete own / anyone's notes (default: op)
- `betternotes.notify` — watchlist join alerts and alert-note notices (default: op)
- `betternotes.admin` — purge and reload (default: op)

## Building

```
mvn package
```

Requires Java 21+. The jar lands in `target/BetterNotes-<version>.jar`. Drop it into `plugins/` on any Paper 1.21+ server.

## Support

Questions, bug reports, feature ideas — join the [Discord server](https://discord.gg/UfnyJgbY4P) or open a GitHub issue.

## License

MIT — see [LICENSE](LICENSE).

## Roadmap

- GUI for browsing notes (`/notes` menu)
- "Add note" quick-action after resolving a [BetterReports](https://github.com/BetterMCPlugins/BetterReports) report
- Web dashboard, shared with BetterAudit and BetterReports (paid tier)
- Velocity network sync (paid tier)
