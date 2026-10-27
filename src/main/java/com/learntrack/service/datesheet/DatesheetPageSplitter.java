package com.learntrack.service.datesheet;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a pasted datesheet into pages using every signal the extraction tool may leave behind.
 *
 * <p>A page break is recognised at a form feed, at a repeated institute header (even when the header
 * is glued to the end of another line, as happens with some PDF extractions) and after a
 * "Page N of M" marker. Anchors that lie within {@link #ANCHOR_MERGE_WINDOW} characters of each
 * other describe the same break, so only the earliest of them is used.</p>
 */
public final class DatesheetPageSplitter {

    /** Headers, form feeds and page markers belonging to one break sit within this many characters. */
    private static final int ANCHOR_MERGE_WINDOW = 80;

    private static final Pattern HEADER = Pattern.compile(
            "(?i)THAPAR\\s+INSTITUTE\\s+OF\\s+ENGINEERING"
    );

    private static final Pattern PAGE_MARKER = Pattern.compile(
            "(?i)\\bpage\\s+\\d+\\s+of\\s+\\d+\\b"
    );

    private DatesheetPageSplitter() {
    }

    /**
     * Splits the text into page bodies. Text before the first anchor (a title or a leading header)
     * is returned as the first page, so no content is ever dropped.
     */
    public static List<String> split(String text) {
        List<String> pages = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return pages;
        }

        List<Integer> sorted = chooseAnchors(text);

        List<Integer> kept = new ArrayList<>();
        for (Integer anchor : sorted) {
            if (anchor <= 0 || anchor >= text.length()) {
                continue;
            }
            if (kept.isEmpty() || anchor - kept.get(kept.size() - 1) > ANCHOR_MERGE_WINDOW) {
                kept.add(anchor);
            }
        }

        int start = 0;
        for (Integer anchor : kept) {
            addPage(pages, text.substring(start, anchor));
            start = anchor;
        }
        addPage(pages, text.substring(start));
        return pages;
    }

    /**
     * Picks the page-break signal that is actually present: repeated institute headers are the most
     * reliable one, because a header always starts a page. Form feeds are used when the headers were
     * not copied, and "Page N of M" markers as the last resort. Only one family is used at a time so
     * that a page is never cut twice (a marker at the bottom of a page and the header at the top of
     * the next describe the same break).
     */
    private static List<Integer> chooseAnchors(String text) {
        List<Integer> headers = new ArrayList<>();
        Matcher header = HEADER.matcher(text);
        while (header.find()) {
            headers.add(header.start());
        }
        if (headers.size() >= 2) {
            return headers;
        }

        List<Integer> formFeeds = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\f') {
                formFeeds.add(i + 1);
            }
        }
        if (!formFeeds.isEmpty()) {
            return formFeeds;
        }

        List<Integer> markers = new ArrayList<>();
        Matcher marker = PAGE_MARKER.matcher(text);
        while (marker.find()) {
            markers.add(marker.end());
        }
        return markers;
    }

    private static void addPage(List<String> pages, String page) {
        String trimmed = page.replace("\f", "\n").trim();
        if (!trimmed.isEmpty()) {
            pages.add(trimmed);
        }
    }
}
