package com.learntrack.service;

import com.learntrack.dao.*;
import com.learntrack.model.DatesheetMatchRow;
import com.learntrack.model.Exam;
import com.learntrack.model.Semester;
import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exams of the batch that the applied datesheet does not list must not stretch the exam window. */
class DatesheetUnlistedExamsTest {

    private static final String DB = "target/test_datesheet_unlisted.db";
    private ExamDAO examDAO;
    private DatesheetAnalyserService service;
    private int semesterId;

    @BeforeEach
    void setUp() throws SQLException {
        new File(DB).delete();
        DBConnection.setCustomUrl("jdbc:sqlite:" + DB);
        try (Connection conn = DBConnection.getConnection()) {
            DBConnection.initSchema(conn);
        }
        examDAO = new ExamDAO();
        service = new DatesheetAnalyserService(examDAO,
                new SubjectListService(new TimetableDAO(), examDAO), new SettingsDAO(), new ExamTimelineService(), null);
        semesterId = new SemesterDAO().insert(new Semester(null, "Odd 2026", "ACTIVE"));

        // Two EST exams that the user dated earlier by hand (1 Dec and 31 Dec), confirmed.
        examDAO.insert(new Exam(null, semesterId, "cognitive computing", "UCS420", "EST", null,
                3.0, 50.0, LocalDate.of(2026, 12, 1), false, LocalTime.of(10, 0), null, 30, null, null, 2.0));
        examDAO.insert(new Exam(null, semesterId, "UCS421", "UCS421", "EST", null,
                3.0, 50.0, LocalDate.of(2026, 12, 31), false, LocalTime.of(10, 0), null, 30, null, null, 2.0));
        examDAO.insert(new Exam(null, semesterId, "UCS539", "UCS539", "EST", null,
                3.0, 50.0, null, true, null, null, 30, null, null, 2.0));
    }

    @Test
    void unlistedExamsLoseTheirOldDateAndTheWindowIsTheSheetSpan() throws SQLException {
        List<DatesheetMatchRow> rows = List.of(new DatesheetMatchRow("UCS539", "UCS539", "UCS539", "EST",
                LocalDate.of(2026, 12, 3), LocalTime.of(9, 0), LocalTime.of(12, 0), "Matched"));

        service.apply(semesterId, rows, null, null, null, new HashSet<>(), "EST");

        Exam listed = examDAO.findBySubject(semesterId, "UCS539").get(0);
        assertEquals(LocalDate.of(2026, 12, 3), listed.getExamDate());
        assertFalse(listed.isExamDateTentative());

        Exam unlisted = examDAO.findBySubject(semesterId, "UCS420").get(0);
        assertNull(unlisted.getExamDate(), "an exam the sheet does not list shows Date TBD, not its old 1 Dec");

        LocalDate[] window = new ExamTimelineService().getDerivedWindow(semesterId, "EST");
        assertArrayEquals(new LocalDate[]{LocalDate.of(2026, 12, 3), LocalDate.of(2026, 12, 3)}, window,
                "the exam period is the sheet's own span, not 1-31 Dec");
    }
}
