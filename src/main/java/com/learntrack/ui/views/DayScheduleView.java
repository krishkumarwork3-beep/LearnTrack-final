package com.learntrack.ui.views;

import com.learntrack.model.DailyLogEntry;
import com.learntrack.model.DayRow;
import com.learntrack.ui.UiKit;
import com.learntrack.util.DurationFormat;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Reusable TableView component displaying the merged daily schedule (DayRow).
 * Fixed rows (class, lab, meal, sleep, exam) are visually muted and read-only.
 * Study rows show full item titles with hours.
 * Includes a summary header showing fixed hours, sleep hours, planned study hours, and done hours.
 */
public class DayScheduleView extends VBox {

    /** Schedule rows always read as HH:MM, not the ISO form with seconds. */
    private static final java.time.format.DateTimeFormatter SLOT_TIME =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    private final ObservableList<DayRow> items = FXCollections.observableArrayList();
    private final TableView<DayRow> table = new TableView<>(items);

    private final Label lblFixedHours = new Label("0.0 h");
    private final Label lblSleepHours = new Label("0.0 h");
    private final Label lblPlannedStudyHours = new Label("0.0 h");
    private final Label lblDoneHours = new Label("0.0 h");

    private Consumer<DayRow> studyRowDoubleClickHandler;
    private BiConsumer<DayRow, Boolean> onToggleDone;
    private ReadOnlyBooleanProperty busyProperty;
    private FlowPane headerBox;

    public DayScheduleView() {
        super(8);
        buildUi();
    }

