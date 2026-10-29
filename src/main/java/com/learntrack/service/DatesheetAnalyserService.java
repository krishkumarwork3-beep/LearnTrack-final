package com.learntrack.service;

import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.ExamTimelineDAO;
import com.learntrack.dao.SettingsDAO;
import com.learntrack.dao.SubjectCreditDAO;
import com.learntrack.dao.TimetableDAO;
import com.learntrack.model.*;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * Service to analyse datesheet text against user subjects, detect clashes/warnings,
 * and apply datesheet schedules to exams.
 */
public class DatesheetAnalyserService {

    private final ExamDAO examDAO;
    private final SubjectListService subjectListService;
    private final SettingsDAO settingsDAO;
    private final ExamTimelineService timelineService;
    private final SemesterReplanService replanService;

    public DatesheetAnalyserService() {
        this.examDAO = new ExamDAO();
        this.subjectListService = new SubjectListService(new TimetableDAO(), this.examDAO);
        this.settingsDAO = new SettingsDAO();
        this.timelineService = new ExamTimelineService();
        this.replanService = null;
    }

    public DatesheetAnalyserService(ExamDAO examDAO, SubjectListService subjectListService,
                                   SettingsDAO settingsDAO, ExamTimelineService timelineService,
                                   SemesterReplanService replanService) {
        this.examDAO = examDAO;
        this.subjectListService = subjectListService;
        this.settingsDAO = settingsDAO;
        this.timelineService = timelineService;
        this.replanService = replanService;
    }

    private static class SubjectMatch {
        final SubjectInfo subject;
        final boolean approximate;

        SubjectMatch(SubjectInfo subject, boolean approximate) {
            this.subject = subject;
            this.approximate = approximate;
        }
    }

    private SubjectMatch findSubjectMatch(String entryCode, Map<String, SubjectInfo> userSubjectMap) {
        if (entryCode == null || entryCode.isBlank()) return null;
        String codeUpper = entryCode.toUpperCase().trim();

        // 1. Exact match
        if (userSubjectMap.containsKey(codeUpper)) {
            return new SubjectMatch(userSubjectMap.get(codeUpper), false);
        }

        // 2. Approximate match differing by trailing batch letter (e.g. UES101A <-> UES101)
        String entryStripped = codeUpper.replaceAll("[A-Za-z]$", "");
        for (Map.Entry<String, SubjectInfo> e : userSubjectMap.entrySet()) {
            String userCode = e.getKey();
            String userStripped = userCode.replaceAll("[A-Za-z]$", "");

            if (entryStripped.equalsIgnoreCase(userCode) ||
                codeUpper.equalsIgnoreCase(userStripped) ||
                (!entryStripped.isEmpty() && entryStripped.equalsIgnoreCase(userStripped))) {
                return new SubjectMatch(e.getValue(), true);
            }
        }

        return null;
    }

    /**
     * Parse and analyse datesheet text for a semester against the user's subjects.
     */
    public DatesheetAnalysisResult analyse(int semesterId, String text) throws SQLException {
        return analyse(semesterId, text, null);
    }

