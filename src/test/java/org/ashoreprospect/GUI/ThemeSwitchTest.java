/**
 * The dark/light pill switch: its geometry, its colours, and the theme listener it registers.
 *
 * <p>{@code ThemeSwitch} registers a listener on {@link AppTheme} in its constructor that is never
 * removed, so each instance is a small leak. {@link #detachListeners()} clears them after every
 * test to keep the registry from growing across the suite and to stop a stray listener repainting
 * nodes from an earlier test.
 */
package org.ashoreprospect.GUI;

import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import org.ashoreprospect.FxTestKit;
import org.ashoreprospect.GUI.theme.BuiltinThemes;
import org.ashoreprospect.GUI.theme.Contrast;
import org.ashoreprospect.net.Endpoints;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("theme-prefs")
class ThemeSwitchTest {

    private static final Path PREF_FILE = Endpoints.dir().resolve("theme.txt");

    private String savedPrefs;

    /** Lets the multi-block theme-change test hand a node between FX tasks. */
    private final java.util.concurrent.atomic.AtomicReference<Object> holder =
            new java.util.concurrent.atomic.AtomicReference<>();

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestKit.ensureStarted();
    }

    @BeforeEach
    void rememberPrefs() throws Exception {
        savedPrefs = Files.isRegularFile(PREF_FILE)
                ? Files.readString(PREF_FILE, StandardCharsets.UTF_8) : null;
        resetToLight();
    }

    @AfterEach
    void detachListeners() throws Exception {
        // ThemeSwitch leaks one listener per instance; drop them so they cannot fire later.
        AppTheme.clearListeners();
        if (savedPrefs == null) Files.deleteIfExists(PREF_FILE);
        else {
            Files.createDirectories(PREF_FILE.getParent());
            Files.writeString(PREF_FILE, savedPrefs, StandardCharsets.UTF_8);
        }
    }

    private static void resetToLight() throws Exception {
        FxTestKit.onFx(() -> AppTheme.setPack(BuiltinThemes.LIGHT.id()));
    }

    /** The switch lays out a track first and a knob second. */
    private static Rectangle trackOf(ThemeSwitch sw) {
        return (Rectangle) sw.getChildren().get(0);
    }

    private static Circle knobOf(ThemeSwitch sw) {
        return (Circle) sw.getChildren().get(1);
    }

    // ==================================================================
    // geometry
    // ==================================================================

    @Test
    void switchHasTheFixedPillSize() throws Exception {
        FxTestKit.onFx(() -> {
            ThemeSwitch sw = new ThemeSwitch();

            assertEquals(52, sw.getPrefWidth(), 0.001);
            assertEquals(28, sw.getPrefHeight(), 0.001);
            assertEquals(52, sw.getMaxWidth(), 0.001);
            assertEquals(28, sw.getMaxHeight(), 0.001);
        });
    }

    @Test
    void switchHoldsExactlyATrackAndAKnob() throws Exception {
        FxTestKit.onFx(() -> {
            ThemeSwitch sw = new ThemeSwitch();

            List<Node> children = sw.getChildren();
            assertEquals(2, children.size(), "a track and a knob, nothing else");
            assertTrue(trackOf(sw) instanceof Rectangle);
            assertTrue(knobOf(sw) instanceof Circle);
        });
    }

    @Test
    void trackIsRoundedToAFullPill() throws Exception {
        FxTestKit.onFx(() -> {
            ThemeSwitch sw = new ThemeSwitch();
            Rectangle track = trackOf(sw);

            assertEquals(52, track.getWidth(), 0.001);
            assertEquals(28, track.getHeight(), 0.001);
            assertEquals(28, track.getArcWidth(), 0.001, "arc width equals the height for a pill");
            assertEquals(28, track.getArcHeight(), 0.001);
        });
    }

    @Test
    void knobHasAnElevenPixelRadius() throws Exception {
        FxTestKit.onFx(() -> {
            ThemeSwitch sw = new ThemeSwitch();
            assertEquals(11, knobOf(sw).getRadius(), 0.001);
        });
    }

    @Test
    void knobDoesNotSwallowClicksMeantForTheTrack() throws Exception {
        FxTestKit.onFx(() -> {
            ThemeSwitch sw = new ThemeSwitch();
            assertTrue(knobOf(sw).isMouseTransparent(),
                    "clicks must reach the StackPane handler, not the knob");
        });
    }

    @Test
    void switchIsCentredOnItsTrack() throws Exception {
        FxTestKit.onFx(() -> {
            ThemeSwitch sw = new ThemeSwitch();
            assertEquals(javafx.geometry.Pos.CENTER, sw.getAlignment());
        });
    }

    // ==================================================================
    // initial paint
    // ==================================================================

    @Test
    void lightThemeStartsTheKnobOnTheLeft() throws Exception {
        FxTestKit.onFx(() -> {
            ThemeSwitch sw = new ThemeSwitch();
            assertEquals(-12, knobOf(sw).getTranslateX(), 0.001, "light sits at -12");
        });
    }

    @Test
    void darkThemeStartsTheKnobOnTheRight() throws Exception {
        FxTestKit.onFx(() -> {
            AppTheme.setPack(BuiltinThemes.DARK.id());
            ThemeSwitch sw = new ThemeSwitch();

            assertEquals(12, knobOf(sw).getTranslateX(), 0.001, "dark sits at +12");
        });
    }

    @Test
    void trackInkComesFromTheLineColourWhenLight() throws Exception {
        FxTestKit.onFx(() -> {
            ThemeSwitch sw = new ThemeSwitch();

            assertEquals(Contrast.parse(AppTheme.palette().line()), trackOf(sw).getFill(),
                    "a light track uses the palette line colour");
        });
    }

    @Test
    void trackInkComesFromTheTodayColourWhenDark() throws Exception {
        FxTestKit.onFx(() -> {
            AppTheme.setPack(BuiltinThemes.DARK.id());
            ThemeSwitch sw = new ThemeSwitch();

            assertEquals(Contrast.parse(AppTheme.palette().today()), trackOf(sw).getFill(),
                    "a dark track uses the palette today colour");
        });
    }

    @Test
    void knobAlwaysTakesThePaperColour() throws Exception {
        FxTestKit.onFx(() -> {
            ThemeSwitch light = new ThemeSwitch();
            assertEquals(Contrast.parse(AppTheme.palette().paper()), knobOf(light).getFill());

            AppTheme.setPack(BuiltinThemes.DARK.id());
            ThemeSwitch dark = new ThemeSwitch();
            assertEquals(Contrast.parse(AppTheme.palette().paper()), knobOf(dark).getFill());
        });
    }

    @Test
    void bothFillsAreRealPaints() throws Exception {
        FxTestKit.onFx(() -> {
            ThemeSwitch sw = new ThemeSwitch();

            assertNotNull(trackOf(sw).getFill(), "the track must never be left unfilled");
            assertNotNull(knobOf(sw).getFill());
            assertTrue(trackOf(sw).getFill() instanceof Color);
            assertTrue(knobOf(sw).getFill() instanceof Color);
        });
    }

    // ==================================================================
    // reacting to a theme change
    // ==================================================================

    @Test
    void aLiveSwitchRepaintsItsKnobWhenTheThemeChanges() throws Exception {
        // Build the switch while light, then flip to dark. The listener paints with a 220ms
        // TranslateTransition, so wait for the animation to finish rather than just draining
        // the FX queue once.
        FxTestKit.onFx(() -> {
            AppTheme.clearListeners();
            ThemeSwitch sw = new ThemeSwitch();
            assertEquals(-12, knobOf(sw).getTranslateX(), 0.001, "built while light");

            holder.set(sw);
            AppTheme.toggle();
        });

        // setPack fires the listeners synchronously, but each one hops to runLater before
        // starting its transition. Pump the queue until the knob actually lands.
        long deadline = System.currentTimeMillis() + 5000;
        boolean landed = false;
        while (System.currentTimeMillis() < deadline) {
            FxTestKit.onFx(() -> { });
            boolean[] atRight = new boolean[1];
            FxTestKit.onFx(() -> atRight[0] = Math.abs(
                    knobOf((ThemeSwitch) holder.get()).getTranslateX() - 12) < 0.01);
            if (atRight[0]) {
                landed = true;
                break;
            }
            Thread.sleep(25);
        }

        assertTrue(landed, "the listener should have slid the knob to the dark side; still at "
                + knobOf((ThemeSwitch) holder.get()).getTranslateX());
    }

    @Test
    void switchSurvivesBeingPutInAParent() throws Exception {
        FxTestKit.onFx(() -> {
            StackPane host = new StackPane();
            ThemeSwitch sw = new ThemeSwitch();

            host.getChildren().add(sw);

            assertEquals(sw, host.getChildren().get(0));
            assertEquals(52, sw.getPrefWidth(), 0.001);
        });
    }
}
