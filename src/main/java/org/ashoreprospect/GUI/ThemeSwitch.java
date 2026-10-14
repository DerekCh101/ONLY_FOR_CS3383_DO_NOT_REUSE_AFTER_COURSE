package org.ashoreprospect.GUI;

import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/** Pill track; the circle slides left (light) / right (dark). */
public final class ThemeSwitch extends StackPane {

    private static final double W = 52;
    private static final double H = 28;
    private static final double R = 11;
    private static final double LIGHT_X = -12;
    private static final double DARK_X = 12;

    private final Circle knob = new Circle(R);
    private final Rectangle track = new Rectangle(W, H);

    public ThemeSwitch() {
        setPrefSize(W, H);
        setMaxSize(W, H);
        setAlignment(Pos.CENTER);
        track.setArcWidth(H);
        track.setArcHeight(H);
        knob.setMouseTransparent(true);
        getChildren().addAll(track, knob);
        paint(false);
        setOnMouseClicked(this::clicked);
        AppTheme.listen(() -> javafx.application.Platform.runLater(() -> paint(true)));
    }

    private void clicked(MouseEvent e) {
        AppTheme.toggle();
    }

    private void paint(boolean animate) {
        boolean dark = AppTheme.isDark();
        track.setStyle(dark
                ? "-fx-fill: #3d8b6e;"
                : "-fx-fill: #c9bba6;");
        knob.setStyle(dark
                ? "-fx-fill: #e8e4dc;"
                : "-fx-fill: #fbf7ef;");
        double to = dark ? DARK_X : LIGHT_X;
        if (!animate) {
            knob.setTranslateX(to);
            return;
        }
        TranslateTransition slide = new TranslateTransition(Duration.millis(220), knob);
        slide.setToX(to);
        slide.play();
    }
}