    /**
     * Parse and analyse datesheet text, using {@code examTypeOverride} when the user has picked an
     * exam type explicitly. Without an override the type comes from the sheet code, and a sheet that
     * names neither MST nor EST keeps no type at all instead of silently pretending to be an MST.
     */
    public DatesheetAnalysisResult analyse(int semesterId, String text, String examTypeOverride) throws SQLException {
        ParsedDatesheet parsed = DatesheetParser.parse(text);

        List<SubjectInfo> userSubjects = subjectListService.buildSubjectList(semesterId);
        Map<String, SubjectInfo> userSubjectMap = new LinkedHashMap<>();
        for (SubjectInfo s : userSubjects) {
            if (s.getSubjectCode() != null && !s.getSubjectCode().isBlank()) {
                userSubjectMap.put(s.getSubjectCode().toUpperCase().trim(), s);
            }
        }

        String examType = examTypeOverride;
        if (examType == null || examType.isBlank()) {
            examType = parsed.getExamType();
        }
        if (examType != null) {
            examType = examType.trim().toUpperCase();
            if (examType.isBlank()) {
                examType = null;
            }
        }

        int durationMinutes = 120;
        try {
            if ("EST".equalsIgnoreCase(examType)) {
                durationMinutes = settingsDAO.getInt("est_exam_duration_minutes", 180);
            } else {
                durationMinutes = settingsDAO.getInt("mst_exam_duration_minutes", 120);
            }
        } catch (Exception ignored) {
        }

        List<DatesheetMatchRow> matchedRows = new ArrayList<>();
        Set<String> matchedSubjectCodes = new HashSet<>();
        List<String> warnings = new ArrayList<>();
        for (String w : parsed.getWarnings()) {
            if (w == null || w.isBlank()) continue;
            if (w.startsWith("Page ")) {
                continue; // Drop page-level ambiguity notices about other pages
            }
            if (w.startsWith("Course ")) {
                String rest = w.substring("Course ".length()).trim();
                String code = rest.split("\\s+")[0];
                if (findSubjectMatch(code, userSubjectMap) == null) {
                    continue;
                }
            } else if (w.startsWith("Duplicate course code ignored: ")) {
                String code = w.substring("Duplicate course code ignored: ".length()).trim();
                if (findSubjectMatch(code, userSubjectMap) == null) {
                    continue;
                }
            }
            warnings.add(w);
        }

        if (examType == null) {
            warnings.add("This datesheet does not name its exam type. Choose MST or EST before applying it.");
        }

        boolean userHasAmbiguousFromParsed = false;
        for (String ambCode : parsed.getAmbiguous().keySet()) {
            if (findSubjectMatch(ambCode, userSubjectMap) != null) {
                userHasAmbiguousFromParsed = true;
                break;
            }
        }

        if ("Column-block".equalsIgnoreCase(parsed.getFormatName()) && userHasAmbiguousFromParsed) {
            warnings.add("This text came out of the PDF with its columns separated. Dates for some courses may be uncertain; choose the correct slot in 'Needs Your Decision', or paste text that keeps each row together (for example from pdftotext -layout).");
        }

        // Group the sheet's entries by the user's subject. A subject the sheet prints under several
        // batch codes (UES101A / UES101B / UES101C) is a decision for the user, never a silent pick
        // of whichever code happened to be read last.
        Map<String, List<DatesheetEntry>> entriesBySubject = new LinkedHashMap<>();
        Map<String, SubjectMatch> matchBySubject = new LinkedHashMap<>();
        for (DatesheetEntry entry : parsed.getEntries()) {
            String code = entry.getCourseCode() != null ? entry.getCourseCode().toUpperCase().trim() : "";
            SubjectMatch match = findSubjectMatch(code, userSubjectMap);
            if (match == null) continue;
            String subjectKey = match.subject.getSubjectCode().toUpperCase(Locale.ROOT).trim();
            entriesBySubject.computeIfAbsent(subjectKey, k -> new ArrayList<>()).add(entry);
            matchBySubject.putIfAbsent(subjectKey, match);
        }

        Map<String, List<DatesheetEntry>> ambiguousUserSubjects = new LinkedHashMap<>();

        for (Map.Entry<String, List<DatesheetEntry>> subjectGroup : entriesBySubject.entrySet()) {
            SubjectInfo subjectInfo = matchBySubject.get(subjectGroup.getKey()).subject;
            List<DatesheetEntry> subjectEntries = subjectGroup.getValue();

            LinkedHashSet<String> sheetCodes = new LinkedHashSet<>();
            for (DatesheetEntry entry : subjectEntries) {
                sheetCodes.add(entry.getCourseCode().toUpperCase(Locale.ROOT).trim());
            }
            if (sheetCodes.size() > 1) {
                ambiguousUserSubjects.put(subjectGroup.getKey(), distinctSlots(subjectEntries));
                warnings.add("Datesheet lists " + String.join(", ", sheetCodes) + " for "
                        + subjectGroup.getKey() + "; choose the slot that applies to you in "
                        + "'Needs Your Decision'.");
                continue;
            }

            for (DatesheetEntry entry : subjectEntries) {
                SubjectMatch match = matchBySubject.get(subjectGroup.getKey());
                String code = entry.getCourseCode() != null
                        ? entry.getCourseCode().toUpperCase(Locale.ROOT).trim() : "";
                LocalTime start = entry.getStart();
                LocalTime end = entry.getEnd() != null
                        ? entry.getEnd()
                        : (start != null ? start.plusMinutes(durationMinutes) : null);

                String status = match.approximate ? "Approximate" : "Matched";
                if (match.approximate) {
                    warnings.add("Approximate match: Datesheet code " + entry.getCourseCode() +
                            " matched to subject " + subjectInfo.getSubjectCode());
                }

                DatesheetMatchRow row = new DatesheetMatchRow(
                        entry.getCourseCode(),
                        entry.getCourseName(),
                        subjectInfo.getDisplayName(),
                        examType,
                        entry.getDate(),
                        start,
                        end,
                        status
                );
                matchedRows.add(row);
                matchedSubjectCodes.add(subjectInfo.getSubjectCode().toUpperCase().trim());

                // Name discrepancy check
                if (isNameDifferent(subjectInfo.getDisplayName(), entry.getCourseName(), code)) {
                    warnings.add("Subject name discrepancy for " + entry.getCourseCode() +
                            ": stored as '" + subjectInfo.getDisplayName() +
                            "', datesheet says '" + entry.getCourseName() + "'");
                }

                // Date in the past check
                if (entry.getDate() != null && entry.getDate().isBefore(LocalDate.now())) {
                    warnings.add("Exam date is in the past: " + entry.getCourseCode() + " on " + entry.getDate());
                }
            }
        }

        // A clash is a real slot collision: two of the user's own exams overlap in time on the
        // same day. Two exams on the same day at different times are tight but not a clash, so
        // they stay out of this list.
        List<String> clashes = new ArrayList<>();
        for (int i = 0; i < matchedRows.size(); i++) {
            for (int j = i + 1; j < matchedRows.size(); j++) {
                DatesheetMatchRow a = matchedRows.get(i);
                DatesheetMatchRow b = matchedRows.get(j);
                if (a.getExamDate() == null || b.getExamDate() == null
                        || !a.getExamDate().equals(b.getExamDate())
                        || a.getStartTime() == null || b.getStartTime() == null) {
                    continue;
                }
                // Two sittings of the same subject are not a clash between subjects.
                String aSubject = a.getUserSubjectName() != null ? a.getUserSubjectName() : a.getCourseCode();
                String bSubject = b.getUserSubjectName() != null ? b.getUserSubjectName() : b.getCourseCode();
                if (aSubject != null && aSubject.equalsIgnoreCase(bSubject)) {
                    continue;
                }
                LocalTime aEnd = a.getEndTime() != null
                        ? a.getEndTime() : a.getStartTime().plusMinutes(durationMinutes);
                LocalTime bEnd = b.getEndTime() != null
                        ? b.getEndTime() : b.getStartTime().plusMinutes(durationMinutes);
                if (a.getStartTime().isBefore(bEnd) && b.getStartTime().isBefore(aEnd)) {
                    String message = "Clash: " + a.getCourseCode() + " ("
                            + a.getStartTime() + " to " + aEnd + ") and " + b.getCourseCode() + " ("
                            + b.getStartTime() + " to " + bEnd + ") overlap on "
                            + a.getExamDate() + ".";
                    clashes.add(message);
                    warnings.add(message);
                }
            }
        }

        // Courses the parser could not place: offer their candidate slots, merged with the batch
        // decisions collected above so that one subject is decided only once.
        for (Map.Entry<String, List<DatesheetEntry>> amb : parsed.getAmbiguous().entrySet()) {
            SubjectMatch m = findSubjectMatch(amb.getKey(), userSubjectMap);
            if (m == null) continue;
            String subjectKey = m.subject.getSubjectCode().toUpperCase(Locale.ROOT).trim();
            List<DatesheetEntry> merged = new ArrayList<>(
                    ambiguousUserSubjects.getOrDefault(subjectKey, Collections.emptyList()));
            merged.addAll(amb.getValue());
            ambiguousUserSubjects.put(subjectKey, distinctSlots(merged));
        }

        // A subject that is waiting for a decision has been found on the sheet: not unmatched.
        matchedSubjectCodes.addAll(ambiguousUserSubjects.keySet());

        // List unmatched user subjects
        List<SubjectInfo> unmatchedSubjects = new ArrayList<>();
        for (SubjectInfo s : userSubjects) {
            String code = s.getSubjectCode() != null ? s.getSubjectCode().toUpperCase().trim() : "";
            if (!matchedSubjectCodes.contains(code)) {
                unmatchedSubjects.add(s);
            }
        }

        return new DatesheetAnalysisResult(
                parsed.getSheetCode(),
                examType,
                matchedRows,
                unmatchedSubjects,
                warnings,
                parsed.getSkippedLines(),
                parsed.getFormatName(),
                parsed.getTotalUniqueCourses(),
                parsed.getFirstDate(),
                parsed.getLastDate(),
                ambiguousUserSubjects,
                parsed.getEntries().size(),
                parsed.getAmbiguous().size(),
                clashes
        );
    }

