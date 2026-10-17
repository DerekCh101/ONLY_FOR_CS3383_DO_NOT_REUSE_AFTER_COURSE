/** Calendar CLI bar: help, movetask, finish, unfinish, clear, undo, redo. */
package org.ashoreprospect.Command;

import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskManagementSystem;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Calendar CLI bar. Each verb is a small object; help is built from the registry.
 * Verbs that mutate the schedule construct {@link Command} instances (same as the GUI).
 */
public final class InAppCli {

    public static final class Result {
        public final String message;
        public final boolean refresh;

        public Result(String message, boolean refresh) {
            this.message = message;
            this.refresh = refresh;
        }
    }

    public interface Verb {
        String name();
        default String usage() { return name(); }
        Result run(TaskManagementSystem sys, String[] args) throws Exception;
    }

    private static final List<Verb> VERBS = new ArrayList<>();

    static {
        VERBS.add(new HelpVerb());
        VERBS.add(new MoveVerb());
        VERBS.add(new FinishVerb());
        VERBS.add(new UnfinishVerb());
        VERBS.add(new ClearVerb());
        VERBS.add(new NamedSystemVerb("undo", "undo"));
        VERBS.add(new NamedSystemVerb("redo", "redo"));
    }

    private InAppCli() {}

    public static void add(Verb verb) {
        VERBS.add(verb);
    }

    public static String helpText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < VERBS.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(VERBS.get(i).usage());
        }
        return sb.toString();
    }

    public static Result run(TaskManagementSystem sys, String line) {
        if (line == null || line.isBlank()) return new Result("", false);
        String[] args = line.trim().split("\\s+");
        String name = args[0].toLowerCase(Locale.ROOT);
        try {
            for (Verb verb : VERBS) {
                if (verb.name().equals(name)) return verb.run(sys, args);
            }
            return new Result("unknown — type help", false);
        } catch (Exception ex) {
            return new Result(ex.getMessage() == null ? "cli error" : ex.getMessage(), false);
        }
    }

    private static final class HelpVerb implements Verb {
        @Override public String name() { return "help"; }
        @Override public String usage() { return "help"; }
        @Override public Result run(TaskManagementSystem sys, String[] args) {
            return new Result(helpText(), false);
        }
    }

    private static final class NamedSystemVerb implements Verb {
        private final String name;
        private final String systemName;

        NamedSystemVerb(String name, String systemName) {
            this.name = name;
            this.systemName = systemName;
        }

        @Override public String name() { return name; }

        @Override
        public Result run(TaskManagementSystem sys, String[] args) throws Exception {
            SystemCommands.run(systemName, sys);
            return new Result(name, false);
        }
    }

    private static final class MoveVerb implements Verb {
        @Override public String name() { return "movetask"; }
        @Override public String usage() { return "movetask id date time [hours]"; }

        @Override
        public Result run(TaskManagementSystem sys, String[] args) throws Exception {
            if (args.length < 4) return new Result(usage(), false);
            Task t = TaskLookup.placed(sys, args[1]);
            if (t == null || t.getPlacedDate() == null || t.getPlacedTime() == null) {
                return new Result("task not found or not placed", false);
            }
            LocalDate date = LocalDate.parse(args[2]);
            LocalTime time = LocalTime.parse(args[3].length() == 5 ? args[3] : args[3]);
            if (args.length >= 5) {
                double hours = AppTimeReference.normalizeDurationHours(Double.parseDouble(args[4]));
                EditTask.Snapshot next = EditTask.Snapshot.of(t);
                next.durationHours = hours;
                new EditTask(t, next, t.canGoUnscheduled()).execute();
                if (t.getPlacedDate() == null || t.getPlacedTime() == null) {
                    return new Result("resized but could not stay placed", true);
                }
            }
            LocalDate oldCol = AppTimeReference.columnDateFor(t.getPlacedDate(), t.getPlacedTime());
            int oldSlot = AppTimeReference.localTimeToSlot(t.getPlacedTime());
            LocalDate newCol = AppTimeReference.columnDateFor(date, time);
            int newSlot = AppTimeReference.localTimeToSlot(time);
            MoveTask mv = new MoveTask(t, oldCol, oldSlot, newCol, newSlot);
            if (!mv.execute()) return new Result("move rejected", false);
            return new Result("moved", true);
        }
    }

    private static final class FinishVerb implements Verb {
        @Override public String name() { return "finish"; }
        @Override public String usage() { return "finish id"; }

        @Override
        public Result run(TaskManagementSystem sys, String[] args) throws Exception {
            if (args.length < 2) return new Result(usage(), false);
            Task t = TaskLookup.placed(sys, args[1]);
            if (t == null) return new Result("task not found", false);
            if (!new FinishTask(t).execute()) return new Result("finish rejected", false);
            return new Result("finished", true);
        }
    }

    private static final class UnfinishVerb implements Verb {
        @Override public String name() { return "unfinish"; }
        @Override public String usage() { return "unfinish id"; }

        @Override
        public Result run(TaskManagementSystem sys, String[] args) throws Exception {
            if (args.length < 2) return new Result(usage(), false);
            Task t = TaskLookup.placed(sys, args[1]);
            if (t == null) return new Result("task not found", false);
            if (!new UnfinishTask(t).execute()) return new Result("unfinish rejected", false);
            return new Result("unfinished", true);
        }
    }

    private static final class ClearVerb implements Verb {
        @Override public String name() { return "clear"; }
        @Override public String usage() { return "clear id"; }

        @Override
        public Result run(TaskManagementSystem sys, String[] args) throws Exception {
            if (args.length < 2) return new Result(usage(), false);
            Task t = TaskLookup.anywhere(sys, args[1]);
            if (t == null) return new Result("task not found", false);
            if (!new ClearTask(t).execute()) return new Result("clear rejected", false);
            return new Result("cleared", true);
        }
    }
}
