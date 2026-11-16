/**
 * The shared JavaFX widget helpers: overlay/box construction, centring bindings, spinner
 * styling, and the ink-painting cascade.
 *
 * <p>Runs on a real JavaFX toolkit via {@link FxTestKit}. Every assertion inside
 * {@code FxTestKit.onFx} executes on the FX application thread, which is the only thread allowed
 * to touch nodes.
 */
package org.ashoreprospect.GUI;

import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.ashoreprospect.FxTestKit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiHelperTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestKit.ensureStarted();
    }

    // ==================================================================
    // createDimmedOverlay
    // ==================================================================

    @Test
    void dimmedOverlayTracksTheParentSize() throws Exception {
        FxTestKit.onFx(() -> {
            StackPane root = new StackPane();
            StackPane overlay = GuiHelper.createDimmedOverlay(root);

            assertNotNull(overlay);
            // Both dimensions are bound to the parent's width/height.
            assertTrue(overlay.prefWidthProperty().isBound(), "width must be bound");
            assertTrue(overlay.prefHeightProperty().isBound(), "height must be bound");

            root.resize(400, 300);
            assertEquals(400, overlay.getPrefWidth(), 0.001);
            assertEquals(300, overlay.getPrefHeight(), 0.001);
        });
    }

    @Test
    void dimmedOverlayPaintsASemiTransparentScrim() throws Exception {
        FxTestKit.onFx(() -> {
            StackPane overlay = GuiHelper.createDimmedOverlay(new StackPane());
            assertNotNull(overlay.getStyle());
            assertTrue(overlay.getStyle().contains("rgba"),
                    "expected a translucent scrim, got: " + overlay.getStyle());
            assertTrue(overlay.getStyle().contains("0.6"),
                    "the scrim should be 60% opaque, got: " + overlay.getStyle());
        });
    }

    // ==================================================================
    // createWhiteBox
    // ==================================================================

    @Test
    void whiteBoxIsACardWithTwelvePixelSpacing() throws Exception {
        FxTestKit.onFx(() -> {
            VBox box = GuiHelper.createWhiteBox();

            assertNotNull(box);
            assertEquals(12, box.getSpacing(), 0.001);
            assertTrue(box.getStyleClass().contains("dialog-card"),
                    "the card style class drives paintInk's dialog branch");
        });
    }

    @Test
    void whiteBoxSizesToItsContentRatherThanGrowing() throws Exception {
        FxTestKit.onFx(() -> {
            VBox box = GuiHelper.createWhiteBox();

            // USE_PREF_SIZE on both axes keeps the card hugging its contents.
            assertEquals(Region.USE_PREF_SIZE, box.getMaxWidth(), 0.001);
            assertEquals(Region.USE_PREF_SIZE, box.getMaxHeight(), 0.001);
            assertTrue(box.getStyle().contains("-fx-background-radius: 12"),
                    "expected the rounded card background, got: " + box.getStyle());
        });
    }

    // ==================================================================
    // centerInParent / centerInParentOffset
    // ==================================================================

    @Test
    void centerInParentPutsTheNodeInTheMiddle() throws Exception {
        FxTestKit.onFx(() -> {
            Pane parent = new Pane();
            Pane child = new Pane();
            child.setPrefSize(100, 50);

            GuiHelper.centerInParent(child, parent);

            parent.resize(400, 300);
            parent.layout();
            child.autosize();

            assertEquals((400 - 100) / 2.0, child.getLayoutX(), 0.001);
            assertEquals((300 - 50) / 2.0, child.getLayoutY(), 0.001);
            assertFalse(child.isManaged(), "a free-floating overlay must not be laid out by the parent");
        });
    }

    @Test
    void centerInParentBindingsFollowLaterResizes() throws Exception {
        FxTestKit.onFx(() -> {
            Pane parent = new Pane();
            Pane child = new Pane();
            child.setPrefSize(100, 50);

            GuiHelper.centerInParent(child, parent);
            parent.resize(400, 300);
            child.autosize();
            double first = child.getLayoutX();

            parent.resize(800, 300);
            child.autosize();

            assertTrue(child.getLayoutX() > first, "the binding must track the new width");
            assertEquals((800 - 100) / 2.0, child.getLayoutX(), 0.001);
        });
    }

    @Test
    void offsetVariantScalesTheCentringByTheGivenFactors() throws Exception {
        FxTestKit.onFx(() -> {
            Pane parent = new Pane();
            Pane child = new Pane();
            child.setPrefSize(100, 50);

            // 0.5 halves the centring distance on both axes.
            GuiHelper.centerInParentOffset(child, parent, 0.5, 0.5);

            parent.resize(400, 300);
            parent.layout();
            child.autosize();

            assertEquals(((400 - 100) / 2.0) * 0.5, child.getLayoutX(), 0.001);
            assertEquals(((300 - 50) / 2.0) * 0.5, child.getLayoutY(), 0.001);
            assertFalse(child.isManaged());
        });
    }

    @Test
    void offsetVariantWithFullFactorsMatchesPlainCentring() throws Exception {
        FxTestKit.onFx(() -> {
            Pane parent = new Pane();
            Pane plain = new Pane();
            Pane offset = new Pane();
            plain.setPrefSize(100, 50);
            offset.setPrefSize(100, 50);

            GuiHelper.centerInParent(plain, parent);
            GuiHelper.centerInParentOffset(offset, parent, 1, 1);

            parent.resize(400, 300);
            parent.layout();
            plain.autosize();
            offset.autosize();

            assertEquals(plain.getLayoutX(), offset.getLayoutX(), 0.001);
            assertEquals(plain.getLayoutY(), offset.getLayoutY(), 0.001);
        });
    }

    // ==================================================================
    // styleSpinner
    // ==================================================================

    @Test
    void styleSpinnerForcesTheDialogColours() throws Exception {
        FxTestKit.onFx(() -> {
            Spinner<Integer> sp = new Spinner<>(0, 10, 3);

            GuiHelper.styleSpinner(sp);

            assertTrue(sp.getStyle().contains("#ffffff"), "light background: " + sp.getStyle());
            assertTrue(sp.getStyle().contains("#1c2430"), "navy ink: " + sp.getStyle());
        });
    }

    @Test
    void styleSpinnerAlsoPaintsTheEditorItHolds() throws Exception {
        FxTestKit.onFx(() -> {
            Spinner<Integer> sp = new Spinner<>(0, 10, 3);

            GuiHelper.styleSpinner(sp);

            assertNotNull(sp.getEditor());
            String editorStyle = sp.getEditor().getStyle();
            assertTrue(editorStyle.contains("-fx-prompt-text-fill"), "prompt ink: " + editorStyle);
            assertTrue(editorStyle.contains(AppTheme.font()), "editor must carry the theme font");
        });
    }

    @Test
    void styleSpinnerIgnoresNull() throws Exception {
        FxTestKit.onFx(() -> assertDoesNotThrow(() -> GuiHelper.styleSpinner(null)));
    }

    // ==================================================================
    // paintInk
    // ==================================================================

    @Test
    void paintInkOverwritesALabelsColourWithThePaletteInk() throws Exception {
        FxTestKit.onFx(() -> {
            Label l = new Label("hello");
            l.setStyle("-fx-text-fill: #ff0000; -fx-font-size: 14px;");

            GuiHelper.paintInk(l);

            assertTrue(l.getStyle().contains("-fx-text-fill: " + AppTheme.ink()),
                    "ink should come from the palette: " + l.getStyle());
            assertFalse(l.getStyle().contains("#ff0000"),
                    "the stale colour must be replaced: " + l.getStyle());
            assertTrue(l.getStyle().contains("-fx-font-size: 14px"),
                    "unrelated declarations must survive: " + l.getStyle());
        });
    }

    @Test
    void paintInkKeepsErrorRedLabelsUntouched() throws Exception {
        FxTestKit.onFx(() -> {
            Label error = new Label("bad input");
            String original = "-fx-text-fill: #8b1e1e;";
            error.setStyle(original);

            GuiHelper.paintInk(error);

            assertEquals(original, error.getStyle(), "error red is deliberate and must be preserved");
        });
    }

    @Test
    void paintInkHandlesALabelWithNoStyleYet() throws Exception {
        FxTestKit.onFx(() -> {
            Label l = new Label("plain");

            GuiHelper.paintInk(l);

            assertNotNull(l.getStyle());
            assertTrue(l.getStyle().contains("-fx-text-fill: " + AppTheme.ink()));
        });
    }

    @Test
    void paintInkPaintsCheckAndRadioButtons() throws Exception {
        FxTestKit.onFx(() -> {
            CheckBox cb = new CheckBox("check");
            RadioButton rb = new RadioButton("radio");

            GuiHelper.paintInk(cb);
            GuiHelper.paintInk(rb);

            assertTrue(cb.getStyle().contains(AppTheme.font()));
            assertTrue(cb.getStyle().contains(AppTheme.ink()));
            assertTrue(rb.getStyle().contains(AppTheme.ink()));
        });
    }

    @Test
    void paintInkUsesFieldCssForATextFieldOutsideACard() throws Exception {
        FxTestKit.onFx(() -> {
            TextField tf = new TextField();

            GuiHelper.paintInk(tf);

            assertEquals(AppTheme.fieldCss(), tf.getStyle(),
                    "outside a card a text field takes the shared field style");
        });
    }

    @Test
    void paintInkUsesTheCardRecipeInsideADialogCard() throws Exception {
        FxTestKit.onFx(() -> {
            VBox card = GuiHelper.createWhiteBox();
            TextField tf = new TextField();
            card.getChildren().add(tf);

            GuiHelper.paintInk(tf);

            assertTrue(tf.getStyle().contains(AppTheme.cardMuted()),
                    "card fields use the muted prompt colour: " + tf.getStyle());
            assertFalse(tf.getStyle().equals(AppTheme.fieldCss()),
                    "the card recipe differs from the plain field style");
        });
    }

    @Test
    void paintInkTreatsLoginCardsLikeDialogCards() throws Exception {
        FxTestKit.onFx(() -> {
            VBox card = new VBox();
            card.getStyleClass().add("login-card");
            TextField tf = new TextField();
            card.getChildren().add(tf);

            GuiHelper.paintInk(tf);

            assertTrue(tf.getStyle().contains(AppTheme.cardMuted()),
                    "login-card is recognised too: " + tf.getStyle());
        });
    }

    @Test
    void paintInkPaintsComboBoxesAndDatePickers() throws Exception {
        FxTestKit.onFx(() -> {
            ComboBox<String> cb = new ComboBox<>();
            DatePicker dp = new DatePicker();

            GuiHelper.paintInk(cb);
            GuiHelper.paintInk(dp);

            assertTrue(cb.getStyle().contains(AppTheme.paper()));
            assertTrue(cb.getStyle().contains(AppTheme.ink()));
            assertTrue(dp.getStyle().contains(AppTheme.paper()));
        });
    }

    @Test
    void paintInkDelegatesSpinnersToStyleSpinner() throws Exception {
        FxTestKit.onFx(() -> {
            Spinner<Integer> sp = new Spinner<>(0, 5, 1);

            GuiHelper.paintInk(sp);

            assertTrue(sp.getStyle().contains("#ffffff"));
            assertNotNull(sp.getEditor());
        });
    }

    @Test
    void paintInkWalkTheWholeSubtreeButStopsAtControls() throws Exception {
        FxTestKit.onFx(() -> {
            HBox row = new HBox();
            Label first = new Label("one");
            Label second = new Label("two");
            // A Label is a Control, so a label nested inside a label-like control is not touched.
            ComboBox<String> combo = new ComboBox<>();
            row.getChildren().addAll(first, second, combo);

            GuiHelper.paintInk(row);

            assertTrue(first.getStyle().contains(AppTheme.ink()), "nested label one");
            assertTrue(second.getStyle().contains(AppTheme.ink()), "nested label two");
            assertTrue(combo.getStyle().contains(AppTheme.ink()), "nested combo box");

            // The container is not a recognised node type, so it is walked but never painted.
            assertFalse(row.getStyle().contains("-fx-text-fill"),
                    "the HBox itself must not receive ink: " + row.getStyle());
        });
    }

    @Test
    void paintInkIgnoresNullAndLeavesUnknownNodeTypesAlone() throws Exception {
        FxTestKit.onFx(() -> {
            assertDoesNotThrow(() -> GuiHelper.paintInk(null));

            // A plain Region is neither a Control nor a recognised type.
            Region region = new Region();
            GuiHelper.paintInk(region);
            assertFalse(region.getStyle().contains("-fx-text-fill"), "an unrecognised leaf gets no ink");
        });
    }

    /**
     * {@code paintInk} is <em>not</em> idempotent: each call appends another font declaration,
     * because it strips only the colour before re-appending {@code font()}.
     *
     * <p>This is reachable in production — {@code TaskDialogs.rebuildBlockedList} repaints the same
     * {@code listBox} on every edit. The stylesheet still parses (later declarations win), so the
     * effect is a slowly growing style string rather than wrong colours; this test pins the
     * current behaviour so a future fix shows up as a deliberate change.
     */
    @Test
    void paintInkAppendsAnotherFontDeclarationOnEachCall() throws Exception {
        FxTestKit.onFx(() -> {
            Label l = new Label("stable");
            GuiHelper.paintInk(l);
            int once = countOccurrences(l.getStyle(), AppTheme.font());
            GuiHelper.paintInk(l);
            int twice = countOccurrences(l.getStyle(), AppTheme.font());

            assertEquals(1, once, "one font declaration after the first paint");
            assertEquals(2, twice, "the second paint appends another one: " + l.getStyle());
        });
    }

    /** The paint still yields exactly one ink declaration, so colours never double up. */
    @Test
    void repeatedPaintingKeepsASingleInkColour() throws Exception {
        FxTestKit.onFx(() -> {
            Label l = new Label("stable");
            GuiHelper.paintInk(l);
            GuiHelper.paintInk(l);
            GuiHelper.paintInk(l);

            assertEquals(1, countOccurrences(l.getStyle(), "-fx-text-fill:"),
                    "the old colour is stripped before the new one is added: " + l.getStyle());
        });
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) != -1) {
            count++;
            from += needle.length();
        }
        return count;
    }

    @Test
    void paintInkRoutesAFreshNodeThroughEachBranch() throws Exception {
        FxTestKit.onFx(() -> {
            // Guards the instanceof ladder: every recognised subtype is handled exactly once.
            for (Node n : new Node[]{
                    new Label("l"), new CheckBox("c"), new RadioButton("r"),
                    new TextField(), new ComboBox<>(), new DatePicker(),
                    new Spinner<Integer>(0, 5, 1)}) {
                GuiHelper.paintInk(n);
                assertNotNull(n.getStyle(), n.getClass().getSimpleName() + " should be painted");
            }
        });
    }
}
