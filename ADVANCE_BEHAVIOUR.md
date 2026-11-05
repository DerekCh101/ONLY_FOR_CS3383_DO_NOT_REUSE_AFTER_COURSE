# Advance behaviour

Exact rules for Ashore Prospect. The main [README](README.md) only lists features. This file is the source of truth for how those features actually behave.

Do not put mailbox passwords in chat or in git. Secrets go in OS user environment, not the project folder. Windows uses `setx`; Linux writes `~/.config/ashore-prospect/user.env`.

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
2. **Postgres** on the host, if this install opened JDBC (skipped on the host for Typewriter — Node owns those `tw_*` tables. Task JDBC still runs on localhost).
3. **Node HTTP**, if a Bearer session token exists. Typewriter POST is JSON with optional `AP1.` **per field** (title/body/text), so Node can fill `tw_diary` / `tw_pages` / `tw_lists`. A whole-bundle `AP1.` blob only in `typewriter.content` is leftover.

Reads at login / load:

1. If the cached `user_id` is a different account, wipe the local task + Typewriter cache for that PC.
2. If offline, load SQLite only.
3. If online and JDBC is up, two-way merge with Postgres.
4. If online and a token exists, pull Node and merge into SQLite, then load memory.

A daemon **node-poll** thread (every 4 s) pulls Node again and reloads the calendar. A **network-watch** thread (every 2 s) only checks NIC up/down for the online chip, toasts, and the deleted-account check.

Android is always a client of the same Node. JavaFX is the host when it talks to Node on this machine (`127.0.0.1` / `localhost`) or to the configured creator-host URL. `deviceId` is registry / GC only — it is **not** the host signal.

---

## 3. Sync rule (last-write-wins)

There are no CRDTs and no extra revision columns. Two ISO instants decide:

| Field | What it covers |
| --- | --- |
| `last_updated_at` | Name, duration, type, deadline, start, lifecycle, finish/unfinish, extras (description) |
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

Typewriter pages / lists use the same `preferRemote` on the bundle `last_updated_at`. Images are content-addressed (SHA-256) on Node under `server/user-files/{username}/diary|pages/`. JavaFX stores page/diary bodies as unwrapped plain text (`TypewriterHtml.storedText`). Node skips `AP1.` ciphertext when sanitizing leftover HTML dumps or clipping titles (do not treat ciphertext as markup).

### Deletes (tombstones)

- Delete writes the row away and records the id in `sync_deletes` (tasks) or the blocked-delete log.
- `dbAddTask` **skips** an id that is still tombstoned. Undo of Clear Past / Clear All must use `dbRestoreTask`: unrecord the tombstone, bump `last_updated_at`, upsert, then copy to Postgres/Node.
- **Postgres** two-way sync: if local tombstoned an id that still exists on Postgres, the Postgres row is deleted. A local-only row that is **not** newer than `last_postgres_sync_at` is treated as “already known and gone on the server” and is dropped + tombstoned. A local-only row **newer** than last sync is pushed.
- **Node** pull: if Node still has an id that was locally tombstoned, the local tombstone is **cleared** and the Node row is merged in. A local-only row not newer than `last_node_sync_at` is dropped + tombstoned (it vanished from Node after the last successful pull).

Tombstone GC: stale ~3 months, abandoned ~12 months (`STALE_MONTHS` / `ABANDONED_MONTHS`).

Account deletion is a **different** tombstone (`TASK.deleted_accounts`). See section 12.

---

## 4. Remember me

Login checkbox **Remember login**. Default is on if `.scheduler/login.txt` has never said otherwise.

When checked at login / register:

- `.scheduler/login.txt` stores `remember=true` and `lastUser`.
- `.scheduler/session.txt` stores `userId`, `username`, Bearer `token`.

When unchecked: prefs say `remember=false` and `session.txt` is deleted.

**Boot:** `SchedulerApp` calls `restoreSession()`. If remember is on and `session.txt` is present, the login screen is skipped and the main window opens on the **local cache**. This works **offline**. The token is used later when Node is reachable.

Logout (Account, idle, failed challenge, revoke-this-PC, deleted-account wipe) clears `session.txt`. Typewriter is saved on a normal logout, **not** on a failed remember-me challenge (see below).

