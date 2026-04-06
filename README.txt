Task Scheduler System – README
1. Project Overview
This project is a command-based task scheduling system with a GUI calendar view.

It allows you to:

Add tasks manually through CLI
Add multiple tasks from a file
Automatically schedule tasks into a weekly calendar
Mark tasks as finished
Undo and redo commands
View scheduled tasks in a GUI
Print all tasks in the CLI
The system separates data storage, scheduling logic, command handling, and GUI display.

2. How the System Is Structured
Below is an explanation of each Java file and its role.

SchedulerApp.java
This is the JavaFX GUI application.

It:

Displays the weekly calendar
Shows scheduled tasks
Refreshes the GUI when the update command is executed
This is the main entry point if running with GUI.

SchedulerEngine.java
This is the core scheduling engine.

It is responsible for:

Taking all tasks from the system
Deciding where to place them in the weekly calendar
Preventing time conflicts
Respecting task priority rules
Scheduling priority order:

Fixed tasks (highest priority)
Deadline tasks
Flexible tasks (lowest priority)
This ensures correct and logical scheduling.

Task.java
This is the task data model.

Each task stores:

Task name
Deadline date (optional)
Deadline time (optional)
Starting date (optional)
Starting time (optional)
Duration (in hours)
A task can be:

Fixed (has starting date/time)
Deadline-based (has deadline)
Flexible (no deadline and no fixed start)
A task cannot be both fixed and deadline-based.

TaskManagementSystem.java
This acts as the central storage system.

It:

Stores all tasks in an ArrayList
Provides access to tasks
Is shared between CLI, scheduler engine, and GUI
Think of this as the internal database of the system.

ClIRunner.java
This is the command-line controller.

It:

Reads user input
Recognizes commands such as:
addTask
addTaskWithFile
update
undo
redo
printAll
Executes the corresponding command
Command.java
This is an interface that defines:

execute()
undo()
redo()
All command classes follow this structure.

RecordedCommand.java
This is the base class for commands that support undo and redo.

It maintains:

An undo stack
A redo stack
When a command executes:

It is added to the undo list
The redo list is cleared
Undo and redo operations are managed here.

AddTask.java
This handles adding a single task via CLI.

Flow:

Ask for task name
Ask for deadline (optional)
If deadline exists, do NOT ask for fixed start
If no deadline, ask for fixed start (optional)
Ask for duration
Create Task object
This command supports undo and redo.

AddTaskWithFile.java
This loads multiple tasks from:

taskInput1.txt

Each task must follow this 6-line format:

TaskName
DeadlineDate
DeadlineTime
Duration
StartingDate
StartingTime

If a task contains both a deadline and a fixed starting time, it will be skipped.

FinishTask.java
This marks a task as finished.

It likely:

Changes task state
Removes or updates scheduling
Supports undo/redo
PrintAllTask.java
Prints all tasks in the system through CLI.

Useful for debugging.

Undo.java
Calls undo on the most recent command.

Redo.java
Reapplies the most recently undone command.

BlockedTimePeriod.java
Represents occupied time slots in the calendar.

Used internally by the scheduling engine to prevent overlaps.

TaskStates.java
Currently not used.

It was likely intended for managing task states such as:

PENDING
FINISHED
CANCELLED
Since it is not integrated, it can be ignored for now.

taskInput1.txt
This file is used for bulk task input.

It must strictly follow the 6-line format per task.

3. Scheduling Algorithm (How Tasks Are Placed)
The weekly calendar:

7 days
6:00 to 22:00
1-hour blocks
Total available slots per week:
7 × 17 = 119 time slots

Scheduling process:

Step 1: Place fixed tasks

These must be placed at exact date/time
Step 2: Place deadline tasks

Scheduled as early as possible
Must be before deadline
Step 3: Place flexible tasks

Fill earliest available free time slots
This ensures:
Fixed > Deadline > Flexible priority.

4. Undo / Redo Logic
The system uses the Command Pattern.

When a command executes:

It is added to the undo stack
Redo stack is cleared
Undo:

Reverses last command
Moves it to redo stack
Redo:

Reapplies last undone command
5. How to Run the Project
Requirements
JDK 17 or above
JavaFX SDK (for GUI)
JavaFX is NOT included in modern JDK versions.

If Using IntelliJ
Download JavaFX SDK from:
https://openjfx.io

Add VM options:

--module-path "path_to_javafx/lib" --add-modules javafx.controls,javafx.fxml

Run SchedulerApp.java
If Running CLI Only
JavaFX is not required.

You can run ClIRunner directly.

6. Typical Usage Flow
Example:

addTask
addTaskWithFile
printAll
update
undo
redo

Important:
You must run update to refresh the GUI after adding tasks.

7. Design Pattern Used
This project uses:

Command Pattern
Separation of concerns
Model (Task)
Engine (SchedulerEngine)
View (SchedulerApp)
Controller (ClIRunner)
The architecture is modular and extendable.

8. Current Limitations
No persistent database (no save to disk)
No automatic priority sorting among deadline tasks
No advanced overflow detection
TaskStates not implemented
Limited input validation
9. Summary
This system:

Automatically schedules tasks
Prevents deadline/fixed conflict
Supports undo/redo
Supports bulk file import
Uses structured architecture
Separates scheduling logic from interface
Everything flows through TaskManagementSystem.

If something breaks:

Check file format
Check JavaFX configuration
Check update command
End of documentation.