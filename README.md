# Task Scheduler

A JavaFX task scheduler with a command-line mode, local SQLite storage, and optional synchronization through the Node.js server and PostgreSQL.
read **[ADVANCE_BEHAVIOUR.md](ADVANCE_BEHAVIOUR.md)** to know lower level of this project.

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

## Requirements

- Java 21
- Docker Desktop, if you want to run the server and PostgreSQL locally
- Node.js, if you want to run the server outside Docker

The Gradle build downloads JavaFX and the database drivers automatically. You do not need to install a separate JavaFX SDK when using Gradle.

## Build the application

Run these commands from the repository root:

```powershell
.\gradlew.bat clean build
```

The normal executable GUI JAR is created at:

```text
build/libs/scheduler-gui-1.0-SNAPSHOT.jar
```

Build the separate CLI JAR with:

```powershell
.\gradlew.bat cliJar
```

This creates:

```text
build/libs/scheduler-cli-1.0-SNAPSHOT.jar
```

Both JARs include their runtime dependencies and have an entry point in their manifest.

## Run the GUI

```powershell
java -jar build\libs\scheduler-gui-1.0-SNAPSHOT.jar
```

The GUI starts with the server-selection screen when it has not been configured yet. PostgreSQL and the Node server are optional for local, offline use. The application stores local data in `scheduler-cache.db`.

## Run the CLI

```powershell
java -jar build\libs\scheduler-cli-1.0-SNAPSHOT.jar
```

The CLI waits for commands at the `Command>` prompt. The following commands are currently implemented:

```text
addTaskFromFile
printAll
update
clearTask
undo
redo
exit
```

`addTask` is currently disabled in CLI mode. Use the GUI to create an individual task, or use `addTaskFromFile` for bulk import.

When PostgreSQL is not running, the CLI creates or opens `scheduler-cache.db` and continues in SQLite-only mode. This is expected. The database is created in the process working directory, so run the JAR from the repository root if you want the file there.

## Import tasks from a file

The default sample file is `src/main/resources/taskInput1.txt`. Each task uses six lines:

```text
Task name
Deadline date
Deadline time
Duration
Starting date
Starting time
```

Run `addTaskFromFile` and follow the prompts. A task with both a deadline and a fixed starting time is skipped.

## Run the server with Docker

From the repository root:

```powershell
docker compose up --build
```

This starts PostgreSQL and the Node.js API. The API is available at:

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

## GitHub Actions

The workflow in `.github/workflows/ci-release-deploy.yml` runs the Gradle and optional Node tests on every pull request, then builds Linux, Windows, and macOS distributions. A successful merge to `main` or `master` that changes application, build, or deployment files automatically creates the next patch tag (for example, `v0.1.2`) and publishes the JARs and ZIP/TAR distributions as a GitHub release. Documentation-only or workflow-only merges do not create a release. Pushing a tag such as `v0.1.1` always publishes a release and the server image to GitHub Container Registry.

The server deployment job is disabled by default. To enable automatic deployment for pushed version tags, create the repository variable `DEPLOY_ENABLED=true` and these secrets: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_PATH`, `DEPLOY_REGISTRY_USERNAME`, and `DEPLOY_REGISTRY_TOKEN`. The target host needs Docker Engine and Docker Compose v2. The deployment uses the published tagged image and the existing PostgreSQL service.

You can also run the workflow manually from the GitHub Actions tab. Select the branch or version tag, then choose `publish_image` to publish the container. Choose `deploy` only when running from a `v*` tag and when the deployment secrets and `DEPLOY_ENABLED=true` are configured.

Local Compose runs still build the server from `server/Dockerfile`. To use a published image manually, set `SERVER_IMAGE`, for example `SERVER_IMAGE=ghcr.io/OWNER/scheduler-server:v0.1.1 docker compose up -d`.

The default Docker database settings are:

```text
Database: Scheduler
User:     postgres
Password: postgres
Port:     5432
```

You can override them with a `.env` file in the repository root:

```text
NODE_HOST_IP=0.0.0.0
NODE_PORT=3456
DB_NAME=Scheduler
DB_USER=postgres
DB_PASSWORD=change-this-password
```

## Run the Node server without Docker

If PostgreSQL is already running on the host:

```powershell
cd server
npm install
npm start
```

The server listens on port `3456` by default. Configure the database connection through the environment variables used by `server/server.js`.

## Runtime files

The application may create these files and directories:

```text
scheduler-cache.db          Local SQLite database
scheduler-device-id.txt    Device identifier
.scheduler/server.txt      Saved Node server URL
.scheduler/postgres.pw     Saved PostgreSQL password
typewriter-images/         Cached typewriter images
```

These are local runtime files and should not be committed.

## Scheduling rules

Tasks are placed in this order:

1. Fixed tasks are placed in their exact slots.
2. Deadline tasks are placed as early as possible before their deadlines.
3. Flexible tasks are placed in the first available slot.

The scheduler uses working hours from `06:00` through `22:00`. Blocked periods are excluded from available slots.

## Project layout

```text
src/main/java/org/example/
  SchedulerApp.java          JavaFX entry point
  TaskManagementSystem.java  Application state and coordination
  SchedulerEngine.java       Task placement engine
  Command/                   CLI commands
  CliRunner/                 CLI entry point
  GUI/                       JavaFX views and controllers
  db/                        SQLite and PostgreSQL repositories
  net/                       Node API and login client
server/                      Node.js API and web UI
build.gradle.kts              Gradle build configuration
docker-compose.yml            PostgreSQL and Node services
```

## Troubleshooting

### The CLI only creates a SQLite database

This is normal when PostgreSQL is unavailable. Look for the `Command>` prompt and enter a command such as `printAll` or `exit`.

### The GUI is empty after using the CLI

Run `update` in the CLI, then restart the GUI so it reloads the local data.

### The application keeps asking for the server

Check `.scheduler/server.txt` and `.scheduler/postgres.pw` in the repository root. Make sure the JAR is being run with the intended working directory.

### PostgreSQL connection errors appear

Start the Docker services with `docker compose up --build`, or continue using SQLite-only mode when synchronization is not needed.