Idle auto-logout is this-PC only (Account → **Device**). Default **120 minutes** with no mouse or keyboard on the main scene. Combo: **Never**, or **5–180** minutes. Stored as `idleMinutes` in `.scheduler/login.txt` (`0` = never). Idle only signs you out. Refresh does not log you out. Encryption is a separate Security option, not idle or reload.

---

## 5. Remember-me random security logout

Opt-in only. Account → Security → **Random security check on Remember-me login**. Stored as `randomChallenge=true` in `.scheduler/login.txt`. Default is **off**.

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

1. Node prints the token and question on the host terminal **immediately**, then replies `{ ok: true }`. The login / phone UI **never** auto-fills the token.
2. SMTP runs **after** the HTTP reply so Gmail cannot time out the click. Node waits **45 s** and tries Gmail **465** (SSL) first, then **587**.
3. Sender and receiver may be the **same** Gmail. Check Inbox and Spam. Spaces in the 16-character App password are stripped; that is correct.
4. Clients (Android, a JavaFX install pointed at Tailscale/LAN, the browser UI) only see `{ ok: true }`. They type the token from email or from the host Node terminal.
5. `/forgot` is rate-limited per username+email for **60 seconds**. A second click in that window does not send a new mail.

### After a successful reset

- Password is bcrypt cost **12**.
- **All** sessions for that user are deleted. Remember-me files on every device stop working until they log in again.

Change-password (logged in) keeps the current Bearer session and deletes the others.

Security question: min 8 characters; answer min 4 after normalize; hash bcrypt 12 on Node only.

---

## 7. SMTP / `SMTP_PASS` (host PC only)

The Account **SMTP** page is shown only when this install talks to Node on **localhost**. Clients do not see it.

Save writes OS user env (not the git working tree):

- Windows: `setx` for `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_FROM`, `SMTP_PASS`, plus `%APPDATA%\ashore-prospect\user.env` so Node can read without a logoff.
- Linux: `~/.config/ashore-prospect/user.env` (or `$XDG_CONFIG_HOME/ashore-prospect/user.env`).

Node `mail.js` load order: process env, then that user-config file, then leftover `.scheduler/smtp.env` / `smtp.txt` if they still exist from older builds.

Restart Node after you save so it reloads env. A Node process started before save will not pick up a new value until restart.

Gmail: use an App password, not the Google login password. Same mailbox as `from` and `to` is allowed.

If mail is off or SMTP fails, reset tokens still work — they print in the host terminal for 8 minutes.

Android is always a **client**. It must not show the host SMTP page and must not write `SMTP_PASS`.

---

## 8. Run Scheduler algorithm

Grid: **30-minute** slots. A column is **06:00 → next calendar morning 05:30** (48 slots). Times 00:00–05:30 belong to the **previous** day’s column. Search horizon is **5 years**.

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

Finish / unfinish is **workflow**, not lifecycle. The description-panel Finish button and CLI `finish` only apply to **placed** tasks. Finishing a parent also finishes its nested events; deleting a parent deletes them.

Duration is stored as **exact minutes** (1–1440). Occupancy on the week grid still **ceils** to 30-minute cells (`occupancySlots`: floor start, ceil end — e.g. 18:36–19:53 occupies 18:30–20:00). Node overlap uses start + duration hours × 60.

Nested events (`parent_id` set, `Task.isQuick()`): not independent tasks. They are never placed on the occupancy grid, never painted as overlay bars, never treated as Fixed/Deadline/Flexible by Run Scheduler, and must lie entirely inside the parent (`AppTimeReference.rangeInside`). Clicks on their time still open the **parent**. They are listed under the parent name as `HH:mm  name  ·  N min` (week caption and Task Information). The description side panel shows **Checklist** first, then **Quick time events**. There is no Type:Fixed on a nested row. Add-task does not pick a parent; only **Add quick event** on a placed parent does.

Leftover unfinished **PAST** work is copied onto the next later placed session with the **same name** (case-insensitive). If none exists yet, the past task gets a hold note and the UI can offer a follow-up Flexible task. Category/subcategory are gone from the model; leftover matching does not use them. Unused SQL columns `category` / `subcategory` may still exist on old DBs (`ALTER ADD COLUMN`) and are not read or written.

A file import row that has **both** a deadline and a fixed start is skipped.

---

## 8a. JavaFX week grid

`WeekGridBuilder` paints two layers:

