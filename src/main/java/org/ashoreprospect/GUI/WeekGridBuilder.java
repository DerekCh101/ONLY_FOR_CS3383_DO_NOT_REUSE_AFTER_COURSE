/** Draws the week grid (48 half-hour rows) and placed tasks. */
package org.ashoreprospect.GUI;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import org.ashoreprospect.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class WeekGridBuilder {

    private static final double CELL_WIDTH = 140;
    private static final double CELL_HEIGHT = 32;
    private static final double NOW_LINE_RIGHT_BLEED = 10;

    private final TaskManagementSystem system;
    private final TaskDragDropSupport dragDrop;
    private final TaskDialogs dialogs;
    private java.util.function.Consumer<Task> onSelectTask;

    static final String CELL_KEY = "ashore.cell";

    private Region nowBar;
    private int nowBarRow = -1;
    private Pane[][] slotCells;
    private Label[] headers;
    private Label[] timeLabels;
    private CellState[][] states;
    private GridPane host;
    private LocalDate overlayWeekStart;
    private boolean realtimeOverlay = true;
    private boolean scrolling;

    /** Live slot payload so reused cells do not capture stale dates/tasks. */
    public static final class CellState {
        public LocalDate columnDate;
        public int slotIndex;
        public Task task;
        public BlockedTime block;
        double downX;
        double downY;
        boolean hoverable;
        Background restBg;
        Border restBorder;
    }

    private static final class Paints {
        Background hover, paper, today, past;
        Background occLive, occEnded, done;
        Background blockNight, blockLunch, blockMorning, blockOther;
        Background dropOk, dropNo;
        Border gray, occLiveEdge, occEndedEdge, doneEdge, dropOkEdge, dropNoEdge;
    }

    private static String paintKey;
    private static Paints paints;

    public static CellState cellState(Pane cell) {
        if (cell == null) return null;
        Object v = cell.getProperties().get(CELL_KEY);
        return v instanceof CellState s ? s : null;
    }

    public WeekGridBuilder(TaskManagementSystem system,
                           TaskDragDropSupport dragDrop,
                           TaskDialogs dialogs) {
        this.system = system;
        this.dragDrop = dragDrop;
        this.dialogs = dialogs;
    }

    public void setOnSelectTask(java.util.function.Consumer<Task> onSelectTask) {
        this.onSelectTask = onSelectTask;
    }

    public void setRealtimeOverlay(boolean on) {
        this.realtimeOverlay = on;
    }

    public void setScrolling(boolean on) {
        this.scrolling = on;
    }

    public void build(GridPane grid, Label sidePanel, int weekOffset) {
        if (!skeletonOn(grid)) ensureSkeleton(grid);
        fillWeek(grid, sidePanel, weekOffset);
    }

    private boolean skeletonOn(GridPane grid) {
        return host == grid
                && slotCells != null
                && slotCells[0][0] != null
                && slotCells[0][0].getParent() == grid;
    }

    private void ensureSkeleton(GridPane grid) {
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        grid.getRowConstraints().clear();
        grid.setHgap(0);
        grid.setVgap(0);
        host = grid;
        nowBar = null;
        nowBarRow = -1;
        int slots = AppTimeReference.SLOTS_PER_DAY;
        slotCells = new Pane[slots][7];
        states = new CellState[slots][7];
        headers = new Label[7];
        timeLabels = new Label[slots];

        for (int col = 0; col < 7; col++) {
            Label hdr = new Label();
            headers[col] = hdr;
            grid.add(hdr, col + 1, 0);
        }

        for (int row = 1; row <= slots; row++) {
            int slotIndex = row - 1;
            Label timeLabel = new Label();
            timeLabels[slotIndex] = timeLabel;
            grid.add(timeLabel, 0, row);

            for (int col = 1; col <= 7; col++) {
                CellState st = new CellState();
                st.slotIndex = slotIndex;
                states[slotIndex][col - 1] = st;

                Pane cell = new Pane();
                cell.setPrefSize(CELL_WIDTH, CELL_HEIGHT);
                cell.setMinHeight(CELL_HEIGHT);
                cell.setMaxHeight(CELL_HEIGHT);
                cell.setPickOnBounds(true);
                cell.getProperties().put(CELL_KEY, st);
                slotCells[slotIndex][col - 1] = cell;
                wireCell(cell, st);
                dragDrop.makeDraggable(cell);
                dragDrop.setupDropTarget(cell);
                grid.add(cell, col, row);
            }
        }
    }

    private void wireCell(Pane cell, CellState st) {
        cell.setOnMousePressed(e -> {
            st.downX = e.getScreenX();
            st.downY = e.getScreenY();
        });
        cell.setOnMouseClicked(e -> {
            if (Math.abs(e.getScreenX() - st.downX) >= 5
                    || Math.abs(e.getScreenY() - st.downY) >= 5) {
                return;
            }
            if (st.columnDate == null) return;
            LocalDate clickDate = AppTimeReference.calendarDateForSlot(st.columnDate, st.slotIndex);
            Task shown = st.task;
            if (shown == null) {
                Task quick = quickAt(system.getPlacedTasks(), st.columnDate, st.slotIndex);
                if (quick != null) shown = system.mainTask(quick);
            }
            if (shown != null) shown = system.mainTask(shown);
            if (shown != null && shown.isQuick()) shown = null;
            if (shown == null) {
                if (e.getButton() == MouseButton.PRIMARY
                        && !isPast(st.columnDate, st.slotIndex)) {
                    dialogs.showTaskPopup(true, clickDate, st.slotIndex);
                }
                return;
            }
            if (e.getButton() == MouseButton.SECONDARY) {
                dialogs.showTaskInfoPopup(shown, clickDate, st.slotIndex);
            } else if (e.getButton() == MouseButton.PRIMARY && onSelectTask != null) {
                onSelectTask.accept(shown);
            }
        });
        cell.setOnMouseEntered(e -> {
            if (scrolling || !st.hoverable) return;
            ensurePaints();
            paintCell(cell, paints.hover, paints.gray);
        });
        cell.setOnMouseExited(e -> {
            if (scrolling || st.restBg == null) return;
            paintCell(cell, st.restBg, st.restBorder);
        });
    }

    private void fillWeek(GridPane grid, Label sidePanel, int weekOffset) {
        var gridData = system.getGrid();
        LocalDate weekStart = AppTimeReference.getStartOfWeek(AppTimeReference.currentColumnDate());
        overlayWeekStart = weekStart.plusDays(weekOffset * 7L);
        List<BlockedTime> blocked = loadBlocked();
        List<Task> placed = system.getPlacedTasks() == null
                ? List.of() : new ArrayList<>(system.getPlacedTasks());

        LocalTime now = AppTimeReference.getCurrentTime();
        int nowSlot = AppTimeReference.localTimeToSlot(now);

        for (int col = 0; col < 7; col++) {
            LocalDate date = weekStart.plusDays(weekOffset * 7L + col);
            String text = date.getDayOfWeek().toString().substring(0, 3) + " "
                    + date.getMonthValue() + "/" + date.getDayOfMonth();
            headers[col].setText(text);
            headers[col].setStyle("-fx-text-fill: " + AppTheme.ink() + ";");
        }

        for (int slotIndex = 0; slotIndex < AppTimeReference.SLOTS_PER_DAY; slotIndex++) {
            Label timeLabel = timeLabels[slotIndex];
            timeLabel.setText(AppTimeReference.formatSlotLabel(slotIndex));
            if (slotIndex == nowSlot) {
                timeLabel.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
            } else {
                timeLabel.setStyle("-fx-text-fill: " + AppTheme.ink() + ";");
            }

            for (int col = 1; col <= 7; col++) {
                LocalDate columnDate = weekStart.plusDays(weekOffset * 7L + (col - 1));
                BlockedTime block = blockedAt(blocked, columnDate, slotIndex);
                boolean past = isPast(columnDate, slotIndex);
                Pane cell = slotCells[slotIndex][col - 1];
                CellState st = states[slotIndex][col - 1];

                Task taskInCell = taskAt(gridData, placed, columnDate, slotIndex);
                if (taskInCell != null && taskInCell.isQuick()) {
                    taskInCell = system.mainTask(taskInCell);
                    if (taskInCell != null && taskInCell.isQuick()) taskInCell = null;
                }

                st.columnDate = columnDate;
                st.slotIndex = slotIndex;
                st.task = taskInCell;
                st.block = block;
                cell.getChildren().clear();

                if (taskInCell != null) {
                    boolean first = taskInCell.getPlacedTime() != null
                            && slotIndex == AppTimeReference.localTimeToSlot(taskInCell.getPlacedTime());
                    cell.getChildren().add(taskCaption(taskInCell, first, taskHasEnded(taskInCell)));
                }

                applyDayStyle(cell, columnDate, slotIndex, block, taskInCell);
                st.hoverable = taskInCell == null && block == null && !past;
                st.restBg = cell.getBackground();
                st.restBorder = cell.getBorder();

                if (taskInCell == null && !past && block != null
                        && isSegmentStart(blocked, columnDate, slotIndex, block)) {
                    String title = block.getTitle() == null || block.getTitle().isBlank()
                            ? "blocked" : block.getTitle();
                    Label why = new Label(title + "  " + block.getStartTime() + "–" + block.getEndTime());
                    why.setStyle("-fx-font-size: 10px; -fx-font-style: italic; -fx-text-fill: "
                            + AppTheme.muted() + ";");
                    why.setMouseTransparent(true);
                    cell.getChildren().add(why);
                }
            }
        }

        if (realtimeOverlay) paintActualBars(placed);
        placeNowBar(grid, weekOffset, now, nowSlot);

        if (sidePanel != null) {
            sidePanel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + AppTheme.ink()
                    + "; " + AppTheme.font() + "-fx-font-size: 12px;");
        }
    }

    private void placeNowBar(GridPane grid, int weekOffset, LocalTime now, int nowSlot) {
        if (weekOffset != 0) {
            if (nowBar != null) {
                grid.getChildren().remove(nowBar);
                nowBarRow = -1;
            }
            return;
        }
        if (nowBar == null) {
            nowBar = new Region();
            nowBar.setMinHeight(3);
            nowBar.setPrefHeight(3);
            nowBar.setMaxHeight(3);
            nowBar.setMouseTransparent(true);
            double barWidth = CELL_WIDTH * 7 + NOW_LINE_RIGHT_BLEED;
            nowBar.setMinWidth(barWidth);
            nowBar.setPrefWidth(barWidth);
            nowBar.setMaxWidth(barWidth);
            GridPane.setValignment(nowBar, VPos.TOP);
            GridPane.setHalignment(nowBar, HPos.LEFT);
            GridPane.setMargin(nowBar, new Insets(0, -NOW_LINE_RIGHT_BLEED, 0, 0));
        }
        nowBar.setStyle("-fx-background-color: red;");
        nowBarRow = nowSlot + 1;
        if (nowBar.getParent() != grid) {
            grid.add(nowBar, 1, nowBarRow, 7, 1);
        } else {
            GridPane.setColumnIndex(nowBar, 1);
            GridPane.setRowIndex(nowBar, nowBarRow);
            GridPane.setColumnSpan(nowBar, 7);
        }
        nowBar.setTranslateY(fractionWithinSlot(now, true) * CELL_HEIGHT);
    }

    private void paintActualBars(List<Task> placed) {
        if (placed == null || slotCells == null) return;
        for (Task t : placed) {
            if (t == null || t.isQuick() || t.getPlacedDate() == null || t.getPlacedTime() == null) continue;
            paintTaskOverlay(t);
        }
    }

    private void paintTaskOverlay(Task t) {
        LocalDate colDate = AppTimeReference.columnDateFor(t.getPlacedDate(), t.getPlacedTime());
        int day = dayIndexFor(colDate);
        if (day < 0) return;
        int startRel = AppTimeReference.relativeMinutes(t.getPlacedTime());
        int endRel = startRel + AppTimeReference.clampMinutes((int) Math.round(t.getDurationMinutes()));
        int startSlot = startRel / AppTimeReference.MINUTES_PER_SLOT;
        int endSlot = (endRel + AppTimeReference.MINUTES_PER_SLOT - 1) / AppTimeReference.MINUTES_PER_SLOT;
        if (endRel % AppTimeReference.MINUTES_PER_SLOT == 0) {
            endSlot = endRel / AppTimeReference.MINUTES_PER_SLOT;
        }
        if (endSlot <= startSlot) endSlot = startSlot + 1;
        boolean dark = AppTheme.isDark();
        boolean ended = taskHasEnded(t);
        String fill = ended
                ? (dark ? "rgba(18,20,24,0.55)" : "rgba(31,79,122,0.50)")
                : (dark ? "rgba(255,255,255,0.32)" : "rgba(31,79,122,0.22)");
        String tickColor = ended
                ? (dark ? "#8b929a" : "#5a7a94")
                : (dark ? "#f4f7fa" : "#1f4f7a");
        for (int slot = startSlot; slot < endSlot && slot < AppTimeReference.SLOTS_PER_DAY; slot++) {
            if (slot < 0) continue;
            Pane cell = slotCells[slot][day];
            if (cell == null) continue;
            int slot0 = slot * AppTimeReference.MINUTES_PER_SLOT;
            int slot1 = slot0 + AppTimeReference.MINUTES_PER_SLOT;
            int a = Math.max(startRel, slot0);
            int b = Math.min(endRel, slot1);
            if (b <= a) continue;
            double y = ((a - slot0) / (double) AppTimeReference.MINUTES_PER_SLOT) * CELL_HEIGHT;
            double h = Math.max(2, ((b - a) / (double) AppTimeReference.MINUTES_PER_SLOT) * CELL_HEIGHT);
            Region bar = new Region();
            bar.setMouseTransparent(true);
            bar.setPrefSize(CELL_WIDTH - 6, h);
            bar.setMinSize(CELL_WIDTH - 6, h);
            bar.setMaxSize(CELL_WIDTH - 6, h);
            bar.setLayoutX(3);
            bar.setLayoutY(y);
            bar.setStyle("-fx-background-color: " + fill + "; -fx-background-radius: 3;");
            cell.getChildren().add(0, bar);
            if (slot == startSlot) {
                Region tick = new Region();
                tick.setMouseTransparent(true);
                tick.setPrefSize(CELL_WIDTH - 4, 2);
                tick.setMinSize(CELL_WIDTH - 4, 2);
                tick.setMaxSize(CELL_WIDTH - 4, 2);
                tick.setLayoutX(2);
                tick.setLayoutY(y);
                tick.setStyle("-fx-background-color: " + tickColor + ";");
                cell.getChildren().add(tick);
            }
        }
    }

    private int dayIndexFor(LocalDate colDate) {
        if (colDate == null || overlayWeekStart == null) return -1;
        long day = java.time.temporal.ChronoUnit.DAYS.between(overlayWeekStart, colDate);
        if (day < 0 || day > 6) return -1;
        return (int) day;
    }

    private static Task quickAt(List<Task> placed, LocalDate columnDate, int slotIndex) {
        if (placed == null) return null;
        for (Task t : placed) {
            if (t == null || !t.isQuick() || t.getPlacedTime() == null || t.getPlacedDate() == null) continue;
            LocalDate col = AppTimeReference.columnDateFor(t.getPlacedDate(), t.getPlacedTime());
            if (!columnDate.equals(col)) continue;
            int startRel = AppTimeReference.relativeMinutes(t.getPlacedTime());
            int endRel = startRel + AppTimeReference.clampMinutes((int) Math.round(t.getDurationMinutes()));
            int slot0 = slotIndex * AppTimeReference.MINUTES_PER_SLOT;
            int slot1 = slot0 + AppTimeReference.MINUTES_PER_SLOT;
            if (startRel < slot1 && endRel > slot0) return t;
        }
        return null;
    }

    private static Task taskAt(java.util.Map<LocalDate, Day> gridData,
                               List<Task> placed, LocalDate columnDate, int slotIndex) {
        if (gridData != null && gridData.containsKey(columnDate)) {
            Task[] cells = gridData.get(columnDate).returnDayGrid();
            if (cells != null && slotIndex >= 0 && slotIndex < cells.length && cells[slotIndex] != null
                    && !cells[slotIndex].isQuick()) {
                return cells[slotIndex];
            }
        }
        for (Task t : placed) {
            if (t == null || t.getPlacedDate() == null || t.getPlacedTime() == null) continue;
            LocalDate col = AppTimeReference.columnDateFor(t.getPlacedDate(), t.getPlacedTime());
            if (!columnDate.equals(col)) continue;
            if (t.isQuick()) continue;
            int start = AppTimeReference.localTimeToSlot(t.getPlacedTime());
            if (start < 0) continue;
            int count = t.getDurationSlots();
            if (count < 1) count = 1;
            if (slotIndex >= start && slotIndex < start + count) return t;
        }
        return null;
    }

    private javafx.scene.Node taskCaption(Task task, boolean firstSlot, boolean past) {
        Label name = taskLabel(task, firstSlot, past);
        if (!firstSlot || task == null || task.isQuick()) return name;
        java.util.List<Task> nested = system.quicksOf(task);
        if (nested.isEmpty()) return name;
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(1, name);
        box.setMouseTransparent(true);
        for (Task q : nested) {
            if (q == null) continue;
            String clock = q.getPlacedTime() == null ? ""
                    : String.format("%02d:%02d  ", q.getPlacedTime().getHour(), q.getPlacedTime().getMinute());
            int mins = (int) Math.round(q.getDurationMinutes());
            Label line = new Label(clock + q.getTaskName() + "  ·  " + mins + " min");
            line.setWrapText(true);
            line.setMouseTransparent(true);
            line.setStyle("-fx-font-size: 9px; -fx-padding: 0 4 0 8; -fx-text-fill: "
                    + AppTheme.ink() + ";");
            box.getChildren().add(line);
        }
        return box;
    }

    private static Label taskLabel(Task task, boolean firstSlot, boolean past) {
        boolean done = task.getWorkflow() == Workflow.FINISHED;
        String name = task.getTaskName() == null ? "" : task.getTaskName();
        String clock = "";
        if (firstSlot && task.getPlacedTime() != null) {
            clock = String.format("%02d:%02d  ", task.getPlacedTime().getHour(), task.getPlacedTime().getMinute());
        }
        Label label = new Label(done ? "done  ·  " + clock + name : (firstSlot ? clock + name : ""));
        label.setWrapText(true);
        label.setMouseTransparent(true);
        String ink = AppTheme.ink();
        if (AppTheme.isDark() && !done) {
            ink = past ? "#e6e8ea" : "#f4f7fa";
        }
        if (done) {
            label.setStyle(
                    "-fx-font-size: 10px;"
                            + "-fx-font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif;"
                            + "-fx-font-style: italic;"
                            + "-fx-text-fill: " + AppTheme.muted() + ";"
                            + "-fx-padding: 3 6 0 8;");
        } else {
            label.setStyle("-fx-font-size: 10px; -fx-padding: 3 4 0 4; -fx-text-fill: "
                    + ink + ";");
        }
        return label;
    }

    public static double fractionWithinSlot(LocalTime time) {
        return fractionWithinSlot(time, false);
    }

    public static double fractionWithinSlot(LocalTime time, boolean withSeconds) {
        int totalMinutes = time.getHour() * 60 + time.getMinute();
        int relative = totalMinutes - AppTimeReference.GRID_START_MINUTES;
        if (relative < 0) {
            relative = (AppTimeReference.MINUTES_PER_DAY - AppTimeReference.GRID_START_MINUTES)
                    + totalMinutes;
        }
        if (withSeconds) {
            int into = (relative % AppTimeReference.MINUTES_PER_SLOT) * 60 + time.getSecond();
            return into / (double) (AppTimeReference.MINUTES_PER_SLOT * 60);
        }
        int intoSlot = relative % AppTimeReference.MINUTES_PER_SLOT;
        return intoSlot / (double) AppTimeReference.MINUTES_PER_SLOT;
    }

    public void updateNowLinePosition() {
        LocalTime now = AppTimeReference.getCurrentTime();
        if (AppTimeReference.localTimeToSlot(now) + 1 != nowBarRow) return;
        if (nowBar != null) {
            nowBar.setTranslateY(fractionWithinSlot(now, true) * CELL_HEIGHT);
        }
    }

    public boolean nowLineNeedsRowChange() {
        if (nowBar == null) return false;
        return AppTimeReference.currentSlotIndex() + 1 != nowBarRow;
    }

    private List<BlockedTime> loadBlocked() {
        BlockedTimePeriod holder = system.getBlockedTime();
        if (holder == null || holder.blockedPeriod == null) return new ArrayList<>();
        return holder.blockedPeriod;
    }

    private static boolean isPast(LocalDate columnDate, int slotIndex) {
        LocalDateTime start = AppTimeReference.dateTimeForSlot(columnDate, slotIndex);
        return !start.isAfter(AppTimeReference.getCurrentDateTime());
    }

    /** Whole task is past only after its end — a live block does not split colors mid-span. */
    static boolean taskHasEnded(Task t) {
        if (t == null || t.getPlacedDate() == null || t.getPlacedTime() == null) return false;
        if (t.getWorkflow() == Workflow.FINISHED || t.getLifeCycle() == TaskLifeCycle.PAST) {
            return true;
        }
        int mins = Math.max(1, (int) Math.round(t.getDurationMinutes()));
        LocalDateTime end = LocalDateTime.of(t.getPlacedDate(), t.getPlacedTime()).plusMinutes(mins);
        return !end.isAfter(AppTimeReference.getCurrentDateTime());
    }

    private static BlockedTime blockedAt(List<BlockedTime> blocked, LocalDate columnDate, int slotIndex) {
        LocalDate date = AppTimeReference.calendarDateForSlot(columnDate, slotIndex);
        LocalTime slotStart = AppTimeReference.slotToLocalTime(slotIndex);
        LocalTime slotEnd = slotStart.plusMinutes(AppTimeReference.MINUTES_PER_SLOT);

        BlockedTime daily = null;
        BlockedTime specific = null;
        boolean exception = false;

        for (BlockedTime b : blocked) {
            if (b.getStartTime() == null || b.getEndTime() == null) continue;
            if (!(slotStart.isBefore(b.getEndTime()) && slotEnd.isAfter(b.getStartTime()))) continue;

            if (b.freesOn(date)) {
                exception = true;
            } else if (b.isException()) {
                continue;
            } else if (b.isDaily()) {
                daily = b;
            } else if (b.appliesOn(date)) {
                specific = b;
            }
        }

        if (exception) return null;
        if (specific != null) return specific;
        return daily;
    }

    private static boolean isSegmentStart(List<BlockedTime> blocked, LocalDate columnDate,
                                          int slotIndex, BlockedTime current) {
        if (current == null || slotIndex <= 0) return current != null;
        BlockedTime prev = blockedAt(blocked, columnDate, slotIndex - 1);
        if (prev == null) return true;
        return !java.util.Objects.equals(prev.getId(), current.getId());
    }

    /** Empty future cells fill green/red; task, past, and blocked keep their paint and only get a rim. */
    static void paintDropHover(Pane cell, CellState st, boolean ok) {
        if (cell == null || st == null || st.columnDate == null) return;
        ensurePaints();
        Task task = st.task;
        if (task != null && task.isQuick()) task = null;
        boolean keepFill = task != null || st.block != null || isPast(st.columnDate, st.slotIndex);
        Border border = ok ? paints.dropOkEdge : paints.dropNoEdge;
        if (keepFill) {
            cell.setBorder(border);
            return;
        }
        paintCell(cell, ok ? paints.dropOk : paints.dropNo, border);
    }

    static void styleSlot(Pane cell, TaskManagementSystem system,
                          LocalDate columnDate, int slotIndex) {
        if (cell == null || system == null || columnDate == null) return;
        Task task = null;
        Day day = system.getGrid() == null ? null : system.getGrid().get(columnDate);
        if (day != null) {
            Task[] g = day.returnDayGrid();
            if (g != null && slotIndex >= 0 && slotIndex < g.length) task = g[slotIndex];
        }
        BlockedTimePeriod holder = system.getBlockedTime();
        List<BlockedTime> blocked = holder == null || holder.blockedPeriod == null
                ? List.of() : holder.blockedPeriod;
        applyDayStyle(cell, columnDate, slotIndex, blockedAt(blocked, columnDate, slotIndex), task);
    }

    private static void applyDayStyle(Pane cell, LocalDate columnDate, int slotIndex,
                               BlockedTime block, Task task) {
        ensurePaints();
        if (task != null && task.isQuick()) task = null;
        if (task != null && task.getWorkflow() == Workflow.FINISHED) {
            paintCell(cell, paints.done, paints.doneEdge);
            return;
        }
        if (task != null) {
            if (taskHasEnded(task)) paintCell(cell, paints.occEnded, paints.occEndedEdge);
            else paintCell(cell, paints.occLive, paints.occLiveEdge);
            return;
        }
        if (isPast(columnDate, slotIndex)) {
            paintCell(cell, paints.past, paints.gray);
            return;
        }
        if (block != null) {
            paintBlocked(cell, block);
            return;
        }
        if (columnDate.equals(AppTimeReference.currentColumnDate())) {
            paintCell(cell, paints.today, paints.gray);
        } else {
            paintCell(cell, paints.paper, paints.gray);
        }
    }

    private static void paintBlocked(Pane cell, BlockedTime block) {
        ensurePaints();
        int hour = block.getStartTime().getHour();
        Background fill;
        if (hour >= 21 || hour < 8) fill = paints.blockNight;
        else if (hour >= 11 && hour < 14) fill = paints.blockLunch;
        else if (hour < 11) fill = paints.blockMorning;
        else fill = paints.blockOther;
        paintCell(cell, fill, paints.gray);
    }

    private static void paintCell(Pane cell, Background bg, Border border) {
        if (cell.getStyle() != null && !cell.getStyle().isEmpty()) cell.setStyle(null);
        if (cell.getBackground() != bg) cell.setBackground(bg);
        if (cell.getBorder() != border) cell.setBorder(border);
    }

    private static void ensurePaints() {
        String key = AppTheme.currentPack().id();
        if (paints != null && key.equals(paintKey)) return;
        paintKey = key;
        boolean dark = AppTheme.isDark();
        Paints p = new Paints();
        p.hover = fillBg(AppTheme.hover());
        p.paper = fillBg(AppTheme.paper());
        p.today = fillBg(AppTheme.today());
        p.past = fillBg(AppTheme.past());
        p.gray = edge("#999999");
        CornerRadii round = new CornerRadii(4);
        p.occLive = new Background(new BackgroundFill(
                Color.web(dark ? "#2f92e0" : "#c5d8ec"), round, Insets.EMPTY));
        p.occEnded = new Background(new BackgroundFill(
                Color.web(dark ? "#5c636c" : "#9eb4c8"), round, Insets.EMPTY));
        p.occLiveEdge = new Border(new BorderStroke(
                Color.web(dark ? "#7ec4f0" : "#7a9bb8"), BorderStrokeStyle.SOLID, round, BorderStroke.THIN));
        p.occEndedEdge = new Border(new BorderStroke(
                Color.web(dark ? "#7a828c" : "#7a9bb8"), BorderStrokeStyle.SOLID, round, BorderStroke.THIN));
        LinearGradient g = new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#c3d2c6")), new Stop(1, Color.web("#dfe6dc")));
        p.done = new Background(new BackgroundFill(g, CornerRadii.EMPTY, Insets.EMPTY));
        p.doneEdge = new Border(new BorderStroke(
                Color.web("#8ea494"), Color.web("#c5cec8"),
                Color.web("#c5cec8"), Color.web("#6f8a76"),
                BorderStrokeStyle.SOLID, BorderStrokeStyle.SOLID,
                BorderStrokeStyle.SOLID, BorderStrokeStyle.SOLID,
                CornerRadii.EMPTY, new BorderWidths(1, 1, 1, 4), Insets.EMPTY));
        p.blockNight = fillBg(dark ? "#2a2830" : "#c5cbe8");
        p.blockLunch = fillBg(dark ? "#4a3a22" : "#f3d5a3");
        p.blockMorning = fillBg(dark ? "#3a3228" : "#b9dde6");
        p.blockOther = fillBg(dark ? "#3a2a48" : "#ddc6ea");
        p.dropOk = fillBg(dark ? "#2f5d3a" : "#c8e6c9");
        p.dropNo = fillBg(dark ? "#6b3232" : "#f5c6c6");
        p.dropOkEdge = new Border(new BorderStroke(
                Color.web("#6aaa6a"), BorderStrokeStyle.SOLID, CornerRadii.EMPTY, new BorderWidths(2)));
        p.dropNoEdge = new Border(new BorderStroke(
                Color.web("#c45c5c"), BorderStrokeStyle.SOLID, CornerRadii.EMPTY, new BorderWidths(2)));
        paints = p;
    }

    private static Background fillBg(String hex) {
        return new Background(new BackgroundFill(Color.web(hex), CornerRadii.EMPTY, Insets.EMPTY));
    }

    private static Border edge(String hex) {
        return new Border(new BorderStroke(
                Color.web(hex), BorderStrokeStyle.SOLID, CornerRadii.EMPTY, BorderStroke.THIN));
    }
}