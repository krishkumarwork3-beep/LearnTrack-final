package com.learntrack.service;

import com.learntrack.model.DatesheetEntry;
import com.learntrack.model.ParsedDatesheet;
import com.learntrack.service.datesheet.ColumnBlockFormat;
import com.learntrack.service.datesheet.DatesheetFormat;
import com.learntrack.service.datesheet.RowWiseFormat;

import java.util.*;

/**
 * Datesheet parser strategy runner.
 *
 * <p>Every available format is asked to parse the text; the results are checked against the
 * invariants below and the one that places the most courses on a date and time wins. Choosing by
 * outcome (rather than by a pre-computed score) keeps badly-cut pastes from being rejected just
 * because the text no longer looks like the format it came from.</p>
 *
 * <p>Row-wise text is the exception: when the text really is a table whose rows carry a date and a
 * time (see {@link RowWiseFormat#layoutScore(String)}), the row-wise reading is authoritative and no
 * other format may overrule it. A column-block paste of the same sheet still reaches the column
 * reader, because its dates and times arrive as separate columns even when the first row of a page
 * happens to be glued into one line.</p>
 *
 * <p>Invariants of a usable result: a course is never both assigned and ambiguous, no ambiguous
 * course has an empty candidate list, and no parser exception escapes.</p>
 */
public class DatesheetParser {

    private static final List<DatesheetFormat> DEFAULT_FORMATS = List.of(
            new RowWiseFormat(),
            new ColumnBlockFormat()
    );

    public static ParsedDatesheet parse(String text) {
        return parse(text, DEFAULT_FORMATS);
    }

    public static ParsedDatesheet parse(String text, List<DatesheetFormat> formats) {
        if (text == null || text.isBlank()) {
            return emptyResult(Collections.emptyList());
        }

        // Normalise line endings, non-breaking spaces
        String normalised = text.replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace('\u00A0', ' ');

        List<DatesheetFormat> candidates = formats != null && !formats.isEmpty()
                ? new ArrayList<>(formats) : new ArrayList<>(DEFAULT_FORMATS);

        List<ParsedDatesheet> results = new ArrayList<>();
        ParsedDatesheet rowWise = null;
        for (DatesheetFormat format : candidates) {
            ParsedDatesheet result;
            try {
                result = format.parse(normalised);
            } catch (Exception ex) {
                // Never throw on bad input; try the next format.
                continue;
            }
            if (result == null || !isValid(result)) {
                continue;
            }
            results.add(result);
            if (format instanceof RowWiseFormat) {
                rowWise = result;
            }
        }

        // A genuine row-wise table is decided by its own rows: another format must never replace
        // correctly read rows with guesses derived from the same text.
        boolean layoutIsRowWise = RowWiseFormat.layoutScore(normalised) >= 0.5;
        if (layoutIsRowWise && rowWise != null && confidentAssignments(rowWise) > 0) {
            return rowWise;
        }

        ParsedDatesheet best = null;
        for (ParsedDatesheet result : results) {
            if (matchesBetter(result, best)) {
                best = result;
            }
        }

        if (best != null) {
            return best;
        }

        return emptyResult(List.of("Could not parse datesheet content."));
    }

    /**
     * True when the result obeys the invariants every format must satisfy.
     */
    public static boolean isValid(ParsedDatesheet result) {
        Set<String> assigned = new HashSet<>();
        for (DatesheetEntry entry : result.getEntries()) {
            if (entry.getCourseCode() == null || entry.getCourseCode().isBlank()) {
                return false;
            }
            assigned.add(entry.getCourseCode().toUpperCase());
        }
        for (Map.Entry<String, List<DatesheetEntry>> ambiguous : result.getAmbiguous().entrySet()) {
            if (assigned.contains(ambiguous.getKey().toUpperCase())) {
                return false;
            }
            if (ambiguous.getValue() == null || ambiguous.getValue().isEmpty()) {
                return false;
            }
            for (DatesheetEntry candidate : ambiguous.getValue()) {
                if (candidate == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Codes placed on a real date and time, which is what a format is ranked by. */
    private static int confidentAssignments(ParsedDatesheet result) {
        int confident = 0;
        for (DatesheetEntry entry : result.getEntries()) {
            if (entry.getDate() != null && entry.getStart() != null) {
                confident++;
            }
        }
        return confident;
    }

    private static int coverage(ParsedDatesheet result) {
        return result.getEntries().size() + result.getAmbiguous().size();
    }

    /**
     * The format that places more courses confidently wins; on a tie the one that accounts for more
     * of the sheet's codes, then the one that leaves fewer courses undecided, then the quieter one.
     */
    private static boolean matchesBetter(ParsedDatesheet candidate, ParsedDatesheet current) {
        if (current == null) {
            return true;
        }

        int candidateConfident = confidentAssignments(candidate);
        int currentConfident = confidentAssignments(current);
        if (candidateConfident != currentConfident) {
            return candidateConfident > currentConfident;
        }

        int candidateCoverage = coverage(candidate);
        int currentCoverage = coverage(current);
        if (candidateCoverage != currentCoverage) {
            return candidateCoverage > currentCoverage;
        }

        int candidateAmbiguous = candidate.getAmbiguous().size();
        int currentAmbiguous = current.getAmbiguous().size();
        if (candidateAmbiguous != currentAmbiguous) {
            return candidateAmbiguous < currentAmbiguous;
        }

        int candidateWarnings = candidate.getWarnings().size();
        int currentWarnings = current.getWarnings().size();
        if (candidateWarnings != currentWarnings) {
            return candidateWarnings < currentWarnings;
        }

        return candidate.getSkippedLines() < current.getSkippedLines();
    }

    private static ParsedDatesheet emptyResult(List<String> warnings) {
        return new ParsedDatesheet("", "MST", Collections.emptyList(), 0, warnings,
                "None", Collections.emptyMap(), null, null);
    }
}