1. **Occupancy** — rounded 30-minute cells for **parent** tasks only. Nested events never occupy. Finished tasks use the green stripe. Live occupancy (dark) `#2f92e0`; after the task’s exact end `#5c636c`. Light occupancy live `#c5d8ec`, ended `#9eb4c8`. Empty now/future (dark) paper `#243044`, today empty `#2e3c56`, hover `#3b4c6a`; past empty `#0b0c0e`. Light empty tiles keep cream paper / today / past from `BuiltinThemes.LIGHT`.
2. **Real-time overlay** — exact-minute translucent bars for **parents only**, plus a start tick. View → **Real-time overlay** (default on, `realtimeOverlay` in `.scheduler/login.txt`). Nested events are **not** drawn as overlay bars. The red “now” line is separate (current week only).

Click: always `system.mainTask(...)`. Nested / cursor-over-nested still opens the parent. Empty future cells open Add task. Right-click on a parent opens Task Information (Y offset 0.45). Drag-over a future empty cell: green border if the drop fits, red if not.

Blocked-times editor: **Every day** (default) vs **Range** (mutually exclusive); **Exception** reveals weekdays and the matching from/to dates. New blocked rows are rejected when they overlap a task or another blocked row of the same kind (`blockedOverlap`: same exception flag, `clockOverlap`, and `daysCanMeet`). Weekdays are ISO 1–7.

Add-task and Task Information have no category fields. Popups for add/info sit slightly above center (`centerInParentOffset` 0.7, 0.45).

Typewriter: `HTMLEditor` is the writing surface. Persist `TypewriterHtml.storedText(...)` — inner writing as plain lines, never the editor’s `<html>` / `contenteditable` dump (`u003chtml` and similar). Counts (lines / words / letters) use that unwrapped text.

---

## 9. Reminders

Device-local. Not synced. Account → **Notifications**.

`.scheduler/login.txt` keys:

| Key | Default | Meaning |
| --- | --- | --- |
| `reminders` | on (`true` if missing) | Master switch |
| `reminderSound` | on | AWT beep with the popup |
| `reminderLeads` | `0,5` | Minutes before start; `0` = when it starts |

Preset ticks: 0, 5, 10, 30, 60, 120, 1440. Custom minutes 1–20160 (14 days). Empty lead list means no fires even if the master switch is on.

`TaskReminders` polls every **20 s**. For each placed unfinished task and each selected lead, it fires **once** when remaining minutes are in `(lead − 2, lead]` (`lead 0`: `(-2, 0]`). Windows tray balloon (`TrayIcon.displayMessage`) plus in-app banner. Focus Assist can hide the tray balloon; the banner still shows.

Web: `localStorage` `ashoreReminders` / `ashoreReminderLeads` / `ashoreReminderSound`, browser `Notification` (optional `silent`) plus a short beep. Same lead list.

---

## 10. Encrypt data at rest

Optional. Account → Security → **Encrypt data at rest**, confirmed with the account password.

- Class: `org.ashoreprospect.crypto.Vault`. Prefix **`AP1.`**. AES-GCM, PBKDF2 **120k** from **account password + userId**.
- Files: `.scheduler/vault.txt` and `vault.device` (device wrap so remember-me can unlock without typing the password again).
- When **on**: existing SQLite / Postgres / Typewriter rows are rewritten sealed; later writes seal on bind / Node JSON. Open on mapRow / fromNode.
- Login unlocks; remember-me uses `unlockRemembered`; logout `Vault.lock()` because the session ended. Idle logout is the same logout — it does not toggle encryption.
- The point is that **pgAdmin sees ciphertext**, not the task text. Ids, times, and username stay readable. A logged-in app (browser or JavaFX) always sees plaintext; login keeps a per-session wrap so refresh still decrypts. Typewriter rows in `tw_diary` / `tw_pages` / `tw_lists` exist; titles and bodies are `AP1.` when encryption is on. Empty `tw_*` with only a blob in `typewriter.content` is leftover.
- Unlock lives in Account → Security → Encrypt data at rest, not on the calendar. Reload does not lock or unlock.
- Browser: `server/vault.js` can unlock the same `AP1.` payload with the account password on that Security fold. Android does not implement Vault yet — it will see `AP1.` blobs unless it uses the same scheme. Do not invent a second crypto format.

---

## 11. Delete this account

Account → Security → **Delete this account**. Requires the current password, then a confirm dialog.

