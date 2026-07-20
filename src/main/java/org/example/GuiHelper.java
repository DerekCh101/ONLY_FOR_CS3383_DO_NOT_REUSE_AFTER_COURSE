package org.example;

import javafx.scene.layout.*;

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
        box.setStyle("-fx-background-color: #f8f8f8; -fx-padding: 20; "
                + "-fx-border-color: #333333; -fx-border-width: 2; "
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
}