    /**
     * Apply matched datesheet rows to exam records, recompute the timeline window,
     * and trigger a replan.
     */
    public void apply(int semesterId, List<DatesheetMatchRow> rows) throws SQLException {
        apply(semesterId, rows, null, null, null, null);
    }

    /**
     * Applies matched rows. {@code sheetStart}/{@code sheetEnd} are kept for callers that still pass
     * the sheet's range; the window itself always comes from the user's dated exams.
     */
    public void apply(int semesterId, List<DatesheetMatchRow> rows, LocalDate sheetStart, LocalDate sheetEnd) throws SQLException {
        apply(semesterId, rows, sheetStart, sheetEnd, null, null);
    }

    /** Applies matched rows and the user's decisions; the window comes from the user's exams. */
    public void apply(int semesterId, List<DatesheetMatchRow> rows,
                      Map<String, DatesheetEntry> resolutions, Set<String> skipped,
                      String chosenExamType) throws SQLException {
        apply(semesterId, rows, null, null, resolutions, skipped, chosenExamType);
    }

    /**
     * Apply matched datesheet rows with ambiguous resolutions.
     * @param resolutions Map of subject code to chosen DatesheetEntry for ambiguous courses
     * @param skipped Set of subject codes to skip (ambiguous courses user chose not to apply)
     * @param sheetStart ignored for the window (kept for source compatibility)
     * @param sheetEnd ignored for the window (kept for source compatibility)
     */
    public void apply(int semesterId, List<DatesheetMatchRow> rows, LocalDate sheetStart, LocalDate sheetEnd,
                     Map<String, DatesheetEntry> resolutions, Set<String> skipped) throws SQLException {
        apply(semesterId, rows, sheetStart, sheetEnd, resolutions, skipped, null);
    }