    private void buildUi() {
        setPadding(new Insets(4, 0, 4, 0));

        // 1. Metric header: stat tiles never squeeze their captions.
        headerBox = new FlowPane(10, 8,
                statTile("Commitments", lblFixedHours, null),
                statTile("Sleep", lblSleepHours, "stat-value-info"),
                statTile("Planned study", lblPlannedStudyHours, "stat-value-info"),
                statTile("Done study", lblDoneHours, "stat-value-success"));
        headerBox.setAlignment(Pos.CENTER_LEFT);

        // 2. Merged table
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(UiKit.emptyState("Nothing scheduled",
                "Pick another day, or add classes and study sessions from the Subjects tab."));

        TableColumn<DayRow, Boolean> colDone = new TableColumn<>("Done");
        colDone.setPrefWidth(56);
        colDone.setMinWidth(56);
        colDone.setMaxWidth(70);
        colDone.setCellValueFactory(cell -> {
            DayRow r = cell.getValue();
            if (r == null || r.isFixed() || r.getDailyLogEntry() == null) {
                return new SimpleBooleanProperty(false);
            }
            return new SimpleBooleanProperty("DONE".equalsIgnoreCase(r.getDailyLogEntry().getStatus()));
        });
        colDone.setCellFactory(col -> new TableCell<DayRow, Boolean>() {
            private final CheckBox checkBox = new CheckBox();
            private boolean updating = false;

            {
                setAlignment(Pos.CENTER);
                checkBox.setOnAction(e -> {
                    if (updating) return;
                    DayRow row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row != null && !row.isFixed() && row.getDailyLogEntry() != null && onToggleDone != null) {
                        onToggleDone.accept(row, checkBox.isSelected());
                    }
                });
            }

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                DayRow row = getTableRow() != null ? getTableRow().getItem() : null;
                if (empty || row == null || row.isFixed() || row.getDailyLogEntry() == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    DailyLogEntry log = row.getDailyLogEntry();
                    boolean isDone = "DONE".equalsIgnoreCase(log.getStatus());
                    updating = true;
                    checkBox.setSelected(isDone);
                    updating = false;

                    LocalDate sessionDate = log.getLogDate() != null ? log.getLogDate() : LocalDate.now();
                    boolean canLog = DashboardView.canLogProgress(sessionDate, LocalDate.now());
                    if (!canLog) {
                        checkBox.setDisable(true);
                        checkBox.setTooltip(new Tooltip("Available on the day of the session"));
                    } else if (busyProperty != null && busyProperty.get()) {
                        checkBox.setDisable(true);
                        checkBox.setTooltip(null);
                    } else {
                        checkBox.setDisable(false);
                        checkBox.setTooltip(null);
                    }
                    setGraphic(checkBox);
                    setText(null);
                }
            }
        });

        TableColumn<DayRow, String> colTime = new TableColumn<>("Time slot");
        colTime.setCellValueFactory(cell -> {
            DayRow r = cell.getValue();
            String s = r.getStart() == null ? "" : r.getStart().format(SLOT_TIME);
            String e;
            if (r.getEnd() == null) {
                e = "";
            } else if (r.getEnd().equals(LocalTime.MAX) || (r.getEnd().equals(LocalTime.MIDNIGHT) && r.getStart().isAfter(LocalTime.MIDNIGHT))) {
                e = "24:00";
            } else {
                e = r.getEnd().format(SLOT_TIME);
            }
            return new SimpleStringProperty(s + " \u2013 " + e);
        });
        colTime.setPrefWidth(150);
        colTime.setMinWidth(140);
        colTime.setCellFactory(c -> new TimeCell());

        TableColumn<DayRow, String> colType = new TableColumn<>("Activity type");
        colType.setCellValueFactory(cell -> new SimpleStringProperty(activityLabel(cell.getValue().getType())));
        colType.setPrefWidth(115);
        colType.setMinWidth(105);
        colType.setCellFactory(c -> new ActivityCell());

        TableColumn<DayRow, String> colLabel = new TableColumn<>("Item / commitment");
        colLabel.setCellValueFactory(new PropertyValueFactory<>("label"));
        colLabel.setPrefWidth(300);
        colLabel.setMinWidth(140);
        setupTextColumn(colLabel, true);

        TableColumn<DayRow, String> colDuration = new TableColumn<>("Duration");
        colDuration.setCellValueFactory(cell -> new SimpleStringProperty(
                DurationFormat.format(cell.getValue().getDurationHours())
        ));
        colDuration.setPrefWidth(90);
        colDuration.setMinWidth(82);
        setupTextColumn(colDuration, false, "tabular");

        TableColumn<DayRow, String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(cell -> new SimpleStringProperty(statusText(cell.getValue())));
        colStatus.setPrefWidth(100);
        colStatus.setMinWidth(92);
        colStatus.setCellFactory(col -> new TableCell<DayRow, String>() {
            private final Label chip = new Label();

            {
                chip.getStyleClass().add("chip");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                if (empty || item == null || item.isBlank()) {
                    setGraphic(null);
                    return;
                }
                chip.setText(item);
                chip.getStyleClass().removeAll("chip-neutral", "chip-ok", "chip-warn", "chip-danger");
                chip.getStyleClass().add(statusChipClass(item));
                setGraphic(chip);
            }
        });

        table.getColumns().addAll(colDone, colTime, colType, colLabel, colDuration, colStatus);

        // Visual distinction: muted fixed rows, tinted study rows, readable double-click target.
        table.setRowFactory(tv -> {
            TableRow<DayRow> row = new TableRow<>() {
                {
                    selectedProperty().addListener((obs, oldV, isSel) -> {
                        if (isSel) {
                            if (!getStyleClass().contains("row-selected")) {
                                getStyleClass().add("row-selected");
                            }
                        } else {
                            getStyleClass().remove("row-selected");
                        }
                    });
                }

                @Override
                protected void updateItem(DayRow item, boolean empty) {
                    super.updateItem(item, empty);
                    getStyleClass().removeAll("day-row-fixed", "day-row-study", "day-row-cancelled",
                            "day-row-done", "day-row-manual", "row-selected");
                    if (empty || item == null) {
                        return;
                    }
                    if (isSelected()) {
                        getStyleClass().add("row-selected");
                    }
                    getStyleClass().add(item.isFixed() ? "day-row-fixed" : "day-row-study");
                    if (item.isCancelled()) {
                        getStyleClass().add("day-row-cancelled");
                    } else if (!item.isFixed()) {
                        DailyLogEntry entry = item.getDailyLogEntry();
                        if (entry != null && "DONE".equalsIgnoreCase(entry.getStatus())) {
                            getStyleClass().add("day-row-done");
                        } else if (entry != null && "MANUAL".equalsIgnoreCase(entry.getStatus())) {
                            getStyleClass().add("day-row-manual");
                        }
                    }
                }
            };

            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    DayRow item = row.getItem();
                    if (item != null && !item.isFixed() && studyRowDoubleClickHandler != null) {
                        studyRowDoubleClickHandler.accept(item);
                    }
                }
            });

            return row;
        });

        getChildren().addAll(headerBox, table);
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    /** Hides the stat tiles when the host page already shows the day's totals. */
    public void setShowSummary(boolean show) {
        headerBox.setVisible(show);
        headerBox.setManaged(show);
    }

    /** Time slot with a thin bar in the activity colour, so the day reads at a glance. */
    private static final class TimeCell extends TableCell<DayRow, String> {
        private final javafx.scene.layout.Region bar = new javafx.scene.layout.Region();
        private final Label text = new Label();
        private final javafx.scene.layout.HBox box = new javafx.scene.layout.HBox(10, bar, text);

        TimeCell() {
            bar.getStyleClass().add("type-bar");
            text.getStyleClass().add("tabular");
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            DayRow row = getTableRow() != null ? getTableRow().getItem() : null;
            if (empty || item == null || row == null) {
                setGraphic(null);
                return;
            }
            bar.getStyleClass().removeIf(s -> s.startsWith("type-bar-"));
            bar.getStyleClass().add("type-bar-" + UiKit.activityKind(row.getType()));
            text.setText(item);
            text.getStyleClass().remove("text-cancelled");
            if (row.isCancelled()) {
                text.getStyleClass().add("text-cancelled");
            }
            setGraphic(box);
        }
    }

    /** Activity type as a coloured pill. */
    private static final class ActivityCell extends TableCell<DayRow, String> {
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            DayRow row = getTableRow() != null ? getTableRow().getItem() : null;
            if (empty || item == null || item.isBlank() || row == null) {
                setGraphic(null);
                return;
            }
            setGraphic(UiKit.pill(item, UiKit.activityKind(row.getType())));
        }
    }

    private VBox statTile(String caption, Label valueLabel, String valueStyleClass) {
        Label cap = new Label(caption);
        cap.getStyleClass().add("stat-label");
        valueLabel.getStyleClass().addAll("stat-value", "tabular");
        if (valueStyleClass != null) {
            valueLabel.getStyleClass().add(valueStyleClass);
        }
        VBox box = new VBox(2, cap, valueLabel);
        box.getStyleClass().add("stat");
        box.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return box;
    }

    /**
     * The calendar's details panel is narrower than the six columns need, so it switches the
     * table to an unconstrained policy: the columns keep their readable widths and the table
     * scrolls horizontally instead of truncating every header and cell.
     */
    public void setCompact(boolean compact) {
        table.setColumnResizePolicy(compact
                ? TableView.UNCONSTRAINED_RESIZE_POLICY
                : TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private void setupTextColumn(TableColumn<DayRow, String> col) {
        setupTextColumn(col, false);
    }

    private void setupTextColumn(TableColumn<DayRow, String> col, boolean showTooltip, String... extraClasses) {
        col.setCellFactory(c -> new TableCell<DayRow, String>() {
            {
                getStyleClass().addAll(extraClasses);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                if (showTooltip) {
                    if (empty || item == null || item.isBlank()) {
                        setTooltip(null);
                    } else {
                        Tooltip tip = getTooltip();
                        if (tip == null) {
                            tip = new Tooltip();
                            setTooltip(tip);
                        }
                        tip.setText(item);
                    }
                }
                DayRow row = getTableRow() != null ? getTableRow().getItem() : null;
                boolean cancelled = row != null && row.isCancelled();
                getStyleClass().remove("text-cancelled");
                if (cancelled) {
                    getStyleClass().add("text-cancelled");
                }
            }
        });
    }

    /** Readable label for a stored activity code, e.g. TOPIC_LEARN -> "Topic · learn". */
    private static String activityLabel(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        switch (type.trim().toUpperCase()) {
            case "TOPIC_LEARN":
                return "Topic \u00b7 learn";
            case "TOPIC_REVISE":
                return "Topic \u00b7 revise";
            case "SKILL_LEARN":
                return "Skill \u00b7 learn";
            case "SKILL_REVISE":
                return "Skill \u00b7 revise";
            case "PYQ_PRACTICE":
                return "PYQ practice";
            case "CLASS":
                return "Class";
            case "LAB":
                return "Lab";
            case "TUTORIAL":
                return "Tutorial";
            case "MEAL":
                return "Meal";
            case "SLEEP":
                return "Sleep";
            case "EXAM":
                return "Exam";
            case "OTHER":
                return "Other";
            default:
                // Unknown codes still read as words instead of shouting in upper case.
                String words = type.replace('_', ' ').trim().toLowerCase();
                return words.isEmpty() ? "" : Character.toUpperCase(words.charAt(0)) + words.substring(1);
        }
    }

    /** Human wording for the row state; the chip carries it, so no raw enum codes. */
    private static String statusText(DayRow row) {
        if (row == null) {
            return "";
        }
        if (row.isCancelled()) {
            return "Cancelled";
        }
        if (row.isFixed()) {
            return "Fixed";
        }
        DailyLogEntry log = row.getDailyLogEntry();
        String status = log == null ? null : log.getStatus();
        if (status == null || status.isBlank()) {
            return "Planned";
        }
        String lower = status.toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String statusChipClass(String status) {
        switch (status == null ? "" : status.toLowerCase()) {
            case "done":
                return "chip-ok";
            case "continuing":
                return "chip-warn";
            case "missed":
            case "cancelled":
                return "chip-danger";
            default:
                return "chip-neutral";
        }
    }

    public ObservableList<DayRow> getItems() {
        return items;
    }

    public void setRows(List<DayRow> rows) {
        items.setAll(rows != null ? rows : List.of());
        updateHeaderMetrics();
    }

    private void updateHeaderMetrics() {
        double fixedHours = 0.0;
        double sleepHours = 0.0;
        double plannedStudyHours = 0.0;
        double doneHours = 0.0;

        for (DayRow r : items) {
            if (r.isCancelled()) {
                continue; // Do not count cancelled class hours in fixed hours
            }
            double hrs = r.getDurationHours();
            if ("SLEEP".equalsIgnoreCase(r.getType())) {
                sleepHours += hrs;
            } else if (r.isFixed()) {
                fixedHours += hrs;
            } else {
                plannedStudyHours += hrs;
                DailyLogEntry log = r.getDailyLogEntry();
                if (log != null && "DONE".equalsIgnoreCase(log.getStatus())) {
                    doneHours += hrs;
                }
            }
        }

        lblFixedHours.setText(DurationFormat.format(fixedHours));
        lblSleepHours.setText(DurationFormat.format(sleepHours));
        lblPlannedStudyHours.setText(DurationFormat.format(plannedStudyHours));
        lblDoneHours.setText(DurationFormat.format(doneHours));
    }

    public TableView<DayRow> getTable() {
        return table;
    }

    public DayRow getSelectedItem() {
        return table.getSelectionModel().getSelectedItem();
    }

    public ObservableList<DayRow> getSelectedItems() {
        return table.getSelectionModel().getSelectedItems();
    }

    public void setOnStudyRowDoubleClicked(Consumer<DayRow> handler) {
        this.studyRowDoubleClickHandler = handler;
    }

    public void setOnToggleDone(BiConsumer<DayRow, Boolean> handler) {
        this.onToggleDone = handler;
    }

    public void bindBusyProperty(ReadOnlyBooleanProperty busy) {
        this.busyProperty = busy;
        if (busy != null) {
            busy.addListener((obs, oldV, newV) -> table.refresh());
        }
    }
}
