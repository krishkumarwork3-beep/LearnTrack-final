package com.learntrack.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Small set of shared UI builders so views do not repeat inline styling.
 * Everything here is JavaFX only; no business logic lives in this class.
 */
public final class UiKit {

    public enum Kind { INFO, SUCCESS, WARN, DANGER }

    /**
     * The app's whole icon set, drawn as 16×16 vector paths so no icon font or image pack is
     * needed. Colour comes from the stylesheet (.icon-glyph / .icon-outline).
     */
    public enum Icon {
        UP("M8 3 L13 9 H10 V13 H6 V9 H3 Z", false),
        DOWN("M8 13 L3 7 H6 V3 H10 V7 H13 Z", false),
        PENCIL("M2.5 13.5 L3.4 10.4 L10.6 3.2 L12.8 5.4 L5.6 12.6 Z", false),
        CLOCK("M8 2.2 A5.8 5.8 0 1 0 8 13.8 A5.8 5.8 0 1 0 8 2.2 M8 5 V8.4 L10.4 9.8", true),
        TRASH("M4 4.5 H12 M6.5 4.5 V2.8 H9.5 V4.5 M5.2 4.5 L5.9 13.5 H10.1 L10.8 4.5 "
                + "M6.8 6.8 V11.5 M9.2 6.8 V11.5", true),
        CLOSE("M4 4 L12 12 M12 4 L4 12", true),
        CHEVRON_DOWN("M4.5 6.5 L8 10 L11.5 6.5", true),
        CHEVRON_RIGHT("M6.5 4.5 L10 8 L6.5 11.5", true),
        CHEVRON_LEFT("M9.5 4.5 L6 8 L9.5 11.5", true),
        HOME("M2.5 7.5 L8 3 L13.5 7.5 M4 6.5 V13 H12 V6.5 M6.6 13 V9.6 H9.4 V13", true),
        CALENDAR("M2.5 4 H13.5 V13.5 H2.5 Z M2.5 6.9 H13.5 M5.4 2.4 V5 M10.6 2.4 V5", true),
        EXAM("M4 3.6 H12 V14 H4 Z M6.2 2.4 H9.8 V4.6 H6.2 Z M6 7.6 H10 M6 10.2 H9", true),
        BOOK("M2.5 3.6 C4.6 3 6.6 3.1 8 4.3 C9.4 3.1 11.4 3 13.5 3.6 V13 C11.4 12.4 9.4 12.6 8 13.7 "
                + "C6.6 12.6 4.6 12.4 2.5 13 Z M8 4.3 V13.7", true),
        BOLT("M9.2 1.8 L3.8 9 H7.8 L6.8 14.2 L12.2 7 H8.2 Z", true),
        SCAN("M4 2.5 H10 L12.5 5 V13.5 H4 Z M10 2.5 V5 H12.5 M6 8 H10.5 M6 10.6 H10.5", true),
        GRID("M2.5 2.5 H13.5 V13.5 H2.5 Z M2.5 6.2 H13.5 M2.5 9.8 H13.5 M6.2 2.5 V13.5 M9.8 2.5 V13.5", true),
        GEAR("M8 5.6 A2.4 2.4 0 1 0 8 10.4 A2.4 2.4 0 1 0 8 5.6 M8 1.6 V3.4 M8 12.6 V14.4 M1.6 8 H3.4 "
                + "M12.6 8 H14.4 M3.5 3.5 L4.8 4.8 M11.2 11.2 L12.5 12.5 M3.5 12.5 L4.8 11.2 M11.2 4.8 L12.5 3.5", true),
        MOON("M12.8 9.6 A5.2 5.2 0 1 1 6.4 3.2 A4.2 4.2 0 0 0 12.8 9.6 Z", true),
        SUN("M8 5.4 A2.6 2.6 0 1 0 8 10.6 A2.6 2.6 0 1 0 8 5.4 M8 1.5 V2.9 M8 13.1 V14.5 M1.5 8 H2.9 "
                + "M13.1 8 H14.5 M3.4 3.4 L4.4 4.4 M11.6 11.6 L12.6 12.6 M3.4 12.6 L4.4 11.6 M11.6 4.4 L12.6 3.4", true),
        TARGET("M8 2 A6 6 0 1 0 8 14 A6 6 0 1 0 8 2 M8 5.2 A2.8 2.8 0 1 0 8 10.8 A2.8 2.8 0 1 0 8 5.2", true),
        REFRESH("M13 8 A5 5 0 1 1 11.6 4.4 M11.8 1.8 V4.7 H8.9", true),
        FLAG("M4 14 V2.6 M4 3 H12 L10.4 5.8 L12 8.6 H4", true),
        SPARK("M8 1.8 L9.4 6.6 L14.2 8 L9.4 9.4 L8 14.2 L6.6 9.4 L1.8 8 L6.6 6.6 Z", true);