    /**
     * Applies matched rows and the user's decisions. {@code chosenExamType} is the type picked in the
     * UI; it is required when the sheet produced decisions but no matched rows (every subject of the
     * user was printed under several batch codes, for example).
     *
     * <p>The stored exam window is always the user's own span: the first to last date of the exams
     * this apply dated (decisions included), never the whole sheet's range. The sheet lists every
     * batch, so its range would mark days without an exam of the user as "exam period" days.
     */
    public void apply(int semesterId, List<DatesheetMatchRow> rows, LocalDate sheetStart, LocalDate sheetEnd,
                     Map<String, DatesheetEntry> resolutions, Set<String> skipped,
                     String chosenExamType) throws SQLException {
        apply(semesterId, rows, sheetStart, sheetEnd, resolutions, skipped, chosenExamType, null);
    }

    /**
     * As above, plus {@code notOnSheet}: the subject codes the analysis found nowhere on the sheet.
     * Their exams of this batch lose any old date ("Date TBD"), because a date that is not on the
     * official sheet must neither show as the exam date nor stretch the exam period. Ambiguous or
     * skipped subjects are on the sheet, so they are never in this set and keep their dates.
     */
    public void apply(int semesterId, List<DatesheetMatchRow> rows, LocalDate sheetStart, LocalDate sheetEnd,
                     Map<String, DatesheetEntry> resolutions, Set<String> skipped,
                     String chosenExamType, Set<String> notOnSheet) throws SQLException {
        boolean hasResolutions = resolutions != null && !resolutions.isEmpty();
        if ((rows == null || rows.isEmpty()) && !hasResolutions) {
            return;
        }

        String targetExamType = chosenExamType;
        if (targetExamType == null || targetExamType.isBlank()) {
            targetExamType = rows != null && !rows.isEmpty() ? rows.get(0).getExamType() : null;
        }
        if (targetExamType == null || targetExamType.isBlank()) {
            targetExamType = "MST";
        }

        // Apply resolutions for ambiguous courses first
        List<DatesheetMatchRow> allRows = rows != null ? new ArrayList<>(rows) : new ArrayList<>();
        if (resolutions != null && !resolutions.isEmpty()) {
            for (Map.Entry<String, DatesheetEntry> resolution : resolutions.entrySet()) {
                String code = resolution.getKey();
                DatesheetEntry entry = resolution.getValue();

                // A course the user chose to skip must never be applied, even if a candidate is supplied.
                if (skipped != null && skipped.stream().anyMatch(s -> s != null && s.equalsIgnoreCase(code))) {
                    continue;
                }
                if (entry == null) {
                    continue;
                }

                // Check if this code is already in rows (it shouldn't be if it was ambiguous)
                boolean alreadyInRows = allRows.stream().anyMatch(r -> 
                    r.getCourseCode() != null && r.getCourseCode().equalsIgnoreCase(code));
                
                if (!alreadyInRows) {
                    // Create a match row from the resolved entry
                    int durationMinutes = 120;
                    try {
                        if ("EST".equalsIgnoreCase(targetExamType)) {
                            durationMinutes = settingsDAO.getInt("est_exam_duration_minutes", 180);
                        } else {
                            durationMinutes = settingsDAO.getInt("mst_exam_duration_minutes", 120);
                        }
                    } catch (Exception ignored) {
                    }
                    
                    LocalTime start = entry.getStart();
                    LocalTime end = entry.getEnd() != null
                            ? entry.getEnd()
                            : (start != null ? start.plusMinutes(durationMinutes) : null);
                    
                    // The resolution key is the user's own subject code: that is the exam to
                    // update. The sheet code it was printed under stays visible as the sheet name.
                    DatesheetMatchRow resolvedRow = new DatesheetMatchRow(
                        code,
                        entry.getCourseName() != null && !entry.getCourseName().isBlank()
                                ? entry.getCourseName() : entry.getCourseCode(),
                        code,
                        targetExamType,
                        entry.getDate(),
                        start,
                        end,
                        "Resolved"
                    );
                    allRows.add(resolvedRow);
                }
            }
        }

        List<Exam> existingExams = examDAO.findBySemester(semesterId);

        for (DatesheetMatchRow row : allRows) {
            String rowExamType = row.getExamType() != null ? row.getExamType() : targetExamType;
            if (!rowExamType.equalsIgnoreCase(targetExamType)) {
                continue;
            }

            String rowCode = row.getCourseCode() != null ? row.getCourseCode().trim() : "";

            Optional<Exam> existingOpt = existingExams.stream()
                    .filter(e -> rowExamType.equalsIgnoreCase(e.getExamType()))
                    .filter(e -> (e.getSubjectCode() != null && e.getSubjectCode().equalsIgnoreCase(rowCode))
                            || (e.getSubject() != null && e.getSubject().equalsIgnoreCase(row.getUserSubjectName())))
                    .findFirst();

            if (existingOpt.isPresent()) {
                Exam exam = existingOpt.get();
                exam.setExamDate(row.getExamDate());
                exam.setExamStartTime(row.getStartTime());
                exam.setExamEndTime(row.getEndTime());
                exam.setExamDateTentative(false);
                examDAO.update(exam);
            } else {
                double credits = 3.0;
                try {
                    credits = new SubjectCreditDAO().getExplicitSubjectCredits(semesterId, rowCode);
                    if (credits <= 0) credits = 3.0;
                } catch (Exception ignored) {
                }

                double pyqHours = 2.0;
                try {
                    pyqHours = settingsDAO.getDouble("pyq_practice_hours", 2.0);
                } catch (Exception ignored) {
                }

                String displayName = row.getUserSubjectName() != null && !row.getUserSubjectName().isBlank()
                        ? row.getUserSubjectName()
                        : (row.getDatesheetCourseName() != null ? row.getDatesheetCourseName() : rowCode);

                Exam newExam = new Exam(
                        null,
                        semesterId,
                        displayName,
                        rowCode,
                        rowExamType,
                        null,
                        credits,
                        20.0,
                        row.getExamDate(),
                        false, // non-tentative
                        row.getStartTime(),
                        row.getEndTime(),
                        30,
                        null,
                        null,
                        pyqHours
                );
                examDAO.insert(newExam);
            }
        }

        // Exams of this batch that the sheet does not list lose their old date ("Date TBD"): a stale
        // "1 Dec" or "31 Dec" is not on the official sheet, so it must neither show as the exam date
        // nor stretch the exam period (and cancel classes) across the whole month.
        clearDatesNotOnSheet(semesterId, targetExamType, notOnSheet);

        ExamTimelineDAO timelineDAO = new ExamTimelineDAO();
        timelineDAO.setDatesheetAnnounced(semesterId, targetExamType, true);
        applyWindow(semesterId, targetExamType, timelineDAO);

        // Replan
        if (replanService != null) {
            replanService.regenerateSemesterPlan(semesterId, LocalDate.now());
        }
    }

