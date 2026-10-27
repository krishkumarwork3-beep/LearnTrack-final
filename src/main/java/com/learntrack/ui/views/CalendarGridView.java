package com.learntrack.ui.views;

import com.learntrack.dao.DailyLogDAO;
import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.SemesterDAO;
import com.learntrack.model.DailyLogEntry;
import com.learntrack.model.DayRow;
import com.learntrack.model.Exam;
import com.learntrack.model.Semester;
import com.learntrack.service.DayScheduleService;
import com.learntrack.service.ExamWindowService;
import com.learntrack.ui.UiKit;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Full semester/month calendar view in a GridPane.
 * Clicking any cell shows that day's full merged schedule using DayScheduleView.
 */
public class CalendarGridView {

    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("EEEE d MMM yyyy");
    private static final String[] WEEKDAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    private final DailyLogDAO dailyLogDAO;
    private final ExamDAO examDAO;
    private final SemesterDAO semesterDAO;
    private final DayScheduleService dayScheduleService;
    private final ExamWindowService examWindowService;

    private YearMonth currentYearMonth;
    private GridPane calendarGrid;
    private Label monthTitle;
    private DayScheduleView dayScheduleView;
    private Label dayDetailsHeader;
    private LocalDate selectedDate = LocalDate.now();

    public CalendarGridView(DailyLogDAO dailyLogDAO, ExamDAO examDAO, SemesterDAO semesterDAO) {
        this(dailyLogDAO, examDAO, semesterDAO, null);
    }

    public CalendarGridView(DailyLogDAO dailyLogDAO, ExamDAO examDAO, SemesterDAO semesterDAO,
                            DayScheduleService dayScheduleService) {
        this.dailyLogDAO = dailyLogDAO;
        this.examDAO = examDAO;
        this.semesterDAO = semesterDAO;
        this.dayScheduleService = dayScheduleService;
        this.examWindowService = new ExamWindowService();
        this.currentYearMonth = YearMonth.now();
    }

