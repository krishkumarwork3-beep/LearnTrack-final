package com.learntrack.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses syllabus text into structured units and subtopics.
 * No JavaFX imports - pure service layer.
 */
public class SyllabusParser {

    private static final Pattern SUBJECT_HEADER_PATTERN = Pattern.compile(
            "^([A-Z]{3}(?:\\d{3}|\\*{3}))\\s*(?::|[-\\u2013]\\s)\\s*(.+)$", Pattern.CASE_INSENSITIVE);   // "UCS503: ...", "UNC***: ...", "UNC513- ..."

    private static final String[] CONTENT_START_MARKERS = {
            "Course Objectives", "course objectives"
    };

    private static final String[] CONTENT_END_MARKERS = {
            "Laboratory Work", "laboratory work", "Laboratory work",
            "Course Learning Outcomes", "course learning outcomes",
            "Text Books", "text books", "TextBooks", "Textbooks",
            "Reference Books", "reference books", "ReferenceBooks"
    };

    /** "Unit Name: rest of line" or "Unit Name - rest of line" (for hyphen headings) */
    private static final Pattern UNIT_LINE_PATTERN = Pattern.compile(
            "^([\\p{L}\\p{N}][^:]{0,120}?)\\s*(?::|\\s*-\\s+(?=[A-Za-z0-9]))\\s*(.*)$");

    private static boolean isPlausibleUnitName(String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return false;
        // Allow longer names for chemistry/physics style headers
        return trimmed.split("\\s+").length <= 20;
    }

    private static final Pattern CREDITS_LINE_PATTERN = Pattern.compile(
            "^L\\s+T\\s+P\\s+Cr\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * Parse syllabus text into units and subtopics.
     * 
     * @param text The raw syllabus text
     * @return List of parsed units
     */
    public List<ParsedUnit> parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }

        // Normalize text: fix line breaks, hyphenation
        String normalized = normalizeText(text);

        // Extract content between markers
        String content = extractRelevantContent(normalized);