    private void clearDatesNotOnSheet(int semesterId, String targetExamType, Set<String> notOnSheet)
            throws SQLException {
        if (notOnSheet == null || notOnSheet.isEmpty()) return;
        Set<String> codes = new HashSet<>();
        for (String c : notOnSheet) {
            if (c != null) codes.add(c.trim().toUpperCase());
        }
        for (Exam e : examDAO.findBySemester(semesterId)) {
            if (!targetExamType.equalsIgnoreCase(e.getExamType()) || e.getExamDate() == null) continue;
            if (e.getSubjectCode() != null && codes.contains(e.getSubjectCode().trim().toUpperCase())) {
                e.setExamDate(null);
                e.setExamDateTentative(false);
                examDAO.update(e);
            }
        }
    }

    /**
     * The window is the user's exam span: min/max of the exams that now carry an exact date.
     * A previous apply's window is replaced, not merged, so days outside the user's exams go
     * back to the weekly timetable.
     */
    private void applyWindow(int semesterId, String targetExamType, ExamTimelineDAO timelineDAO) throws SQLException {
        LocalDate[] userWindow = timelineService.getDerivedWindow(semesterId, targetExamType);
        if (userWindow != null) {
            timelineDAO.setWindow(semesterId, targetExamType, userWindow[0], userWindow[1]);
            // Keep every exam of this type on the same window (stale window fields confuse the planner).
            for (Exam e : examDAO.findBySemester(semesterId)) {
                if (targetExamType.equalsIgnoreCase(e.getExamType())) {
                    e.setWindowStartDate(userWindow[0]);
                    e.setWindowEndDate(userWindow[1]);
                    examDAO.update(e);
                }
            }
        } else {
            timelineService.recomputeWindowFromDatesheet(semesterId, targetExamType);
        }
    }

