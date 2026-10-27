package com.learntrack.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a parsed syllabus unit with its subtopics.
 */
public class ParsedUnit {
    private String name;
    private List<String> subtopics;

    public ParsedUnit(String name) {
        this.name = name;
        this.subtopics = new ArrayList<>();
    }

    public ParsedUnit(String name, List<String> subtopics) {
        this.name = name;
        this.subtopics = new ArrayList<>(subtopics);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getSubtopics() {
        return subtopics;
    }

    public void setSubtopics(List<String> subtopics) {
        this.subtopics = subtopics;
    }

    public void addSubtopic(String subtopic) {
        this.subtopics.add(subtopic);
    }
}