        // Parse units from content
        return parseUnits(content);
    }

    /**
     * Extract detected subject code from header like "UCS420: COGNITIVE COMPUTING"
     */
    public String extractSubjectCode(String text) {
        if (text == null) return null;
        
        String[] lines = text.split("\n");
        for (int i = 0; i < Math.min(5, lines.length); i++) {
            Matcher m = SUBJECT_HEADER_PATTERN.matcher(lines[i].trim());
            if (m.matches()) {
                return m.group(1).toUpperCase();
            }
        }
        return null;
    }

    /**
     * Normalize text: join wrapped lines, fix hyphenation, collapse whitespace.
     */
    private String normalizeText(String text) {
        // Replace various line break styles
        text = text.replaceAll("\\r\\n", "\n");
        text = text.replaceAll("\\r", "\n");

        // Fix hyphenated line breaks (word- \n continuation)
        text = text.replaceAll("([a-z])-\\s*\\n\\s*([a-z])", "$1$2");

        // Join lines that don't end with sentence terminators or colons
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n");
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            
            if (line.isEmpty()) {
                sb.append("\n");
                continue;
            }

            // Check if this line should be joined with next
            boolean shouldJoin = false;
            if (i < lines.length - 1) {
                String nextLine = lines[i + 1].trim();
                // Join if line doesn't end with punctuation and next line doesn't look like a header
                if (!line.matches(".*[.,:;!?]\\s*$") && 
                    !nextLine.isEmpty() && 
                    !Character.isUpperCase(nextLine.charAt(0)) &&
                    !nextLine.matches("^[A-Z][a-z]+.*:.*")) {
                    shouldJoin = true;
                }
            }

            if (shouldJoin) {
                sb.append(line).append(" ");
            } else {
                sb.append(line).append("\n");
            }
        }

        // Collapse multiple spaces
        String result = sb.toString().replaceAll(" +", " ");
        return result;
    }

    /**
     * Extract content between "Course Objectives" and end markers.
     */
    private String extractRelevantContent(String text) {
        String lowerText = text.toLowerCase();
        
        // Find start position
        int startPos = 0;
        for (String marker : CONTENT_START_MARKERS) {
            int pos = lowerText.indexOf(marker.toLowerCase());
            if (pos >= 0) {
                // Skip to end of the objectives paragraph
                int lineEnd = text.indexOf('\n', pos);
                if (lineEnd > 0) {
                    startPos = lineEnd + 1;
                } else {
                    startPos = pos + marker.length();
                }
                break;
            }
        }

        // Find end position
        int endPos = text.length();
        for (String marker : CONTENT_END_MARKERS) {
            int pos = lowerText.indexOf(marker.toLowerCase(), startPos);
            if (pos >= 0 && pos < endPos) {
                endPos = pos;
            }
        }

        if (startPos < endPos) {
            return text.substring(startPos, endPos).trim();
        }
        
        // If no markers found, return whole text (skip header and credits line)
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean skipInitial = true;
        
        for (String line : lines) {
            if (skipInitial) {
                // Skip subject header and L T P Cr line
                if (SUBJECT_HEADER_PATTERN.matcher(line.trim()).matches() ||
                    CREDITS_LINE_PATTERN.matcher(line.trim()).matches() ||
                    line.trim().matches("^\\d+\\s+\\d+\\s+\\d+\\s+\\d+\\s*$")) {
                    continue;
                }
                skipInitial = false;
            }
            sb.append(line).append("\n");
        }
        
        return sb.toString().trim();
    }

    /**
     * Parse units from content text.
     * Each unit is "Unit Name: subtopic1, subtopic2, ..."
     * Handles mid-paragraph unit headers.
     */
    private List<ParsedUnit> parseUnits(String content) {
        List<ParsedUnit> units = new ArrayList<>();
        
        // Split content into potential unit blocks
        // Look for "Title Case Phrase:" patterns
        Pattern unitPattern = Pattern.compile(
                "([A-Z][A-Za-z\\s&-]+?):\\s*([^\\n]+(?:\\n(?![A-Z][A-Za-z\\s&-]+?:)[^\\n]+)*)",
                Pattern.MULTILINE
        );

        Matcher matcher = unitPattern.matcher(content);
        
        while (matcher.find()) {
            String unitName = matcher.group(1).trim();
            String unitContent = matcher.group(2).trim();

            // Validate it looks like a unit header (2-6 words, title case)
            if (!isLikelyUnitHeader(unitName)) {
                continue;
            }

            // Parse subtopics from unit content
            List<String> subtopics = parseSubtopics(unitContent);
            
            if (!subtopics.isEmpty()) {
                ParsedUnit unit = new ParsedUnit(unitName, subtopics);
                units.add(unit);
            }
        }

        return units;
    }

    /**
     * Check if text looks like a unit header (Title Case, 2-6 words).
     */
    private boolean isLikelyUnitHeader(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        // Must start with uppercase
        if (!Character.isUpperCase(text.charAt(0))) {
            return false;
        }

        // Count words
        String[] words = text.trim().split("\\s+");
        if (words.length < 2 || words.length > 8) {
            return false;
        }

        // Check if most words are capitalized (Title Case)
        int capitalizedCount = 0;
        for (String word : words) {
            if (!word.isEmpty() && Character.isUpperCase(word.charAt(0))) {
                capitalizedCount++;
            }
        }

        return capitalizedCount >= words.length - 1; // Allow one lowercase word (and, or, etc.)
    }

    /**
     * Parse subtopics from unit content, splitting on commas but respecting parentheses.
     */
    private List<String> parseSubtopics(String content) {
        List<String> subtopics = new ArrayList<>();
        List<String> rawSubtopics = splitRespectingParentheses(content, ',');

        for (String subtopic : rawSubtopics) {
            String cleaned = subtopic.trim();
            
            // Remove newlines within subtopic
            cleaned = cleaned.replaceAll("\\s*\\n\\s*", " ");
            cleaned = cleaned.replaceAll("\\s+", " ");
            
            if (!cleaned.isEmpty() && cleaned.length() > 2) {
                subtopics.add(cleaned);
            }
        }

        // Remove duplicates while preserving order
        return new ArrayList<>(new LinkedHashSet<>(subtopics));
    }

    // =====================================================================
    //  Paste-import parsing (TopicFormController "Paste Syllabus" dialog)
    // =====================================================================

    /** Bullet glyphs that survive copy/paste from Word/PDF (Symbol/Wingdings private-use area, boxes, arrows...). */
    private static final Pattern LEADING_GLYPH_BULLET = Pattern.compile(
            "^[\\uE000-\\uF8FF\\uFFFD\\u25A0-\\u25FF\\u2022\\u2023\\u2043\\u2219\\u00B7\\u27A2\\u27A4\\u2756\\u2713\\u2714\\u2794]+\\s*");

    /** Hard stop: nothing after these is syllabus content. */
    private static final Pattern HARD_STOP_LINE = Pattern.compile(
            "^(?:text\\s*books?|reference\\s*books?|recommended\\s+(?:books|readings?)|suggested\\s+readings?"
            + "|evaluation\\s+scheme)\\b.*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Soft skip: the section is ignored until the next "Unit ..." heading or a "Syllabus" line
     * (Laboratory Work sits in the middle of multi-unit syllabi; Course Outcomes sit above the syllabus).
     */
    private static final Pattern SOFT_SKIP_LINE = Pattern.compile(
            "^(?:laboratory\\s+work|lab(?:oratory)?\\s+(?:work|experiments?)"
            + "|(?:micro|minor)\\s+projects?\\b|assignments?\\s*:?\\s*$|self[-\\s]learn(?:ing)?\\b)\\b.*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Outcomes / learning-objectives section (a numbered list of "Understand ...", "Apply ..."
     * statements, never syllabus content). Matches every heading variant seen in scheme booklets:
     * "Course Learning Outcomes (CLOs):", "Course Learning Objectives (CLO)", "Course outcomes",
     * "Learning Outcomes", "CLOs:" and the bare "After / Upon / On (the successful) completion of ..." lead-in.
     * Plain "Course Objective:" is NOT matched: it is a one-paragraph intro that often sits right above
     * the syllabus.
     */
    private static final Pattern OUTCOMES_LINE = Pattern.compile(
            "^(?:course\\s+(?:learning\\s+)?(?:outcomes?|objectives?)\\s*\\(\\s*clos?\\s*\\)"
            + "|course\\s+learning\\s+(?:outcomes?|objectives?)"
            + "|course\\s+outcomes?|learning\\s+outcomes?|expected\\s+outcomes?"
            + "|clos?\\s*:"
            + "|(?:after|upon|on)\\s+(?:the\\s+)?(?:successful\\s+)?completion\\s+of\\b)(?!\\p{L}).*",
            Pattern.CASE_INSENSITIVE);

    /** "The students will be able to:", "Students should be able to" - lead-in of an outcomes list. */
    private static final Pattern OUTCOME_LEAD_IN = Pattern.compile(
            "(?i).*\\b(?:able\\s+to|will\\s+be|should\\s+be|students?|learners?)\\b.*");

    /** A numbered or bulleted list item ("1. ...", "(2) ...", "a) ..."), used to recognise outcome lists. */
    private static final Pattern LIST_ITEM_LINE = Pattern.compile("^(?:\\(?\\d{1,2}[.)]|[a-z][.)]|\\u2022)\\s*.*");

    /** Page-footer "Approved in 1st meeting of Academic Council ... Revised in ..." (may wrap onto a second line). */
    private static final Pattern APPROVED_LINE = Pattern.compile("^approved\\s+in\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern APPROVED_TAIL = Pattern.compile(
            ".*\\b(?:council|revised|meeting|held)\\b.*", Pattern.CASE_INSENSITIVE);

    /** Where a soft skip ends. */
    private static final Pattern RESUME_LINE = Pattern.compile(
            "^(?:unit[-\\s]*(?:\\d+|[IVX]+)(?![A-Za-z]).*|syllabus\\b.*)$", Pattern.CASE_INSENSITIVE);

    /** Header-ish lines that are never syllabus content. */
    private static final Pattern SKIP_LINE = Pattern.compile(
            "^(?:(?:detailed\\s+)?syllabus|course\\s+contents?)\\s*:?\\s*$"
            + "|^(?:.*\\s)?L\\s+T\\s+P(?:\\s+Cr)?(?:\\s+[\\d.]+){0,4}\\s*$"   // "L T P Cr", "L T P", "MACHINE LEARNING L T P 3 0 2"
            + "|^\\d+\\s+\\d+\\s+\\d+(?:\\s+[\\d.]+)?\\s*$", Pattern.CASE_INSENSITIVE);

    /** A bare "Syllabus" / "Course contents" line. */
    private static final Pattern SYLLABUS_MARKER = Pattern.compile(
            "^(?:(?:detailed\\s+)?syllabus|course\\s+contents?)\\s*:?\\s*$", Pattern.CASE_INSENSITIVE);

    /** Units that are really metadata (dropped from the result). */
    private static final Pattern NON_CONTENT_NAME = Pattern.compile(
            "^(?:course\\s+objectives?|objectives?|pre-?requisites?|syllabus|course\\s+contents?)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Parse pasted syllabus text.
     * <p>
     * Supported shapes:
     * <ul>
     *   <li>numbered units ("Unit 1: Title:", "Unit-II", "1. Title") with bullet / plain lines beneath,
     *       including Word/PDF glyph bullets that paste as boxes;</li>
     *   <li>one unit per line ("Unit Name: a, b, c"), hard-wrapped or not;</li>
     *   <li>paragraph style (units separated by blank lines) where the unit paragraph contains
     *       inner sub-headings, e.g. "Acoustics: ...; Ultrasonics: ...". Inner sub-headings are kept as a
     *       prefix on their items ("Acoustics: Reverberation time") instead of becoming bogus units.</li>
     * </ul>
     * Trailing sections (Laboratory Work, Text Books, "Approved in ...") are ignored.
     */
    public static List<ParsedUnit> parseUnitList(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }

        List<String> lines = prepareLines(text);
        if (lines.stream().allMatch(String::isEmpty)) {
            return new ArrayList<>();
        }

        // Strategies are tried in their historical order and the first plausible result wins, so
        // every layout that parsed correctly before keeps exactly the same result. Only when a
        // strategy produces something implausible (empty units, outcome sentences as unit names...)
        // do the other readings compete, and the best-scoring one is returned.
        List<ParsedUnit> numbered = parseNumberedUnitLines(lines);
        if (!numbered.isEmpty() && isPlausible(numbered)) {
            return refineItems(numbered);
        }

        List<ParsedUnit> colon = parseColonUnits(lines);
        if (numbered.isEmpty() && colon.size() >= 2 && isPlausible(colon)) {
            return refineItems(colon);
        }

        List<List<ParsedUnit>> candidates = new ArrayList<>();
        candidates.add(numbered);
        candidates.add(colon);
        candidates.add(parseHeadingBlocks(lines));
        candidates.add(parseLeadClauseBlocks(lines));
        // single line containing several "Title Case:" headers
        long nonEmpty = lines.stream().filter(l -> !l.isEmpty()).count();
        if (nonEmpty == 1) {
            candidates.add(parseSingleLineUnits(lines.stream().filter(l -> !l.isEmpty()).findFirst().get()));
        }

        List<ParsedUnit> best = new ArrayList<>();
        double bestScore = Double.NEGATIVE_INFINITY;
        for (List<ParsedUnit> c : candidates) {
            double s = score(c);
            if (s > bestScore) { bestScore = s; best = c; }
        }
        return refineItems(best);
    }

    /** Items longer than this were almost certainly never split. */
    private static final int RUN_ON_ITEM = 120;

    /**
     * Last clean-up pass, touching only run-on items (longer than {@link #RUN_ON_ITEM}):
     * <ul>
     *   <li>an unclosed "(" in the source ("models (Residual approach, Walter model, ...") hides every
     *       comma after it, so the item is re-split as if the bracket were not there;</li>
     *   <li>items chained with spaced dashes ("A – B - C - D") are split on the dashes.</li>
     * </ul>
     * Normal-length items are returned unchanged, so well-parsed syllabi are not affected.
     */
    static List<ParsedUnit> refineItems(List<ParsedUnit> units) {
        for (ParsedUnit u : units) {
            Map<String, String> out = new LinkedHashMap<>();
            for (String item : u.getSubtopics()) {
                for (String piece : refineItem(item)) out.putIfAbsent(piece.toLowerCase(), piece);
            }
            u.setSubtopics(new ArrayList<>(out.values()));
        }
        return units;
    }

    private static List<String> refineItem(String item) {
        List<String> result = new ArrayList<>();
        if (item.length() <= RUN_ON_ITEM) {
            result.add(item);
            return result;
        }
        String prefix = "";
        String body = item;
        int colon = item.indexOf(": ");
        if (colon > 0 && colon < 60 && !item.substring(0, colon).contains("(")) {   // keep "Heading: " prefixes
            prefix = item.substring(0, colon + 2);
            body = item.substring(colon + 2);
        }
        List<String> parts;
        if (parenDepth(body) > 0) {
            parts = splitTopLevel(body.replace("(", " ").replace(")", " "), true);
        } else if (body.split("\\s[-\\u2013\\u2014]\\s").length >= 3) {
            parts = List.of(body.split("\\s[-\\u2013\\u2014]\\s"));
        } else {
            result.add(item);
            return result;
        }
        for (String p : parts) {
            String c = cleanItem(p);
            if (!c.isEmpty()) result.add(prefix + c);
        }
        if (result.isEmpty()) result.add(item);
        return result;
    }

    // ---------------------------------------------------------------------
    //  Choosing between readings
    // ---------------------------------------------------------------------

    /** Unit names that are really learning-outcome statements ("Understand ...", "Apply ..."). */
    private static final Pattern OUTCOME_VERB_NAME = Pattern.compile(
            "^(?:unit\\s+\\w+\\.\\s*)?(?:to\\s+)?(?:understand|apply|analy[sz]e|evaluate|determine|solve|learn|explain|"
            + "demonstrate|comprehend|design|develop|identify|describe|assess|examine|model|characteri[sz]e|find|"
            + "discriminate|establish|correlate|appreciate|implement|interpret|use|compute|formulate|construct|"
            + "recogni[sz]e|define|illustrate|summari[sz]e|compare|create|perform|make|acquire|gain|the\\s+basics)\\b.*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Sanity check on a reading: most units must have sub-topics and unit names must look like
     * titles, not sentences.
     */
    static boolean isPlausible(List<ParsedUnit> units) {
        if (units.isEmpty()) return false;
        int empty = 0, sentenceNames = 0;
        for (ParsedUnit u : units) {
            if (u.getSubtopics().isEmpty()) empty++;
            String n = u.getName();
            if (wordCount(n) > 12 || OUTCOME_VERB_NAME.matcher(n).matches()) sentenceNames++;
        }
        return empty * 3 <= units.size() && sentenceNames * 3 <= units.size();
    }

    /**
     * Quality score of a reading. Rewards units that carry sub-topics, penalises empty units,
     * sentence-like unit names and run-on items that were never split.
     */
    static double score(List<ParsedUnit> units) {
        if (units == null || units.isEmpty()) return Double.NEGATIVE_INFINITY;
        double s = 0;
        for (ParsedUnit u : units) {
            int n = u.getSubtopics().size();
            s += n == 0 ? -3 : 2 + 0.5 * Math.min(n, 12);
            String name = u.getName();
            if (wordCount(name) > 12) s -= 4;
            if (OUTCOME_VERB_NAME.matcher(name).matches()) s -= 4;
            for (String item : u.getSubtopics()) {
                if (item.length() > 160) s -= 1;
            }
        }
        if (units.size() == 1) s -= 4;   // one giant unit is rarely the intended structure
        return s;
    }

    // ---------------------------------------------------------------------
    //  Stand-alone headings: a short title on its own line, then a paragraph or a numbered list
    //
    //      Lexical Analysis                       Engineering Drawing Concepts
    //      Regular Expression, Finite ...         1. Introduction to Engineering Drawing
    //      ... Specification of Tokens.           2. Projection systems: First angle, ...
    // ---------------------------------------------------------------------

    private static final Pattern ITEM_MARKER = Pattern.compile("^(?:\\u2022|\\(?\\d{1,2}[.)]|[a-z][.)])\\s*");

    static List<ParsedUnit> parseHeadingBlocks(List<String> lines) {
        int maxLen = 0;
        for (String l : lines) maxLen = Math.max(maxLen, l.length());

        List<Integer> heads = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (isStandaloneHeading(lines, i, maxLen)) heads.add(i);
        }
        List<ParsedUnit> units = new ArrayList<>();
        if (heads.size() < 2) return units;

        for (int h = 0; h < heads.size(); h++) {
            int from = heads.get(h) + 1;
            int to = h + 1 < heads.size() ? heads.get(h + 1) : lines.size();
            String name = lines.get(heads.get(h)).replaceAll("[\\s:]+$", "").trim();

            boolean listBody = false;
            for (int i = from; i < to; i++) {
                if (ITEM_MARKER.matcher(lines.get(i)).find()) { listBody = true; break; }
            }

            Map<String, String> subs = new LinkedHashMap<>();
            if (listBody) {
                // numbered / bulleted items; unmarked lines continue the item above
                List<StringBuilder> items = new ArrayList<>();
                for (int i = from; i < to; i++) {
                    String l = lines.get(i);
                    if (l.isEmpty()) continue;
                    Matcher m = ITEM_MARKER.matcher(l);
                    if (m.find()) {
                        items.add(new StringBuilder(l.substring(m.end()).trim()));
                    } else if (!items.isEmpty()) {
                        appendLine(items.get(items.size() - 1), l);
                    } else {
                        items.add(new StringBuilder(l));
                    }
                }
                for (StringBuilder item : items) {
                    for (String clause : splitTopLevel(item.toString(), false)) {
                        for (String it : expandClause(clause)) subs.putIfAbsent(it.toLowerCase(), it);
                    }
                }
            } else {
                StringBuilder body = new StringBuilder();
                for (int i = from; i < to; i++) {
                    if (!lines.get(i).isEmpty()) appendLine(body, lines.get(i));
                }
                for (String it : extractSubtopics(body.toString())) subs.putIfAbsent(it.toLowerCase(), it);
            }
            if (NON_CONTENT_NAME.matcher(name).matches()) continue;
            units.add(new ParsedUnit(name, new ArrayList<>(subs.values())));
        }
        return units;
    }

    // ---------------------------------------------------------------------
    //  Lead-clause paragraphs: the first ';'-clause of each paragraph is the unit title
    //
    //      Basic Prompting Techniques; Writing clear and effective instructions; Using simple
    //      examples in prompts; Role-based prompting for different tasks
    // ---------------------------------------------------------------------

    static List<ParsedUnit> parseLeadClauseBlocks(List<String> lines) {
        List<StringBuilder> blocks = new ArrayList<>();
        StringBuilder cur = null;
        for (String l : lines) {
            if (l.isEmpty()) { cur = null; continue; }
            if (cur == null) { cur = new StringBuilder(); blocks.add(cur); }
            appendLine(cur, l);
        }

        List<ParsedUnit> units = new ArrayList<>();
        int leadBlocks = 0;
        for (StringBuilder b : blocks) {
            String text = b.toString().trim();
            String[] h = headerOf(text);
            int semi = topLevelIndex(text, ';');
            int colon = topLevelIndex(text, ':');
            if (h != null && (semi < 0 || (colon >= 0 && colon < semi))) {
                // an ordinary "Name: items" paragraph mixed into the document
                if (!NON_CONTENT_NAME.matcher(h[0]).matches()) units.add(new ParsedUnit(h[0], extractSubtopics(h[1])));
                continue;
            }
            if (semi > 0) {
                String lead = cleanItem(text.substring(0, semi));
                if (!lead.isEmpty() && wordCount(lead) <= 8 && Character.isUpperCase(lead.charAt(0))) {
                    List<String> items = new ArrayList<>();
                    for (String part : splitTopLevel(text.substring(semi + 1), false)) {
                        String c = cleanItem(part);
                        if (!c.isEmpty()) items.add(c);
                    }
                    units.add(new ParsedUnit(lead, items));
                    leadBlocks++;
                    continue;
                }
            }
            if (!units.isEmpty()) {
                for (String part : splitTopLevel(text, false)) {
                    String c = cleanItem(part);
                    if (!c.isEmpty()) units.get(units.size() - 1).addSubtopic(c);
                }
            }
        }
        return leadBlocks >= 2 ? units : new ArrayList<>();
    }

    /** Index of the first {@code ch} outside parentheses, or -1. */
    private static int topLevelIndex(String s, char ch) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (c == ch && depth == 0) return i;
        }
        return -1;
    }

    /**
     * A heading line: short, starts with a capital, carries no list punctuation, does not end a
     * sentence, starts a fresh block (after a blank line or a finished sentence) and is followed by
     * content rather than by another heading.
     */
    private static boolean isStandaloneHeading(List<String> lines, int i, int maxLen) {
        String l = lines.get(i);
        if (l.isEmpty() || ITEM_MARKER.matcher(l).find()) return false;
        String t = l.endsWith(":") ? l.substring(0, l.length() - 1).trim() : l;
        if (t.isEmpty()) return false;
        boolean capital = Character.isUpperCase(t.charAt(0))
                || (t.length() > 1 && Character.isDigit(t.charAt(0)) && Character.isLetter(t.charAt(1)));   // "2D Drafting"
        if (!capital) return false;
        if (wordCount(t) > 9 || t.length() > 70) return false;
        if (maxLen >= 60 && t.length() > 0.7 * maxLen) return false;
        if (t.matches(".*[,;:].*") || t.matches(".*[.!?]$")) return false;
        if (NON_CONTENT_NAME.matcher(t).matches() || OUTCOME_VERB_NAME.matcher(t).matches()) return false;

        int p = i - 1;
        if (p >= 0 && !lines.get(p).isEmpty() && !endsSentence(lines.get(p))) return false;

        int n = i + 1;
        while (n < lines.size() && lines.get(n).isEmpty()) n++;
        if (n >= lines.size()) return false;
        String next = lines.get(n);
        // the next line must be content: a list item or a line clearly longer than a heading
        return ITEM_MARKER.matcher(next).find() || next.length() > Math.max(t.length() + 10, 40);
    }

    // ---------------------------------------------------------------------
    //  Step 0: cleaning
    // ---------------------------------------------------------------------

    /**
     * Normalise raw pasted text into trimmed lines ("" = blank line):
     * unify line endings, drop invisible chars, turn glyph bullets (incl. the "box" characters
     * from Symbol/Wingdings fonts) into "• ", drop header/footer noise, cut off trailing sections.
     */
    static List<String> prepareLines(String text) {
        String t = text.replace("\r\n", "\n").replace('\r', '\n')
                .replace('\u2028', '\n').replace('\u2029', '\n')
                .replace('\u000B', '\n').replace('\f', '\n');
        t = t.replaceAll("[\\u00A0\\u2007\\u202F\\t]", " ");
        t = t.replaceAll("[\\u200B\\u200C\\u200D\\u2060\\uFEFF\\u00AD]", "");

        List<String> out = new ArrayList<>();
        boolean carryBullet = false;
        // 0 = reading, 1 = inside a soft-skipped section, 2 = just after an "Approved in" line,
        // 3 = inside an outcomes list (ends at a "Unit"/"Syllabus" line or a new "Title: ..." paragraph)
        int skip = 0;
        boolean outcomeBreak = true;   // in mode 3: the previous line closed a sentence or was blank

        for (String raw : t.split("\n", -1)) {
            String line = raw.trim();

            boolean bullet = false;
            Matcher gm = LEADING_GLYPH_BULLET.matcher(line);
            if (gm.find()) {
                bullet = true;
                line = line.substring(gm.end());
            }
            // glyph bullets in the middle of a line separate items; stray ones are just removed
            line = line.replaceAll("\\s[\\uE000-\\uF8FF\\uFFFD]+\\s", " ; ");
            line = line.replaceAll("[\\uE000-\\uF8FF\\uFFFD]", "");
            line = line.replaceAll(" {2,}", " ").trim();

            if (line.isEmpty()) {
                if (skip == 3) { outcomeBreak = true; continue; }
                if (skip == 1) continue;
                if (bullet) {
                    carryBullet = true;            // bullet alone on a line: belongs to the next text line
                } else if (out.isEmpty() || !out.get(out.size() - 1).isEmpty()) {
                    out.add("");                   // collapse consecutive blank lines
                }
                continue;
            }

            if (bullet || carryBullet) line = "\u2022 " + line;
            carryBullet = false;

            String probe = line.startsWith("\u2022 ") ? line.substring(2) : line;

            if (skip == 1) {
                if (RESUME_LINE.matcher(probe).matches()) skip = 0; else continue;
            } else if (skip == 3) {
                // Outcomes are numbered statements; the syllabus resumes at a unit heading or at an
                // unnumbered "Title: ..." line that starts a fresh sentence.
                String[] head = headerOf(probe);
                boolean resume = RESUME_LINE.matcher(probe).matches()
                        || (outcomeBreak && !LIST_ITEM_LINE.matcher(line).matches()
                            && Character.isUpperCase(probe.charAt(0)) && head != null
                            && !OUTCOME_LEAD_IN.matcher(head[0]).matches());
                if (!resume) {
                    outcomeBreak = endsSentence(probe);
                    continue;
                }
                skip = 0;
            } else if (skip == 2) {
                skip = 0;
                if (APPROVED_TAIL.matcher(probe).matches() && headerOf(probe) == null) continue;
            }

            if (SYLLABUS_MARKER.matcher(probe).matches()) {
                // "Syllabus" separates the intro from the content: keep it as a paragraph break
                if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) out.add("");
                continue;
            }
            if (SUBJECT_HEADER_PATTERN.matcher(probe).matches() || SKIP_LINE.matcher(probe).matches()) {
                continue;
            }
            if (HARD_STOP_LINE.matcher(probe).matches()) {
                break;                             // books / readings: nothing after is syllabus content
            }
            if (OUTCOMES_LINE.matcher(probe).matches()) { skip = 3; outcomeBreak = true; continue; }
            if (SOFT_SKIP_LINE.matcher(probe).matches()) { skip = 1; continue; }
            if (APPROVED_LINE.matcher(probe).matches()) { skip = 2; continue; }
            out.add(line);
        }

        while (!out.isEmpty() && out.get(0).isEmpty()) out.remove(0);
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    // ---------------------------------------------------------------------
    //  "Name: items" units (one per line, or one per blank-line separated paragraph)
    // ---------------------------------------------------------------------

    private static List<ParsedUnit> parseColonUnits(List<String> lines) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String l : lines) {
            if (l.isEmpty()) {
                if (!cur.isEmpty()) { blocks.add(cur); cur = new ArrayList<>(); }
            } else {
                cur.add(l);
            }
        }
        if (!cur.isEmpty()) blocks.add(cur);

        // Blank lines are only trusted as unit separators when at least two separate blocks
        // start with a "Name:" header. (A lone header paragraph above a block of one-unit-per-line
        // text must not swallow that block.)
        int headerLed = 0;
        for (List<String> b : blocks) {
            String[] h = headerOf(b.get(0));
            if (h != null && !NON_CONTENT_NAME.matcher(h[0]).matches()) headerLed++;
        }
        boolean paragraphMode = headerLed >= 2 && !(blocks.size() <= 2 && headerLed == 2 && blocks.get(0).size() > 4);

        List<String> names = new ArrayList<>();
        List<StringBuilder> bodies = new ArrayList<>();

        if (paragraphMode) {
            for (List<String> block : blocks) {
                boolean allHeaders = block.size() >= 2;
                for (String l : block) {
                    if (headerOf(l) == null) { allHeaders = false; break; }
                }
                if (allHeaders) {
                    feedLines(block, names, bodies);       // genuine one-unit-per-line group
                    continue;
                }
                String[] h = headerOf(block.get(0));
                if (h != null) {
                    // the paragraph starts a unit; a later line may still start another one
                    // ("...Radix Sort." / "Trees and their applications: ..."), decided line by line
                    int before = names.size();
                    feedLines(block, names, bodies, true);
                    if (names.size() == before) {           // feedLines declined the first line
                        names.add(h[0]);
                        bodies.add(new StringBuilder(h[1]));
                        for (int i = 1; i < block.size(); i++) appendLine(bodies.get(bodies.size() - 1), block.get(i));
                    }
                } else if (!bodies.isEmpty()) {
                    for (String l : block) appendLine(bodies.get(bodies.size() - 1), l);
                }
                // else: text before the first unit - ignored
            }
        } else {
            List<String> all = new ArrayList<>();
            for (String l : lines) if (!l.isEmpty()) all.add(l);
            feedLines(all, names, bodies);
        }

        List<ParsedUnit> units = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i).replaceAll("\\s+", " ").replaceAll("[\\s:]+$", "").trim();
            if (name.isEmpty() || NON_CONTENT_NAME.matcher(name).matches()) continue;
            units.add(new ParsedUnit(name, extractSubtopics(bodies.get(i).toString())));
        }
        return units;
    }

    /**
     * Line-by-line grouping: a line starts a new unit only if it is a plausible "Name: ..." header and
     * does not look like the wrapped tail of the previous line.
     */
    private static void feedLines(List<String> lines, List<String> names, List<StringBuilder> bodies) {
        feedLines(lines, names, bodies, false);
    }

    /** @param forceFirst the first line opens a new paragraph, so a header there always starts a unit */
    private static void feedLines(List<String> lines, List<String> names, List<StringBuilder> bodies,
                                  boolean forceFirst) {
        // Bullet-led units ("• Verbal Ability: ...", "• Quantitative Reasoning: ..."): when the headers
        // are bulleted, ONLY bulleted lines may start a unit - everything else is wrapped text.
        int bulletHeaders = 0;
        boolean firstHeaderBulleted = false, seenHeader = false;
        for (String l : lines) {
            if (headerOf(l) == null) continue;
            if (!seenHeader) { firstHeaderBulleted = l.startsWith("\u2022"); seenHeader = true; }
            if (l.startsWith("\u2022")) bulletHeaders++;
        }
        boolean bulletUnits = firstHeaderBulleted && bulletHeaders >= 2;

        int maxLen = 0;
        for (String l : lines) maxLen = Math.max(maxLen, l.length());

        // Some booklets end every unit's last line with a stray comma ("... Bio sensors,") before the
        // next "Quantum Size Effect: ..." line. When that happens at least twice with short Title-Case
        // headings, a comma no longer proves the next heading is wrapped text.
        // A label that occurs more than once ("RAPIDS acceleration: ...") is an inline tag inside a unit,
        // never a unit of its own.
        String lowerDoc = String.join("\n", lines).toLowerCase();
        java.util.function.Predicate<String> uniqueLabel = title -> {
            String needle = title.toLowerCase() + ":";
            int first = lowerDoc.indexOf(needle);
            return first >= 0 && lowerDoc.indexOf(needle, first + 1) < 0;
        };
        int commaLed = 0;
        for (int i = 1; i < lines.size(); i++) {
            String[] h = headerOf(lines.get(i));
            if (h != null && lines.get(i - 1).trim().endsWith(",") && isTitleCaseHeading(h[0])
                    && uniqueLabel.test(h[0])) commaLed++;
        }
        boolean trailingCommaStyle = commaLed >= 2;

        String prev = null;
        boolean first = true;
        for (String line : lines) {
            String[] h = headerOf(line);
            boolean bulleted = line.startsWith("\u2022");
            boolean startsUnit = h != null;
            boolean forced = first && forceFirst && startsUnit;
            first = false;

            if (startsUnit && bulletUnits && !bulleted && !forced) startsUnit = false;

            if (startsUnit && !forced && !bulletUnits && !bodies.isEmpty()) {
                StringBuilder last = bodies.get(bodies.size() - 1);
                int words = wordCount(h[0]);
                boolean strayComma = trailingCommaStyle && last.toString().trim().endsWith(",")
                        && parenDepth(last) <= 0 && isTitleCaseHeading(h[0]) && uniqueLabel.test(h[0]);
                if (endsOpen(last) && !strayComma) {
                    // previous line stops mid-list (",", ";", "-", ":" or open "(" or "and"): a wrapped inner heading
                    startsUnit = false;
                } else if (prev != null && !endsSentence(prev)
                        && (h[0].contains(",") || ((words <= 1 || prev.matches(".*-\\w+$")) && prev.length() >= Math.max(60, 0.8 * maxLen) && similarWidth(prev, line)))) {
                    startsUnit = false;
                } else if (Character.isLowerCase(h[0].charAt(0))) {
                    startsUnit = false;           // "author's purpose, Introduction to X: ..." is wrapped text
                }
            }

            if (startsUnit) {
                names.add(h[0]);
                bodies.add(new StringBuilder(h[1]));
            } else if (!bodies.isEmpty()) {
                appendLine(bodies.get(bodies.size() - 1), line);
            }
            prev = line;
        }
    }

    /** 1-6 words, first word capitalised, and no lower-case word except short joiners ("and", "of", ...) ... */
    private static boolean isTitleCaseHeading(String title) {
        String[] words = title.trim().split("\\s+");
        if (words.length == 0 || words.length > 6 || !Character.isUpperCase(words[0].charAt(0))) return false;
        int lower = 0;
        for (String w : words) {
            if (!w.isEmpty() && Character.isLowerCase(w.charAt(0))
                    && !w.matches("(?i)and|of|in|for|to|the|on|with|&")) lower++;
        }
        return lower <= 1;   // "Synthesis and processing" still counts
    }

    /** Two physical lines of (almost) the same width: typical of hard-wrapped text. */
    private static boolean similarWidth(String a, String b) {
        int x = a.length(), y = b.length();
        return Math.min(x, y) >= 0.88 * Math.max(x, y);
    }

    private static boolean endsSentence(String line) {
        String t = line.trim();
        if (t.isEmpty()) return true;
        char c = t.charAt(t.length() - 1);
        return c == '.' || c == ';' || c == ':' || c == '!' || c == '?';
    }

    /** {title, rest} if the line looks like "Unit Name: items...", else null. */
    private static String[] headerOf(String line) {
        if (line == null) return null;
        if (line.startsWith("\u2022")) line = line.substring(1).trim();   // "• Unit Name: ..." (bullet-led units)
        Matcher m = UNIT_LINE_PATTERN.matcher(line);
        if (!m.matches()) return null;
        String title = m.group(1).trim();
        if (title.isEmpty() || title.contains(";") || !isPlausibleUnitName(title)) return null;
        if (title.matches("(?i)Category\\s+\\d+.*")) return null;
        if (title.matches(".*\\.\\s+\\S.*")) return null;            // "foo. Bar:" -> sentence, not a title
        if (!title.matches(".*[\\p{L}\\p{N}].*")) return null;
        return new String[]{title, m.group(2).trim()};
    }

    private static int wordCount(String s) {
        return s.trim().isEmpty() ? 0 : s.trim().split("\\s+").length;
    }

    private static boolean endsOpen(CharSequence sb) {
        int i = sb.length() - 1;
        while (i >= 0 && Character.isWhitespace(sb.charAt(i))) i--;
        if (i < 0) return false;
        char c = sb.charAt(i);
        if (c == ',' || c == ';' || c == '-' || c == '\u2013' || c == '\u2014' || c == ':' || parenDepth(sb) > 0) return true;
        String s = sb.toString().trim().toLowerCase();
        return s.endsWith(" and") || s.endsWith(" or") || s.endsWith(" with") || s.endsWith(" of") || s.endsWith(" in");
    }

    /** Append a wrapped physical line to a unit body (bullets act as item separators). */
    private static void appendLine(StringBuilder sb, String line) {
        String l = line.trim();
        boolean bullet = l.startsWith("\u2022");
        if (bullet) l = l.substring(1).trim();
        if (l.isEmpty()) return;
        if (sb.length() == 0) { sb.append(l); return; }
        if (bullet || l.matches("(?i)Category\\s+\\d+:.*")) { sb.append(" ; ").append(l); return; }
        int n = sb.length();
        // "Non-" + "reflecting" -> "Non-reflecting", but "Applications -" + "Foo" keeps the space
        if (sb.charAt(n - 1) == '-' && n >= 2 && Character.isLetter(sb.charAt(n - 2))) {
            sb.append(l);
        } else {
            sb.append(' ').append(l);
        }
    }

    // ---------------------------------------------------------------------
    //  Sub-topic extraction for "Name: items" units
    // ---------------------------------------------------------------------

    /**
     * Split a unit body into sub-topics. Inner sub-headings ("Acoustics: ...", "Diffraction: ...") that
     * follow ';' / '.' (or start the body) scope the items after them and are kept as an
     * "Heading: item" prefix.
     */
    private static List<String> extractSubtopics(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String[] seg : splitSubSections(body)) {
            String prefix = seg[0];
            for (String item : splitTopLevel(seg[1], true)) {
                String c = cleanItem(item);
                if (c.isEmpty()) continue;
                if (prefix != null && !c.toLowerCase().startsWith(prefix.toLowerCase() + ":")) c = prefix + ": " + c;
                out.putIfAbsent(c.toLowerCase(), c);
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Returns [prefixOrNull, text] segments. */
    private static List<String[]> splitSubSections(String body) {
        List<String[]> segs = new ArrayList<>();
        int segStart = 0;
        String prefix = null;
        int depth = 0;

        for (int c = 0; c < body.length(); c++) {
            char ch = body.charAt(c);
            if (ch == '(') depth++;
            else if (ch == ')') depth = Math.max(0, depth - 1);
            else if (ch == ':' && depth == 0
                    && (c + 1 == body.length() || Character.isWhitespace(body.charAt(c + 1)))) {
                int s = findTitleStart(body, c, segStart);
                if (s < 0) continue;
                String title = body.substring(s, c).trim();
                if (!isSubHeaderTitle(title)) continue;
                String before = body.substring(segStart, s);
                if (!before.trim().isEmpty()) segs.add(new String[]{prefix, before});
                prefix = title;
                segStart = c + 1;
            } else if (ch == '.' && depth == 0 && prefix != null
                    && isSentenceEnd(body, c, body.substring(segStart, c))) {
                // a full stop closes the scope of the current sub-heading
                segs.add(new String[]{prefix, body.substring(segStart, c + 1)});
                prefix = null;
                segStart = c + 1;
            }
        }
        segs.add(new String[]{prefix, body.substring(segStart)});
        return segs;
    }

    /** Start index of the heading that ends at colon index c, or -1 if there is none. */
    private static int findTitleStart(String s, int c, int segStart) {
        for (int j = c - 1; j >= segStart; j--) {
            char ch = s.charAt(j);
            if (ch == '(' || ch == ')') return -1;
            if (ch == ',') {
                String candidate = s.substring(j + 1, c).trim();
                if (candidate.matches("(?i)(?:Category\\s+\\d+|Introduction\\s+to\\b|Advanced\\b|Algebra\\b|Review\\b|\\*).*")) {
                    return skipSpaces(s, j + 1);
                }
                return -1;
            }
            if (ch == ';' || ch == ':') return skipSpaces(s, j + 1);
            if (ch == '.' && j + 1 < s.length() && Character.isWhitespace(s.charAt(j + 1))) return skipSpaces(s, j + 1);
        }
        return skipSpaces(s, segStart);
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static boolean isSubHeaderTitle(String t) {
        if (t.isEmpty() || t.length() > 80 || wordCount(t) > 8) return false;
        return Character.isUpperCase(t.charAt(0));
    }

    /**
     * Split on ';' (always), ',' (optional) and sentence-ending '.', ignoring anything inside parentheses.
     * A '.' ends a sentence only when followed by an uppercase word and preceded by 3+ lowercase letters,
     * so "e.g. Foo", "vs. Foo" and "Fig. 3" are left alone.
     */
    private static List<String> splitTopLevel(String s, boolean commas) {
        List<String> res = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') { depth++; cur.append(ch); }
            else if (ch == ')') { depth = Math.max(0, depth - 1); cur.append(ch); }
            else if (depth == 0 && (ch == ';' || (commas && ch == ','))) {
                res.add(cur.toString()); cur.setLength(0);
            } else if (depth == 0 && ch == '.' && isSentenceEnd(s, i, cur)) {
                res.add(cur.toString()); cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        res.add(cur.toString());
        return res;
    }

    private static boolean isSentenceEnd(String s, int dot, CharSequence before) {
        if (dot + 1 >= s.length() || !Character.isWhitespace(s.charAt(dot + 1))) return false;
        int k = dot + 1;
        while (k < s.length() && Character.isWhitespace(s.charAt(k))) k++;
        if (k >= s.length() || !Character.isUpperCase(s.charAt(k))) return false;
        if (before.length() > 0 && before.charAt(before.length() - 1) == ')') return true;   // "(WTO). Global ..."
        int lower = 0;
        for (int i = before.length() - 1; i >= 0 && Character.isLowerCase(before.charAt(i)); i--) lower++;
        return lower >= 3;
    }

    private static String cleanItem(String s) {
        String c = s.replaceAll("\\s+", " ").trim();
        c = c.replaceFirst("^[\\u2022,;.:]+\\s*", "");
        c = c.replaceFirst("^[-\\u2013\\u2014]\\s+", "");
        c = c.replaceFirst("(?:\\s+[-\\u2013\\u2014])?[\\s,;.:]*$", "").trim();   // trailing punctuation / dangling dash
        c = c.replaceFirst("^(?:and|or)\\s+(?=\\S)", "");                            // "..., and opera" -> "opera"
        return c.matches(".*[\\p{L}\\p{N}].*") ? c : "";
    }

    // ---------------------------------------------------------------------
    //  Numbered units: "Unit 1: Title:", "Unit-II", "1. Title"
    // ---------------------------------------------------------------------

    /** "Unit 1. Title", "Unit 2: Title", "Unit-I", "Unit-II", "1. Title", "2: Title" */
    private static final Pattern NUMBERED_UNIT_PATTERN = Pattern.compile(
            "^\\s*(?:unit[-\\s]*(\\d+|[IVX]+)(?![A-Za-z])|(?<![\\w.])(\\d{1,2})(?=\\s*[.:)](?:\\s|$)))"   // 1-2 digits: "2025." is a wrapped year, not unit 2025
            + "\\s*[.:)\\-\\u2013\\u2014]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    /** Leading bullet / list markers on a subtopic line. */
    private static final Pattern BULLET_PREFIX = Pattern.compile(
            "^(?:[\\u2022\\u25CF\\u25CB\\u25AA\\u25E6\\u00B7*\\-\\u2013\\u2014]|\\(?\\d+[.)]|[a-z][.)])\\s+");

    /**
     * Parse syllabus text where main units are explicitly numbered.
     * Returns an empty list when the text has no numbered unit headings.
     */
    static List<ParsedUnit> parseNumberedUnits(String text) {
        return parseNumberedUnitLines(prepareLines(text));
    }

    private static List<ParsedUnit> parseNumberedUnitLines(List<String> lines) {
        List<ParsedUnit> units = new ArrayList<>();

        boolean anyHeader = false;
        for (String l : lines) {
            if (NUMBERED_UNIT_PATTERN.matcher(l).matches()) { anyHeader = true; break; }
        }
        if (!anyHeader) return units;

        // Pass 1: group lines under their numbered header
        List<String> names = new ArrayList<>();
        List<String> inlines = new ArrayList<>();
        List<Integer> headerLens = new ArrayList<>();
        List<List<String>> groups = new ArrayList<>();

        for (String line : lines) {
            if (line.isEmpty()) continue;
            Matcher m = NUMBERED_UNIT_PATTERN.matcher(line);
            if (m.matches()) {
                String unitNum = m.group(1) != null ? m.group(1) : m.group(2);
                String rest = m.group(3).trim();

                // Title ends at the first colon outside parentheses
                int depth = 0, colon = -1;
                for (int i = 0; i < rest.length(); i++) {
                    char c = rest.charAt(i);
                    if (c == '(') depth++;
                    else if (c == ')') depth = Math.max(0, depth - 1);
                    else if (c == ':' && depth == 0) { colon = i; break; }
                }
                String title = (colon >= 0 ? rest.substring(0, colon) : rest).trim();
                String inline = colon >= 0 ? rest.substring(colon + 1).trim() : "";

                names.add(title.isEmpty() ? "Unit " + unitNum : "Unit " + unitNum + ". " + title);
                inlines.add(inline);
                headerLens.add(line.length());
                groups.add(new ArrayList<>());
            } else if (!names.isEmpty()) {
                groups.get(groups.size() - 1).add(line);
            }
            // else: text before the first unit - ignored
        }

        int maxBodyLen = 0;
        for (List<String> g : groups) for (String l : g) maxBodyLen = Math.max(maxBodyLen, l.length());
        for (int i = 0; i < inlines.size(); i++) maxBodyLen = Math.max(maxBodyLen, headerLens.get(i));
        double wrapThreshold = Math.max(50, 0.8 * maxBodyLen);

        // Pass 2: turn each group into sub-topics
        for (int u = 0; u < names.size(); u++) {
            List<String> grp = groups.get(u);

            // if the unit uses bullets at all, every non-bulleted line is a wrapped continuation
            boolean bulleted = false;
            for (String l : grp) {
                if (BULLET_PREFIX.matcher(l).find()) { bulleted = true; break; }
            }

            List<String> fragments = new ArrayList<>();
            StringBuilder pending = inlines.get(u).isEmpty() ? null : new StringBuilder(inlines.get(u));
            int prevLen = headerLens.get(u);

            for (String raw : grp) {
                String line = raw;
                Matcher b = BULLET_PREFIX.matcher(line);
                boolean isBullet = b.find();
                if (isBullet) line = line.substring(b.end()).trim();
                if (line.isEmpty()) continue;

                // a full-width physical line that stops mid-phrase was hard-wrapped:
                // "...Learning theories, Behavior" / "Modification."
                boolean wrapped = prevLen >= wrapThreshold && pending != null && !endsSentence(pending.toString());
                boolean continuation = !isBullet && pending != null && pending.length() > 0
                        && (bulleted || wrapped || endsOpen(pending) || Character.isLowerCase(line.charAt(0)));
                prevLen = raw.length();

                if (continuation) {
                    appendLine(pending, line);
                } else {
                    if (pending != null) fragments.add(pending.toString());
                    pending = new StringBuilder(line);
                }
            }
            if (pending != null) fragments.add(pending.toString());

            Map<String, String> subs = new LinkedHashMap<>();
            for (String fragment : fragments) {
                for (String clause : splitTopLevel(fragment, false)) {
                    for (String item : expandClause(clause)) {
                        subs.putIfAbsent(item.toLowerCase(), item);
                    }
                }
            }
            units.add(new ParsedUnit(names.get(u), new ArrayList<>(subs.values())));
        }

        return units;
    }

    /**
     * One ';'-clause of a bullet. "network theorems: A, B, C" becomes
     * ["network theorems: A", "network theorems: B", "network theorems: C"] so each theorem is tracked
     * separately without losing what it belongs to; "Inheritance: extends and super" stays one item.
     */
    private static List<String> expandClause(String clause) {
        List<String> result = new ArrayList<>();
        int depth = 0, colon = -1;
        for (int i = 0; i < clause.length(); i++) {
            char c = clause.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (c == ':' && depth == 0
                    && (i + 1 == clause.length() || Character.isWhitespace(clause.charAt(i + 1)))) { colon = i; break; }
        }

        if (colon > 0) {
            String label = clause.substring(0, colon).replaceAll("\\s+", " ").trim();
            if (!label.isEmpty() && !label.contains(",") && wordCount(label) <= 8) {
                List<String> items = new ArrayList<>();
                for (String part : splitTopLevel(clause.substring(colon + 1), true)) {
                    String c = cleanItem(part);
                    if (!c.isEmpty()) items.add(c);
                }
                if (items.size() >= 2) {
                    for (String it : items) result.add(label + ": " + it);
                    return result;
                }
                String whole = cleanItem(clause);
                if (!whole.isEmpty()) result.add(whole);
                return result;
            }
        }

        for (String part : splitTopLevel(clause, true)) {
            String c = cleanItem(part);
            if (!c.isEmpty()) result.add(c);
        }
        return result;
    }

    private static int parenDepth(CharSequence s) {
        int d = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '(') d++;
            else if (s.charAt(i) == ')') d--;
        }
        return d;
    }

    /**
     * Fallback parser for single-line input with multiple "Title Case:" headers.
     */
    private static List<ParsedUnit> parseSingleLineUnits(String text) {
        List<ParsedUnit> units = new ArrayList<>();
        
        // Pattern: 2-8 capitalized words followed by colon
        Pattern headerPattern = Pattern.compile(
            "(?:^|\\s)([A-Z][a-zA-Z]*(?:\\s+(?:of|and|in|for|to|[A-Z][a-zA-Z]*))+)\\s*:",
            Pattern.MULTILINE
        );

        Matcher matcher = headerPattern.matcher(text);
        List<Integer> headerStarts = new ArrayList<>();
        List<String> headerNames = new ArrayList<>();

        while (matcher.find()) {
            String header = matcher.group(1).trim();
            String[] words = header.split("\\s+");
            
            // Validate: 2-8 words, mostly capitalized
            if (words.length >= 2 && words.length <= 8) {
                int capitalCount = 0;
                for (String word : words) {
                    if (!word.isEmpty() && Character.isUpperCase(word.charAt(0))) {
                        capitalCount++;
                    }
                }
                
                if (capitalCount >= words.length - 1) {
                    headerStarts.add(matcher.start());
                    headerNames.add(header);
                }
            }
        }

        // Extract content between headers
        for (int i = 0; i < headerStarts.size(); i++) {
            int start = text.indexOf(':', headerStarts.get(i)) + 1;
            int end = (i < headerStarts.size() - 1) ? headerStarts.get(i + 1) : text.length();
            
            String subtopicsText = text.substring(start, end).trim();
            
            // Find where next header starts within this text
            for (int j = i + 1; j < headerNames.size(); j++) {
                int nextHeaderPos = subtopicsText.indexOf(headerNames.get(j) + ":");
                if (nextHeaderPos > 0) {
                    subtopicsText = subtopicsText.substring(0, nextHeaderPos).trim();
                    break;
                }
            }

            List<String> rawSubtopics = splitRespectingParentheses(subtopicsText, ',');
            List<String> cleanedSubtopics = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            for (String subtopic : rawSubtopics) {
                String cleaned = subtopic.trim();
                cleaned = cleaned.replaceAll("\\s+", " ");
                cleaned = cleaned.replaceAll(",$", "");
                
                // Remove trailing header text
                for (String headerName : headerNames) {
                    if (cleaned.endsWith(headerName)) {
                        cleaned = cleaned.substring(0, cleaned.length() - headerName.length()).trim();
                    }
                }
                
                if (!cleaned.isEmpty() && seen.add(cleaned.toLowerCase())) {
                    cleanedSubtopics.add(cleaned);
                }
            }

            if (!cleanedSubtopics.isEmpty()) {
                ParsedUnit unit = new ParsedUnit(headerNames.get(i), cleanedSubtopics);
                units.add(unit);
            }
        }

        return units;
    }

    /**
     * Split text on delimiters (comma or semicolon) while respecting parentheses.
     * "AI (Artificial Intelligence); ML" -> ["AI (Artificial Intelligence)", "ML"]
     * "a, b; c" -> ["a", "b", "c"]
     */
    public static List<String> splitRespectingParentheses(String text, char delimiter) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return result;
        }

        StringBuilder current = new StringBuilder();
        int parenDepth = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '(') {
                parenDepth++;
                current.append(c);
            } else if (c == ')') {
                parenDepth--;
                current.append(c);
            } else if ((c == delimiter || c == ';' || c == ',') && parenDepth == 0) {
                // Split here on comma or semicolon
                String part = current.toString().trim();
                if (!part.isEmpty()) {
                    result.add(part);
                }
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }

        // Add last part
        String part = current.toString().trim();
        if (!part.isEmpty()) {
            result.add(part);
        }

        return result;
    }
}