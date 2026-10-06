package org.wetype.quick.integration;

import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Read-only deletion planning. No editor text or connection is retained after
 * a call; plans contain positions only. The caller owns batching, selection
 * prediction and the actual edit.
 * A plan must be applied immediately to the same InputConnection.
 */
public final class EditorDeletion {
    public static final int NO_OP = 0;
    public static final int DELETE_BEFORE = 1;
    public static final int REPLACE_SELECTION = 2;
    public static final int SEND_DEL = 3;
    public static final int UNSUPPORTED = 4;
    private static final int BEFORE_LIMIT = 2048;
    private static final int AFTER_LIMIT = 32;

    private EditorDeletion() { }

    /** All positions and lengths use UTF-16 units; -1 means unknown. */
    public static final class DeletionPlan {
        public final int operation;
        public final int beforeUtf16;
        public final int selectionStart;
        public final int selectionEnd;
        public final int expectedSelectionStart;
        public final int expectedSelectionEnd;
        /** Fixed diagnostic identifier; never contains editor text. */
        public final String reason;

        private DeletionPlan(int operation, int beforeUtf16, Selection selection,
                int expected, String reason) {
            this.operation = operation;
            this.beforeUtf16 = beforeUtf16;
            this.selectionStart = selection == null ? -1 : selection.start;
            this.selectionEnd = selection == null ? -1 : selection.end;
            this.expectedSelectionStart = expected;
            this.expectedSelectionEnd = expected;
            this.reason = reason;
        }
    }

    /**
     * Plans one editor backspace. Cached positions are compatibility hints only:
     * they cannot authorize deleting text or replacing a selection. A SEND_DEL
     * plan asks the caller to stop repeat and let the editor handle one DEL.
     */
    public static DeletionPlan planSingle(InputConnection connection,
            int cachedStart, int cachedEnd) {
        if (connection == null) return refused(null, "no-input-connection");
        Selection selection = readSelection(connection);
        if (selection == null) return delegate(null, "selection-unavailable");
        if (selection.start != selection.end) {
            return new DeletionPlan(REPLACE_SELECTION, 0, selection,
                    Math.min(selection.start, selection.end), "selected-text");
        }
        if (selection.start == 0) {
            return new DeletionPlan(NO_OP, 0, selection, 0, "cursor-at-start");
        }

        CharSequence before;
        CharSequence after;
        try {
            before = connection.getTextBeforeCursor(BEFORE_LIMIT, 0);
            after = connection.getTextAfterCursor(AFTER_LIMIT, 0);
        } catch (RuntimeException unavailable) {
            return delegate(selection, "context-unavailable");
        } catch (LinkageError unavailable) {
            return delegate(selection, "context-unavailable");
        }
        Selection checked = readSelection(connection);
        if (checked == null || !selection.sameAs(checked)) {
            return refused(checked, "selection-changed");
        }
        if (before == null || after == null) {
            return delegate(selection, "context-unavailable");
        }
        int beforeLength = before.length();
        int afterLength = after.length();
        if (beforeLength == 0 || beforeLength > BEFORE_LIMIT
                || afterLength > AFTER_LIMIT || beforeLength > selection.start) {
            return refused(selection, "invalid-context-window");
        }

        // A short response is not proof of the document's beginning. Only the
        // fresh absolute cursor can establish that the entire prefix is here.
        boolean fullPrefix = beforeLength == selection.start;
        String text = before.toString() + after.toString();
        int cursor = beforeLength;
        // Bounded windows may bisect a surrogate at their far edges. Discard
        // that incomplete edge only; never discard anything next to the cursor.
        if (!fullPrefix && Character.isLowSurrogate(text.charAt(0))) {
            text = text.substring(1);
            cursor--;
        }
        if (afterLength == AFTER_LIMIT && text.length() > cursor
                && Character.isHighSurrogate(text.charAt(text.length() - 1))) {
            text = text.substring(0, text.length() - 1);
        }
        if (cursor <= 0 || !validUtf16(text)) {
            return refused(selection, "incomplete-code-point");
        }

        int boundary;
        try {
            // Android ICU (API 24+) understands extended character boundaries.
            // Reflection keeps this source compatible with the API 16 stub.
            Class<?> type = Class.forName("android.icu.text.BreakIterator");
            Object iterator = type.getMethod("getCharacterInstance", Locale.class)
                    .invoke(null, Locale.ROOT);
            type.getMethod("setText", String.class).invoke(iterator, text);
            boolean atBoundary = ((Boolean) type.getMethod("isBoundary", Integer.TYPE)
                    .invoke(iterator, Integer.valueOf(cursor))).booleanValue();
            if (!atBoundary) return refused(selection, "cursor-inside-grapheme");
            boundary = ((Integer) type.getMethod("preceding", Integer.TYPE)
                    .invoke(iterator, Integer.valueOf(cursor))).intValue();
        } catch (Exception unavailable) {
            return delegate(selection, "grapheme-api-unavailable");
        } catch (LinkageError unavailable) {
            return delegate(selection, "grapheme-api-unavailable");
        }
        if (boundary < 0 || boundary >= cursor) {
            return refused(selection, "grapheme-boundary-unavailable");
        }
        if (!fullPrefix && boundary == 0) {
            return refused(selection, "truncated-grapheme");
        }
        // Older ICU versions may not recognize newer emoji sequences. Never
        // use one of their reported boundaries to cut a visible ZWJ sequence,
        // modifier, variation selector, or combining-mark sequence ourselves.
        if (boundary > 0 && (codePointBefore(text, boundary) == 0x200d
                || isExtension(Character.codePointAt(text, boundary)))) {
            return refused(selection, "ambiguous-grapheme");
        }
        if (cursor < text.length() && (codePointBefore(text, cursor) == 0x200d
                || isExtension(Character.codePointAt(text, cursor)))) {
            return refused(selection, "cursor-inside-grapheme");
        }
        // Regional-indicator pairing depends on the parity of the entire run.
        // A run reaching a truncated left edge has unknown parity even when
        // ICU reports an apparent boundary later in the sample.
        int regionalEnd = cursor;
        while (regionalEnd > 0 && isExtension(codePointBefore(text, regionalEnd))) {
            regionalEnd -= Character.charCount(codePointBefore(text, regionalEnd));
        }
        if (!fullPrefix && regionalEnd > 0
                && isRegionalIndicator(codePointBefore(text, regionalEnd))) {
            int runStart = regionalEnd;
            while (runStart > 0 && isRegionalIndicator(codePointBefore(text, runStart))) {
                runStart -= Character.charCount(codePointBefore(text, runStart));
            }
            if (runStart == 0) return refused(selection, "truncated-regional-run");
        }
        int length = cursor - boundary;
        return new DeletionPlan(DELETE_BEFORE, length, selection,
                selection.start - length, "previous-grapheme");
    }

