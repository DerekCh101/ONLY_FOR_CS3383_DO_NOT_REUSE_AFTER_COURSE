# Advance behaviour

Exact rules for Ashore Prospect. The main [README](README.md) only lists features. This file is the source of truth for how those features actually behave.

Do not put mailbox passwords in chat or in git. `.scheduler/` is gitignored.

---

## 1. Window: work-area snap, not hard fullscreen

The stage is undecorated. A themed title bar (– □ ✕) sits at the top.

- Launch and “max” call `WindowFrame.snapWorkArea()`.
- That sizes the window to the **primary screen visual bounds** (the desktop minus the Windows taskbar).
- The app **never** uses exclusive fullscreen (`setFullScreen` stays false). It also does not use JavaFX `setMaximized`, which on an undecorated stage often covers the taskbar.
- The title bar stays visible. Click and drag it to move the window.
- If the window is snapped and you drag the title bar, it first restores the previous size, then follows the mouse (normal Windows unsnap).
- Double-click the title bar, or the □ / ❐ button, toggles snap / restore.
- When not snapped, you can resize from a 6 px edge. Minimum size is 720 × 480.

---

## 2. Data path (phone-book write)

Every change on this PC is written in this order:

1. **SQLite** `scheduler-cache.db` in the working directory (always).
2. **Postgres** on the host, if this install opened JDBC (skipped on the creator host — Node owns that DB).
3. **Node HTTP**, if a Bearer session token exists.

Reads at login / load:

1. If the cached `user_id` is a different account, wipe the local task + Typewriter cache for that PC.
2. If offline, load SQLite only.
3. If online and JDBC is up, two-way merge with Postgres.
4. If online and a token exists, pull Node and merge into SQLite, then load memory.

A daemon **node-poll** thread (every 4 s) pulls Node again and reloads the calendar. A **network-watch** thread (every 2 s) only checks NIC up/down for the online chip and toasts.

Android is always a client of the same Node. JavaFX is the host when it talks to Node on this machine (`127.0.0.1` / `localhost`) or to the configured creator-host URL. `deviceId` is registry / GC only — it is **not** the host signal.

---

## 3. Sync rule (last-write-wins)

There are no CRDTs and no extra revision columns. Two ISO instants decide:

| Field | What it covers |
| --- | --- |
| `last_updated_at` | Name, duration, type, deadline, start, lifecycle, finish/unfinish |
| `placement_updated_at` | Only the placed date/time on the grid |

### Who wins

`TaskRepository.preferRemote(localTs, remoteTs, hostDevice)`:

1. Remote timestamp missing (including **both** null) → keep **local**. There is no Node clock to prefer.
2. Local missing, remote present → take **remote**.
3. Remote is strictly after local → take **remote**.
4. Local is strictly after remote → keep **local**.
5. **Equal instants** (`compareTo == 0`):
   - Host device → keep **local**.
   - Client → take **remote**.

Newer always wins. The host/client flag is only the same-timestamp tie.

### Task merge details

- Start from a copy of local, then overlay the winning side’s metadata fields from the `last_updated_at` winner.
- Placement is separate:
  - Remote has a slot and local does not → take remote’s slot.
  - Both have a slot → `preferRemote` on `placement_updated_at`.
  - Local has a slot and remote does not → keep local’s slot.

Blocked times use the same `preferRemote` on `last_updated_at` (whole row, no split placement clock).

Typewriter pages / lists use the same `preferRemote` on the bundle `last_updated_at`. Images are content-addressed (SHA-256) on Node.

### Deletes (tombstones)

- Delete writes the row away and records the id in `sync_deletes` (tasks) or the blocked-delete log.
- `dbAddTask` **skips** an id that is still tombstoned. Undo of Clear Past / Clear All must use `dbRestoreTask`: unrecord the tombstone, bump `last_updated_at`, upsert, then copy to Postgres/Node.
- **Postgres** two-way sync: if local tombstoned an id that still exists on Postgres, the Postgres row is deleted. A local-only row that is **not** newer than `last_postgres_sync_at` is treated as “already known and gone on the server” and is dropped + tombstoned. A local-only row **newer** than last sync is pushed.
- **Node** pull: if Node still has an id that was locally tombstoned, the local tombstone is **cleared** and the Node row is merged in. A local-only row not newer than `last_node_sync_at` is dropped + tombstoned (it vanished from Node after the last successful pull).

