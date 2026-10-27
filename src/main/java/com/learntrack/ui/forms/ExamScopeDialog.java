package com.learntrack.ui.forms;

import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.ExamScopeDAO;
import com.learntrack.dao.SyllabusDAO;
import com.learntrack.model.Exam;
import com.learntrack.model.SyllabusUnit;
import com.learntrack.model.SyllabusSubtopic;
import com.learntrack.service.ExamScopeService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Modal dialog for selecting syllabus scope for an exam.
 * Redesigned with proper CheckBoxTreeCell, no manual listeners, multi-select, search, etc.
 */
public class ExamScopeDialog {

    private final Exam exam;
    private final String subjectCode;
    private final int semesterId;
    private final SyllabusDAO syllabusDAO;
    private final ExamDAO examDAO;
    private final ExamScopeService examScopeService;
    private final Runnable onSave;

    private Stage dialog;
    private TreeView<SyllabusItem> treeView;
    private Set<Integer> selectedSubtopicIds;
    private List<SyllabusUnit> allUnits;
    private Map<Integer, List<SyllabusSubtopic>> allSubtopicsByUnit;
    private TextField searchField;
    private Label counterLabel;
    private Button renameBtn;
    private Button deleteBtn;
    private Map<TreeItem<SyllabusItem>, Boolean> expandedState;

    public ExamScopeDialog(Exam exam, String subjectCode, int semesterId,
                           SyllabusDAO syllabusDAO, ExamDAO examDAO,
                           ExamScopeService examScopeService,
                           Runnable onSave) {
        this.exam = exam;
        this.subjectCode = subjectCode;
        this.semesterId = semesterId;
        this.syllabusDAO = syllabusDAO;
        this.examDAO = examDAO;
        this.examScopeService = examScopeService;
        this.onSave = onSave;
        this.selectedSubtopicIds = new HashSet<>();
        this.expandedState = new HashMap<>();
    }

