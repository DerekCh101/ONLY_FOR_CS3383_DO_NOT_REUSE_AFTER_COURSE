/** Dark/light toggle control on the Account screen. */
package org.ashoreprospect.GUI;

import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.ashoreprospect.GUI.theme.Contrast;
import org.ashoreprospect.GUI.theme.ThemePalette;

/** Pill track; the circle slides left (light) / right (dark). Colors come from the pack. */
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
        ThemePalette p = AppTheme.palette();
        boolean dark = AppTheme.isDark();
        track.setFill(Contrast.parse(dark ? p.today() : p.line()));
        if (track.getFill() == null) track.setFill(Color.GRAY);
        knob.setFill(Contrast.parse(p.paper()));
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