Tombstone GC: stale ~3 months, abandoned ~12 months (`STALE_MONTHS` / `ABANDONED_MONTHS`).

---

## 4. Remember me

Login checkbox **Remember login**. Default is on if `.scheduler/login.txt` has never said otherwise.

When checked at login / register:

- `.scheduler/login.txt` stores `remember=true` and `lastUser`.
- `.scheduler/session.txt` stores `userId`, `username`, Bearer `token`.

When unchecked: prefs say `remember=false` and `session.txt` is deleted.

**Boot:** `SchedulerApp` calls `restoreSession()`. If remember is on and `session.txt` is present, the login screen is skipped and the main window opens on the **local cache**. This works **offline**. The token is used later when Node is reachable.

Logout (Account, idle, failed challenge, revoke-this-PC) clears `session.txt`. Typewriter is saved on a normal logout, **not** on a failed remember-me challenge (see below).

Idle auto-logout: **100 minutes** without any input on the main scene.

---

## 5. Remember-me random security logout

Opt-in only. Account → **Random security check on Remember-me login**. Stored as `randomChallenge=true` in `.scheduler/login.txt`. Default is **off**.

On each main-window load, `maybeRememberChallenge()` runs if **all** of these are true:

1. Remember me is on.
2. Random challenge is on.
3. `NetworkWatch` says a real NIC is up (not loopback).
4. `Math.random() <= 0.18` — about **18%** of those launches.

Then it waits **1.2 s** and asks Node `GET /challenge-question`. If the account has no security question, the overlay is **not** shown.

The answer is posted to Node `POST /challenge-answer`. Node bcrypt-compares a **normalized** answer (trim, lowercase, collapse spaces). Nothing to brute-force in the SQLite cache.

- Correct → overlay closes, session stays.
- Wrong, or **Logout** on the overlay → `wipeCacheAndLogout()`:
  - skip Typewriter save
  - wipe local SQLite tasks + Typewriter for this user (`wipeLocalCacheOnly`) — **does not** delete Node / Postgres
  - clear session and return to login

If Node is down, the question fetch fails silently (no overlay).

---

## 6. Forget password

Register **requires** email + security question + answer. Without those, only a host admin can recover the account.

Email **alone is not enough**. Reset needs:

1. Username + email on the account (`POST /forgot`).
2. A one-time token.
3. The security-question answer.
4. A new password of at least 8 characters (`POST /reset`).

### Token

- Created only if that username+email exists **and** a security answer is stored.
- Expires in **8 minutes** (`NOW() + INTERVAL '8 minutes'`).
- Single use (`used_at` set when consumed).
- `/forgot` **always** returns `{ ok: true }` (no user enumeration), including on errors.
- Same username+email pair is rate-limited to once per **60 seconds** (still `{ ok: true }`).

### How the token is delivered

1. Node prints the token and question on the host terminal **immediately**, then replies `{ ok: true }`. The login / phone UI **never** auto-fills the token (that used to happen only on the host PC talking to `127.0.0.1`; it is removed).
2. SMTP runs **after** the HTTP reply so Gmail cannot time out the click. A 12-second greeting timeout caused `Greeting never received` on this network; Node now waits **45 s** and tries Gmail **465** (SSL) first, then **587**.
3. Sender and receiver may be the **same** Gmail. Check Inbox and Spam. Spaces in the 16-character App password are stripped; that is correct.
4. Clients (Android, a JavaFX install pointed at Tailscale/LAN) only see `{ ok: true }`. They type the token from email or from the host Node terminal.
5. `/forgot` is rate-limited per username+email for **60 seconds**. A second click in that window does not send a new mail.

### After a successful reset

- Password is bcrypt cost **12**.
- **All** sessions for that user are deleted. Remember-me files on every device stop working until they log in again.

Change-password (logged in) keeps the current Bearer session and deletes the others.

Security question: min 8 characters; answer min 4 after normalize; hash bcrypt 12 on Node only.

---

## 7. SMTP / `SMTP_PASS` (host PC only)

The Account **SMTP** page is shown only when this install talks to Node on **localhost**. Clients do not see it.

Save writes:

- `.scheduler/smtp.txt` — `host`, `port`, `user`, `from` (no password line).
- `.scheduler/smtp.env` — `SMTP_PASS=…` so the already-configured Node can read it on the next restart without waiting for a new Windows login.
- Windows `setx SMTP_PASS` (and `SMTP_USER` / `SMTP_HOST`) so **new** terminals inherit the user env.