    /**
     * Plans the complete prefix for one deleteSurroundingText(count, 0) call.
     * This never estimates document length from getTextBeforeCursor, changes a
     * selection, or loops over chunks. The same bounded context check as single
     * deletion establishes a safe cursor boundary, but the deletion count comes
     * only from fresh absolute metadata. An ambiguous editor is left unchanged.
     */
    public static DeletionPlan planBeforeCursor(InputConnection connection) {
        DeletionPlan single = planSingle(connection, -1, -1);
        Selection selection = single.selectionStart < 0 || single.selectionEnd < 0
                ? null : new Selection(single.selectionStart, single.selectionEnd);
        if (single.operation == REPLACE_SELECTION) {
            return refused(selection, "selection-not-collapsed");
        }
        if (single.operation == NO_OP) return single;
        if (single.operation != DELETE_BEFORE || selection == null
                || selection.start != selection.end) return refused(selection, single.reason);
        return new DeletionPlan(DELETE_BEFORE, selection.start, selection, 0, "complete-prefix");
    }

    private static DeletionPlan refused(Selection selection, String reason) {
        return new DeletionPlan(UNSUPPORTED, 0, selection, -1, reason);
    }

    private static DeletionPlan delegate(Selection selection, String reason) {
        return new DeletionPlan(SEND_DEL, 0, selection, -1, reason);
    }

    private static Selection readSelection(InputConnection connection) {
        Selection surrounding = readSurroundingSelection(connection);
        if (surrounding != null) return surrounding;
        try {
            ExtractedTextRequest request = new ExtractedTextRequest();
            request.hintMaxChars = 1;
            request.hintMaxLines = 1;
            // Hints are not limits. Some editors return a larger window; we
            // inspect its metadata and length only, never copy its text.
            ExtractedText extracted = connection.getExtractedText(request, 0);
            if (extracted == null || extracted.text == null
                    || extracted.partialStartOffset != -1) return null;
            return validatedSelection(extracted.startOffset, extracted.selectionStart,
                    extracted.selectionEnd, extracted.text.length());
        } catch (RuntimeException unavailable) {
            return null;
        } catch (LinkageError unavailable) {
            return null;
        }
    }

    private static Selection readSurroundingSelection(InputConnection connection) {
        try {
            Method get = InputConnection.class.getMethod("getSurroundingText",
                    Integer.TYPE, Integer.TYPE, Integer.TYPE);
            Object surrounding = get.invoke(connection,
                    Integer.valueOf(0), Integer.valueOf(0), Integer.valueOf(0));
            if (surrounding == null) return null;
            Class<?> type = Class.forName("android.view.inputmethod.SurroundingText");
            int offset = ((Integer) type.getMethod("getOffset").invoke(surrounding)).intValue();
            int start = ((Integer) type.getMethod("getSelectionStart").invoke(surrounding)).intValue();
            int end = ((Integer) type.getMethod("getSelectionEnd").invoke(surrounding)).intValue();
            CharSequence text = (CharSequence) type.getMethod("getText").invoke(surrounding);
            return text == null ? null : validatedSelection(offset, start, end, text.length());
        } catch (Exception unavailable) {
            return null;
        } catch (LinkageError unavailable) {
            return null;
        }
    }

    private static Selection validatedSelection(int offset, int start, int end, int length) {
        if (offset < 0 || start < 0 || end < 0 || start > length || end > length
                || ((long) offset + start) > Integer.MAX_VALUE
                || ((long) offset + end) > Integer.MAX_VALUE) return null;
        return new Selection(offset + start, offset + end);
    }

    private static boolean validUtf16(String text) {
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (Character.isHighSurrogate(value)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(value)) {
                return false;
            }
        }
        return true;
    }

    private static int codePointBefore(String text, int position) {
        return Character.codePointBefore(text, position);
    }

    private static boolean isRegionalIndicator(int point) {
        return point >= 0x1f1e6 && point <= 0x1f1ff;
    }

    private static boolean isExtension(int point) {
        int type = Character.getType(point);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK || point == 0x200d
                || (point >= 0xfe00 && point <= 0xfe0f)
                || (point >= 0xe0100 && point <= 0xe01ef)
                || (point >= 0x1f3fb && point <= 0x1f3ff)
                || (point >= 0xe0020 && point <= 0xe007f);
    }

    private static final class Selection {
        final int start;
        final int end;
        Selection(int start, int end) {
            this.start = start;
            this.end = end;
        }
        boolean sameAs(Selection other) {
            return start == other.start && end == other.end;
        }
    }
}
