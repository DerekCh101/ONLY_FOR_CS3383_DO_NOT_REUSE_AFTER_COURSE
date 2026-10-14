# Ashore Prospect

JavaFX calendar and Typewriter notes. Offline-first on this PC, optional multi-device sync through Node + Postgres.

**If you are a power user and need the exact rules** (sync / last-write-wins, forget-password, remember-me, random security logout, Run Scheduler, window snap, SMTP env), read **[ADVANCE_BEHAVIOUR.md](ADVANCE_BEHAVIOUR.md)** instead of this file. This page only lists what the product does.

## Features

- Week and month calendars with a live “now” line
- Tasks: **fixed** (exact slot), **deadline** (place before a cutoff), **flexible** (first free slot)
- Drag a task to another slot; finish / unfinish
- Blocked time: weekday repeat or one-off date
- Floating **+** and Action menu: add task, blocked times, import file, Run Scheduler, Clear Past, Clear All
- Undo / redo for recorded commands (including after Clear Past / Clear All)
- Typewriter: notes, lists, folders, images
- Account: username, email, password, security question, sessions, theme
- Remember login on this PC (opens the local cache next launch, including offline)
- Optional random security check after a remember-me auto-login
- Register requires email + security question (needed to recover)
- Forgot password: token + security question are sent together; token lasts 8 minutes
- Host-only SMTP page: the App password is stored as `SMTP_PASS` (Windows env)
- Custom title bar: fill the desktop work area (taskbar stays); drag the top bar to move
- Dark / light theme
- Online / offline chip; work continues from the local SQLite cache when the net is down
- Multi-device sync when the host Node + Postgres are up
- CLI for the same task commands without JavaFX
