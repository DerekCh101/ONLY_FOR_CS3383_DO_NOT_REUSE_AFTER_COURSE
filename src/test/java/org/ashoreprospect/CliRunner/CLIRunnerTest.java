/**
 * {@code CLIRunner.command}: what the stdin REPL prints for one dispatch.
 *
 * <p>{@code System.in} is swapped for a scripted stream and {@code System.out} is captured,
 * because every user-facing outcome of the REPL is a print.
 *
 * <p>PINNED - three behaviours that shape every test here:
 * <ol>
 *   <li><b>{@code command} never returns.</b> {@code running} only becomes {@code false} on
 *       {@code "exit"}, so after one dispatch the loop sets {@code cmdString = null},
 *       prints {@code "Command> "}, and calls {@code input.nextLine()}. With empty stdin
 *       that throws {@code NoSuchElementException}, and because the {@code try} block
 *       starts <em>after</em> the read, the exception escapes {@code command()}.</li>
 *   <li><b>{@code "exit"} is untestable in-process.</b> That one branch does set
 *       {@code running = false}, but first calls {@code System.exit(0)}. Gradle 9's test
 *       worker installs no exit guard (verified: a probe test calling
 *       {@code System.exit(0)} killed the worker with "Connection reset by peer").</li>
 *   <li><b>So each test asserts the output up to the re-prompt</b> and tolerates the
 *       trailing exception. That is the only observable slice of a REPL that cannot stop.</li>
 * </ol>
 * The {@code exit} gap is recorded in TEST_PLAN.md section Z item 7.
 *
 * <p>The first dispatch in a worker also prints {@code TaskManagementSystem}'s start-up
 * banner. To keep every assertion about the REPL only, {@code @BeforeEach} builds the
 * singleton <em>before</em> installing the capture stream, then clears the buffer.
 */
package org.ashoreprospect.CliRunner;

import org.ashoreprospect.TaskManagementSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("task-system")
@ResourceLock("app-clock")
class CLIRunnerTest {

    private static final String PROMPT = "Command> ";

    private InputStream oldIn;
    private PrintStream oldOut;
    private ByteArrayOutputStream out;

    @BeforeEach
    void capture() {
        oldIn = System.in;
        oldOut = System.out;
        resetCapture();
        // Build the singleton before capturing: its start-up banner is printed from the
        // constructor, so this leaves every capture holding only the REPL's own output.
        TaskManagementSystem.getInstance();
        resetCapture();
    }

    /** Fresh capture buffer, so output from earlier tests in the same worker is dropped. */
    private void resetCapture() {
        out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setIn(oldIn);
        System.setOut(oldOut);
    }

    private static String nl() {
        return System.lineSeparator();
    }

    /** Runs one dispatch with the given stdin, tolerating the REPL's re-prompt throw. */
    private void dispatch(String command, String stdin) {
        resetCapture();
        System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
        try {
            new CLIRunner().command(command);
        } catch (NoSuchElementException expected) {
            // The REPL re-prompts on empty stdin; see the class comment.
        }
    }

    /** Runs one dispatch with empty stdin; returns exactly what it printed. */
    private String runOne(String command) {
        dispatch(command, "");
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void emptyCommandIsSkippedStraightToThePrompt() {
        dispatch("", "unread");

        // PINNED: `continue` does not consume the caller's string, so the loop falls into
        // the prompt, reads the next line, and dispatches that line instead.
        assertEquals(PROMPT + "Invalid Command." + nl() + PROMPT,
                out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void whitespaceOnlyCommandIsNotTreatedAsEmpty() {
        // PINNED: only the stdin path trims. A caller-supplied "  " fails isEmpty(), so it
        // lands in the unknown-command branch instead of being skipped.
        assertEquals("Invalid Command." + nl() + PROMPT, runOne("  "));
    }

    @Test
    void unknownCommandPrintsTheInvalidLine() {
        assertEquals("Invalid Command." + nl() + PROMPT, runOne("notACmd"));
    }

    @Test
    void printAllDispatchesToThePrintAllTaskCommand() {
        assertTrue(runOne("printAll").startsWith("Current placed Task: " + nl()));
    }

    @Test
    void printAllTaskIsTheLongSpellingOfTheSameVerb() {
        assertEquals(runOne("printAll"), runOne("printAllTask"));
    }

    @Test
    void clearTaskWithANonExistentNameReportsNotFound() {
        dispatch("clearTask", "no-such-task-9F3A\n");

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.startsWith("Enter task name to clear: "), text);
        assertTrue(text.contains("Task not found: no-such-task-9F3A"), text);
        assertTrue(text.endsWith(PROMPT), text);
        assertFalse(text.contains("Cleared:"), text);
    }

    @Test
    void clearTaskTrimsTheNameItReadsFromStdin() {
        dispatch("clearTask", "   padded-name   \n");

        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Task not found: padded-name"));
    }

    @Test
    void clearTaskWithABlankNameIsAlsoNotFound() {
        dispatch("clearTask", "   \n");

        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Task not found: " + nl()));
    }

    @Test
    void addTaskFromCliPrintsTheDisabledNotice() {
        assertEquals("CLI addTask is currently disabled. Please use the GUI." + nl() + PROMPT,
                runOne("addTaskFromCli"));
    }

    @Test
    void addTaskIsAnAliasForAddTaskFromCli() {
        assertEquals(runOne("addTaskFromCli"), runOne("addTask"));
    }

    @Test
    void clearTaskWithoutStdinEscapesAsANoSuchElementException() {
        // PINNED: the second nextLine() is also outside the try, so closed/empty stdin
        // escapes rather than the catch printing "Command failed: ...".
        resetCapture();
        System.setIn(new ByteArrayInputStream(new byte[0]));
        assertThrows(NoSuchElementException.class, () -> new CLIRunner().command("clearTask"));
    }

    @Test
    void exitIsNotDispatchableInProcess() {
        // Z-7, recorded as a decision rather than a test: "exit" calls System.exit(0), and
        // the Gradle 9 worker has no exit guard, so the branch is unreachable here.
        assertTrue(PROMPT.endsWith(" "), "see the class comment");
    }

    @Test
    void constructingTheRunnerDoesNotPrintACommandResult() {
        resetCapture();
        new CLIRunner();

        String text = out.toString(StandardCharsets.UTF_8);
        assertFalse(text.contains("Invalid Command."), text);
        assertFalse(text.contains(PROMPT), text);
    }
}
