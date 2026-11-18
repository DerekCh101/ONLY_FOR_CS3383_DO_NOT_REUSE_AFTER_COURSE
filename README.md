# Task Scheduler

A JavaFX task scheduler with a browser UI, a command-line mode, local SQLite storage, and optional synchronization through the Node.js server and PostgreSQL.

Read **[ADVANCE_BEHAVIOUR.md](ADVANCE_BEHAVIOUR.md)** for the exact sync, login, scheduler, and account rules. This file only covers features and setup.

## Features

- Week, day, and month calendars. Week has a live “now” line and drag-to-move / resize. Day shows yesterday / today / tomorrow descriptions only. Date and time edits live on the week grid.
- Times are stored to the minute. Week **occupancy** paints rounded 30-minute cells; an optional **Real-time overlay** (View menu, on by default) draws the exact-minute bar on the parent task only.
- Dark week tiles: empty now/future `#243044`, today empty `#2e3c56`, past empty `#0b0c0e`; occupancy live `#2f92e0`, occupancy after the task ended `#5c636c`. Light theme keeps the older blue occupancy.
- Tasks: **fixed** (exact start), **deadline** (place before a cutoff), **flexible** (first free slot). No category or subcategory.
- Nested **quick events** are attributes of a placed parent (clock + name + minutes under the parent name). Clicks always open the parent. They do not occupy the grid, do not get a live overlay, and must stay inside the parent window. Finish or delete the parent finishes/deletes them.
- Description checklist (`[x]` / `[ ]` lines); Finish / Unfinish for placed tasks. Unfinished leftover past work is noted on a later session with the **same name**.
- Blocked times: every-day vs a date or range, plus an exception from that pattern. Tasks cannot sit on blocked time; two blocked rows of the same kind cannot overlap on both clock and calendar day.
- Floating **+** and Action menu: add task, blocked times, view task list, import file, Run Scheduler, Clear Past, Clear All.
- Undo / redo for recorded calendar commands (including after Clear Past / Clear All).
- Typewriter: Pages, Diary, Lists; images by SHA-256; in-page line numbers; lines / words / letters. Diary days with writing are underlined. The HTML editor stores unwrapped plain text, not a full HTML page dump. Host writes Typewriter through Node into Postgres (`tw_diary` / `tw_pages` / `tw_lists`); a local cache wipe flushes that first so the server copy stays.
- Account split: **User info**, **Security**, **Notifications**, **Device**. Idle logout on this PC (Device): default 120 minutes, or Never / 5–180 minutes. Idle only signs you out; refresh does not.
- Reminders (default on): Windows popup + in-app banner. Tick 5 min / 10 min / 30 min / 1 hour / 2 hours / 1 day / when it starts, or add a custom lead. Optional sound.
- Optional encrypt-at-rest (AES-GCM, `AP1.` prefix) so pgAdmin cannot read task text. Account password; Security option, not idle or reload.
- Delete this account (password + confirm). Other devices wipe that account’s local cache the next time they open and reach the server.
- Remember login on this PC (opens the local cache next launch, including offline).
- Optional random security check after a remember-me auto-login.
- Register requires email + security question (needed to recover). Forgot password: token + security question are sent together; token lasts 8 minutes.
- Host-only SMTP page: App password is stored as OS env `SMTP_PASS` (Windows `setx`, Linux user env — not in the project folder).
- Custom title bar: fill the desktop work area (taskbar stays); drag the top bar to move.
- Dark / light theme.
- Online / offline chip; work continues from the local SQLite cache when the net is down.
- Multi-device sync when the host Node + Postgres are up.
- Browser UI on the same Node (`/`) with Calendar / Typewriter / Account. Same login, reminders, nested events, occupancy + overlay, and delete-account. Unlock encrypted data in Account → Security. No auto-scheduler, undo, or drag.
- CLI for the same task commands without JavaFX.

## Requirements

- Java 21
- Docker Desktop, if you want to run the server and PostgreSQL locally
- Node.js 22, if you want to run the server outside Docker

The Gradle build downloads JavaFX 23 and the database drivers automatically. You do not need to install a separate JavaFX SDK when using Gradle.

## Build the application

Run these commands from the repository root:

```powershell
.\gradlew.bat clean build
```

The executable GUI JAR is created under `build/libs/` with the `scheduler-gui` base name:

```text
build/libs/scheduler-gui-<version>.jar
```

`build` also produces the CLI JAR:

```text
build/libs/scheduler-cli-<version>.jar
```

`<version>` is the `version` set in `build.gradle.kts` (currently `v0.1.7`). Both JARs include their runtime dependencies and have an entry point in their manifest. The CI workflow also builds `distZip` / `distTar` distributions with launcher scripts.

Run the tests with coverage:

```powershell
.\gradlew.bat test jacocoTestReport
```

`.\gradlew.bat check` runs the tests plus per-class line-coverage gates on the headless-testable core. The database repositories now have per-class thresholds from 80% to 95% line coverage. PostgreSQL repository tests use temporary SQLite databases with narrow SQL compatibility adapters, so they do not require a live server.