Node `mail.js` load order for the password: process env `SMTP_PASS`, then `smtp.env`, then a leftover `pass=` in `smtp.txt`. Host / user / from: file then `SMTP_*` env.

`setx` does **not** update a Node process that is already running. Restart Node after you save.

Gmail: use an App password, not the Google login password. Same mailbox as `from` and `to` is allowed.

If mail is off or SMTP fails, reset tokens still work — they print in the host terminal for 8 minutes. Restart Node after saving SMTP so it reloads `smtp.env`. A Node process started before save will not see `SMTP_PASS` in its environment.

Android is always a **client**. It must not show the host SMTP page and must not write `SMTP_PASS`.

---

## 8. Run Scheduler algorithm

Grid: **30-minute** slots. A column is **06:00 → next calendar morning 05:30** (48 slots). Search horizon is **5 years**.

Task types (`Task.state`):

| State | Meaning | Run Scheduler |
| --- | --- | --- |
| 1 Fixed | Exact start date/time | **Left alone** |
| 2 Deadline | Must finish by deadline date (and time if set) | Re-placed |
| 3 Flexible | No deadline | Re-placed |

**Action → Run Scheduler** (`RunSchedulerCommand` → `TaskManagementSystem.updateSchedule`):

1. Snapshot every **non-fixed** task (for undo).
2. Pull those tasks off the grid and clear their placed date/time.
3. Split remaining work into deadline vs flexible. Skip `PAST` in the placer.
4. **Deadline order:** earlier deadline **date** first; same date → **longer** duration first.
5. **Place deadline:** from `searchStart` (the later of the week/month base column and “today’s” column) through the deadline day, earliest slot first.
   - Today’s column starts at **current slot + 1** (never in the past).
   - Past columns have no free slots.
   - Slot must be empty, after now, and the **finish** must be `<=` deadline datetime. If there is no deadline time, the start calendar date must not be after the deadline date.
   - Deadline placement **respects overload**: a day with ≤ 1 hour of free slots (after the blocked-hour budget) is skipped.
6. **Place flexible:** same left-to-right / earliest-slot walk over the 5-year horizon, but **ignores overload**.
7. If nothing fits → lifecycle `UNSCHEDULED`, no slot.
8. Persist each non-fixed task that was in the snapshot (`dbAddTask`).

Fixed tasks stay on their slots. Undo restores the snapshot placements (or pending/unscheduled) and writes them back.

`refitActiveTasks()` (used when adding / loading in some paths) is different: it **keeps** already-placed active tasks if they still fit, pins `PAST` where they sit, then schedules only what is still unscheduled/pending.

Lifecycle after a clock tick:

- No placement → `UNSCHEDULED`
- Placement end `<=` now → `PAST`
- Else → `ONGOING`

Duration snaps down to whole 30-minute slots (minimum one slot).

A file import row that has **both** a deadline and a fixed start is skipped.

---

## 9. Files on this PC

Working directory must be the **repo root**.

| Path | Role |
| --- | --- |
| `scheduler-cache.db` | Local tasks, blocked, Typewriter |
| `.scheduler/server.txt` | Node URL |
| `.scheduler/postgres.pw` | Postgres password for Java JDBC |
| `.scheduler/session.txt` | Remember-me Bearer token |
| `.scheduler/login.txt` | Remember + random-challenge prefs |
| `.scheduler/theme.txt` | Dark / light |
| `.scheduler/smtp.txt` | SMTP host/user/from |
| `.scheduler/smtp.env` | `SMTP_PASS` for Node |
| `scheduler-device-id.txt` | This install’s device id |
| `typewriter-images/` | Image cache |

Java does not need env vars if those files exist. Fallbacks: `postgres_pw`, `PGPASSWORD`, `SCHEDULER_NODE`, `SCHEDULER_PG`.

Node still needs Postgres credentials in **its** process. After server-pick `setx`, open a **new** terminal and `cd server && node server.js` (`0.0.0.0:3456`).

---

## 10. Run (short)

- JDK 17+ and a JavaFX SDK on the module path (`javafx.controls`, `javafx.fxml`).
- Entry: `org.ashoreprospect.SchedulerApp`. CLI: `org.ashoreprospect.CliRunner.CLIRunner`.
- First launch (or missing Postgres password on a non-creator PC) shows server pick; Continue writes `.scheduler/` and `setx postgres_pw`.
- Host: PostgreSQL database `Scheduler`, then Node as above.
