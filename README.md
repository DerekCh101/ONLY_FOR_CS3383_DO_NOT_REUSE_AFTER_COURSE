# Ashore Prospect

JavaFX calendar and Typewriter notes. Offline-first on this PC, optional multi-device sync through Node + Postgres.

**If you are a power user and need the exact rules** (sync / last-write-wins, forget-password, remember-me, reminders, encryption, delete-account, Run Scheduler, week occupancy / overlay, nested events, window snap, SMTP files), read **[ADVANCE_BEHAVIOUR.md](ADVANCE_BEHAVIOUR.md)** instead of this file. This page only lists what the product does.

## Features

- Week, day, and month calendars. Week has a live “now” line and drag-to-move / resize. Day shows yesterday / today / tomorrow descriptions only. Date and time edits live on the week grid.
- Times are stored to the minute. Week **occupancy** paints rounded 30-minute cells; an optional **Real-time overlay** (View menu, on by default) draws the exact-minute bar on the parent task only.
- Dark week tiles: empty now/future `#243044`, today empty `#2e3c56`, past empty `#0b0c0e`; occupancy live `#2f92e0`, occupancy after the task ended `#5c636c`. Light theme keeps the older blue occupancy.
- Tasks: **fixed** (exact start), **deadline** (place before a cutoff), **flexible** (first free slot). No category or subcategory.
- Nested **quick events** are attributes of a placed parent (clock + name + minutes under the parent name). Clicks always open the parent. They do not occupy the grid, do not get a live overlay, and must stay inside the parent window. Finish or delete the parent finishes/deletes them.
- Description checklist (`[x]` / `[ ]` lines); Finish / Unfinish for placed tasks. Unfinished leftover past work is noted on a later session with the **same name**.
- Blocked times: every-day vs a date or range, plus an exception from that pattern. Tasks cannot sit on blocked time; two blocked rows of the same kind cannot overlap on both clock and calendar day.
- Floating **+** and Action menu: add task, blocked times, view task list, import file, Run Scheduler, Clear Past, Clear All
- Undo / redo for recorded calendar commands (including after Clear Past / Clear All)
- Typewriter: Pages, Diary, Lists; images by SHA-256; in-page line numbers; lines / words / letters. Diary days with writing are underlined. The HTML editor stores unwrapped plain text, not a full HTML page dump. Host writes Typewriter through Node into Postgres (`tw_diary` / `tw_pages` / `tw_lists`); a local cache wipe flushes that first so the server copy stays.
- Account split: **User info**, **Security**, **Notifications**, **Device**. Idle logout on this PC (Device): default 120 minutes, or Never / 5–180 minutes. Idle only signs you out; refresh does not.
- Reminders (default on): Windows popup + in-app banner. Tick 5 min / 10 min / 30 min / 1 hour / 2 hours / 1 day / when it starts, or add a custom lead. Optional sound.
- Optional encrypt-at-rest (AES-GCM, `AP1.` prefix) so pgAdmin cannot read task text. Account password; Security option, not idle or reload.
- Delete this account (password + confirm). Other devices wipe that account’s local cache the next time they open and reach the server.
- Remember login on this PC (opens the local cache next launch, including offline)
- Optional random security check after a remember-me auto-login
- Register requires email + security question (needed to recover)
- Forgot password: token + security question are sent together; token lasts 8 minutes
- Host-only SMTP page: App password is stored as OS env `SMTP_PASS` (Windows `setx`, Linux user env — not in the project folder)
- Custom title bar: fill the desktop work area (taskbar stays); drag the top bar to move
- Dark / light theme
- Online / offline chip; work continues from the local SQLite cache when the net is down
- Multi-device sync when the host Node + Postgres are up
- Browser UI on the same Node (`/`) with Calendar / Typewriter / Account. Same login, reminders, nested events, occupancy + overlay, and delete-account. Unlock encrypted data in Account → Security. No auto-scheduler, undo, or drag.
- CLI for the same task commands without JavaFX