## Run the GUI

```powershell
java -jar build\libs\scheduler-gui-<version>.jar
```

The GUI starts with the server-selection screen when it has not been configured yet. PostgreSQL and the Node server are optional for local, offline use. The application stores local data in `scheduler-cache.db`.

## Run the CLI

```powershell
java -jar build\libs\scheduler-cli-<version>.jar
```

The CLI waits for commands at the `Command>` prompt. The commands are:

```text
addTaskFromFile   import tasks from a file
printAll          print every task (alias: printAllTask)
finishTask        finish a placed task
unfinishTask      unfinish a placed task
clearTask         delete a task by name
clearAllTask      delete every task
clearPastTasks    delete past tasks (alias: clearPast)
update            reload and re-place the schedule
updateGUI         refresh the in-memory view
runScheduler      run the auto-scheduler (undoable)
undo              undo the last recorded command
redo              redo the last undone command
exit              quit
```

`addTask` and `addTaskFromCli` are currently disabled in CLI mode. Use the GUI to create an individual task, or use `addTaskFromFile` for bulk import.

When PostgreSQL is not running, the CLI creates or opens `scheduler-cache.db` and continues in SQLite-only mode. This is expected. The database is created in the process working directory, so run the JAR from the repository root if you want the file there.

## Import tasks from a file

The bundled sample is `src/main/resources/taskInput1.txt`. Each task is a name, a duration in hours, and a type (`1` fixed, `2` deadline, `3` flexible). Fixed tasks add a start date and time; deadline tasks add a deadline date and time. Flexible tasks need nothing more.

```text
Morning Standup
1
1
20-07-2026
09-00
Write Project Proposal
3
2
22-07-2026
18-00
Buy Groceries
1
3
```

Dates use `dd-MM-yyyy`, times use `HH-mm`. A deadline task with a blank time defaults to `23:59`. A row with both a deadline and a fixed start is skipped.

## Run the server with Docker

The fastest path is to double-click `start.bat` on Windows, or run `./start.sh` on Linux/macOS. Both start the containers and open the browser UI at `http://localhost:3456`.

Doing it by hand from the repository root:

```powershell
docker compose up -d --build
```

This starts PostgreSQL 16 and the Node.js API + web UI. The API is available at:

```text
http://127.0.0.1:3456
```

Stop the containers with:

```powershell
docker compose down
```

To also delete the PostgreSQL data volume:

```powershell
docker compose down -v
```

## Configuration

The default Docker database settings are:

```text
Database: Scheduler
User:     postgres
Password: ashore123
Port:     5432
```

Override the password with a `.env` file in the repository root (this is the only variable `docker-compose.yml` reads):

```text
POSTGRES_PASSWORD=change-this-password
```

The Node server reads these environment variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `HOST_LISTEN_IP` | `0.0.0.0` | Interface Node binds to |
| `HostNodeServerListenToWherePort` | `3456` | Node listen port |
| `PGHOST` | `127.0.0.1` | PostgreSQL host |
| `PGPORT` | `5432` | PostgreSQL port |
| `PGUSER` | `postgres` | PostgreSQL user |
| `PGPASSWORD` / `postgres_pw` | — | PostgreSQL password |
| `PGDATABASE` | `Scheduler` | PostgreSQL database |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_FROM` / `SMTP_PASS` | — | Password-reset mail |

Secrets are stored in the **OS user environment**, not in the project folder. On Windows the app uses `setx` and also writes `%APPDATA%\ashore-prospect\user.env`; on Linux it writes `~/.config/ashore-prospect/user.env`. The relevant keys are:

```text
SCHEDULER_NODE   Node URL (JDBC is still localhost-only)
postgres_pw      PostgreSQL password
SMTP_*           Host mailbox; see ADVANCE_BEHAVIOUR.md §7
```

Restart Node after saving SMTP or password changes so it reloads the environment.

For Gmail, `SMTP_PASS` must be an **App password**, not the Google account password.

## Run the Node server without Docker

If PostgreSQL is already running on the host:

```powershell
cd server
npm install
npm start
```

The server listens on port `3456` by default. Configure the database through the `PG*` variables above.

## Scheduling rules

The grid uses 30-minute slots, one column per calendar day, from `06:00` through the next morning’s `05:30`. The search horizon is 5 years.

1. **Fixed** tasks stay on their exact start and are never moved by Run Scheduler.
2. **Deadline** tasks are re-placed earliest-slot-first before their deadline, earliest deadline date first, then earliest deadline time, then longer duration first. A day with no more than 1 hour of free time is treated as overloaded and skipped.
3. **Flexible** tasks are re-placed earliest-slot-first and ignore the overload rule.

Past columns have no free slots. A task that cannot fit becomes `UNSCHEDULED`. Undo restores the previous placements. See [ADVANCE_BEHAVIOUR.md §8](ADVANCE_BEHAVIOUR.md) for the full algorithm.

## Data and sync

Every change writes to local SQLite first, then Postgres if JDBC is open, then Node if a Bearer session exists. Conflicts are resolved last-write-wins using `last_updated_at` for task details and `placement_updated_at` for grid placement; a tie favours the host device. Deletes leave tombstones so a stale device cannot resurrect them. Reads merge SQLite with Node (and Postgres) at login and on a 4-second poll. See [ADVANCE_BEHAVIOUR.md §2–3](ADVANCE_BEHAVIOUR.md).

## Project layout

```text
src/main/java/org/ashoreprospect/
  SchedulerApp.java          JavaFX entry point
  TaskManagementSystem.java  Application state and coordination
  SchedulerEngine.java       Task placement engine
  AppTimeReference.java      30-minute slot / column mapping
  BlockedTime*, Day, Task     Domain model
  Command/                   Commands, SystemCommands, undo/redo, CLI loop
  CliRunner/                 CLI entry point (CLIRunner)
  GUI/                       JavaFX views, dialogs, typewriter, server pick
  GUI/theme/                 Theme palettes and contrast checks
  crypto/                    AES-GCM encrypt-at-rest vault
  db/                        SQLite and PostgreSQL repositories
  net/                       Node API client, login, endpoints, OS env
