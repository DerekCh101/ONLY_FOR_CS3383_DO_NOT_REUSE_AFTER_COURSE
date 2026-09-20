Task Scheduler
==============

JavaFX calendar and typewriter notes. Local SQLite first, then optional
sync through Node and Postgres.

The GUI is the main app. The CLI still works.


What it does
------------
- Place, move, finish, and unfinish tasks on a week / month grid
- Fixed, deadline, and flexible tasks
- Blocked time (weekday repeat or one-off date)
- Typewriter pages (text + images)
- Undo / redo for recorded CLI commands
- Bulk import from a 6-line-per-task file
- Offline-first cache on this PC
- Multi-device sync when Node + Postgres are up


Layout
------
Task_Scheduler/
  src/main/java/org/example/
    SchedulerApp.java          JavaFX entry
    TaskManagementSystem.java  in-memory store
    SchedulerEngine.java       placement engine
    Task.java, Day.java, BlockedTime*.java
    Command/                   CLI commands
    CliRunner/                 CLI loop
    GUI/                       calendar, dialogs, typewriter, server pick
    db/                        SQLite + Postgres
    net/                       login, Node client, Endpoints
  server/                      Node HTTP API + web UI
  scheduler-cache.db           local SQLite (runtime)
  .scheduler/                  Node URL + Postgres password (runtime)
  typewriter-images/           image cache (runtime)

Do not commit:
  .scheduler/
  scheduler-cache.db
  scheduler-device-id.txt
  typewriter-images/


How data is stored
------------------
This PC writes SQLite first. Then it copies the same change to Postgres
and Node if they are reachable.

  scheduler-cache.db
      Tasks, blocked times, typewriter. Works offline.

  Postgres
      Shared DB when the host is up.
      Java skips JDBC on the creator host.

  Node (server/)
      HTTP API, auth, typewriter images.

Local files live in the project working directory
(repo root in IntelliJ):

  .scheduler/server.txt
      Node URL (http://127.0.0.1:3456 or Tailscale)

  .scheduler/postgres.pw
      Postgres password for Java

  scheduler-cache.db
      SQLite cache

  scheduler-device-id.txt
      This install's device id

  typewriter-images/
      Image cache

Java does not need env vars if those files exist.

Fallbacks:
  postgres_pw
  PGPASSWORD
  SCHEDULER_NODE
  SCHEDULER_PG

Node still needs postgres_pw in ITS terminal.
setx does not update a Node process that is already running.
Open a new terminal after Continue on the server-pick screen.


Requirements
------------
- JDK 17+
- JavaFX SDK for the GUI: https://openjfx.io
- Node.js on the host PC
- PostgreSQL on the host PC if you want multi-device sync


Run the GUI (IntelliJ)
----------------------
1. Open Task_Scheduler.
   Working directory must be the repo root, not src\...

2. VM options:

   --module-path "<path-to-javafx>/lib" --add-modules javafx.controls,javafx.fxml

3. Run org.example.SchedulerApp

First launch (or missing password on a non-creator PC) shows server pick:

  - Node URL, for example http://127.0.0.1:3456 or http://100.x.x.x:3456
  - Postgres password
  - Continue writes .scheduler/ and setx postgres_pw

Then log in.

Change server later from the UI. That logs out and rebuilds the
Node and Postgres clients.


Run Node (host)
---------------
  cd server
  node server.js

Use a NEW terminal after setx so Node can see postgres_pw.

Defaults: listen 0.0.0.0:3456

HOST_LISTEN_IP and HostNodeServerListenToWherePort are optional.
Java does not set them.


Run Node and Postgres with Docker
----------------------------------
Docker Compose starts PostgreSQL, waits for it to become healthy, and then
starts the Node server. The PostgreSQL database and the TASK schema are
created automatically on the first start.

From the repo root:

  docker compose up --build

The Node API is available at:

  http://127.0.0.1:3456 // default ip and port

Use that URL in the Java server-pick screen when Docker is running on the
same PC. Stop the containers with:

  docker compose down

PostgreSQL data is stored in the Docker volume postgres-data and survives
container restarts. To remove the containers and all stored PostgreSQL data:

  docker compose down -v

Optional .env settings in the repo root:

  NODE_HOST_IP=0.0.0.0
  NODE_PORT=3456
  DB_NAME=Scheduler
  DB_USER=postgres
  DB_PASSWORD=change-this-password

The Compose file passes these settings to Node as HOST_LISTEN_IP,
HostNodeServerListenToWherePort, PGUSER, PGPASSWORD, and PGDATABASE.
Inside Docker, Node connects to PostgreSQL using the service name postgres.


CLI only
--------
JavaFX is not required. Run:

  org.example.CliRunner.CLIRunner

Typical commands:
  addTask
  addTaskWithFile
  printAll
  update
  undo
  redo
  finish / unfinish / clear

File import (taskInput1.txt), six lines per task:

  TaskName
  DeadlineDate
  DeadlineTime
  Duration
  StartingDate
  StartingTime

A row with both a deadline and a fixed start is skipped.

After CLI changes, run update or reopen the GUI so the calendar reloads.


Scheduling
----------
Hours: 06:00-22:00

Order:
  1. Fixed (exact slot)
  2. Deadline (as early as possible, before the deadline)
  3. Flexible (first free slot)

Blocked periods stay off the grid.
Undo / redo uses the Command pattern.


Sync (short)
------------
- Add / update / delete -> SQLite, then Postgres if up, then Node if a token exists
- On load: wipe local cache if the logged-in user changed,
  pull Postgres, pull Node, load memory
- Deletes are tombstoned so they do not return from another device
- Typewriter pages follow the same path. Images can live on Node
- Creator host: Java does not open Postgres JDBC. Node owns that DB


If something breaks
-------------------
Server pick every launch
  Check .scheduler/server.txt and .scheduler/postgres.pw
  under the project root

Files appearing under src\...
  IntelliJ working directory is wrong

Node cannot open Postgres
  Restart Node after Continue / setx
  Run: echo %postgres_pw%

GUI empty after CLI
  Run update or restart the GUI

Change server still hits the old DB
  returnToLogin must call TypewriterStore.resetPostgres()
  and TaskManagementSystem.resetInstance()

Duplicate method compile error
  Only one tryReconnectPostgres() in TaskRepository