        private final String path;
        private final boolean outline;

        Icon(String path, boolean outline) {
            this.path = path;
            this.outline = outline;
        }
    }

    private static VBox toastLayer;

    private UiKit() {
    }

    /**
     * Loads the bundled Inter faces (SIL OFL, see fonts/Inter-LICENSE.txt). Call once at
     * startup before the first scene; if a face cannot be loaded JavaFX falls back to the
     * system UI font, so the app still runs.
     */
    public static void loadFonts() {
        String[] faces = {
            "/fonts/Inter-Regular.ttf", "/fonts/Inter-Medium.ttf",
            "/fonts/Inter-SemiBold.ttf", "/fonts/Inter-Bold.ttf"
        };
        for (String face : faces) {
            java.net.URL url = UiKit.class.getResource(face);
            if (url != null) {
                Font.loadFont(url.toExternalForm(), 13);
            }
        }
    }

    // ------------------------------------------------------------------ text

    public static Label pageTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("page-title");
        return label;
    }

    public static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    public static Label subtle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("subtle");
        label.setWrapText(true);
        return label;
    }

    public static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("hint");
        label.setWrapText(true);
        return label;
    }

    /**
     * Page header: title and one-line description on the left, the screen's primary action
     * on the right. Every tab uses this so the top of each page reads the same way.
     */
    public static HBox pageHeader(String title, String description, Node action) {
        VBox text = new VBox(4);
        text.getStyleClass().add("page-header");
        text.getChildren().add(pageTitle(title));
        if (description != null && !description.isBlank()) {
            Label descriptionLabel = new Label(description);
            descriptionLabel.getStyleClass().add("page-description");
            descriptionLabel.setWrapText(true);
            text.getChildren().add(descriptionLabel);
        }
        HBox row = new HBox(16, text);
        row.getStyleClass().add("page-header-row");
        HBox.setHgrow(text, Priority.ALWAYS);
        if (action != null) {
            row.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().add(action);
        }
        return row;
    }

    // ------------------------------------------------------------------ containers

    public static VBox card(Node... children) {
        VBox box = new VBox(16, children);
        box.getStyleClass().add("card");
        return box;
    }

    public static VBox cardFlat(Node... children) {
        VBox box = new VBox(8, children);
        box.getStyleClass().add("card-flat");
        return box;
    }

    public static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    // ------------------------------------------------------------------ buttons

    public static Button primaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("btn-primary");
        return button;
    }

    public static Button secondaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("btn-secondary");
        return button;
    }

    public static Button dangerButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("btn-danger");
        return button;
    }

    /**
     * Destructive action inside a page: outlined in the danger colour. Filled red is reserved for
     * the confirmation dialog that actually performs the deletion.
     */
    public static Button dangerTextButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("btn-danger-soft");
        return button;
    }

    public static Button ghostButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("btn-ghost");
        return button;
    }

    /** Compact icon-only button; the tooltip is required so the action stays discoverable. */
    public static Button iconButton(Icon icon, String tooltip) {
        Button button = new Button();
        button.setGraphic(iconNode(icon));
        button.getStyleClass().add("btn-icon");
        button.setTooltip(new Tooltip(tooltip));
        button.setAccessibleText(tooltip);
        return button;
    }

    public static Button dangerIconButton(Icon icon, String tooltip) {
        Button button = iconButton(icon, tooltip);
        button.getStyleClass().add("btn-icon-danger");
        return button;
    }

    /** Icon node for places that swap the graphic in place (expand / collapse toggles). */
    public static Node icon(Icon icon) {
        return iconNode(icon);
    }

    private static Node iconNode(Icon icon) {
        javafx.scene.shape.SVGPath path = new javafx.scene.shape.SVGPath();
        path.setContent(icon.path);
        path.getStyleClass().add(icon.outline ? "icon-outline" : "icon-glyph");
        path.setMouseTransparent(true);
        return path;
    }

    // ------------------------------------------------------------------ v2 building blocks

    /**
     * Circular progress ring with a value in the middle. Style hooks: .ring-track, .ring-arc,
     * .ring-value, .ring-caption; add ring-ok / ring-warn / ring-danger on the returned pane.
     */
    public static javafx.scene.layout.StackPane progressRing(double fraction, String value, String caption,
                                                             double radius, double stroke) {
        javafx.scene.shape.Circle track = new javafx.scene.shape.Circle(radius);
        track.getStyleClass().add("ring-track");
        track.setStrokeWidth(stroke);
        javafx.scene.shape.Arc arc = new javafx.scene.shape.Arc(0, 0, radius, radius, 90,
                -360 * Math.max(0.0, Math.min(1.0, fraction)));
        arc.setType(javafx.scene.shape.ArcType.OPEN);
        arc.getStyleClass().add("ring-arc");
        arc.setStrokeWidth(stroke);
        javafx.scene.Group shapes = new javafx.scene.Group(track, arc);
        // Keep the group's bounds fixed whatever the arc length, so the ring never shifts.
        javafx.scene.shape.Circle bounds = new javafx.scene.shape.Circle(radius + stroke / 2.0);
        bounds.setFill(javafx.scene.paint.Color.TRANSPARENT);
        shapes.getChildren().add(0, bounds);

        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("ring-value");
        VBox center = new VBox(0, valueLabel);
        center.setAlignment(Pos.CENTER);
        if (caption != null && !caption.isBlank()) {
            Label captionLabel = new Label(caption);
            captionLabel.getStyleClass().add("ring-caption");
            center.getChildren().add(captionLabel);
        }
        javafx.scene.layout.StackPane ring = new javafx.scene.layout.StackPane(shapes, center);
        ring.getStyleClass().add("ring");
        ring.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return ring;
    }

    /** Dashboard tile: tinted icon, big value, label and an optional sub-line. */
    public static HBox kpi(Icon icon, String tint, String label, String value, String sub) {
        javafx.scene.layout.StackPane iconBox = new javafx.scene.layout.StackPane(iconNode(icon));
        iconBox.getStyleClass().addAll("kpi-icon", "kpi-icon-" + tint);
        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().addAll("kpi-value", "tabular");
        Label labelLabel = new Label(label);
        labelLabel.getStyleClass().add("kpi-label");
        VBox text = new VBox(1, labelLabel, valueLabel);
        if (sub != null) {
            Label subLabel = new Label(sub);
            subLabel.getStyleClass().add("kpi-sub");
            text.getChildren().add(subLabel);
        }
        HBox box = new HBox(12, iconBox, text);
        box.getStyleClass().add("kpi");
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** Rounded label in one of the activity colours (learn, revise, pyq, skill, class, meal, sleep, exam, other). */
    public static Label pill(String text, String kind) {
        Label label = new Label(text);
        label.getStyleClass().addAll("pill", "pill-" + kind);
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    /** Pill showing a 1..5 importance level in its colour. */
    public static Label importancePill(int importance) {
        int level = com.learntrack.model.SyllabusSubtopic.clampImportance(importance);
        Label label = new Label(com.learntrack.model.SyllabusSubtopic.importanceLabel(level));
        label.getStyleClass().addAll("pill", "imp-" + level);
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    /**
     * Compact coloured drop-down for a 1..5 level (importance or priority). The callback runs
     * only when the user picks a different value.
     */
    public static javafx.scene.control.ComboBox<Integer> levelPicker(int current,
                                                                     java.util.function.IntFunction<String> labeler,
                                                                     java.util.function.IntConsumer onChange) {
        javafx.scene.control.ComboBox<Integer> box = new javafx.scene.control.ComboBox<>();
        box.getItems().addAll(5, 4, 3, 2, 1);
        box.getStyleClass().add("importance-picker");
        javafx.util.Callback<javafx.scene.control.ListView<Integer>, javafx.scene.control.ListCell<Integer>> cells =
                lv -> new javafx.scene.control.ListCell<>() {
                    @Override
                    protected void updateItem(Integer item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : labeler.apply(item));
                    }
                };
        box.setCellFactory(cells);
        box.setButtonCell(cells.call(null));
        int level = Math.max(1, Math.min(5, current));
        box.setValue(level);
        box.getStyleClass().add("imp-" + level);
        box.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) {
                return;
            }
            box.getStyleClass().removeAll("imp-1", "imp-2", "imp-3", "imp-4", "imp-5");
            box.getStyleClass().add("imp-" + newV);
            if (oldV != null && !oldV.equals(newV) && onChange != null) {
                onChange.accept(newV);
            }
        });
        return box;
    }

    /** Activity colour family for a schedule type code (TOPIC_LEARN, CLASS, SLEEP, ...). */
    public static String activityKind(String type) {
        if (type == null) {
            return "other";
        }
        switch (type.trim().toUpperCase()) {
            case "TOPIC_LEARN": return "learn";
            case "TOPIC_REVISE": return "revise";
            case "PYQ_PRACTICE": return "pyq";
            case "SKILL": case "SKILL_LEARN": case "SKILL_REVISE": return "skill";
            case "CLASS": case "LAB": case "TUTORIAL": return "class";
            case "MEAL": return "meal";
            case "SLEEP": return "sleep";
            case "EXAM": return "exam";
            default: return "other";
        }
    }

    // ------------------------------------------------------------------ chips / badges / stats

    public static Label chip(String text, Kind kind) {
        Label label = new Label(text);
        label.getStyleClass().addAll("chip", chipClass(kind));
        return label;
    }

    /**
     * Status as a small dot plus plain text. Preferred over a coloured chip: the meaning is
     * carried by the words, and the dot only reinforces it.
     */
    public static HBox status(Kind kind, String text) {
        HBox box = new HBox(6, statusDot(kind), statusText(text));
        box.getStyleClass().add("status-line");
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public static Region statusDot(Kind kind) {
        Region dot = new Region();
        dot.getStyleClass().addAll("status-dot", dotClass(kind));
        return dot;
    }

    public static Label statusText(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("status-text");
        return label;
    }

    /** Re-colours an existing status dot in place. */
    public static void setStatusDot(Region dot, Kind kind) {
        if (dot == null) {
            return;
        }
        dot.getStyleClass().removeAll("status-dot-ok", "status-dot-warn", "status-dot-danger", "status-dot-info");
        dot.getStyleClass().add(dotClass(kind));
    }

    public static Label badge(String text, Kind kind) {
        Label label = new Label(text);
        label.getStyleClass().addAll("badge", badgeClass(kind));
        return label;
    }

    /** Restyles an existing badge in place (used for status chips that update). */
    public static void setBadge(Label label, String text, Kind kind) {
        if (label == null) {
            return;
        }
        label.setText(text);
        if (!label.getStyleClass().contains("badge")) {
            label.getStyleClass().add("badge");
        }
        label.getStyleClass().removeAll("badge-success", "badge-warning", "badge-danger",
                "badge-neutral", "badge-info", "badge-violet");
        label.getStyleClass().add(badgeClass(kind));
    }

    public static VBox stat(String label, String value) {
        return stat(label, value, null);
    }

    public static VBox stat(String label, String value, String valueStyleClass) {
        Label caption = new Label(label);
        caption.getStyleClass().add("stat-label");
        Label amount = new Label(value);
        amount.getStyleClass().addAll("stat-value", "tabular");
        if (valueStyleClass != null) {
            amount.getStyleClass().add(valueStyleClass);
        }
        VBox box = new VBox(2, caption, amount);
        box.getStyleClass().add("stat");
        box.setMinWidth(Region.USE_PREF_SIZE);
        return box;
    }

    /** Sets the value of a stat tile created by {@link #stat(String, String)}. */
    public static void setStatValue(VBox stat, String value) {
        if (stat == null || stat.getChildren().size() < 2) {
            return;
        }
        Node node = stat.getChildren().get(1);
        if (node instanceof Label) {
            ((Label) node).setText(value);
        }
    }

    public static void setStatValueClass(VBox stat, String valueStyleClass) {
        if (stat == null || stat.getChildren().size() < 2) {
            return;
        }
        Node node = stat.getChildren().get(1);
        if (node instanceof Label) {
            node.getStyleClass().removeAll(
                    "stat-value-success", "stat-value-info", "stat-value-warning", "stat-value-danger");
            if (valueStyleClass != null) {
                node.getStyleClass().add(valueStyleClass);
            }
        }
    }

    // ------------------------------------------------------------------ banners / empty states

    public static HBox banner(Kind kind, String title, String text) {
        return banner(kind, title, text, null);
    }

    public static HBox banner(Kind kind, String title, String text, Node action) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("banner-title");
        titleLabel.setWrapText(true);
        Label textLabel = new Label(text);
        textLabel.getStyleClass().add("banner-text");
        textLabel.setWrapText(true);

        VBox content = new VBox(2, titleLabel, textLabel);
        HBox.setHgrow(content, Priority.ALWAYS);

        HBox box = new HBox(10);
        box.getStyleClass().addAll("banner", bannerClass(kind));
        box.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(content);
        if (action != null) {
            box.getChildren().add(action);
        }
        return box;
    }

    public static VBox emptyState(String title, String hint) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("empty-state-title");
        titleLabel.setWrapText(true);
        Label hintLabel = new Label(hint);
        hintLabel.getStyleClass().add("empty-state-hint");
        VBox box = new VBox(8, titleLabel, hintLabel);
        box.getStyleClass().add("empty-state");
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(32));
        return box;
    }

    // ------------------------------------------------------------------ toast

    /**
     * Installs the overlay that receives non-blocking messages. MainApp puts it on top of the
     * main layout; views only call {@link #toast(String)}.
     */
    public static void setToastLayer(VBox layer) {
        toastLayer = layer;
    }

    public static void toast(String message) {
        toast(message, Kind.SUCCESS);
    }

    public static void toast(String message, Kind kind) {
        if (message == null || message.isBlank()) {
            return;
        }
        VBox layer = toastLayer;
        if (layer == null) {
            return;
        }
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> toast(message, kind));
            return;
        }

        Label text = new Label(message);
        text.setWrapText(true);
        HBox toast = new HBox(text);
        toast.getStyleClass().addAll("toast", "toast-" + kind.name().toLowerCase());
        toast.setAlignment(Pos.CENTER_LEFT);
        layer.getChildren().add(toast);

        FadeTransition in = new FadeTransition(Duration.millis(150), toast);
        in.setFromValue(0);
        in.setToValue(1);
        PauseTransition hold = new PauseTransition(Duration.millis(kind == Kind.DANGER ? 5200 : 3200));
        FadeTransition out = new FadeTransition(Duration.millis(260), toast);
        out.setFromValue(1);
        out.setToValue(0);
        SequentialTransition sequence = new SequentialTransition(in, hold, out);
        sequence.setOnFinished(e -> layer.getChildren().remove(toast));
        sequence.play();
    }

    // ------------------------------------------------------------------ dialogs

    /**
     * Error dialog with a plain-language heading and the technical text folded into the
     * expandable details area instead of being shown as the message.
     */
    public static void error(Window owner, String heading, String technicalDetail) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle("Something went wrong");
        alert.setHeaderText(heading);
        alert.setContentText(plainLanguage(technicalDetail));
        styleDialog(alert.getDialogPane());

        if (technicalDetail != null && !technicalDetail.isBlank()
                && !technicalDetail.equals(plainLanguage(technicalDetail))) {
            TextArea details = new TextArea(technicalDetail);
            details.setEditable(false);
            details.setWrapText(true);
            details.setPrefRowCount(5);
            details.getStyleClass().add("detail-area");
            alert.getDialogPane().setExpandableContent(details);
        }
        alert.showAndWait();
    }

    public static void error(Window owner, String heading, Throwable cause) {
        error(owner, heading, cause == null ? null : cause.getMessage());
    }

    /**
     * Dialogs live in their own scene, so they must be given the app stylesheet and the
     * currently active theme explicitly.
     */
    public static void styleDialog(javafx.scene.control.DialogPane pane) {
        if (pane == null) {
            return;
        }
        if (!pane.getStyleClass().contains("dialog-pane")) {
            pane.getStyleClass().add("dialog-pane");
        }
        java.net.URL light = UiKit.class.getResource("/css/learntrack.css");
        if (light != null && !pane.getStylesheets().contains(light.toExternalForm())) {
            pane.getStylesheets().add(light.toExternalForm());
        }
        if (Theme.isDarkActive()) {
            java.net.URL dark = UiKit.class.getResource("/css/learntrack-dark.css");
            if (dark != null && !pane.getStylesheets().contains(dark.toExternalForm())) {
                pane.getStylesheets().add(dark.toExternalForm());
            }
        }
    }

    public static void styleDialog(javafx.scene.control.Dialog<?> dialog) {
        if (dialog != null) {
            styleDialog(dialog.getDialogPane());
        }
    }

    /**
     * Esc closes a hand-built Stage dialog. Dialogs and Alerts do not need this: JavaFX already
     * maps Esc to their CANCEL_CLOSE button. Uses an event filter so a focused text field cannot
     * swallow the key.
     */
    public static void closeOnEscape(javafx.stage.Stage stage) {
        if (stage == null || stage.getScene() == null) {
            return;
        }
        stage.getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                event.consume();
                stage.close();
            }
        });
    }

    /** Confirmation dialog; returns true when the user accepts. */
    public static boolean confirm(Window owner, String title, String heading, String message,
                                  String confirmLabel) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle(title);
        alert.setHeaderText(heading);
        alert.setContentText(message);
        styleDialog(alert.getDialogPane());
        Button ok = (Button) alert.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK);
        if (ok != null && confirmLabel != null) {
            ok.setText(confirmLabel);
        }
        return alert.showAndWait()
                .filter(button -> button == javafx.scene.control.ButtonType.OK)
                .isPresent();
    }

    /** Turns raw JDBC/SQLite text into a sentence a user can act on. */
    public static String plainLanguage(String raw) {
        if (raw == null || raw.isBlank()) {
            return "The operation could not be completed.";
        }
        String lower = raw.toLowerCase();
        if (lower.contains("sqlite_constraint_notnull") || lower.contains("not null constraint failed")) {
            return "A required value was missing, so the record was not saved.";
        }
        if (lower.contains("sqlite_constraint_unique") || lower.contains("unique constraint failed")) {
            return "That entry already exists.";
        }
        if (lower.contains("sqlite_constraint_foreignkey") || lower.contains("foreign key constraint failed")) {
            return "This entry is still referenced by other records.";
        }
        if (lower.contains("sqlite_busy") || lower.contains("database is locked")) {
            return "The database is busy. Please try again in a moment.";
        }
        if (lower.contains("no such table") || lower.contains("no such column")) {
            return "The database schema is out of date and could not be read.";
        }
        if (lower.contains("connection") || lower.contains("sqlite")) {
            return "The database could not be reached, so nothing was changed.";
        }
        return raw;
    }

    private static String chipClass(Kind kind) {
        switch (kind) {
            case SUCCESS: return "chip-ok";
            case WARN: return "chip-warn";
            case DANGER: return "chip-danger";
            default: return "chip-neutral";
        }
    }

    private static String dotClass(Kind kind) {
        switch (kind) {
            case SUCCESS: return "status-dot-ok";
            case WARN: return "status-dot-warn";
            case DANGER: return "status-dot-danger";
            case INFO: return "status-dot-info";
            default: return "";
        }
    }

    private static String badgeClass(Kind kind) {
        switch (kind) {
            case SUCCESS: return "badge-success";
            case WARN: return "badge-warning";
            case DANGER: return "badge-danger";
            default: return "badge-neutral";
        }
    }

    private static String bannerClass(Kind kind) {
        switch (kind) {
            case SUCCESS: return "banner-success";
            case WARN: return "banner-warn";
            case DANGER: return "banner-danger";
            default: return "banner-info";
        }
    }
}
