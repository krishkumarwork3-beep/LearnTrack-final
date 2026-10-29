package com.learntrack.model;

/**
 * Represents a subtopic within a syllabus unit.
 */
public class SyllabusSubtopic {
    private int subtopicId;
    private int unitId;
    private String name;
    private int position;
    private boolean done;
    private int difficulty; // 1-10 scale
    private double hoursToCover = 1.0;
    private double hoursToRevise = 0.5;
    private int importance = 3; // 1 optional .. 5 must-know

    public SyllabusSubtopic() {}

    public SyllabusSubtopic(int subtopicId, int unitId, String name, int position, boolean done) {
        this(subtopicId, unitId, name, position, done, 5, 1.0, 0.5);
    }

    public SyllabusSubtopic(int subtopicId, int unitId, String name, int position, boolean done, int difficulty) {
        this(subtopicId, unitId, name, position, done, difficulty, 1.0, 0.5);
    }

    public SyllabusSubtopic(int subtopicId, int unitId, String name, int position, boolean done, int difficulty, double hoursToCover, double hoursToRevise) {
        this.subtopicId = subtopicId;
        this.unitId = unitId;
        this.name = name;
        this.position = position;
        this.done = done;
        setDifficulty(difficulty);
        this.hoursToCover = hoursToCover > 0 ? hoursToCover : 1.0;
        this.hoursToRevise = hoursToRevise > 0 ? hoursToRevise : 0.5;
    }

    public int getSubtopicId() {
        return subtopicId;
    }

    public void setSubtopicId(int subtopicId) {
        this.subtopicId = subtopicId;
    }

    public int getUnitId() {
        return unitId;
    }

    public void setUnitId(int unitId) {
        this.unitId = unitId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public int getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(int difficulty) {
        // Clamp to 1-10
        this.difficulty = Math.max(1, Math.min(10, difficulty));
    }

    public double getHoursToCover() {
        return hoursToCover;
    }

    public void setHoursToCover(double hoursToCover) {
        this.hoursToCover = hoursToCover > 0 ? hoursToCover : 1.0;
    }

    public double getHoursToRevise() {
        return hoursToRevise;
    }

    public void setHoursToRevise(double hoursToRevise) {
        this.hoursToRevise = hoursToRevise > 0 ? hoursToRevise : 0.5;
    }

    /** How much this subtopic matters for the exam: 1 optional, 2 low, 3 normal, 4 high, 5 must-know. */
    public int getImportance() {
        return importance;
    }

    public void setImportance(int importance) {
        this.importance = clampImportance(importance);
    }

    public static int clampImportance(int importance) {
        return Math.max(1, Math.min(5, importance));
    }

    /** Short label for an importance level, shared by every screen that shows it. */
    public static String importanceLabel(int importance) {
        switch (clampImportance(importance)) {
            case 1: return "Optional";
            case 2: return "Low";
            case 4: return "High";
            case 5: return "Must-know";
            default: return "Normal";
        }
    }
}
