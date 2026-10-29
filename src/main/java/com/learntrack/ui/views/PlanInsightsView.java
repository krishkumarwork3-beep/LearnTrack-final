package com.learntrack.ui.views;

import com.learntrack.model.Exam;
import com.learntrack.service.CoveragePlan;
import com.learntrack.service.SemesterReplanService;
import com.learntrack.ui.UiKit;
import com.learntrack.ui.charts.FeasibilityBarView;
import com.learntrack.util.DurationFormat;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Coverage page: for every subject, how much of each exam's syllabus (MST, EST, other
 * evaluations) the current plan gets learned before that exam, and which topics do not fit.
 * Read-only; it reflects the latest replan.
 */
public class PlanInsightsView {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private final SemesterReplanService replanService;
    private final FeasibilityBarView feasibilityBarView;
    private final VBox content = new VBox(16);
    private final HBox summary = new HBox(14);
    private final VBox subjects = new VBox(16);

    public PlanInsightsView(SemesterReplanService replanService, FeasibilityBarView feasibilityBarView) {
        this.replanService = replanService;
        this.feasibilityBarView = feasibilityBarView;
    }

    public Node getView() {
        HBox header = UiKit.pageHeader("Coverage",
                "How much of each exam's syllabus your plan gets learned before the exam, subject by subject. "
                        + "Learning follows the syllabus order; if time runs short, the topics left out are listed.",
                null);
        content.getChildren().setAll(header, summary);
        if (feasibilityBarView != null) {
            VBox feasibility = UiKit.card(UiKit.sectionTitle("Workload"), feasibilityBarView);
            feasibility.setSpacing(10);
            content.getChildren().add(feasibility);
        }
        content.getChildren().add(subjects);
        content.setPadding(new Insets(24, 26, 24, 26));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("page-scroll");
        refresh();
        return scroll;
    }

    public void refresh() {
        CoveragePlan plan = replanService == null ? null : replanService.getLastCoveragePlan();
        summary.getChildren().clear();
        subjects.getChildren().clear();
        if (feasibilityBarView != null) feasibilityBarView.refresh();

        if (plan == null || plan.getExams().isEmpty()) {
            subjects.getChildren().add(UiKit.emptyState("No upcoming exams",
                    "Add exams in Exams and choose their syllabus to see what you can cover before each one."));
            return;
        }

        // Summary tiles
        int examsWithScope = 0, fullyCovered = 0, notFitting = 0, withoutScope = 0;
        for (CoveragePlan.ExamCoverage c : plan.getExams()) {
            if (c.getTotalTopics() == 0) {
                withoutScope++;
                continue;
            }
            examsWithScope++;
            if (c.getAtRisk().isEmpty()) fullyCovered++;
            notFitting += c.getAtRisk().size();
        }
        for (HBox tile : List.of(
                UiKit.kpi(UiKit.Icon.TARGET, "learn", "Syllabus learned",
                        examsWithScope == 0 ? "–" : Math.round(plan.overallLearned() * 100) + "%",
                        examsWithScope == 0 ? "choose a syllabus in Exams" : Math.round(plan.overallCoverage() * 100) + "% by exam day if you follow the plan"),
                UiKit.kpi(UiKit.Icon.FLAG, "revise", "Exams fully covered",
                        fullyCovered + " of " + examsWithScope,
                        withoutScope == 0 ? "every topic fits before the exam"
                                : withoutScope + " more exam" + (withoutScope == 1 ? " has" : "s have") + " no syllabus yet"),
                UiKit.kpi(UiKit.Icon.SPARK, "exam", "Topics that don't fit",
                        String.valueOf(notFitting), notFitting == 0 ? "nothing left out" : "listed under each exam"))) {
            HBox.setHgrow(tile, Priority.ALWAYS);
            tile.setMaxWidth(Double.MAX_VALUE);
            tile.setPrefWidth(0);
            summary.getChildren().add(tile);
        }

        // Group exams by subject, subjects ordered by their nearest exam
        Map<String, List<CoveragePlan.ExamCoverage>> bySubject = new LinkedHashMap<>();
        List<CoveragePlan.ExamCoverage> ordered = new ArrayList<>(plan.getExams());
        ordered.sort(Comparator.comparing(CoveragePlan.ExamCoverage::getDeadline));
        for (CoveragePlan.ExamCoverage c : ordered) {
            Exam e = c.getExam();
            String key = e.getSubjectCode() != null && !e.getSubjectCode().isBlank() ? e.getSubjectCode() : e.getSubject();
            bySubject.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<String, List<CoveragePlan.ExamCoverage>> entry : bySubject.entrySet()) {
            subjects.getChildren().add(subjectCard(entry.getKey(), entry.getValue()));
        }
    }

