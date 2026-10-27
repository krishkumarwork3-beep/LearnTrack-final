package com.learntrack.model;

/**
 * Represents a syllabus unit (section) within a subject.
 */
public class SyllabusUnit {
    private int unitId;
    private int semesterId;
    private String subjectCode;
    private String name;
    private int position;

    public SyllabusUnit() {}

    public SyllabusUnit(int unitId, int semesterId, String subjectCode, String name, int position) {
        this.unitId = unitId;
        this.semesterId = semesterId;
        this.subjectCode = subjectCode;
        this.name = name;
        this.position = position;
    }

    public int getUnitId() {
        return unitId;
    }

    public void setUnitId(int unitId) {
        this.unitId = unitId;
    }

    public int getSemesterId() {
        return semesterId;
    }

    public void setSemesterId(int semesterId) {
        this.semesterId = semesterId;
    }

    public String getSubjectCode() {
        return subjectCode;
    }

    public void setSubjectCode(String subjectCode) {
        this.subjectCode = subjectCode;
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
}
