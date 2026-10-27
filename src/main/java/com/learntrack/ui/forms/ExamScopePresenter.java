package com.learntrack.ui.forms;

import com.learntrack.model.SyllabusSubtopic;
import com.learntrack.model.Topic;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Helper class for exam scope presentation logic.
 * Handles grouping, filtering, sorting, and status derivation.
 */
public class ExamScopePresenter {

    /**
     * Data class for a unit with its subtopics and progress.
     */
    public static class UnitScope {
        private final int unitId;
        private final String unitName;
        private final int totalSubtopics;
        private final List<SubtopicScope> selectedSubtopics;

        public UnitScope(int unitId, String unitName, int totalSubtopics, List<SubtopicScope> selectedSubtopics) {
            this.unitId = unitId;
            this.unitName = unitName;
            this.totalSubtopics = totalSubtopics;
            this.selectedSubtopics = selectedSubtopics;
        }

        public int getUnitId() { return unitId; }
        public String getUnitName() { return unitName; }
        public int getTotalSubtopics() { return totalSubtopics; }
        public int getSelectedCount() { return selectedSubtopics.size(); }
        public List<SubtopicScope> getSelectedSubtopics() { return selectedSubtopics; }

        /**
         * Average progress across all selected subtopics in this unit.
         * Progress = 1 - (remaining / needed) for each linked topic, 0 if no topic exists.
         */
        public double getProgress() {
            if (selectedSubtopics.isEmpty()) return 0.0;
            double sum = 0.0;
            for (SubtopicScope sub : selectedSubtopics) {
                sum += sub.getProgress();
            }
            return sum / selectedSubtopics.size();
        }
    }

    /**
     * Data class for a subtopic with its linked topic status.
     */
    public static class SubtopicScope {
        private final int subtopicId;
        private final String name;
        private final int difficulty;
        private final double hoursToCover;
        private final double hoursToRevise;
        private final Topic linkedTopic; // null if no topic exists

        public SubtopicScope(int subtopicId, String name, int difficulty, Topic linkedTopic) {
            this(subtopicId, name, difficulty, 1.0, 0.5, linkedTopic);
        }

        public SubtopicScope(int subtopicId, String name, int difficulty, double hoursToCover, double hoursToRevise, Topic linkedTopic) {
            this.subtopicId = subtopicId;
            this.name = name;
            this.difficulty = difficulty;
            this.hoursToCover = hoursToCover;
            this.hoursToRevise = hoursToRevise;
            this.linkedTopic = linkedTopic;
        }

        public int getSubtopicId() { return subtopicId; }
        public String getName() { return name; }
        public int getDifficulty() { return difficulty; }
        public double getHoursToCover() { return hoursToCover; }
        public double getHoursToRevise() { return hoursToRevise; }
        public Topic getLinkedTopic() { return linkedTopic; }

        /**
         * Get status label based on linked topic phase and remaining hours.
         */
        public String getStatusLabel() {
            if (linkedTopic == null) {
                return "Not started";
            }
            String phase = linkedTopic.getPhase();
            if ("REVISION".equalsIgnoreCase(phase)) {
                return linkedTopic.getRevisionRemainingHours() > 0.01 ? "Revising" : "Done";
            } else { // LEARNING
                return linkedTopic.getRemainingHours() > 0.01 ? "Learning" : "Done";
            }
        }

        /**
         * Get progress: 1 - (remaining / needed), 0 if no topic.
         */
        public double getProgress() {
            if (linkedTopic == null) return 0.0;
            if ("REVISION".equalsIgnoreCase(linkedTopic.getPhase())) {
                if (linkedTopic.getRevisionHoursNeeded() < 0.01) return 1.0;
                return 1.0 - (linkedTopic.getRevisionRemainingHours() / linkedTopic.getRevisionHoursNeeded());
            } else {
                if (linkedTopic.getHoursNeeded() < 0.01) return 1.0;
                return 1.0 - (linkedTopic.getRemainingHours() / linkedTopic.getHoursNeeded());
            }
        }

