package com.learntrack.service;

import com.learntrack.dao.SyllabusDAO;
import com.learntrack.dao.TopicDAO;
import com.learntrack.model.Topic;

import java.sql.SQLException;
import java.time.LocalDate;

/**
 * Records learning progress on exam topics and keeps the syllabus in step: a topic whose
 * learning hours are used up is learned, and its syllabus subtopic is ticked "studied"
 * (so every other exam that covers it only revises it). Undoing a session reverses both.
 */
public class StudyProgressService {

    private final TopicDAO topicDAO;
    private final SyllabusDAO syllabusDAO;

    public StudyProgressService(TopicDAO topicDAO, SyllabusDAO syllabusDAO) {
        this.topicDAO = topicDAO;
        this.syllabusDAO = syllabusDAO;
    }

    /**
     * A learning session of {@code hours} was done. The topic counts as learned only when its
     * remaining hours are used up, so ticking the first half of a split topic does not finish it.
     */
    public void completeLearnSession(int topicId, double hours, LocalDate date) throws SQLException {
        Topic topic = topicDAO.findById(topicId);
        if (topic == null) return;
        double remaining = topic.getRemainingHours() - Math.max(0.0, hours);
        if (remaining <= 0.01) {
            finish(topic, date);
        } else {
            topic.setRemainingHours(remaining);
            topicDAO.update(topic);
        }
    }

    /** The student says the topic is fully learned, whatever hours were planned. */
    public void finishTopic(int topicId, LocalDate date) throws SQLException {
        Topic topic = topicDAO.findById(topicId);
        if (topic != null) finish(topic, date);
    }

    /** A ticked learning session was unticked: give its hours back and untick the syllabus. */
    public void undoLearnSession(int topicId, double hours) throws SQLException {
        Topic topic = topicDAO.findById(topicId);
        if (topic == null) return;
        topic.setPhase("LEARNING");
        topic.setRemainingHours(Math.min(topic.getHoursNeeded(), topic.getRemainingHours() + Math.max(0.0, hours)));
        topicDAO.update(topic);
        if (topic.getSubtopicId() != null) {
            syllabusDAO.updateSubtopicDone(topic.getSubtopicId(), false);
        }
    }

    private void finish(Topic topic, LocalDate date) throws SQLException {
        topic.markLearningDone(date);
        topicDAO.update(topic);
        if (topic.getSubtopicId() != null) {
            syllabusDAO.updateSubtopicDone(topic.getSubtopicId(), true);
        }
    }
}