    public Node getView() {
        VBox root = new VBox(16);
        root.setPadding(new Insets(24));
        root.getStyleClass().add("page");

        monthTitle = new Label();
        monthTitle.getStyleClass().add("section-title");
        monthTitle.setMinWidth(180);
        monthTitle.setAlignment(Pos.CENTER);

        Button btnPrev = UiKit.secondaryButton("Prev");
        btnPrev.setTooltip(new Tooltip("Previous month"));
        btnPrev.setOnAction(e -> {
            currentYearMonth = currentYearMonth.minusMonths(1);
            refreshCalendar();
        });

        Button btnNext = UiKit.secondaryButton("Next");
        btnNext.setTooltip(new Tooltip("Next month"));
        btnNext.setOnAction(e -> {
            currentYearMonth = currentYearMonth.plusMonths(1);
            refreshCalendar();
        });

        Button btnToday = UiKit.secondaryButton("Today");
        btnToday.setOnAction(e -> {
            currentYearMonth = YearMonth.now();
            selectedDate = LocalDate.now();
            refreshCalendar();
            showDaySchedule(selectedDate);
        });

        HBox navGroup = new HBox(8, btnPrev, monthTitle, btnNext, btnToday);
        navGroup.setAlignment(Pos.CENTER_LEFT);
        HBox navBar = UiKit.pageHeader("Calendar",
                "Month view, with the selected day's schedule beside it.", navGroup);

        // ------------------------------------------------------------- grid
        calendarGrid = new GridPane();
        calendarGrid.setHgap(8);
        calendarGrid.setVgap(8);
        calendarGrid.setMaxWidth(Double.MAX_VALUE);
        calendarGrid.setMaxHeight(Double.MAX_VALUE);
        for (int col = 0; col < 7; col++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(100.0 / 7.0);
            cc.setHgrow(Priority.ALWAYS);
            calendarGrid.getColumnConstraints().add(cc);
        }
        RowConstraints headRow = new RowConstraints();
        headRow.setMinHeight(24);
        calendarGrid.getRowConstraints().add(headRow);
        for (int row = 1; row <= 6; row++) {
            RowConstraints rc = new RowConstraints();
            rc.setMinHeight(72);
            rc.setVgrow(Priority.ALWAYS);
            calendarGrid.getRowConstraints().add(rc);
        }

        // The month grid may be taller than a small window, so it scrolls while the legend stays put.
        ScrollPane gridScroll = new ScrollPane(calendarGrid);
        gridScroll.setFitToWidth(true);
        gridScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // Let the six week rows share the full height when the window is tall; scroll when not.
        calendarGrid.minHeightProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                () -> Math.max(0, gridScroll.getViewportBounds().getHeight() - 12),
                gridScroll.viewportBoundsProperty()));
        VBox.setVgrow(gridScroll, Priority.ALWAYS);
        VBox gridWrap = new VBox(10, gridScroll, buildLegend());
        gridWrap.setMaxWidth(Double.MAX_VALUE);

        // ------------------------------------------------------------- details
        VBox detailsBox = new VBox(8);
        detailsBox.getStyleClass().add("card");
        detailsBox.setMinWidth(340);
        detailsBox.setPrefWidth(420);

        dayDetailsHeader = new Label("Day Schedule Details");
        dayDetailsHeader.getStyleClass().add("section-title");
        dayDetailsHeader.setWrapText(true);

        dayScheduleView = new DayScheduleView();
        dayScheduleView.setCompact(true);
        VBox.setVgrow(dayScheduleView, Priority.ALWAYS);
        detailsBox.getChildren().addAll(dayDetailsHeader, dayScheduleView);

        SplitPane split = new SplitPane(gridWrap, detailsBox);
        split.setDividerPositions(0.55);
        split.getStyleClass().add("calendar-split");
        SplitPane.setResizableWithParent(detailsBox, Boolean.FALSE);
        VBox.setVgrow(split, Priority.ALWAYS);

        root.getChildren().addAll(navBar, split);

        refreshCalendar();
        showDaySchedule(selectedDate);
        return root;
    }

    private HBox buildLegend() {
        HBox legend = new HBox();
        legend.getStyleClass().add("legend");
        legend.getChildren().addAll(
                legendItem("Exam day", "day-cell-exam"),
                legendItem("MST / EST period", "day-cell-window"),
                legendItem("Today", "day-cell-today"),
                legendItem("Selected", "day-cell-selected"));
        return legend;
    }

    private HBox legendItem(String text, String styleClass) {
        Region swatch = new Region();
        swatch.getStyleClass().addAll("legend-swatch", styleClass);
        Label label = new Label(text);
        label.getStyleClass().add("legend-text");
        HBox item = new HBox(6, swatch, label);
        item.getStyleClass().add("legend-item");
        item.setAlignment(Pos.CENTER_LEFT);
        return item;
    }

    public void refreshCalendar() {
        if (calendarGrid == null) {
            return;
        }
        calendarGrid.getChildren().clear();
        monthTitle.setText(currentYearMonth.getMonth().name() + " " + currentYearMonth.getYear());

        for (int col = 0; col < 7; col++) {
            Label dayHead = new Label(WEEKDAYS[col]);
            dayHead.getStyleClass().add("weekday-head");
            dayHead.setMaxWidth(Double.MAX_VALUE);
            dayHead.setAlignment(Pos.CENTER);
            calendarGrid.add(dayHead, col, 0);
        }

        LocalDate firstDay = currentYearMonth.atDay(1);
        int dayOfWeekOffset = firstDay.getDayOfWeek().getValue() - 1; // 0 = Monday
        int daysInMonth = currentYearMonth.lengthOfMonth();

        try {
            Semester active = semesterDAO.getActiveSemester();
            List<Exam> exams = (active != null) ? examDAO.findBySemester(active.getSemesterId()) : Collections.emptyList();
            Map<LocalDate, List<Exam>> examsByDate = new HashMap<>();
            Set<LocalDate> eveDates = new HashSet<>();

            for (Exam e : exams) {
                // A tentative date is only an assumption (e.g. the start of a rough window), not a
                // real sitting, so it must not paint an EXAM badge on a day the user has no exam.
                if (e.getExamDate() != null && !e.isExamDateTentative()) {
                    examsByDate.computeIfAbsent(e.getExamDate(), k -> new ArrayList<>()).add(e);
                    // EVE only applies to NORMAL exams (Plan §8, Fix 8a)
                    if ("NORMAL".equalsIgnoreCase(e.getExamType())) {
                        eveDates.add(e.getExamDate().minusDays(1));
                    }
                }
            }

            LocalDate monthStart = currentYearMonth.atDay(1);
            LocalDate monthEnd = currentYearMonth.atEndOfMonth();
            List<DailyLogEntry> monthLogs = dailyLogDAO.findByDateRange(monthStart, monthEnd);
            Map<LocalDate, Double> studyHoursByDate = new HashMap<>();
            Map<LocalDate, Integer> sessionCountByDate = new HashMap<>();
            for (DailyLogEntry log : monthLogs) {
                LocalDate d = log.getLogDate();
                String itype = log.getItemType();
                if (itype != null && (itype.startsWith("TOPIC") || itype.startsWith("PYQ") || itype.startsWith("SKILL"))) {
                    studyHoursByDate.merge(d, log.getDurationHours(), Double::sum);
                    sessionCountByDate.merge(d, 1, Integer::sum);
                }
            }

            Map<LocalDate, String> examWindowTypeByDate = new HashMap<>();
            if (active != null) {
                for (LocalDate d = monthStart; !d.isAfter(monthEnd); d = d.plusDays(1)) {
                    final LocalDate windowDate = d;
                    try {
                        examWindowService.examTypeOnDate(active.getSemesterId(), windowDate)
                                .ifPresent(type -> examWindowTypeByDate.put(windowDate, type));
                    } catch (SQLException ignored) {
                    }
                }
            }

            int row = 1;
            int col = dayOfWeekOffset;
            for (int dayNum = 1; dayNum <= daysInMonth; dayNum++) {
                LocalDate date = currentYearMonth.atDay(dayNum);
                VBox cell = createDayCell(date,
                        examsByDate.get(date),
                        eveDates.contains(date),
                        studyHoursByDate.getOrDefault(date, 0.0),
                        sessionCountByDate.getOrDefault(date, 0),
                        examWindowTypeByDate.get(date),
                        date.equals(selectedDate));
                calendarGrid.add(cell, col, row);

                col++;
                if (col > 6) {
                    col = 0;
                    row++;
                }
            }

            // Keep the last week rectangular with quiet placeholders.
            if (col != 0) {
                while (col <= 6) {
                    Region filler = new Region();
                    filler.getStyleClass().add("day-cell");
                    filler.setOpacity(0.25);
                    calendarGrid.add(filler, col, row);
                    col++;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private VBox createDayCell(LocalDate date, List<Exam> examsToday, boolean isEve,
                               double studyHours, int sessionCount,
                               String examWindowType, boolean isSelected) {
        boolean isToday = date.equals(LocalDate.now());
        boolean hasExam = examsToday != null && !examsToday.isEmpty();
        boolean isExamWindow = (examWindowType != null);

        VBox cell = new VBox(4);
        cell.getStyleClass().add("day-cell");
        cell.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        // Allow cells to shrink so all seven columns stay visible at the minimum window width.
        cell.setMinSize(24, 64);

        if (hasExam) {
            cell.getStyleClass().add("day-cell-exam");
        } else if (isExamWindow) {
            cell.getStyleClass().add("day-cell-window");
        }
        // Ordinary study days stay neutral: only exams and exam windows tint a cell.
        if (isToday) {
            cell.getStyleClass().add("day-cell-today");
        }
        if (isSelected) {
            cell.getStyleClass().add("day-cell-selected");
        }

        Label dayLbl = new Label(String.valueOf(date.getDayOfMonth()));
        dayLbl.getStyleClass().addAll("day-number", "tabular");
        HBox topRow = new HBox(6, dayLbl, UiKit.spacer());
        topRow.setAlignment(Pos.CENTER_LEFT);

        Node status;
        if (hasExam) {
            // EXAM is one of the few states that keep a filled chip.
            status = UiKit.chip("EXAM", UiKit.Kind.INFO);
        } else if (isExamWindow) {
            status = UiKit.status(UiKit.Kind.WARN, examWindowType + " period");
        } else if (isEve) {
            status = UiKit.status(UiKit.Kind.WARN, "Exam eve");
        } else {
            status = null;
        }

        cell.getChildren().add(topRow);
        if (status != null) {
            cell.getChildren().add(status);
        }

        if (hasExam) {
            Label subjects = new Label(summariseSubjects(examsToday));
            subjects.getStyleClass().add("day-subjects");
            subjects.setWrapText(true);
            cell.getChildren().add(subjects);
        } else if (studyHours > 0) {
            Label hours = new Label(String.format("%.1f h planned", studyHours));
            hours.getStyleClass().addAll("day-hours", "tabular");
            cell.getChildren().add(hours);
        }

        Tooltip.install(cell, new Tooltip(date.format(FULL_DATE) + " · " + describe(date, examsToday,
                isEve, studyHours, examWindowType)));

        cell.setOnMouseClicked(e -> selectDay(date));
        return cell;
    }

    private String summariseSubjects(List<Exam> exams) {
        List<String> subjects = new ArrayList<>();
        for (Exam e : exams) {
            if (e.getSubject() != null && !subjects.contains(e.getSubject())) {
                subjects.add(e.getSubject());
            }
        }
        if (subjects.isEmpty()) {
            return "";
        }
        if (subjects.size() <= 2) {
            return String.join(", ", subjects);
        }
        return subjects.get(0) + ", " + subjects.get(1) + " +" + (subjects.size() - 2);
    }

    private String describe(LocalDate date, List<Exam> exams, boolean isEve,
                            double studyHours, String examWindowType) {
        StringBuilder sb = new StringBuilder();
        if (exams != null && !exams.isEmpty()) {
            sb.append("Exam: ").append(summariseSubjects(exams));
        } else if (examWindowType != null) {
            sb.append(examWindowType).append(" exam period, no classes");
        } else if (isEve) {
            sb.append("Exam eve");
        } else {
            sb.append("Normal timetable day");
        }
        if (studyHours > 0) {
            sb.append(". ").append(String.format("%.1f h of study planned", studyHours));
        }
        return sb.toString();
    }

    private void selectDay(LocalDate date) {
        selectedDate = date;
        refreshCalendar();
        showDaySchedule(date);
    }

    private void showDaySchedule(LocalDate date) {
        String headerText = date.format(FULL_DATE);

        if (dayDetailsHeader != null) {
            // Label exam-period days: the weekly timetable is not followed on them.
            try {
                Semester active = semesterDAO.getActiveSemester();
                if (active != null) {
                    String examType = examWindowService.examTypeOnDate(active.getSemesterId(), date).orElse(null);
                    if (examType != null) {
                        headerText += "\n" + examType + " exam period, no classes";
                    }
                }
            } catch (SQLException ignored) {
            }
            dayDetailsHeader.setText(headerText);
        }
        if (dayScheduleView == null) {
            return;
        }

        try {
            Semester active = semesterDAO.getActiveSemester();
            if (active == null || dayScheduleService == null) {
                dayScheduleView.setRows(Collections.emptyList());
                return;
            }
            List<DayRow> rows = dayScheduleService.build(active.getSemesterId(), date);
            dayScheduleView.setRows(rows);
        } catch (SQLException ex) {
            ex.printStackTrace();
            dayScheduleView.setRows(Collections.emptyList());
        }
    }
}
