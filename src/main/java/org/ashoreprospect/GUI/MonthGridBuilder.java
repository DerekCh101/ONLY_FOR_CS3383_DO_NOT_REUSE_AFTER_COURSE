/** Draws the month calendar cells and task chips. */
package org.ashoreprospect.GUI;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.Day;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskManagementSystem;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Month calendar: 7 columns (Mon–Sun), up to 6 rows.
 * Shows task names for that day and HK public holiday labels when known.
 */
public class MonthGridBuilder {

    private static final double CELL_MIN_WIDTH = 110;
    private static final double CELL_MIN_HEIGHT = 90;

    private final TaskManagementSystem system;
    private final TaskDialogs dialogs;
    private java.util.function.Consumer<Task> onSelectTask;

    public MonthGridBuilder(TaskManagementSystem system, TaskDialogs dialogs) {
        this.system = system;
        this.dialogs = dialogs;
    }

    public void setOnSelectTask(java.util.function.Consumer<Task> onSelectTask) {
        this.onSelectTask = onSelectTask;
    }

    /**
     * @param monthOffset 0 = current month, -1 previous, +1 next, …
     */
    public void build(GridPane grid, Label sidePanel, int monthOffset) {
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        grid.getRowConstraints().clear();
        grid.setHgap(2);
        grid.setVgap(2);
        grid.setPadding(new Insets(4));

        LocalDate today = AppTimeReference.currentColumnDate();
        YearMonth ym = YearMonth.from(today).plusMonths(monthOffset);
        LocalDate firstOfMonth = ym.atDay(1);
        LocalDate gridStart = AppTimeReference.getStartOfWeek(firstOfMonth); // Monday

        // Title row (spans)
        Label title = new Label(ym.getMonth() + " " + ym.getYear());
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: "
                + AppTheme.ink() + ";");
        grid.add(title, 0, 0, 7, 1);

        // Weekday headers
        String[] headers = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        for (int c = 0; c < 7; c++) {
            Label h = new Label(headers[c]);
            h.setStyle("-fx-font-weight: bold; -fx-padding: 4; -fx-text-fill: "
                    + AppTheme.ink() + ";");
            h.setMaxWidth(Double.MAX_VALUE);
            h.setAlignment(Pos.CENTER);
            grid.add(h, c, 1);
        }

        Map<LocalDate, String> holidays = HongKongHolidays.map();