        /**
         * Get status category for filtering.
         */
        public StatusCategory getStatusCategory() {
            if (linkedTopic == null) return StatusCategory.NOT_STARTED;
            String status = getStatusLabel();
            if ("Done".equals(status)) return StatusCategory.DONE;
            if ("Not started".equals(status)) return StatusCategory.NOT_STARTED;
            return StatusCategory.IN_PROGRESS;
        }
    }

    public enum StatusCategory {
        ALL, NOT_STARTED, IN_PROGRESS, DONE
    }

    public enum SortMode {
        SYLLABUS_ORDER, HARDEST_FIRST, MOST_HOURS_FIRST
    }

    /**
     * Group selected subtopics by unit.
     */
    public List<UnitScope> groupByUnit(List<SyllabusSubtopic> selectedSubtopics, 
                                       Map<Integer, String> unitNames,
                                       Map<Integer, Integer> unitTotalCounts,
                                       Map<Integer, Topic> subtopicToTopic) {
        Map<Integer, List<SubtopicScope>> byUnit = new HashMap<>();

        for (SyllabusSubtopic subtopic : selectedSubtopics) {
            int unitId = subtopic.getUnitId();
            
            Topic linkedTopic = subtopicToTopic.get(subtopic.getSubtopicId());
            SubtopicScope scope = new SubtopicScope(
                subtopic.getSubtopicId(),
                subtopic.getName(),
                subtopic.getDifficulty(),
                subtopic.getHoursToCover(),
                subtopic.getHoursToRevise(),
                linkedTopic
            );
            
            byUnit.computeIfAbsent(unitId, k -> new ArrayList<>()).add(scope);
        }

        List<UnitScope> units = new ArrayList<>();
        for (Map.Entry<Integer, List<SubtopicScope>> entry : byUnit.entrySet()) {
            int unitId = entry.getKey();
            String unitName = unitNames.getOrDefault(unitId, "Unit " + unitId);
            int totalCount = unitTotalCounts.getOrDefault(unitId, entry.getValue().size());
            units.add(new UnitScope(unitId, unitName, totalCount, entry.getValue()));
        }

        // Sort units by ID (syllabus order)
        units.sort(Comparator.comparingInt(UnitScope::getUnitId));
        return units;
    }

    /**
     * Filter subtopics by status category.
     */
    public List<SubtopicScope> filter(List<SubtopicScope> subtopics, StatusCategory category) {
        if (category == StatusCategory.ALL) return new ArrayList<>(subtopics);
        return subtopics.stream()
                .filter(s -> s.getStatusCategory() == category)
                .collect(Collectors.toList());
    }

    /**
     * Sort subtopics by mode.
     * Note: HARDEST_FIRST sorts by hours to cover (same as MOST_HOURS_FIRST) because
     * topic difficulty is stored as neutral 1 in planning; the syllabus difficulty
     * field is display-only and must not drive scheduling order.
     */
    public List<SubtopicScope> sort(List<SubtopicScope> subtopics, SortMode mode) {
        List<SubtopicScope> sorted = new ArrayList<>(subtopics);
        if (mode == SortMode.HARDEST_FIRST) {
            sorted.sort(Comparator.comparingDouble(SubtopicScope::getHoursToCover).reversed());
        } else if (mode == SortMode.MOST_HOURS_FIRST) {
            sorted.sort(Comparator.comparingDouble(SubtopicScope::getHoursToCover).reversed());
        }
        // SYLLABUS_ORDER: keep as-is (already in subtopic_id order)
        return sorted;
    }

    /**
     * Calculate total hours needed across all selected subtopics.
     */
    public double calculateTotalHours(List<SyllabusSubtopic> selectedSubtopics,
                                      Map<Integer, Topic> subtopicToTopic) {
        double total = 0.0;
        for (SyllabusSubtopic subtopic : selectedSubtopics) {
            Topic topic = subtopicToTopic.get(subtopic.getSubtopicId());
            if (topic != null) {
                total += topic.getHoursNeeded();
            }
        }
        return total;
    }

    /**
     * Get difficulty color class.
     */
    public String getDifficultyClass(int difficulty) {
        if (difficulty <= 3) return "diff-easy";
        if (difficulty <= 7) return "diff-mid";
        return "diff-hard";
    }
}
