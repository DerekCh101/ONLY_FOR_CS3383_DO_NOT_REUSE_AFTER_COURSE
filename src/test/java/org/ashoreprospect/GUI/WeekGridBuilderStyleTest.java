package org.ashoreprospect.GUI;

import javafx.scene.layout.Background;
import javafx.scene.layout.Border;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.BlockedTime;
import org.ashoreprospect.Day;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskLifeCycle;
import org.ashoreprospect.TaskManagementSystem;
import org.ashoreprospect.Workflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@ResourceLock("app-clock")
@ResourceLock("node-session")
@ResourceLock("theme-prefs")
class WeekGridBuilderStyleTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);
    private TaskManagementSystem system;

    @BeforeEach
    void setUp() {
        AppTimeReference.setClock(Clock.fixed(Instant.parse("2026-10-12T09:00:00Z"), ZoneOffset.UTC));
        TaskManagementSystem.resetInstance();
        system = TaskManagementSystem.getInstance();
        system.getGrid().clear();
        system.getBlockedTime().replaceBlockedPeriods(List.of());
    }

    @AfterEach
    void tearDown() {
        TaskManagementSystem.resetInstance();
        AppTimeReference.setClock(Clock.systemDefaultZone());
    }

    @Test
    void emptyCellsShowPastTodayAndOtherDays() {
        Pane cell = new Pane();

        WeekGridBuilder.styleSlot(cell, system, TODAY, 5);
        assertEquals(Color.web(AppTheme.past()), cell.getBackground().getFills().get(0).getFill());
        WeekGridBuilder.styleSlot(cell, system, TODAY, 8);
        assertEquals(Color.web(AppTheme.today()), cell.getBackground().getFills().get(0).getFill());
        WeekGridBuilder.styleSlot(cell, system, TODAY.plusDays(1), 8);
        assertEquals(Color.web(AppTheme.paper()), cell.getBackground().getFills().get(0).getFill());
    }

    @Test
    void specificBlocksOverrideDailyBlocksAndExceptionsFreeTheSlot() {
        LocalDate date = TODAY.plusDays(1);
        BlockedTime daily = BlockedTime.daily("Daily", LocalTime.of(7, 0), LocalTime.NOON);
        BlockedTime specific = BlockedTime.once("Specific", date, LocalTime.of(10, 0), LocalTime.of(11, 0));
        Pane cell = new Pane();
        system.getBlockedTime().replaceBlockedPeriods(List.of(daily));
        WeekGridBuilder.styleSlot(cell, system, date, 8);
        Background dailyPaint = cell.getBackground();

        system.getBlockedTime().replaceBlockedPeriods(List.of(daily, specific));
        WeekGridBuilder.styleSlot(cell, system, date, 8);
        assertNotEquals(dailyPaint, cell.getBackground());

        BlockedTime exception = BlockedTime.once("Free", date, LocalTime.of(10, 0), LocalTime.of(11, 0));
        exception.setException(true);
        system.getBlockedTime().replaceBlockedPeriods(List.of(daily, specific, exception));
        WeekGridBuilder.styleSlot(cell, system, date, 8);
        assertEquals(Color.web(AppTheme.paper()), cell.getBackground().getFills().get(0).getFill());
    }

    @Test
    void afterMidnightBlockUsesTheNextCalendarDate() {
        Pane cell = new Pane();
        BlockedTime block = BlockedTime.once("Night", TODAY.plusDays(1), LocalTime.MIDNIGHT, LocalTime.of(1, 0));
        system.getBlockedTime().replaceBlockedPeriods(List.of(block));

        WeekGridBuilder.styleSlot(cell, system, TODAY, 36);
        Background blocked = cell.getBackground();
        assertNotEquals(Color.web(AppTheme.today()), blocked.getFills().get(0).getFill());

        system.getBlockedTime().replaceBlockedPeriods(List.of());
        WeekGridBuilder.styleSlot(cell, system, TODAY, 36);
        assertEquals(Color.web(AppTheme.today()), cell.getBackground().getFills().get(0).getFill());
    }

    @Test
    void tasksOverrideBlockedPaintAndShowFinishedAndEndedStates() {
        LocalDate date = TODAY.plusDays(1);
        Pane cell = new Pane();
        system.getBlockedTime().replaceBlockedPeriods(List.of(
                BlockedTime.once("Blocked", date, LocalTime.of(10, 0), LocalTime.of(11, 0))));
        WeekGridBuilder.styleSlot(cell, system, date, 8);
        Background blocked = cell.getBackground();

        Task task = new Task("Task", 1, 3, null, null, null, null);
        task.setPlacedDate(date);
        task.setPlacedTime(LocalTime.of(10, 0));
        Day day = new Day(system.getBlockedTime());
        day.placeTask(8, task);
        system.getGrid().put(date, day);
        WeekGridBuilder.styleSlot(cell, system, date, 8);
        Background live = cell.getBackground();
        assertNotEquals(blocked, live);

        task.setWorkflow(Workflow.FINISHED);
        WeekGridBuilder.styleSlot(cell, system, date, 8);
        Background finished = cell.getBackground();
        assertNotEquals(live, finished);

        task.setWorkflow(Workflow.UNFINISHED);
        task.setLifeCycle(TaskLifeCycle.PAST);
        WeekGridBuilder.styleSlot(cell, system, date, 8);
        assertNotEquals(live, cell.getBackground());
        assertNotEquals(finished, cell.getBackground());
    }

    @Test
    void dropFeedbackRecolorsEmptyCellsButKeepsOccupiedAndPastFills() {
        Pane cell = new Pane();
        WeekGridBuilder.CellState state = new WeekGridBuilder.CellState();
        state.columnDate = TODAY.plusDays(1);
        state.slotIndex = 8;
        WeekGridBuilder.styleSlot(cell, system, state.columnDate, state.slotIndex);
        Background empty = cell.getBackground();

        WeekGridBuilder.paintDropHover(cell, state, true);
        Background allowed = cell.getBackground();
        Border allowedBorder = cell.getBorder();
        assertNotEquals(empty, allowed);
        WeekGridBuilder.paintDropHover(cell, state, false);
        assertNotEquals(allowed, cell.getBackground());
        assertNotEquals(allowedBorder, cell.getBorder());

        WeekGridBuilder.styleSlot(cell, system, state.columnDate, state.slotIndex);
        state.task = new Task("Task", 1, 3, null, null, null, null);
        Background occupiedFill = cell.getBackground();
        WeekGridBuilder.paintDropHover(cell, state, true);
        assertSame(occupiedFill, cell.getBackground());

        state.task = null;
        state.block = BlockedTime.daily("Blocked", LocalTime.of(10, 0), LocalTime.of(11, 0));
        WeekGridBuilder.paintDropHover(cell, state, false);
        assertSame(occupiedFill, cell.getBackground());

        state.block = null;
        state.columnDate = TODAY;
        state.slotIndex = 5;
        WeekGridBuilder.styleSlot(cell, system, state.columnDate, state.slotIndex);
        Background past = cell.getBackground();
        WeekGridBuilder.paintDropHover(cell, state, true);
        assertSame(past, cell.getBackground());
    }
}