        // 6 weeks × 7 days
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 7; col++) {
                LocalDate date = gridStart.plusDays(row * 7L + col);
                VBox cell = buildDayCell(date, ym, today, holidays);
                grid.add(cell, col, row + 2);
                GridPane.setHgrow(cell, Priority.ALWAYS);
                GridPane.setVgrow(cell, Priority.ALWAYS);
            }
        }

        if (sidePanel != null) {
            sidePanel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + AppTheme.ink()
                    + "; " + AppTheme.font() + "-fx-font-size: 12px;");
        }
    }

    private VBox buildDayCell(LocalDate date,
                              YearMonth visibleMonth,
                              LocalDate today,
                              Map<LocalDate, String> holidays) {
        VBox cell = new VBox(2);
        cell.setPadding(new Insets(4));
        cell.setMinSize(CELL_MIN_WIDTH, CELL_MIN_HEIGHT);
        cell.setPrefSize(CELL_MIN_WIDTH, CELL_MIN_HEIGHT);

        boolean inMonth = YearMonth.from(date).equals(visibleMonth);
        boolean isToday = date.equals(today);
        String holidayName = holidays.get(date);

        String bg;
        if (isToday) {
            bg = AppTheme.today();
        } else if (holidayName != null) {
            bg = AppTheme.isDark() ? "#5a4320" : "#ffe0b2";
        } else if (!inMonth) {
            bg = AppTheme.isDark() ? "#161c24" : "#e8e8e8";
        } else if (date.isBefore(today)) {
            bg = AppTheme.past();
        } else {
            bg = AppTheme.paper();
        }
        cell.setStyle(
                "-fx-background-color: " + bg + ";"
                        + "-fx-border-color: #999999;"
                        + "-fx-border-width: 1;"
        );

        Label dayNum = new Label(String.valueOf(date.getDayOfMonth()));
        dayNum.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: "
                + (inMonth ? AppTheme.ink() : AppTheme.muted()) + ";");
        cell.getChildren().add(dayNum);

        if (holidayName != null) {
            Label hol = new Label(holidayName);
            hol.setWrapText(true);
            hol.setStyle("-fx-font-size: 9px; -fx-text-fill: #c62828;");
            cell.getChildren().add(hol);
        }

        List<Task> tasks = tasksOn(date);
        int shown = 0;
        for (Task t : tasks) {
            if (shown >= 4) {
                Label more = new Label("+" + (tasks.size() - 4) + " more");
                more.setStyle("-fx-font-size: 9px; -fx-text-fill: " + AppTheme.muted() + ";");
                cell.getChildren().add(more);
                break;
            }
            Label line = new Label("• " + t.getTaskName());
            line.setWrapText(true);
            line.setStyle("-fx-font-size: 9px; -fx-text-fill: " + AppTheme.ink() + ";");
            cell.getChildren().add(line);
            shown++;
        }

        final LocalDate clicked = date;
        final List<Task> dayTasks = new ArrayList<>(tasks);
        cell.setOnMouseClicked(e -> {
            boolean pastDay = clicked.isBefore(AppTimeReference.currentColumnDate());
            if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                if (!pastDay && dialogs != null) dialogs.showTaskPopup(false, clicked, -1);
                return;
            }
            if (!dayTasks.isEmpty() && onSelectTask != null) {
                onSelectTask.accept(dayTasks.get(0));
                return;
            }
            if (!pastDay && dialogs != null) dialogs.showTaskPopup(false, clicked, -1);
        });

        return cell;
    }

    /** Unique tasks that occupy any slot on this date. */
    private List<Task> tasksOn(LocalDate date) {
        Set<Task> set = new LinkedHashSet<>();
        Day day = system.getGrid().get(date);
        if (day != null) {
            for (Task t : day.returnDayGrid()) {
                if (t != null && !t.isQuick()) set.add(t);
            }
        }
        // Fallback: placed list (in case grid not built for that day)
        for (Task t : system.getPlacedTasks()) {
            if (t == null || t.isQuick()) continue;
            if (date.equals(AppTimeReference.columnDateFor(t.getPlacedDate(), t.getPlacedTime()))) {
                set.add(t);
            }
        }
        return new ArrayList<>(set);
    }

    /**
     * Fixed Gregorian HK holidays every year + a few known floating dates
     * for 2026–2027 (update yearly for lunar/Easter accuracy).
     */
    public static final class HongKongHolidays {
        private HongKongHolidays() {}

        public static Map<LocalDate, String> map() {
            Map<LocalDate, String> m = new HashMap<>();

            int y0 = AppTimeReference.getCurrentDate().getYear();
            for (int y = y0 - 1; y <= y0 + 2; y++) {
                m.put(LocalDate.of(y, 1, 1), "New Year's Day");
                m.put(LocalDate.of(y, 5, 1), "Labour Day");
                m.put(LocalDate.of(y, 7, 1), "HKSAR Establishment Day");
                m.put(LocalDate.of(y, 10, 1), "National Day");
                m.put(LocalDate.of(y, 12, 25), "Christmas Day");
                m.put(LocalDate.of(y, 12, 26), "The first weekday after Christmas Day");
            }

            // --- 2026 floating / gazetted (verify against gov.hk if needed) ---
            m.put(LocalDate.of(2026, 2, 17), "Lunar New Year");
            m.put(LocalDate.of(2026, 2, 18), "Lunar New Year");
            m.put(LocalDate.of(2026, 2, 19), "Lunar New Year");
            m.put(LocalDate.of(2026, 4, 3), "Good Friday");
            m.put(LocalDate.of(2026, 4, 4), "Day after Good Friday");
            m.put(LocalDate.of(2026, 4, 5), "Ching Ming / Easter");
            m.put(LocalDate.of(2026, 4, 6), "Easter Monday");
            m.put(LocalDate.of(2026, 5, 24), "Buddha's Birthday");
            m.put(LocalDate.of(2026, 6, 19), "Dragon Boat Festival");
            m.put(LocalDate.of(2026, 9, 26), "Day after Mid-Autumn Festival");
            m.put(LocalDate.of(2026, 10, 19), "Chung Yeung Festival");

            // --- 2027 sample floating (update when official list is out) ---
            m.put(LocalDate.of(2027, 2, 6), "Lunar New Year");
            m.put(LocalDate.of(2027, 2, 7), "Lunar New Year");
            m.put(LocalDate.of(2027, 2, 8), "Lunar New Year");

            return m;
        }
    }
}