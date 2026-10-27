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
            "^([A-Z]{3}\\d{3})\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

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
            "^(?:text\\s*books?|reference\\s*books?|recommended\\s+(?:books|readings?)|suggested\\s+readings?)\\b.*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Soft skip: the section is ignored until the next "Unit ..." heading or a "Syllabus" line
     * (Laboratory Work sits in the middle of multi-unit syllabi; Course Outcomes sit above the syllabus).
     */
    private static final Pattern SOFT_SKIP_LINE = Pattern.compile(
            "^(?:laboratory\\s+work|lab(?:oratory)?\\s+(?:work|experiments?)|course\\s+(?:learning\\s+)?outcomes?)\\b.*",
            Pattern.CASE_INSENSITIVE);

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
            + "|^L\\s+T\\s+P\\s+Cr\\s*$"
            + "|^\\d+\\s+\\d+\\s+\\d+\\s+[\\d.]+\\s*$", Pattern.CASE_INSENSITIVE);

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

        List<ParsedUnit> numbered = parseNumberedUnitLines(lines);
        if (!numbered.isEmpty()) {
            return numbered;
        }

        List<ParsedUnit> units = parseColonUnits(lines);

        // Fallback: single line containing several "Title Case:" headers
        long nonEmpty = lines.stream().filter(l -> !l.isEmpty()).count();
        if (units.isEmpty() && nonEmpty == 1) {
            units.addAll(parseSingleLineUnits(lines.stream().filter(l -> !l.isEmpty()).findFirst().get()));
        }
        return units;
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
        int skip = 0;   // 0 = reading, 1 = inside a soft-skipped section, 2 = just after an "Approved in" line

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
            } else if (skip == 2) {
                skip = 0;
                if (APPROVED_TAIL.matcher(probe).matches() && headerOf(probe) == null) continue;
            }

            if (SUBJECT_HEADER_PATTERN.matcher(probe).matches() || SKIP_LINE.matcher(probe).matches()) {
                continue;
            }
            if (HARD_STOP_LINE.matcher(probe).matches()) {
                break;                             // books / readings: nothing after is syllabus content
            }
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
                    names.add(h[0]);
                    bodies.add(new StringBuilder(h[1]));
                    for (int i = 1; i < block.size(); i++) appendLine(bodies.get(bodies.size() - 1), block.get(i));
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

        String prev = null;
        for (String line : lines) {
            String[] h = headerOf(line);
            boolean bulleted = line.startsWith("\u2022");
            boolean startsUnit = h != null;

            if (startsUnit && bulletUnits && !bulleted) startsUnit = false;

            if (startsUnit && !bulletUnits && !bodies.isEmpty()) {
                StringBuilder last = bodies.get(bodies.size() - 1);
                int words = wordCount(h[0]);
                if (endsOpen(last)) {
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
            "^\\s*(?:unit[-\\s]*(\\d+|[IVX]+)(?![A-Za-z])|(?<![\\w.])(\\d+)(?=\\s*[.:)](?:\\s|$)))"
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