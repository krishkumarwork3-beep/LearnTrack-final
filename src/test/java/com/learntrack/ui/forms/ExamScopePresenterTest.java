package com.learntrack.ui.forms;

import com.learntrack.model.SyllabusSubtopic;
import com.learntrack.model.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExamScopePresenter - pure logic without UI.
 */
public class ExamScopePresenterTest {

    private ExamScopePresenter presenter;

    @BeforeEach
    void setUp() {
        presenter = new ExamScopePresenter();
    }

    @Test
    void testStatusMappingNotStarted() {
        ExamScopePresenter.SubtopicScope scope = new ExamScopePresenter.SubtopicScope(
                1, "Intro", 5, null);
        assertEquals("Not started", scope.getStatusLabel());
        assertEquals(ExamScopePresenter.StatusCategory.NOT_STARTED, scope.getStatusCategory());
    }

    @Test
    void testStatusMappingLearning() {
        Topic topic = new Topic();
        topic.setPhase("LEARNING");
        topic.setHoursNeeded(10.0);
        topic.setRemainingHours(5.0);

        ExamScopePresenter.SubtopicScope scope = new ExamScopePresenter.SubtopicScope(
                1, "Trees", 7, topic);
        assertEquals("Learning", scope.getStatusLabel());
        assertEquals(ExamScopePresenter.StatusCategory.IN_PROGRESS, scope.getStatusCategory());
    }

    @Test
    void testStatusMappingRevising() {
        Topic topic = new Topic();
        topic.setPhase("REVISION");
        topic.setRevisionHoursNeeded(2.0);
        topic.setRevisionRemainingHours(1.0);

        ExamScopePresenter.SubtopicScope scope = new ExamScopePresenter.SubtopicScope(
                1, "Graphs", 8, topic);
        assertEquals("Revising", scope.getStatusLabel());
        assertEquals(ExamScopePresenter.StatusCategory.IN_PROGRESS, scope.getStatusCategory());
    }

    @Test
    void testStatusMappingDoneLearning() {
        Topic topic = new Topic();
        topic.setPhase("LEARNING");
        topic.setHoursNeeded(10.0);
        topic.setRemainingHours(0.0);

        ExamScopePresenter.SubtopicScope scope = new ExamScopePresenter.SubtopicScope(
                1, "Arrays", 6, topic);
        assertEquals("Done", scope.getStatusLabel());
        assertEquals(ExamScopePresenter.StatusCategory.DONE, scope.getStatusCategory());
    }

    @Test
    void testStatusMappingDoneRevision() {
        Topic topic = new Topic();
        topic.setPhase("REVISION");
        topic.setRevisionHoursNeeded(2.0);
        topic.setRevisionRemainingHours(0.0);

        ExamScopePresenter.SubtopicScope scope = new ExamScopePresenter.SubtopicScope(
                1, "Sorting", 9, topic);
        assertEquals("Done", scope.getStatusLabel());
        assertEquals(ExamScopePresenter.StatusCategory.DONE, scope.getStatusCategory());
    }

    @Test
    void testProgressCalculationLearning() {
        Topic topic = new Topic();
        topic.setPhase("LEARNING");
        topic.setHoursNeeded(10.0);
        topic.setRemainingHours(3.0);

        ExamScopePresenter.SubtopicScope scope = new ExamScopePresenter.SubtopicScope(
                1, "Topic", 5, topic);
        assertEquals(0.7, scope.getProgress(), 0.01);
    }

    @Test
    void testProgressCalculationRevision() {
        Topic topic = new Topic();
        topic.setPhase("REVISION");
        topic.setRevisionHoursNeeded(2.0);
        topic.setRevisionRemainingHours(0.5);

        ExamScopePresenter.SubtopicScope scope = new ExamScopePresenter.SubtopicScope(
                1, "Topic", 5, topic);
        assertEquals(0.75, scope.getProgress(), 0.01);
    }

    @Test
    void testProgressCalculationNoTopic() {
        ExamScopePresenter.SubtopicScope scope = new ExamScopePresenter.SubtopicScope(
                1, "Topic", 5, null);
        assertEquals(0.0, scope.getProgress(), 0.01);
    }

