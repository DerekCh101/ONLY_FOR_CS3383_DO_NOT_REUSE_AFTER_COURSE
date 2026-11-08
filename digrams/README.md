# Java PlantUML diagrams

These sources document the Java portion under `src/main/java`.

## Class diagrams

- `java-class-overview.puml`: complete project-level type and dependency overview.
- `java-class-core.puml`: scheduling domain and core service classes.
- `java-class-commands.puml`: command, CLI, and undo/redo classes.
- `java-class-gui.puml`: JavaFX presentation and theme classes.
- `java-class-infrastructure.puml`: persistence, networking, and security classes.

## Sequence diagrams

- `sequence-startup-login.puml`: application startup and authentication.
- `sequence-add-task.puml`: GUI task creation and scheduling.
- `sequence-scheduler-undo.puml`: scheduler execution followed by undo.
- `sequence-gui-edit.puml`: task editing and finishing.
- `sequence-network-sync.puml`: local persistence and server synchronization.
- `sequence-task-drag-move.puml`: drag a placed task to another time slot.
- `sequence-task-delete-restore.puml`: delete a task and restore it with undo.
- `sequence-import-tasks.puml`: import tasks from the bundled task file.
- `sequence-blocked-time-edit.puml`: create and delete blocked-time periods.
- `sequence-unscheduled-recovery.puml`: recover or delete an unscheduled task.
- `sequence-typewriter-sync.puml`: load, save, and synchronize Typewriter data.
- `sequence-account-lifecycle.puml`: account settings, sessions, and logout.
- `sequence-node-poll-merge.puml`: poll Node and reload newer remote data.

`vp-style.puml` is shared by every diagram. It uses rectangular UML elements, pale blue headers, light package grouping, and orthogonal connectors to approximate a Visual Paradigm class-diagram presentation.

## Rendering

From this directory, with PlantUML installed:

```text
plantuml -tsvg *.puml
```

For report images, PNG output can be generated with:

```text
plantuml -tpng *.puml
```
