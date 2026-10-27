package com.learntrack.model;

/**
 * Represents an academic semester.
 * Plan Section 3.
 */
public class Semester {
    private Integer semesterId;
    private String name;
    private String status; // 'ACTIVE', 'COMPLETED', etc.

    public Semester() {
        this.status = "ACTIVE";
    }

    public Semester(Integer semesterId, String name, String status) {
        this.semesterId = semesterId;
        this.name = name;
        this.status = status != null ? status : "ACTIVE";
    }

    public Integer getSemesterId() {
        return semesterId;
    }

    public void setSemesterId(Integer semesterId) {
        this.semesterId = semesterId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return name + " (" + status + ")";
    }
}