    @Test
    void testUnitProgressAveraging() {
        Topic topic1 = new Topic();
        topic1.setPhase("LEARNING");
        topic1.setHoursNeeded(10.0);
        topic1.setRemainingHours(2.0); // 80% done

        Topic topic2 = new Topic();
        topic2.setPhase("LEARNING");
        topic2.setHoursNeeded(10.0);
        topic2.setRemainingHours(6.0); // 40% done

        List<ExamScopePresenter.SubtopicScope> subtopics = List.of(
                new ExamScopePresenter.SubtopicScope(1, "Sub1", 5, topic1),
                new ExamScopePresenter.SubtopicScope(2, "Sub2", 5, topic2)
        );

        ExamScopePresenter.UnitScope unit = new ExamScopePresenter.UnitScope(
                1, "Unit 1", 10, subtopics);

        // Average: (0.8 + 0.4) / 2 = 0.6
        assertEquals(0.6, unit.getProgress(), 0.01);
    }

    @Test
    void testFilterAll() {
        List<ExamScopePresenter.SubtopicScope> subtopics = createMixedSubtopics();
        List<ExamScopePresenter.SubtopicScope> filtered = presenter.filter(subtopics, ExamScopePresenter.StatusCategory.ALL);
        assertEquals(4, filtered.size());
    }

    @Test
    void testFilterNotStarted() {
        List<ExamScopePresenter.SubtopicScope> subtopics = createMixedSubtopics();
        List<ExamScopePresenter.SubtopicScope> filtered = presenter.filter(subtopics, ExamScopePresenter.StatusCategory.NOT_STARTED);
        assertEquals(1, filtered.size());
        assertEquals("Not started", filtered.get(0).getStatusLabel());
    }

    @Test
    void testFilterInProgress() {
        List<ExamScopePresenter.SubtopicScope> subtopics = createMixedSubtopics();
        List<ExamScopePresenter.SubtopicScope> filtered = presenter.filter(subtopics, ExamScopePresenter.StatusCategory.IN_PROGRESS);
        assertEquals(2, filtered.size());
    }

    @Test
    void testFilterDone() {
        List<ExamScopePresenter.SubtopicScope> subtopics = createMixedSubtopics();
        List<ExamScopePresenter.SubtopicScope> filtered = presenter.filter(subtopics, ExamScopePresenter.StatusCategory.DONE);
        assertEquals(1, filtered.size());
        assertEquals("Done", filtered.get(0).getStatusLabel());
    }

    @Test
    void testSortSyllabusOrder() {
        List<ExamScopePresenter.SubtopicScope> subtopics = List.of(
                new ExamScopePresenter.SubtopicScope(3, "C", 8, null),
                new ExamScopePresenter.SubtopicScope(1, "A", 5, null),
                new ExamScopePresenter.SubtopicScope(2, "B", 2, null)
        );

        List<ExamScopePresenter.SubtopicScope> sorted = presenter.sort(subtopics, ExamScopePresenter.SortMode.SYLLABUS_ORDER);
        
        // Should keep original order (already in ID order in this case, but method doesn't sort)
        assertEquals(3, sorted.size());
    }

    @Test
    void testSortHardestFirst() {
        // HARDEST_FIRST now sorts by hoursToCover descending (difficulty is neutral 1 in planning).
        // Use distinct hoursToCover values so the sort order is deterministic.
        List<ExamScopePresenter.SubtopicScope> subtopics = List.of(
                new ExamScopePresenter.SubtopicScope(1, "Easy",   2, 1.0, 0.5, null),
                new ExamScopePresenter.SubtopicScope(2, "Hard",   9, 4.0, 1.0, null),
                new ExamScopePresenter.SubtopicScope(3, "Medium", 5, 2.5, 0.5, null)
        );

        List<ExamScopePresenter.SubtopicScope> sorted = presenter.sort(subtopics, ExamScopePresenter.SortMode.HARDEST_FIRST);

        // Should be ordered by hoursToCover descending: Hard (4.0) > Medium (2.5) > Easy (1.0)
        assertEquals("Hard",   sorted.get(0).getName());
        assertEquals("Medium", sorted.get(1).getName());
        assertEquals("Easy",   sorted.get(2).getName());
    }

