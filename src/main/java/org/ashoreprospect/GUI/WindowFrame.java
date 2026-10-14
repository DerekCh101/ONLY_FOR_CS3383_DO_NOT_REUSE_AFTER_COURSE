package org.ashoreprospect.GUI;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Undecorated stage + themed title bar.
 * “Max” fills the work area (taskbar stays). Never exclusive fullscreen.
 * Drag the title bar to move; if snapped, drag pulls it off like a normal window.
 */
public final class WindowFrame {

    private static final int EDGE = 6;

    private static Stage stage;
    private static StackPane frame;
    private static BorderPane body;
    private static Label title;
    private static Button maxBtn;
    private static double dragOffX;
    private static double dragOffY;
    private static boolean dragging;
    private static boolean snapped;
    private static double restX = 80;
    private static double restY = 60;
    private static double restW = 1100;
    private static double restH = 720;
    private static Edge edge = Edge.NONE;
    private static double startX;
    private static double startY;
    private static double startW;
    private static double startH;

    private enum Edge { NONE, N, S, E, W, NE, NW, SE, SW }

    private WindowFrame() {}

    public static void install(Stage s) {
        if (stage != null) return;
        stage = s;
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setFullScreen(false);
        stage.setFullScreenExitHint("");

        frame = new StackPane();
        frame.getStyleClass().add("ashore-root");
        body = new BorderPane();
        body.setTop(buildBar());
        StackPane.setAlignment(body, Pos.CENTER);
        frame.getChildren().add(body);
        stage.titleProperty().addListener((o, a, b) -> paintTitle());
    }

    public static StackPane frame() {
        return frame;
    }

    public static void setContent(Parent content) {
        if (body == null) return;
        body.setCenter(content);
        if (content instanceof Region r) {
            r.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        }
        ensureScene();
        AppTheme.apply(stage.getScene());
        paintTitle();
    }

    /** Fill the desktop work area. Windows taskbar stays. Title bar stays. */
    public static void snapWorkArea() {
        if (stage == null) return;
        stage.setFullScreen(false);
        stage.setMaximized(false);
        if (!snapped) rememberRest();
        Rectangle2D v = Screen.getPrimary().getVisualBounds();
        stage.setX(v.getMinX());
        stage.setY(v.getMinY());
        stage.setWidth(v.getWidth());
        stage.setHeight(v.getHeight());
        snapped = true;
        paintTitle();
    }

    private static void rememberRest() {
        if (stage.getWidth() > 200 && stage.getHeight() > 200 && !snapped) {
            restX = stage.getX();
            restY = stage.getY();
            restW = stage.getWidth();
            restH = stage.getHeight();
        }
    }

    private static void restoreWindow() {
        stage.setFullScreen(false);
        stage.setMaximized(false);
        snapped = false;
        stage.setX(restX);
        stage.setY(restY);
        stage.setWidth(Math.max(720, restW));
        stage.setHeight(Math.max(480, restH));
        paintTitle();
    }

    private static void ensureScene() {
        if (stage.getScene() == null) {
            Rectangle2D b = Screen.getPrimary().getVisualBounds();
            Scene scene = new Scene(frame, b.getWidth(), b.getHeight());
            stage.setScene(scene);
            hookScene(scene);
        } else if (stage.getScene().getRoot() != frame) {
            stage.getScene().setRoot(frame);
            hookScene(stage.getScene());
        }
    }