    private Node subjectCard(String code, List<CoveragePlan.ExamCoverage> exams) {
        Exam first = exams.get(0).getExam();
        String name = first.getSubject() == null || first.getSubject().equalsIgnoreCase(code) ? code : first.getSubject();
        Label title = UiKit.sectionTitle(name);
        HBox head = new HBox(10, title);
        head.setAlignment(Pos.BASELINE_LEFT);
        if (!name.equalsIgnoreCase(code)) {
            Label codeLabel = UiKit.subtle(code);
            head.getChildren().add(codeLabel);
        }
        VBox rows = new VBox(0);
        for (CoveragePlan.ExamCoverage c : exams) {
            rows.getChildren().add(examRow(c));
        }
        VBox card = UiKit.card(head, rows);
        card.setSpacing(8);
        return card;
    }

    /** Bar with the learned part solid, the planned part lighter, and the rest empty. */
    private static Node twoPartBar(double learned, double byExam) {
        double l = Math.max(0, Math.min(1, learned));
        double p = Math.max(0, Math.min(1, byExam) - l);
        Region learnedPart = new Region();
        learnedPart.getStyleClass().add("bar-learned");
        Region plannedPart = new Region();
        plannedPart.getStyleClass().add("bar-planned");
        Region rest = new Region();
        GridPane bar = new GridPane();
        bar.getStyleClass().add("two-part-bar");
        double[] shares = {l, p, 1 - l - p};
        Region[] parts = {learnedPart, plannedPart, rest};
        for (int i = 0; i < 3; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(shares[i] * 100);
            bar.getColumnConstraints().add(cc);
            parts[i].setMinHeight(8);
            parts[i].setPrefHeight(8);
            parts[i].setMaxWidth(Double.MAX_VALUE);
            bar.add(parts[i], i, 0);
        }
        bar.setMaxWidth(Double.MAX_VALUE);
        return bar;
    }

    private Node examRow(CoveragePlan.ExamCoverage c) {
        Exam e = c.getExam();
        String type = "NORMAL".equalsIgnoreCase(e.getExamType())
                ? (e.getEvalName() != null && !e.getEvalName().isBlank() ? e.getEvalName() : "Evaluation")
                : e.getExamType();
        Label typeLabel = new Label(type);
        typeLabel.getStyleClass().addAll("pill", "MST".equalsIgnoreCase(type) ? "pill-learn"
                : "EST".equalsIgnoreCase(type) ? "pill-exam" : "pill-revise");
        typeLabel.setMinWidth(Region.USE_PREF_SIZE);

        String when;
        if (e.getExamDate() == null || e.isExamDateTentative()) {
            when = "date not announced · planning to " + c.getDeadline().format(DAY);
        } else {
            long days = ChronoUnit.DAYS.between(LocalDate.now(), e.getExamDate());
            when = e.getExamDate().format(DAY) + " · " + (days <= 0 ? "today" : days == 1 ? "tomorrow" : "in " + days + " days");
        }
        Label whenLabel = new Label(when);
        whenLabel.getStyleClass().add("insight-meta");
        HBox top = new HBox(10, typeLabel, whenLabel, UiKit.spacer());
        top.setAlignment(Pos.CENTER_LEFT);

        VBox row = new VBox(8, top);
        row.getStyleClass().add("insight-row");
        row.setPadding(new Insets(12, 0, 12, 0));

        if (c.getTotalTopics() == 0) {
            row.getChildren().add(UiKit.hint("No syllabus chosen for this exam yet. Open Exams › Add from syllabus."));
            return row;
        }

        // Two honest numbers: what is learned now, and what the plan gets learned by exam day.
        double learnedShare = c.secured();
        double byExam = c.coverage();
        Label pct = new Label(Math.round(learnedShare * 100) + "% learned");
        pct.getStyleClass().addAll("insight-pct", "tabular");
        Label target = new Label("→ " + Math.round(byExam * 100) + "% by the exam");
        target.getStyleClass().add("insight-meta");
        top.getChildren().addAll(pct, target);

        Node bar = twoPartBar(learnedShare, byExam);

        int learned = c.getLearnedTopics();
        int planned = c.getPlannedTopics();
        int left = c.getAtRisk().size();
        Label detail = new Label(learned + " of " + c.getTotalTopics() + " topics learned · " + planned
                + " more planned before the exam (" + DurationFormat.format(c.getPlannedHours()) + ")"
                + (left > 0 ? " · " + left + " don't fit" : ""));
        detail.getStyleClass().add("insight-meta");
        detail.setWrapText(true);
        row.getChildren().addAll(bar, detail);

        if (left > 0) {
            FlowPane missing = new FlowPane(8, 6);
            int shown = 0;
            for (CoveragePlan.AtRiskTopic t : c.getAtRisk()) {
                if (shown++ >= 12) break;
                Label item = new Label(t.name + " · " + DurationFormat.format(t.hours));
                item.getStyleClass().addAll("pill", "pill-other");
                missing.getChildren().add(item);
            }
            if (left > 12) missing.getChildren().add(UiKit.hint("+ " + (left - 12) + " more"));
            row.getChildren().addAll(UiKit.hint("Not enough free time before this exam for:"), missing);
        }
        return row;
    }
}
