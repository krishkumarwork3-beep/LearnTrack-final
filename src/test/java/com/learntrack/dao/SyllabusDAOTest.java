package com.learntrack.dao;

import com.learntrack.model.SyllabusSubtopic;
import com.learntrack.model.SyllabusUnit;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SyllabusDAO with a temporary in-memory database.
 */
public class SyllabusDAOTest {

    private static final String TEST_DB_PATH = "target/test_syllabus.db";
    private SyllabusDAO dao;

    @BeforeEach
    void setUp() throws SQLException {
        new File(TEST_DB_PATH).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + TEST_DB_PATH);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }
        dao = new SyllabusDAO();
        
        // Create test semesters
        SemesterDAO semesterDAO = new SemesterDAO();
        semesterDAO.insert(new com.learntrack.model.Semester(null, "Test Semester 1", "ACTIVE"));
        semesterDAO.insert(new com.learntrack.model.Semester(null, "Test Semester 2", "INACTIVE"));
    }

    @AfterEach
    void tearDown() {
        DBConnection.resetToDefaultUrl();
        new File(TEST_DB_PATH).delete();
    }

    @Test
    void testInsertAndFindUnits() throws SQLException {
        int unit1Id = dao.insertUnit(1, "UCS503", "Introduction to Software Engineering", 0);
        int unit2Id = dao.insertUnit(1, "UCS503", "Requirements Engineering", 1);
        int unit3Id = dao.insertUnit(1, "UCS420", "Cognitive Computing Basics", 0);

        assertTrue(unit1Id > 0);
        assertTrue(unit2Id > 0);
        assertTrue(unit3Id > 0);

        List<SyllabusUnit> units = dao.findUnitsBySubject(1, "UCS503");
        assertEquals(2, units.size());
        assertEquals("Introduction to Software Engineering", units.get(0).getName());
        assertEquals("Requirements Engineering", units.get(1).getName());
        assertEquals(0, units.get(0).getPosition());
        assertEquals(1, units.get(1).getPosition());

        List<SyllabusUnit> units420 = dao.findUnitsBySubject(1, "UCS420");
        assertEquals(1, units420.size());
        assertEquals("Cognitive Computing Basics", units420.get(0).getName());
    }

    @Test
    void testUpdateUnitName() throws SQLException {
        int unitId = dao.insertUnit(1, "UCS503", "Old Name", 0);
        dao.updateUnitName(unitId, "New Name");

        List<SyllabusUnit> units = dao.findUnitsBySubject(1, "UCS503");
        assertEquals(1, units.size());
        assertEquals("New Name", units.get(0).getName());
    }

    @Test
    void testUpdateUnitPosition() throws SQLException {
        int unit1Id = dao.insertUnit(1, "UCS503", "Unit 1", 0);
        int unit2Id = dao.insertUnit(1, "UCS503", "Unit 2", 1);

        dao.updateUnitPosition(unit1Id, 1);
        dao.updateUnitPosition(unit2Id, 0);

        List<SyllabusUnit> units = dao.findUnitsBySubject(1, "UCS503");
        assertEquals(2, units.size());
        // They should be returned ordered by position
        assertEquals("Unit 2", units.get(0).getName());
        assertEquals("Unit 1", units.get(1).getName());
    }

    @Test
    void testDeleteUnit() throws SQLException {
        int unitId = dao.insertUnit(1, "UCS503", "Unit to Delete", 0);
        dao.insertSubtopic(unitId, "Subtopic 1", 0);
        dao.insertSubtopic(unitId, "Subtopic 2", 1);

        List<SyllabusUnit> unitsBefore = dao.findUnitsBySubject(1, "UCS503");
        assertEquals(1, unitsBefore.size());

        dao.deleteUnit(unitId);

        List<SyllabusUnit> unitsAfter = dao.findUnitsBySubject(1, "UCS503");
        assertEquals(0, unitsAfter.size());

        // Subtopics should also be deleted (cascade)
        List<SyllabusSubtopic> subtopics = dao.findSubtopicsByUnit(unitId);
        assertEquals(0, subtopics.size());
    }

    @Test
    void testInsertAndFindSubtopics() throws SQLException {
        int unitId = dao.insertUnit(1, "UCS503", "Testing Unit", 0);
        
        int sub1Id = dao.insertSubtopic(unitId, "Subtopic One", 0);
        int sub2Id = dao.insertSubtopic(unitId, "Subtopic Two", 1);
        int sub3Id = dao.insertSubtopic(unitId, "Subtopic Three", 2);

        assertTrue(sub1Id > 0);
        assertTrue(sub2Id > 0);
        assertTrue(sub3Id > 0);

        List<SyllabusSubtopic> subtopics = dao.findSubtopicsByUnit(unitId);
        assertEquals(3, subtopics.size());
        assertEquals("Subtopic One", subtopics.get(0).getName());
        assertEquals("Subtopic Two", subtopics.get(1).getName());
        assertEquals("Subtopic Three", subtopics.get(2).getName());
        assertEquals(0, subtopics.get(0).getPosition());
        assertEquals(1, subtopics.get(1).getPosition());
        assertEquals(2, subtopics.get(2).getPosition());
        assertFalse(subtopics.get(0).isDone());
    }

    @Test
    void testUpdateSubtopicName() throws SQLException {
        int unitId = dao.insertUnit(1, "UCS503", "Unit", 0);
        int subtopicId = dao.insertSubtopic(unitId, "Old Subtopic Name", 0);

        dao.updateSubtopicName(subtopicId, "New Subtopic Name");

        List<SyllabusSubtopic> subtopics = dao.findSubtopicsByUnit(unitId);
        assertEquals(1, subtopics.size());
        assertEquals("New Subtopic Name", subtopics.get(0).getName());
    }

    @Test
    void testUpdateSubtopicPosition() throws SQLException {
        int unitId = dao.insertUnit(1, "UCS503", "Unit", 0);
        int sub1Id = dao.insertSubtopic(unitId, "First", 0);
        int sub2Id = dao.insertSubtopic(unitId, "Second", 1);

        dao.updateSubtopicPosition(sub1Id, 1);
        dao.updateSubtopicPosition(sub2Id, 0);

        List<SyllabusSubtopic> subtopics = dao.findSubtopicsByUnit(unitId);
        assertEquals(2, subtopics.size());
        assertEquals("Second", subtopics.get(0).getName());
        assertEquals("First", subtopics.get(1).getName());
    }

    @Test
    void testUpdateSubtopicDone() throws SQLException {
        int unitId = dao.insertUnit(1, "UCS503", "Unit", 0);
        int subtopicId = dao.insertSubtopic(unitId, "Subtopic", 0);

        List<SyllabusSubtopic> subtopics = dao.findSubtopicsByUnit(unitId);
        assertFalse(subtopics.get(0).isDone());

        dao.updateSubtopicDone(subtopicId, true);
        subtopics = dao.findSubtopicsByUnit(unitId);
        assertTrue(subtopics.get(0).isDone());

        dao.updateSubtopicDone(subtopicId, false);
        subtopics = dao.findSubtopicsByUnit(unitId);
        assertFalse(subtopics.get(0).isDone());
    }

    @Test
    void testDeleteSubtopic() throws SQLException {
        int unitId = dao.insertUnit(1, "UCS503", "Unit", 0);
        int sub1Id = dao.insertSubtopic(unitId, "Keep This", 0);
        int sub2Id = dao.insertSubtopic(unitId, "Delete This", 1);

        List<SyllabusSubtopic> subtopicsBefore = dao.findSubtopicsByUnit(unitId);
        assertEquals(2, subtopicsBefore.size());

        dao.deleteSubtopic(sub2Id);

        List<SyllabusSubtopic> subtopicsAfter = dao.findSubtopicsByUnit(unitId);
        assertEquals(1, subtopicsAfter.size());
        assertEquals("Keep This", subtopicsAfter.get(0).getName());
    }

    @Test
    void testDeleteAllForSubject() throws SQLException {
        dao.insertUnit(1, "UCS503", "Unit 1", 0);
        dao.insertUnit(1, "UCS503", "Unit 2", 1);
        dao.insertUnit(1, "UCS420", "Unit A", 0);

        List<SyllabusUnit> units503Before = dao.findUnitsBySubject(1, "UCS503");
        List<SyllabusUnit> units420Before = dao.findUnitsBySubject(1, "UCS420");
        assertEquals(2, units503Before.size());
        assertEquals(1, units420Before.size());

        dao.deleteAllForSubject(1, "UCS503");

        List<SyllabusUnit> units503After = dao.findUnitsBySubject(1, "UCS503");
        List<SyllabusUnit> units420After = dao.findUnitsBySubject(1, "UCS420");
        assertEquals(0, units503After.size());
        assertEquals(1, units420After.size());
    }

    @Test
    void testDeleteAllForSemester() throws SQLException {
        dao.insertUnit(1, "UCS503", "Sem 1 Unit", 0);
        dao.insertUnit(2, "UCS420", "Sem 2 Unit", 0);

        List<SyllabusUnit> sem1Before = dao.findUnitsBySubject(1, "UCS503");
        List<SyllabusUnit> sem2Before = dao.findUnitsBySubject(2, "UCS420");
        assertEquals(1, sem1Before.size());
        assertEquals(1, sem2Before.size());

        dao.deleteAllForSemester(1);

        List<SyllabusUnit> sem1After = dao.findUnitsBySubject(1, "UCS503");
        List<SyllabusUnit> sem2After = dao.findUnitsBySubject(2, "UCS420");
        assertEquals(0, sem1After.size());
        assertEquals(1, sem2After.size());
    }
}