    private static void hookScene(Scene scene) {
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, WindowFrame::onMove);
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, WindowFrame::onPress);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, WindowFrame::onDrag);
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            dragging = false;
            edge = Edge.NONE;
        });
    }

    private static HBox buildBar() {
        title = new Label("Ashore Prospect");
        title.getStyleClass().add("win-title");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);

        Button min = winBtn("–", "win-min");
        maxBtn = winBtn("□", "win-max");
        Button close = winBtn("✕", "win-close");
        min.setOnAction(e -> stage.setIconified(true));
        maxBtn.setOnAction(e -> toggleMax());
        close.setOnAction(e -> Platform.exit());

        HBox bar = new HBox(0, title, grow, min, maxBtn, close);
        bar.getStyleClass().add("win-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !(e.getTarget() instanceof Button)) toggleMax();
        });
        bar.setOnMousePressed(e -> {
            if (e.getTarget() instanceof Button) return;
            beginDrag(e);
        });
        bar.setOnMouseDragged(e -> {
            if (!dragging) return;
            if (snapped) unsnapForDrag(e);
            stage.setX(e.getScreenX() - dragOffX);
            stage.setY(e.getScreenY() - dragOffY);
        });
        return bar;
    }

    private static void beginDrag(MouseEvent e) {
        dragging = true;
        if (snapped) {
            dragOffX = restW * 0.5;
            dragOffY = Math.min(20, e.getSceneY());
        } else {
            dragOffX = e.getScreenX() - stage.getX();
            dragOffY = e.getScreenY() - stage.getY();
        }
    }

    private static void unsnapForDrag(MouseEvent e) {
        rememberRest();
        restoreWindow();
        double w = stage.getWidth();
        dragOffX = Math.max(24, Math.min(w - 24, e.getSceneX()));
        dragOffY = Math.min(20, e.getSceneY());
        stage.setX(e.getScreenX() - dragOffX);
        stage.setY(e.getScreenY() - dragOffY);
    }

    private static Button winBtn(String text, String extra) {
        Button b = new Button(text);
        b.getStyleClass().addAll("win-btn", extra);
        b.setFocusTraversable(false);
        b.setMinSize(40, 36);
        b.setPrefSize(40, 36);
        b.setMaxSize(40, 36);
        return b;
    }

    private static void toggleMax() {
        if (snapped) restoreWindow();
        else snapWorkArea();
    }

    private static void paintTitle() {
        if (title != null && stage != null) {
            String t = stage.getTitle();
            title.setText(t == null || t.isBlank() || "Scheduler".equalsIgnoreCase(t)
                    ? "Ashore Prospect" : t);
        }
        if (maxBtn != null) {
            maxBtn.setText(snapped ? "❐" : "□");
        }
    }

    private static boolean locked() {
        return snapped;
    }

    private static void onMove(MouseEvent e) {
        if (stage == null || locked() || dragging) return;
        Edge h = hit(e.getSceneX(), e.getSceneY(),
                stage.getScene().getWidth(), stage.getScene().getHeight());
        stage.getScene().setCursor(cursor(h));
    }

    private static void onPress(MouseEvent e) {
        if (stage == null || locked()) return;
        edge = hit(e.getSceneX(), e.getSceneY(),
                stage.getScene().getWidth(), stage.getScene().getHeight());
        if (edge == Edge.NONE) return;
        startX = stage.getX();
        startY = stage.getY();
        startW = stage.getWidth();
        startH = stage.getHeight();
        dragOffX = e.getScreenX();
        dragOffY = e.getScreenY();
        e.consume();
    }

    private static void onDrag(MouseEvent e) {
        if (stage == null || locked() || edge == Edge.NONE) return;
        double dx = e.getScreenX() - dragOffX;
        double dy = e.getScreenY() - dragOffY;
        double x = startX;
        double y = startY;
        double w = startW;
        double h = startH;
        if (edge == Edge.E || edge == Edge.NE || edge == Edge.SE) w = Math.max(720, startW + dx);
        if (edge == Edge.S || edge == Edge.SE || edge == Edge.SW) h = Math.max(480, startH + dy);
        if (edge == Edge.W || edge == Edge.NW || edge == Edge.SW) {
            w = Math.max(720, startW - dx);
            x = startX + (startW - w);
        }
        if (edge == Edge.N || edge == Edge.NE || edge == Edge.NW) {
            h = Math.max(480, startH - dy);
            y = startY + (startH - h);
        }
        stage.setX(x);
        stage.setY(y);
        stage.setWidth(w);
        stage.setHeight(h);
        e.consume();
    }

    private static Edge hit(double x, double y, double w, double h) {
        boolean left = x <= EDGE;
        boolean right = x >= w - EDGE;
        boolean top = y <= EDGE;
        boolean bot = y >= h - EDGE;
        if (top && left) return Edge.NW;
        if (top && right) return Edge.NE;
        if (bot && left) return Edge.SW;
        if (bot && right) return Edge.SE;
        if (top) return Edge.N;
        if (bot) return Edge.S;
        if (left) return Edge.W;
        if (right) return Edge.E;
        return Edge.NONE;
    }

    private static Cursor cursor(Edge e) {
        return switch (e) {
            case N, S -> Cursor.V_RESIZE;
            case E, W -> Cursor.H_RESIZE;
            case NE, SW -> Cursor.NE_RESIZE;
            case NW, SE -> Cursor.NW_RESIZE;
            default -> Cursor.DEFAULT;
        };
    }
}