server/
  server.js                  Node HTTP server entry
  routes/                    auth, tasks, blocked, typewriter, static
  lib/                       mail, vault crypto, sanitising, password rules
  index.html, app.js, ...    Browser UI
digrams/                     PlantUML class and sequence diagrams
build.gradle.kts             Gradle build configuration
docker-compose.yml           PostgreSQL and Node services
start.bat / start.sh         One-command Docker start
```

## CI, releases, and deployment

The workflow in `.github/workflows/ci-release-deploy.yml` runs the Gradle tests and builds Linux, Windows, and macOS distributions on every pull request, and runs the Node tests in `server/`. A successful merge to `main` or `master` that changes `src/main/**` creates the next patch tag (for example `v0.1.8`) and publishes the JARs and ZIP/TAR distributions as a GitHub release. Documentation-only or workflow-only merges do not create a release. Pushing a tag such as `v0.1.7` always publishes a release and the `ghcr.io/<owner>/scheduler-server` image.

The server deployment job is disabled by default. To enable automatic deployment for pushed version tags, create the repository variable `DEPLOY_ENABLED=true` and these secrets: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_PATH`, `DEPLOY_REGISTRY_USERNAME`, and `DEPLOY_REGISTRY_TOKEN`. The target host needs Docker Engine and Docker Compose v2.

You can also run the workflow manually from the GitHub Actions tab. Select the branch or version tag, then choose `publish_image` to publish the container. Choose `deploy` only when running from a `v*` tag and when the deployment secrets are configured.

Local Compose runs build the server from `server/Dockerfile`. To use a published image manually, set `SERVER_IMAGE`, for example `SERVER_IMAGE=ghcr.io/OWNER/scheduler-server:v0.1.7 docker compose up -d`.

The version shown in the UI (`AppVersion.LABEL`) and `version` in `build.gradle.kts` must match the release tag.

## Runtime files

The application may create these files and directories:

```text
scheduler-cache.db          Local SQLite database (tasks, blocked, Typewriter)
scheduler-device-id.txt     Device identifier
typewriter-images/          Cached Typewriter images
.scheduler/session.txt      Remember-me Bearer token
.scheduler/login.txt        Remember / challenge / reminder / idle / overlay prefs
.scheduler/theme.txt        Dark or light
.scheduler/vault.txt        Encrypt-at-rest state
.scheduler/vault.device     Device key wrap for remember-me unlock
server/user-files/          Typewriter uploads on the Node host
```

Secrets (`SCHEDULER_NODE`, `postgres_pw`, `SMTP_*`) live in the OS user environment, not here. Older builds may still read leftover `.scheduler/postgres.pw` and `.scheduler/server.txt`, then remove them on the next save.

These are local runtime files and should not be committed.

## Troubleshooting

### The CLI only creates a SQLite database

This is normal when PostgreSQL is unavailable. Look for the `Command>` prompt and enter a command such as `printAll` or `exit`.

### The GUI is empty after using the CLI

Run `update` in the CLI, then restart the GUI so it reloads the local data.

### The application keeps asking for the server

Check that the OS user environment contains `SCHEDULER_NODE`, and that the JAR is being run with the repository root as its working directory. Node must also be reachable at that URL.

### PostgreSQL connection errors appear

Start the Docker services with `docker compose up -d --build`, or continue using SQLite-only mode when synchronization is not needed. Node needs `postgres_pw` (or `PGPASSWORD`) in **its own** environment.

### Password-reset email never arrives

Confirm `SMTP_PASS` is a Gmail App password, then restart Node. The reset token is also printed in the Node terminal for 8 minutes.

## Documentation

- [ADVANCE_BEHAVIOUR.md](ADVANCE_BEHAVIOUR.md) — exact sync, login, security, scheduler, reminder, and account rules.
- [digrams/README.md](digrams/README.md) — PlantUML class and sequence diagrams for the Java side.
