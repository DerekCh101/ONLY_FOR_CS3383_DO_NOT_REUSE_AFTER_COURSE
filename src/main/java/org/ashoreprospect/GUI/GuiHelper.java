package org.ashoreprospect.GUI;

import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class GuiHelper {

    public static StackPane createDimmedOverlay(Region root) {
        StackPane overlay = new StackPane();
        overlay.prefWidthProperty().bind(root.widthProperty());
        overlay.prefHeightProperty().bind(root.heightProperty());
        overlay.setStyle("-fx-background-color: rgba(0, 0, 0, 0.6);");
        return overlay;
    }

    public static VBox createWhiteBox() {
        VBox box = new VBox(12);
        box.setStyle("-fx-background-color: " + AppTheme.paper() + "; -fx-padding: 20; "
                + "-fx-border-color: " + AppTheme.ink() + "; -fx-border-width: 2; "
                + "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 10, 0, 0, 0);");
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return box;
    }

    public static void centerInParent(Pane node, Region parent) {
        node.layoutXProperty().bind(
                parent.widthProperty().subtract(node.widthProperty()).divide(2));
        node.layoutYProperty().bind(
                parent.heightProperty().subtract(node.heightProperty()).divide(2));
        node.setManaged(false);
    }

    public static void centerInParentOffset(Pane node, Region parent,
                                            double widthOffset, double heightOffset) {
        node.layoutXProperty().bind(
                parent.widthProperty().subtract(node.widthProperty()).divide(2).multiply(widthOffset));
        node.layoutYProperty().bind(
                parent.heightProperty().subtract(node.heightProperty()).divide(2).multiply(heightOffset));
        node.setManaged(false);
    }

    /** Duration spinner editor is always navy-on-white (dialog paper). */
    public static void styleSpinner(Spinner<?> sp) {
        if (sp == null) return;
        sp.setStyle("-fx-background-color: #ffffff; -fx-text-fill: #1c2430;");
        if (sp.getEditor() != null) {
            sp.getEditor().setStyle(
                    "-fx-background-color: #ffffff; -fx-text-fill: #1c2430; "
                            + "-fx-prompt-text-fill: #8a8378; " + AppTheme.font());
        }
    }

    /** Force ink on labels/fields so dark theme never keeps default black text. */
    public static void paintInk(Node n) {
        if (n == null) return;
        if (n instanceof Label l) {
            String s = l.getStyle() == null ? "" : l.getStyle();
            l.setStyle(s.replaceAll("-fx-text-fill:\\s*#[0-9a-fA-F]{3,8}", "")
                    + "; -fx-text-fill: " + AppTheme.ink() + "; " + AppTheme.font());
        } else if (n instanceof CheckBox || n instanceof RadioButton) {
            Labeled l = (Labeled) n;
            l.setStyle(AppTheme.font() + "-fx-text-fill: " + AppTheme.ink() + ";");
        } else if (n instanceof TextInputControl tf) {
            tf.setStyle(AppTheme.fieldCss());
        } else if (n instanceof ComboBox<?> cb) {
            cb.setStyle("-fx-background-color: " + AppTheme.paper() + "; -fx-text-fill: "
                    + AppTheme.ink() + ";");
        } else if (n instanceof DatePicker dp) {
            dp.setStyle("-fx-background-color: " + AppTheme.paper() + ";");
        } else if (n instanceof Spinner<?> sp) {
            styleSpinner(sp);
        }
        if (n instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) paintInk(child);
        }
    }
}