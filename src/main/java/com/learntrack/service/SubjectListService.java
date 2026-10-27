package com.learntrack.service;

import com.learntrack.dao.ExamDAO;
import com.learntrack.dao.KeptSubjectDAO;
import com.learntrack.dao.SubjectNameDAO;
import com.learntrack.dao.TimetableDAO;
import com.learntrack.model.Exam;
import com.learntrack.model.SubjectInfo;
import com.learntrack.model.WeeklyTimetableEntry;

import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service to build the unified subject list for the active semester.
 * No JavaFX imports - pure service layer.
 */
public class SubjectListService {

    private static final Pattern LABEL_PATTERN = Pattern.compile("^(.*?)\\s+(lecture|tutorial|practical|lab|class)$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> ACADEMIC_TYPES = Set.of("CLASS", "TUTORIAL", "LAB");

    private final TimetableDAO timetableDAO;
    private final ExamDAO examDAO;

    public SubjectListService(TimetableDAO timetableDAO, ExamDAO examDAO) {
        this.timetableDAO = timetableDAO;
        this.examDAO = examDAO;
    }

    /**
     * Build the complete subject list for a semester by merging:
     * - Timetable subjects (CLASS/TUTORIAL/LAB entries with subject_code)
     * - Kept subjects (from kept_subjects table)
     * - Exam subjects (from exams table)
     * 
     * De-duplicates by subject code and resolves display names from subject_names.
     */
    public List<SubjectInfo> buildSubjectList(int semesterId) throws SQLException {
        Map<String, SubjectInfo> subjectMap = new LinkedHashMap<>();

        // Get display name mappings
        Map<String, String> subjectNames = SubjectNameDAO.findAllAsMap(semesterId);
        Map<String, String> keptSubjects = KeptSubjectDAO.findAllAsMap(semesterId);

        // 1. Add timetable subjects
        List<WeeklyTimetableEntry> entries = timetableDAO.findBySemester(semesterId);
        Set<String> timetableSubjectCodes = new HashSet<>();

        for (WeeklyTimetableEntry entry : entries) {
            String type = entry.getActivityType();
            if (type != null && ACADEMIC_TYPES.contains(type.toUpperCase())) {
                String subjectCode = entry.getSubjectCode();
                
                // If subject_code is null, try to extract from label
                if (subjectCode == null || subjectCode.trim().isEmpty()) {
                    subjectCode = extractSubjectCodeFromLabel(entry.getLabel());
                }

                if (subjectCode != null && !subjectCode.trim().isEmpty()) {
                    subjectCode = subjectCode.toUpperCase().trim();
                    timetableSubjectCodes.add(subjectCode);
                    
                    if (!subjectMap.containsKey(subjectCode)) {
                        String displayName = resolveDisplayName(subjectCode, subjectNames, keptSubjects, entry.getLabel());
                        subjectMap.put(subjectCode, new SubjectInfo(subjectCode, displayName, true, false, false));
                    }
                }
            }
        }

        // 2. Add kept subjects
        for (Map.Entry<String, String> kept : keptSubjects.entrySet()) {
            String subjectCode = kept.getKey().toUpperCase().trim();
            if (!subjectMap.containsKey(subjectCode)) {
                String displayName = kept.getValue();
                if (displayName == null || displayName.trim().isEmpty()) {
                    displayName = subjectCode;
                }
                subjectMap.put(subjectCode, new SubjectInfo(subjectCode, displayName, false, true, false));
            } else {
                // Subject is in timetable AND kept list - mark as kept for study
                subjectMap.get(subjectCode).setKeptForStudy(true);
            }
        }

        // 3. Add exam subjects
        List<Exam> exams = examDAO.findBySemester(semesterId);
        for (Exam exam : exams) {
            String subjectCode = exam.getSubjectCode();
            if (subjectCode == null || subjectCode.trim().isEmpty()) {
                subjectCode = exam.getSubject();
            }
            if (subjectCode != null && !subjectCode.trim().isEmpty()) {
                subjectCode = subjectCode.toUpperCase().trim();
                
                if (!subjectMap.containsKey(subjectCode)) {
                    String displayName = resolveDisplayName(subjectCode, subjectNames, keptSubjects, null);
                    subjectMap.put(subjectCode, new SubjectInfo(subjectCode, displayName, false, false, true));
                } else {
                    subjectMap.get(subjectCode).setHasExam(true);
                }
            }
        }

        // 4. Attach credits to each subject
        com.learntrack.dao.SubjectCreditDAO creditDAO = new com.learntrack.dao.SubjectCreditDAO();
        Map<String, Double> allCredits = creditDAO.getAllCredits(semesterId);
        for (SubjectInfo info : subjectMap.values()) {
            Double cr = allCredits.get(info.getSubjectCode().toUpperCase());
            if (cr != null && cr > 0) {
                info.setCredits(cr);
            } else {
                info.setCredits(creditDAO.getCredits(semesterId, info.getSubjectCode()));
            }
        }

        return new ArrayList<>(subjectMap.values());
    }

    /**
     * Extract subject code from label by removing common suffixes.
     */
    private String extractSubjectCodeFromLabel(String label) {
        if (label == null || label.trim().isEmpty()) {
            return null;
        }

        // Try pattern matching first
        Matcher m = LABEL_PATTERN.matcher(label.trim());
        if (m.matches()) {
            return m.group(1).trim();
        }

        // Fallback: return the label as-is
        return label.trim();
    }

    /**
     * Resolve display name priority: subject_names > kept_subjects > label extraction > subject code.
     */
    private String resolveDisplayName(String subjectCode, Map<String, String> subjectNames, 
                                      Map<String, String> keptSubjects, String label) {
        // Priority 1: subject_names table
        if (subjectNames.containsKey(subjectCode)) {
            String name = subjectNames.get(subjectCode);
            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
        }

        // Priority 2: kept_subjects display_name
        if (keptSubjects.containsKey(subjectCode)) {
            String name = keptSubjects.get(subjectCode);
            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
        }

        // Priority 3: extract from label
        if (label != null && !label.trim().isEmpty()) {
            String extracted = extractSubjectCodeFromLabel(label);
            if (extracted != null && !extracted.equalsIgnoreCase(subjectCode)) {
                return extracted;
            }
        }

        // Priority 4: Use subject code itself
        return subjectCode;
    }
}
