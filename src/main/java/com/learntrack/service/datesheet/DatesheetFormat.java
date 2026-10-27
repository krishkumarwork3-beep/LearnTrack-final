package com.learntrack.service.datesheet;

import com.learntrack.model.ParsedDatesheet;

/**
 * Strategy interface for datesheet format parsers.
 */
public interface DatesheetFormat {
    /**
     * Score between 0.0 and 1.0 indicating how likely the text matches this format.
     */
    double score(String text);

    /**
     * Parse text into ParsedDatesheet.
     */
    ParsedDatesheet parse(String text);

    /**
     * Format name for UI and reporting.
     */
    String name();
}