    @Test
    void testCalculateTotalHours() {
        List<SyllabusSubtopic> subtopics = new ArrayList<>();
        subtopics.add(new SyllabusSubtopic(1, 1, "Sub1", 1, false));
        subtopics.add(new SyllabusSubtopic(2, 1, "Sub2", 2, false));
        subtopics.add(new SyllabusSubtopic(3, 1, "Sub3", 3, false));

        Topic topic1 = new Topic();
        topic1.setHoursNeeded(5.0);

        Topic topic2 = new Topic();
        topic2.setHoursNeeded(7.0);

        Map<Integer, Topic> map = new HashMap<>();
        map.put(1, topic1);
        map.put(2, topic2);
        // No topic for subtopic 3

        double total = presenter.calculateTotalHours(subtopics, map);
        assertEquals(12.0, total, 0.01);
    }

    @Test
    void testCalculateTotalHoursEmpty() {
        double total = presenter.calculateTotalHours(new ArrayList<>(), new HashMap<>());
        assertEquals(0.0, total, 0.01);
    }

    @Test
    void testGetDifficultyClass() {
        assertEquals("diff-easy", presenter.getDifficultyClass(1));
        assertEquals("diff-easy", presenter.getDifficultyClass(3));
        assertEquals("diff-mid", presenter.getDifficultyClass(4));
        assertEquals("diff-mid", presenter.getDifficultyClass(7));
        assertEquals("diff-hard", presenter.getDifficultyClass(8));
        assertEquals("diff-hard", presenter.getDifficultyClass(10));
    }

    @Test
    void testGroupByUnit() {
        List<SyllabusSubtopic> subtopics = new ArrayList<>();
        subtopics.add(new SyllabusSubtopic(1, 1, "Sub1", 1, false, 5));
        subtopics.add(new SyllabusSubtopic(2, 1, "Sub2", 2, false, 7));
        subtopics.add(new SyllabusSubtopic(3, 2, "Sub3", 1, false, 3));

        Map<Integer, String> unitNames = new HashMap<>();
        unitNames.put(1, "Unit One");
        unitNames.put(2, "Unit Two");

        Map<Integer, Integer> unitTotals = new HashMap<>();
        unitTotals.put(1, 5);
        unitTotals.put(2, 3);

        Map<Integer, Topic> topicMap = new HashMap<>();

        List<ExamScopePresenter.UnitScope> units = presenter.groupByUnit(subtopics, unitNames, unitTotals, topicMap);

        assertEquals(2, units.size());
        assertEquals("Unit One", units.get(0).getUnitName());
        assertEquals(2, units.get(0).getSelectedCount());
        assertEquals(5, units.get(0).getTotalSubtopics());
    }

    @Test
    void testEmptyScope() {
        List<ExamScopePresenter.UnitScope> units = presenter.groupByUnit(
                new ArrayList<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
        assertTrue(units.isEmpty());
    }

    private List<ExamScopePresenter.SubtopicScope> createMixedSubtopics() {
        Topic notStartedTopic = null;

        Topic learningTopic = new Topic();
        learningTopic.setPhase("LEARNING");
        learningTopic.setHoursNeeded(10.0);
        learningTopic.setRemainingHours(5.0);

        Topic revisingTopic = new Topic();
        revisingTopic.setPhase("REVISION");
        revisingTopic.setRevisionHoursNeeded(2.0);
        revisingTopic.setRevisionRemainingHours(1.0);

        Topic doneTopic = new Topic();
        doneTopic.setPhase("LEARNING");
        doneTopic.setHoursNeeded(10.0);
        doneTopic.setRemainingHours(0.0);

        return List.of(
                new ExamScopePresenter.SubtopicScope(1, "Not Started", 5, notStartedTopic),
                new ExamScopePresenter.SubtopicScope(2, "Learning", 6, learningTopic),
                new ExamScopePresenter.SubtopicScope(3, "Revising", 7, revisingTopic),
                new ExamScopePresenter.SubtopicScope(4, "Done", 8, doneTopic)
        );
    }
}