    public Set<Integer> showAndWait(Stage owner) {
        dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(owner);
        dialog.setTitle("Syllabus Scope - " + exam.getDisplayTitle());

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));

        Label header = new Label("Select syllabus scope for " + exam.getDisplayTitle());
        header.getStyleClass().add("section-title");
        header.setWrapText(true);

        // Load data
        loadData();

        if (allUnits.isEmpty()) {
            buildEmptyState(root, header);
        } else {
            buildNormalUI(root, header);
        }

        Scene scene = new Scene(root, 720, 640);
        com.learntrack.ui.Theme.apply(scene, com.learntrack.ui.Theme.isDarkActive());
        // Esc closes the dialog like Cancel.
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                dialog.close();
            }
        });
        dialog.setScene(scene);
        dialog.setResizable(true);
        dialog.showAndWait();
        return selectedSubtopicIds;
    }

    private void loadData() {
        try {
            ExamScopeDAO examScopeDAO = new ExamScopeDAO();
            selectedSubtopicIds = examScopeDAO.findSubtopicIdsForExam(exam.getExamId());
            allUnits = syllabusDAO.findUnitsBySubject(semesterId, subjectCode);
            allSubtopicsByUnit = new HashMap<>();
            for (SyllabusUnit unit : allUnits) {
                allSubtopicsByUnit.put(unit.getUnitId(), syllabusDAO.findSubtopicsByUnit(unit.getUnitId()));
            }
        } catch (SQLException e) {
            e.printStackTrace();
            selectedSubtopicIds = new HashSet<>();
            allUnits = new ArrayList<>();
            allSubtopicsByUnit = new HashMap<>();
        }
    }

    private void buildEmptyState(VBox root, Label header) {
        VBox emptyBox = com.learntrack.ui.UiKit.emptyState("No syllabus exists for this subject yet",
                "Add units here, or paste the whole syllabus in the Subjects tab.");

        Button addUnitBtn = com.learntrack.ui.UiKit.secondaryButton("+ Add Unit");
        addUnitBtn.setOnAction(e -> {
            addUnit();
            // Reload UI
            root.getChildren().clear();
            loadData();
            if (allUnits.isEmpty()) {
                buildEmptyState(root, header);
            } else {
                buildNormalUI(root, header);
            }
        });

        emptyBox.getChildren().add(addUnitBtn);

        Button cancelBtn = com.learntrack.ui.UiKit.secondaryButton("Close");
        cancelBtn.setOnAction(e -> dialog.close());
        HBox bottomBar = new HBox(cancelBtn);
        bottomBar.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        root.getChildren().addAll(header, emptyBox, bottomBar);
    }

    private void buildNormalUI(VBox root, Label header) {
        // Search field
        searchField = new TextField();
        searchField.setPromptText("Search units and subtopics...");
        searchField.textProperty().addListener((obs, old, val) -> rebuildTree());

        // Counter label
        counterLabel = new Label();
        updateCounter();
        counterLabel.getStyleClass().add("subtle");

        HBox searchRow = new HBox(12, searchField, counterLabel);
        searchRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        // TreeView
        treeView = new TreeView<>();
        treeView.setCellFactory(CheckBoxTreeCell.forTreeView());
        treeView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        VBox.setVgrow(treeView, Priority.ALWAYS);

        // Space key toggles selection
        treeView.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.SPACE) {
                toggleSelectedRows();
                e.consume();
            }
        });

        rebuildTree();

        // Selection buttons
        HBox selectionBar = new HBox(8);
        selectionBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Button checkSelectedBtn = com.learntrack.ui.UiKit.ghostButton("Check selected");
        checkSelectedBtn.setOnAction(e -> checkSelectedRows());

        Button uncheckSelectedBtn = com.learntrack.ui.UiKit.ghostButton("Uncheck selected");
        uncheckSelectedBtn.setOnAction(e -> uncheckSelectedRows());

        Button selectAllBtn = com.learntrack.ui.UiKit.ghostButton("Select all");
        selectAllBtn.setOnAction(e -> selectAll());

        Button clearBtn = com.learntrack.ui.UiKit.ghostButton("Clear");
        clearBtn.setOnAction(e -> clearAll());

        Button expandAllBtn = com.learntrack.ui.UiKit.ghostButton("Expand all");
        expandAllBtn.setOnAction(e -> expandAll());

        Button collapseAllBtn = com.learntrack.ui.UiKit.ghostButton("Collapse all");
        collapseAllBtn.setOnAction(e -> collapseAll());

        selectionBar.getChildren().addAll(checkSelectedBtn, uncheckSelectedBtn, selectAllBtn, clearBtn, expandAllBtn, collapseAllBtn);

        // Edit buttons
        HBox editBar = new HBox(8);
        editBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Button addUnitBtn = com.learntrack.ui.UiKit.primaryButton("+ Add Unit");
        addUnitBtn.setOnAction(e -> addUnit());

        Button addSubtopicBtn = com.learntrack.ui.UiKit.secondaryButton("+ Add Subtopic");
        addSubtopicBtn.setOnAction(e -> addSubtopic());

        renameBtn = com.learntrack.ui.UiKit.secondaryButton("Rename");
        renameBtn.setOnAction(e -> renameSelected());

        deleteBtn = com.learntrack.ui.UiKit.dangerTextButton("Delete");
        deleteBtn.setOnAction(e -> deleteSelected());

        Button copyBtn = com.learntrack.ui.UiKit.secondaryButton("Copy scope from...");
        copyBtn.setOnAction(e -> showCopyDialog());

        editBar.getChildren().addAll(addUnitBtn, addSubtopicBtn, renameBtn, deleteBtn, copyBtn);

        // Listen to selection for rename/delete enable
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> {
            int count = treeView.getSelectionModel().getSelectedItems().size();
            renameBtn.setDisable(count != 1);
            deleteBtn.setDisable(count != 1);
        });
        renameBtn.setDisable(true);
        deleteBtn.setDisable(true);

        // Dialog buttons
        HBox dialogBar = new HBox(8);
        dialogBar.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        Button saveBtn = com.learntrack.ui.UiKit.primaryButton("Save");
        saveBtn.setOnAction(e -> handleSave());

        Button cancelBtn = com.learntrack.ui.UiKit.ghostButton("Cancel");
        cancelBtn.setOnAction(e -> dialog.close());

        dialogBar.getChildren().addAll(saveBtn, cancelBtn);

        root.getChildren().addAll(header, searchRow, treeView, selectionBar, editBar, dialogBar);
    }

    private void rebuildTree() {
        // Save expanded state before rebuild
        saveExpandedState();

        // Sync selected state from tree before rebuilding
        syncSelectedFromTree();

        TreeItem<SyllabusItem> root = new TreeItem<>(new SyllabusItem("Root", null, null));
        root.setExpanded(true);

        String searchText = searchField != null ? searchField.getText().toLowerCase().trim() : "";
        boolean filtering = !searchText.isEmpty();

        for (SyllabusUnit unit : allUnits) {
            List<SyllabusSubtopic> subtopics = allSubtopicsByUnit.getOrDefault(unit.getUnitId(), Collections.emptyList());

            // Filter subtopics
            List<SyllabusSubtopic> filteredSubtopics = subtopics;
            if (filtering) {
                filteredSubtopics = subtopics.stream()
                        .filter(s -> s.getName().toLowerCase().contains(searchText))
                        .collect(Collectors.toList());
                // Also check unit name
                if (filteredSubtopics.isEmpty() && !unit.getName().toLowerCase().contains(searchText)) {
                    continue; // Skip this unit entirely
                } else if (unit.getName().toLowerCase().contains(searchText)) {
                    filteredSubtopics = subtopics; // Show all subtopics if unit matches
                }
            }

            if (filteredSubtopics.isEmpty() && filtering) {
                continue;
            }

            CheckBoxTreeItem<SyllabusItem> unitItem = new CheckBoxTreeItem<>(
                    new SyllabusItem(unit.getName(), unit, null)
            );
            unitItem.setIndependent(false); // Tri-state propagation

            for (SyllabusSubtopic subtopic : filteredSubtopics) {
                String badge = examScopeService != null ? examScopeService.getCoveredBeforeBadge(exam.getExamId(), subtopic.getSubtopicId()) : null;
                CheckBoxTreeItem<SyllabusItem> subtopicItem = new CheckBoxTreeItem<>(
                        new SyllabusItem(subtopic.getName(), unit, subtopic, badge)
                );
                subtopicItem.setIndependent(false);
                subtopicItem.setSelected(selectedSubtopicIds.contains(subtopic.getSubtopicId()));
                unitItem.getChildren().add(subtopicItem);
            }

            root.getChildren().add(unitItem);
        }

        treeView.setRoot(root);
        treeView.setShowRoot(false);

        // Restore expanded state
        restoreExpandedState();

        updateCounter();
    }

    private void saveExpandedState() {
        expandedState.clear();
        if (treeView.getRoot() != null) {
            saveExpandedStateRecursive(treeView.getRoot());
        }
    }

    private void saveExpandedStateRecursive(TreeItem<SyllabusItem> item) {
        if (item.getValue() != null && item.getValue().getUnit() != null) {
            expandedState.put(item, item.isExpanded());
        }
        for (TreeItem<SyllabusItem> child : item.getChildren()) {
            saveExpandedStateRecursive(child);
        }
    }

    private void restoreExpandedState() {
        if (treeView.getRoot() != null) {
            restoreExpandedStateRecursive(treeView.getRoot());
        }
    }

    private void restoreExpandedStateRecursive(TreeItem<SyllabusItem> item) {
        if (item.getValue() != null && item.getValue().getUnit() != null) {
            // Match by unit ID
            for (Map.Entry<TreeItem<SyllabusItem>, Boolean> entry : expandedState.entrySet()) {
                SyllabusItem oldItem = entry.getKey().getValue();
                if (oldItem != null && oldItem.getUnit() != null &&
                    item.getValue().getUnit() != null &&
                    oldItem.getUnit().getUnitId() == item.getValue().getUnit().getUnitId()) {
                    item.setExpanded(entry.getValue());
                    break;
                }
            }
        }
        for (TreeItem<SyllabusItem> child : item.getChildren()) {
            restoreExpandedStateRecursive(child);
        }
    }

    private void syncSelectedFromTree() {
        selectedSubtopicIds.clear();
        if (treeView != null && treeView.getRoot() != null) {
            syncSelectedFromTreeRecursive(treeView.getRoot());
        }
    }

    private void syncSelectedFromTreeRecursive(TreeItem<SyllabusItem> item) {
        if (item instanceof CheckBoxTreeItem) {
            CheckBoxTreeItem<SyllabusItem> cbItem = (CheckBoxTreeItem<SyllabusItem>) item;
            if (cbItem.isSelected() && cbItem.getValue() != null && cbItem.getValue().getSubtopic() != null) {
                selectedSubtopicIds.add(cbItem.getValue().getSubtopic().getSubtopicId());
            }
        }
        for (TreeItem<SyllabusItem> child : item.getChildren()) {
            syncSelectedFromTreeRecursive(child);
        }
    }

    private void updateCounter() {
        int totalSubtopics = allSubtopicsByUnit.values().stream().mapToInt(List::size).sum();
        counterLabel.setText(selectedSubtopicIds.size() + " of " + totalSubtopics + " subtopics selected");
    }

    private void toggleSelectedRows() {
        List<TreeItem<SyllabusItem>> selected = new ArrayList<>(treeView.getSelectionModel().getSelectedItems());
        for (TreeItem<SyllabusItem> item : selected) {
            if (item instanceof CheckBoxTreeItem) {
                CheckBoxTreeItem<SyllabusItem> cbItem = (CheckBoxTreeItem<SyllabusItem>) item;
                cbItem.setSelected(!cbItem.isSelected());
            }
        }
        syncSelectedFromTree();
        updateCounter();
    }

    private void checkSelectedRows() {
        List<TreeItem<SyllabusItem>> selected = new ArrayList<>(treeView.getSelectionModel().getSelectedItems());
        for (TreeItem<SyllabusItem> item : selected) {
            if (item instanceof CheckBoxTreeItem) {
                ((CheckBoxTreeItem<SyllabusItem>) item).setSelected(true);
            }
        }
        syncSelectedFromTree();
        updateCounter();
    }

    private void uncheckSelectedRows() {
        List<TreeItem<SyllabusItem>> selected = new ArrayList<>(treeView.getSelectionModel().getSelectedItems());
        for (TreeItem<SyllabusItem> item : selected) {
            if (item instanceof CheckBoxTreeItem) {
                ((CheckBoxTreeItem<SyllabusItem>) item).setSelected(false);
            }
        }
        syncSelectedFromTree();
        updateCounter();
    }

    private void selectAll() {
        forEachCheckBoxItem(treeView.getRoot(), item -> item.setSelected(true));
        syncSelectedFromTree();
        updateCounter();
    }

    private void clearAll() {
        forEachCheckBoxItem(treeView.getRoot(), item -> item.setSelected(false));
        syncSelectedFromTree();
        updateCounter();
    }

    private void expandAll() {
        forEachItem(treeView.getRoot(), item -> item.setExpanded(true));
    }

    private void collapseAll() {
        forEachItem(treeView.getRoot(), item -> item.setExpanded(false));
    }

    private void forEachCheckBoxItem(TreeItem<SyllabusItem> root, java.util.function.Consumer<CheckBoxTreeItem<SyllabusItem>> action) {
        if (root instanceof CheckBoxTreeItem) {
            action.accept((CheckBoxTreeItem<SyllabusItem>) root);
        }
        for (TreeItem<SyllabusItem> child : root.getChildren()) {
            forEachCheckBoxItem(child, action);
        }
    }

    private void forEachItem(TreeItem<SyllabusItem> root, java.util.function.Consumer<TreeItem<SyllabusItem>> action) {
        action.accept(root);
        for (TreeItem<SyllabusItem> child : root.getChildren()) {
            forEachItem(child, action);
        }
    }

    private void showCopyDialog() {
        try {
            List<Exam> otherExams = examDAO.findBySemester(semesterId);
            List<Exam> sameSubjectExams = otherExams.stream()
                    .filter(e -> e.getExamId() != exam.getExamId() && subjectCode.equalsIgnoreCase(e.getSubjectCode()))
                    .collect(Collectors.toList());

            if (sameSubjectExams.isEmpty()) {
                showAlert("No Other Exams", "No other exams exist for this subject to copy from.");
                return;
            }

            ChoiceDialog<Exam> copyDialog = new ChoiceDialog<>(sameSubjectExams.get(0), sameSubjectExams);
            copyDialog.setTitle("Copy Scope");
            copyDialog.setHeaderText("Copy syllabus scope from another exam");
            copyDialog.setContentText("Select exam:");

            Optional<Exam> result = copyDialog.showAndWait();
            result.ifPresent(sourceExam -> {
                try {
                    ExamScopeDAO examScopeDAO = new ExamScopeDAO();
                    Set<Integer> sourceScope = examScopeDAO.findSubtopicIdsForExam(sourceExam.getExamId());
                    selectedSubtopicIds.clear();
                    selectedSubtopicIds.addAll(sourceScope);
                    rebuildTree();
                } catch (SQLException e) {
                    e.printStackTrace();
                    showAlert("Error", "Failed to copy scope: " + e.getMessage());
                }
            });

        } catch (SQLException e) {
            e.printStackTrace();
            showAlert("Error", "Failed to load exams: " + e.getMessage());
        }
    }

    private void addUnit() {
        TextInputDialog dialog = new TextInputDialog();
        com.learntrack.ui.UiKit.styleDialog(dialog);
        dialog.setTitle("Add Unit");
        dialog.setHeaderText("Add a new syllabus unit");
        dialog.setContentText("Unit name:");
        com.learntrack.ui.UiKit.styleDialog(dialog);

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            if (name.trim().isEmpty()) {
                return;
            }
            try {
                int position = allUnits.size();
                syllabusDAO.insertUnit(semesterId, subjectCode, name.trim(), position);
                loadData();
                rebuildTree();
            } catch (SQLException e) {
                e.printStackTrace();
                showAlert("Error", "Failed to add unit: " + e.getMessage());
            }
        });
    }

    private void addSubtopic() {
        TreeItem<SyllabusItem> selected = treeView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert("No Selection", "Please select a unit to add a subtopic to.");
            return;
        }

        SyllabusItem item = selected.getValue();
        SyllabusUnit unit = item.getUnit();
        if (unit == null) {
            showAlert("Invalid Selection", "Please select a unit (not a subtopic).");
            return;
        }

        Dialog<java.util.Map<String, Object>> dialog = new Dialog<>();
        dialog.setTitle("Add Subtopic");
        dialog.setHeaderText("Add a new subtopic to: " + unit.getName());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new javafx.geometry.Insets(10));

        TextField nameField = new TextField();
        nameField.setPromptText("Subtopic name");

        Spinner<Double> coverSpinner = new Spinner<>(0.1, 100.0, 1.0, 0.25);
        coverSpinner.setEditable(true);
        coverSpinner.setPrefWidth(120);

        Spinner<Double> reviseSpinner = new Spinner<>(0.1, 100.0, 0.5, 0.25);
        reviseSpinner.setEditable(true);
        reviseSpinner.setPrefWidth(120);

        grid.add(new Label("Name:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("Hours to cover:"), 0, 1);
        grid.add(coverSpinner, 1, 1);
        grid.add(new Label("Hours to revise:"), 0, 2);
        grid.add(reviseSpinner, 1, 2);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        com.learntrack.ui.UiKit.styleDialog(dialog);
        nameField.setOnAction(e -> {
            javafx.scene.Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
            if (ok instanceof Button) {
                ((Button) ok).fire();
            }
        });

        dialog.setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                java.util.Map<String, Object> result = new java.util.HashMap<>();
                result.put("name", nameField.getText());
                result.put("hoursToCover", coverSpinner.getValue());
                result.put("hoursToRevise", reviseSpinner.getValue());
                return result;
            }
            return null;
        });

        Optional<java.util.Map<String, Object>> result = dialog.showAndWait();
        result.ifPresent(data -> {
            String name = (String) data.get("name");
            double cover = (Double) data.get("hoursToCover");
            double revise = (Double) data.get("hoursToRevise");
            if (!name.trim().isEmpty()) {
                try {
                    List<SyllabusSubtopic> existing = syllabusDAO.findSubtopicsByUnit(unit.getUnitId());
                    int position = existing.size();
                    int newSubtopicId = syllabusDAO.insertSubtopic(unit.getUnitId(), name.trim(), position, 5, cover, revise);
                    
                    // Auto-check the new subtopic
                    selectedSubtopicIds.add(newSubtopicId);
                    
                    loadData();
                    rebuildTree();
                } catch (SQLException e) {
                    e.printStackTrace();
                    showAlert("Error", "Failed to add subtopic: " + e.getMessage());
                }
            }
        });
    }

    private void renameSelected() {
        TreeItem<SyllabusItem> selected = treeView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }

        SyllabusItem item = selected.getValue();
        TextInputDialog dialog = new TextInputDialog(item.getName());
        com.learntrack.ui.UiKit.styleDialog(dialog);
        dialog.setTitle("Rename");
        dialog.setHeaderText("Rename: " + item.getName());
        dialog.setContentText("New name:");
        com.learntrack.ui.UiKit.styleDialog(dialog);

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(newName -> {
            if (newName.trim().isEmpty()) {
                return;
            }
            try {
                if (item.getSubtopic() != null) {
                    syllabusDAO.updateSubtopicName(item.getSubtopic().getSubtopicId(), newName.trim());
                } else if (item.getUnit() != null) {
                    syllabusDAO.updateUnitName(item.getUnit().getUnitId(), newName.trim());
                }
                loadData();
                rebuildTree();
            } catch (SQLException e) {
                e.printStackTrace();
                showAlert("Error", "Failed to rename: " + e.getMessage());
            }
        });
    }

    private void deleteSelected() {
        TreeItem<SyllabusItem> selected = treeView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }

        SyllabusItem item = selected.getValue();
        String itemType = item.getSubtopic() != null ? "subtopic" : "unit";
        String message = "Delete " + itemType + " '" + item.getName() + "'?\n\n" +
                "This will also remove it from the Subjects tab and from every exam's scope.";

        if (com.learntrack.ui.UiKit.confirm(dialog, "Confirm delete",
                "Delete " + itemType + " “" + item.getName() + "”?", message, "Delete")) {
            try {
                if (item.getSubtopic() != null) {
                    int subtopicId = item.getSubtopic().getSubtopicId();
                    selectedSubtopicIds.remove(subtopicId);
                    syllabusDAO.deleteSubtopic(subtopicId);
                } else if (item.getUnit() != null) {
                    syllabusDAO.deleteUnit(item.getUnit().getUnitId());
                }
                loadData();
                rebuildTree();
            } catch (SQLException e) {
                e.printStackTrace();
                showAlert("Error", "Failed to delete: " + e.getMessage());
            }
        }
    }

    private void handleSave() {
        syncSelectedFromTree();
        
        // Call service off FX thread
        Task<ExamScopeService.ScopeSyncResult> task = new Task<>() {
            @Override
            protected ExamScopeService.ScopeSyncResult call() throws Exception {
                return examScopeService.saveScopeAndSyncTopics(exam.getExamId(), selectedSubtopicIds);
            }
        };

        task.setOnSucceeded(e -> {
            ExamScopeService.ScopeSyncResult result = task.getValue();
            if (result.getKeptStudied() > 0) {
                showAlert("Topics Kept", 
                    result.getKeptStudied() + " topic(s) kept because you already studied them.");
            }
            if (onSave != null) {
                onSave.run();
            }
            dialog.close();
        });

        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            ex.printStackTrace();
            showAlert("Error", "Failed to save scope: " + ex.getMessage());
        });

        new Thread(task).start();
    }

    private void showAlert(String title, String message) {
        Platform.runLater(() -> {
            if ("Error".equalsIgnoreCase(title)) {
                com.learntrack.ui.UiKit.error(dialog, title, message);
            } else {
                com.learntrack.ui.UiKit.toast(message, com.learntrack.ui.UiKit.Kind.INFO);
            }
        });
    }

    /**
     * Wrapper class for TreeView items
     */
    private static class SyllabusItem {
        private final String name;
        private final SyllabusUnit unit;
        private final SyllabusSubtopic subtopic;
        private final String badge;

        public SyllabusItem(String name, SyllabusUnit unit, SyllabusSubtopic subtopic) {
            this(name, unit, subtopic, null);
        }

        public SyllabusItem(String name, SyllabusUnit unit, SyllabusSubtopic subtopic, String badge) {
            this.name = name;
            this.unit = unit;
            this.subtopic = subtopic;
            this.badge = badge;
        }

        public String getName() {
            return name;
        }

        public SyllabusUnit getUnit() {
            return unit;
        }

        public SyllabusSubtopic getSubtopic() {
            return subtopic;
        }

        @Override
        public String toString() {
            if (subtopic != null) {
                String badgeStr = (badge != null && !badge.isEmpty()) ? " [" + badge + "]" : "";
                return String.format("%s%s (%.1fh / %.1fh rev)", name, badgeStr, subtopic.getHoursToCover(), subtopic.getHoursToRevise());
            }
            return name;
        }
    }
}
