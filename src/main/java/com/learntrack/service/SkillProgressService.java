package com.learntrack.service;

import com.learntrack.dao.SkillSubtopicDAO;
import com.learntrack.model.SkillSubtopic;

import java.sql.SQLException;
import java.time.LocalDate;

/**
 * Service for skill subtopic progress tracking.
 * Encapsulates the logic for logging skill learning and revision completion.
 */
public class SkillProgressService {

    private final SkillSubtopicDAO skillSubtopicDAO;

    public SkillProgressService(SkillSubtopicDAO skillSubtopicDAO) {
        this.skillSubtopicDAO = skillSubtopicDAO;
    }

    /**
     * Log progress on a SKILL_LEARN session.
     * If remaining hours drop to zero or below, marks the subtopic as learned.
     *
     * @param subtopicId   ID of the skill subtopic
     * @param hoursLogged  hours logged in this session
     * @param sessionDate  date of the session
     * @throws SQLException if database operation fails
     */
    public void logLearningProgress(int subtopicId, double hoursLogged, LocalDate sessionDate) throws SQLException {
        SkillSubtopic st = skillSubtopicDAO.findById(subtopicId);
        if (st != null) {
            double rem = Math.max(0.0, st.getLearnRemainingHours() - hoursLogged);
            st.setLearnRemainingHours(rem);
            if (rem <= 0.01) {
                // Mark as learned: sets learned=1, learned_on, revision_due_date, learn_remaining_hours=0
                skillSubtopicDAO.markLearned(st.getSubtopicId(), sessionDate, st.getReviseAfterDays());
            } else {
                skillSubtopicDAO.update(st);
            }
        }
    }

    /**
     * Log progress on a SKILL_REVISE session.
     * If remaining hours drop to zero or below, marks the subtopic as revised.
     *
     * @param subtopicId   ID of the skill subtopic
     * @param hoursLogged  hours logged in this session
     * @param sessionDate  date of the session
     * @throws SQLException if database operation fails
     */
    public void logRevisionProgress(int subtopicId, double hoursLogged, LocalDate sessionDate) throws SQLException {
        SkillSubtopic st = skillSubtopicDAO.findById(subtopicId);
        if (st != null) {
            double rem = Math.max(0.0, st.getReviseRemainingHours() - hoursLogged);
            st.setReviseRemainingHours(rem);
            if (rem <= 0.01) {
                // Mark as revised: sets revised=1, revised_on, revise_remaining_hours=0
                skillSubtopicDAO.markRevised(st.getSubtopicId(), sessionDate);
            } else {
                skillSubtopicDAO.update(st);
            }
        }
    }
}