`POST /me/delete` with `{ "password": "…" }` (Bearer session). Wrong password → `401`. Empty password → `400`.

On success Node, in one transaction:

1. Deletes that user’s Typewriter rows, tasks, blocked times, reset tokens, sessions, then the `users` row.
2. Inserts `TASK.deleted_accounts(user_id, deleted_at)` (`ON CONFLICT` refreshes `deleted_at`).
3. Removes `server/user-files/{username}` after the transaction (best-effort).

Unauthenticated `GET /account-status?userId=` returns `{ deleted: true }` **only** if that id is in `deleted_accounts`. Live users and unknown ids return `{ deleted: false }`. Do **not** treat every `401` as deletion (session revoke is not a tombstone).

### Stale / offline clients

A device that was off during deletion keeps its SQLite / browser cache until it next **opens and can reach Node**. Then:

- Desktop: `SchedulerApp.wipeIfAccountDeleted()` on boot, when a real NIC comes up, and from the main window. If deleted: `wipeLocalCacheOnly`, `Vault.eraseLocalKeys()` (deletes `vault.txt` + `vault.device`), logout, login screen.
- Web: same check on boot, `online`, and `401`. Clears `token`, `ashoreUserId`, in-memory tasks, and vault memory.
- Android should call the same `GET /account-status?userId=` on open/online and wipe local data if `deleted: true`.

“Clear this PC’s cache” is **not** account deletion: it flushes Typewriter to Node first, then wipes local SQLite/Typewriter and logs out. Node / Postgres stay. A failed remember-me challenge still wipes without that flush.

---

## 12. Files on this PC

Working directory must be the **repo root**.

| Path | Role |
| --- | --- |
| `scheduler-cache.db` | Local tasks, blocked, Typewriter |
| OS env `SCHEDULER_NODE` | Node URL (Windows `setx` / Linux user env) |
| OS env `postgres_pw` | Postgres password for Java JDBC and Node |
| OS env `SMTP_*` | Host SMTP; see §7 |
| `~/.config/ashore-prospect/user.env` (Linux) or `%APPDATA%\ashore-prospect\user.env` (Windows) | Same keys, outside the repo |
| `.scheduler/session.txt` | Remember-me Bearer token |
| `.scheduler/login.txt` | Remember, random-challenge, reminder prefs, `idleMinutes`, `realtimeOverlay` |
| `.scheduler/theme.txt` | Dark / light |
| `.scheduler/vault.txt` | Encrypt-at-rest wanted + wrapped key |
| `.scheduler/vault.device` | Device wrap for remember-me unlock |
| `scheduler-device-id.txt` | This install’s device id (Account → User info, Copy) |
| `typewriter-images/` | Image cache |

Secrets are OS env, not project files. Fallbacks: leftover `.scheduler/postgres.pw` / `server.txt` from older builds (read, then deleted on next save).

Node still needs Postgres credentials in **its** process. After server-pick persists `postgres_pw`, restart Node yourself (`cd server && node server.js`, `0.0.0.0:3456`).

---

## 13. Browser UI

Same Node origin (`server/index.html`, `app.js`, `shell.js`, `vault.js`). React SPA chrome: Calendar / Typewriter / Account (User / Security / Notifications / Device).

Has: login/register, week chips, add/delete/finish, blocked times, nested events under the parent name, occupancy + optional real-time overlay, Typewriter (Pages → Diary → Lists), reminder leads + sound, Account Security vault unlock, delete-account, stale-client wipe, idle logout. No category/subcategory. No calendar vault bar.

Does **not** have: auto-scheduler, undo/redo, drag-drop, day/month desktop layouts, Windows tray.

---

## 14. Run (short)

- JDK **21** and JavaFX **23** (`javafx.controls`, `javafx.fxml`, `javafx.web`). Gradle Kotlin DSL; compile in IntelliJ.
- Entry: `org.ashoreprospect.SchedulerApp`. CLI: `org.ashoreprospect.CliRunner.CLIRunner`.
- First launch (or missing Postgres password on a non-creator PC) shows server pick; Continue persists `SCHEDULER_NODE` and `postgres_pw` in OS user env (Windows `setx`, Linux user env).
- Host: PostgreSQL database `Scheduler`, schema `TASK`, then Node as above. Restart Node yourself after SMTP or route changes.
