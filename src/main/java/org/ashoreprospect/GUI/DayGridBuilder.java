/** Day view: yesterday / today / tomorrow as description cards. No drag or time edits. */
package org.ashoreprospect.GUI;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;
import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.Day;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskManagementSystem;
import org.ashoreprospect.Workflow;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

public class DayGridBuilder {

    private final TaskManagementSystem system;
    private Consumer<Task> onSelectTask;

    public DayGridBuilder(TaskManagementSystem system) {
        this.system = system;
    }

    public void setOnSelectTask(Consumer<Task> onSelectTask) {
        this.onSelectTask = onSelectTask;
    }

    /**
     * @param dayOffset 0 = today in the middle column; −1 / +1 slides the three-day window
     */
    public void build(GridPane grid, Label sidePanel, int dayOffset) {
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        grid.getRowConstraints().clear();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(8));

        for (int i = 0; i < 3; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(100.0 / 3.0);
            cc.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(cc);
        }
        RowConstraints rc = new RowConstraints();
        rc.setVgrow(Priority.ALWAYS);
        rc.setFillHeight(true);
        grid.getRowConstraints().add(rc);

        LocalDate today = AppTimeReference.currentColumnDate();
        LocalDate center = today.plusDays(dayOffset);
        for (int i = 0; i < 3; i++) {
            LocalDate date = center.plusDays(i - 1L);
            VBox col = column(date, today);
            GridPane.setHgrow(col, Priority.ALWAYS);
            GridPane.setVgrow(col, Priority.ALWAYS);
            grid.add(col, i, 0);
        }

        if (sidePanel != null) {
            sidePanel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + AppTheme.ink()
                    + "; " + AppTheme.font() + "-fx-font-size: 12px;");
        }
    }

    private VBox column(LocalDate date, LocalDate today) {
        boolean isToday = date.equals(today);
        Label title = new Label(heading(date, today));
        title.setWrapText(true);
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: "
                + AppTheme.ink() + ";");
        Label sub = new Label(date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + "  " + date);
        sub.setStyle("-fx-font-size: 11px; -fx-text-fill: " + AppTheme.muted() + ";");

        VBox cards = new VBox(8);
        cards.setPadding(new Insets(4, 2, 12, 2));
        List<Task> tasks = tasksOn(date);
        tasks.sort(Comparator.comparing(t -> t.getPlacedTime() == null
                ? LocalTime.MIN : t.getPlacedTime()));
        if (tasks.isEmpty()) {
            Label empty = new Label("No tasks");
            empty.setStyle("-fx-font-size: 12px; -fx-text-fill: " + AppTheme.muted() + ";");
            cards.getChildren().add(empty);
        } else {
            for (Task t : tasks) cards.getChildren().add(card(t));
        }

        ScrollPane scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox box = new VBox(6, title, sub, scroll);
        box.setPadding(new Insets(10));
        box.setFillWidth(true);
        String bg = isToday ? AppTheme.today() : AppTheme.paper();
        box.setStyle("-fx-background-color: " + bg + "; -fx-background-radius: 8; "
                + "-fx-border-color: #999999; -fx-border-radius: 8; -fx-border-width: "
                + (isToday ? "2" : "1") + ";");
        return box;
    }

    private VBox card(Task task) {
        Label name = new Label(task.getTaskName() == null ? "Untitled" : task.getTaskName());
        name.setWrapText(true);
        name.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: "
                + AppTheme.ink() + ";");
        String when = "";
        if (task.getPlacedTime() != null) {
            when = task.getPlacedTime().toString();
            if (task.getDurationHours() > 0) {
                when += "  ·  " + task.getDurationHours() + " h";
            }
        }
        if (task.getWorkflow() == Workflow.FINISHED) {
            when = (when.isEmpty() ? "" : when + "  ·  ") + "done";
        }
        Label meta = new Label(when);
        meta.setStyle("-fx-font-size: 11px; -fx-text-fill: " + AppTheme.muted() + ";");

        VBox body = new VBox(3, name, meta);
        for (String line : descLines(task.getDescription())) {
            Label item = new Label(line);
            item.setWrapText(true);
            item.setStyle("-fx-font-size: 12px; -fx-text-fill: " + AppTheme.ink() + ";");
            body.getChildren().add(item);
        }
        if (task.getDescription() == null || task.getDescription().isBlank()) {
            Label none = new Label("No description");
            none.setStyle("-fx-font-size: 11px; -fx-font-style: italic; -fx-text-fill: "
                    + AppTheme.muted() + ";");
            body.getChildren().add(none);
        }

        body.setPadding(new Insets(8));
        body.setAlignment(Pos.TOP_LEFT);
        body.setStyle("-fx-background-color: " + AppTheme.paper() + "; -fx-background-radius: 6; "
                + "-fx-border-color: #999999; -fx-border-radius: 6;");
        body.setOnMouseClicked(e -> {
            if (onSelectTask != null) onSelectTask.accept(task);
        });
        return body;
    }

    private static List<String> descLines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        String[] lines = raw.split("\\R", -1);
        boolean marks = false;
        for (String line : lines) {
            String t = line == null ? "" : line.trim();
            if (t.startsWith("[x]") || t.startsWith("[X]") || t.startsWith("[ ]")) {
                marks = true;
                break;
            }
        }
        if (!marks) {
            out.add(raw.trim());
            return out;
        }
        for (String line : lines) {
            String t = line == null ? "" : line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("[x]") || t.startsWith("[X]")) {
                out.add("☑  " + t.substring(3).trim());
            } else if (t.startsWith("[ ]")) {
                out.add("☐  " + t.substring(3).trim());
            } else {
                out.add(t);
            }
        }
        return out;
    }

    private List<Task> tasksOn(LocalDate date) {
        Set<Task> set = new LinkedHashSet<>();
        Day day = system.getGrid().get(date);
        if (day != null) {
            for (Task t : day.returnDayGrid()) {
                if (t != null && !t.isQuick()) set.add(t);
            }
        }
        for (Task t : system.getPlacedTasks()) {
            if (t == null || t.isQuick()) continue;
            if (date.equals(AppTimeReference.columnDateFor(t.getPlacedDate(), t.getPlacedTime()))) {
                set.add(t);
            }
        }
        return new ArrayList<>(set);
    }

    private static String heading(LocalDate date, LocalDate today) {
        if (date.equals(today)) return "Today";
        if (date.equals(today.minusDays(1))) return "Yesterday";
        if (date.equals(today.plusDays(1))) return "Tomorrow";
        return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }
}