    /** Candidate slots deduplicated by date and time, keeping the first sheet code that offered one. */
    private List<DatesheetEntry> distinctSlots(List<DatesheetEntry> candidates) {
        List<DatesheetEntry> distinct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (candidates == null) {
            return distinct;
        }
        for (DatesheetEntry candidate : candidates) {
            if (candidate == null) continue;
            String key = candidate.getDate() + "|" + candidate.getStart();
            if (seen.add(key)) {
                distinct.add(candidate);
            }
        }
        return distinct;
    }

    private boolean isNameDifferent(String storedName, String datesheetName, String courseCode) {
        if (storedName == null || datesheetName == null) return false;
        String s = storedName.trim();
        String d = datesheetName.trim();
        if (s.isEmpty() || d.isEmpty()) return false;
        if (s.equalsIgnoreCase(d)) return false;
        if (s.equalsIgnoreCase(courseCode)) return false;

        String sNorm = s.replaceAll("[^A-Za-z0-9\\s]", " ").toLowerCase();
        String dNorm = d.replaceAll("[^A-Za-z0-9\\s]", " ").toLowerCase();

        if (sNorm.contains(dNorm) || dNorm.contains(sNorm)) return false;

        Set<String> sWords = new HashSet<>(Arrays.asList(sNorm.split("\\s+")));
        Set<String> dWords = new HashSet<>(Arrays.asList(dNorm.split("\\s+")));

        sWords.removeIf(w -> w.length() < 3 || w.equals("and") || w.equals("the") || w.equals("for"));
        dWords.removeIf(w -> w.length() < 3 || w.equals("and") || w.equals("the") || w.equals("for"));

        if (sWords.isEmpty() || dWords.isEmpty()) return false;

        for (String w : sWords) {
            if (dWords.contains(w)) return false;
        }

        return true;
    }
}
