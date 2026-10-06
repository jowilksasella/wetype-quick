package org.wetype.quick.integration;

import com.tencent.wetype.plugin.hld.candidate.d;

/** A displayable WeType candidate whose identity belongs only to the Quick engine. */
public final class QuickNativeCandidate extends d {
    public static final int ACTION_COMMIT = 0;
    public static final int ACTION_MORE = 1;

    public final long generation;
    public final int index;
    public final String commitText;
    public final int action;

    public QuickNativeCandidate(long generation, int index, String text, int action,
            int candidateType, int generateType) {
        // Candidate(int) takes a native ID byte length, NOT the display text length.
        // No native ID is assigned. The marker MUST be intercepted before ID APIs.
        super(0, requireText(text));
        if (index < 0) throw new IllegalArgumentException("negative candidate index");
        if (action != ACTION_COMMIT && action != ACTION_MORE) {
            throw new IllegalArgumentException("unknown Quick candidate action");
        }
        this.generation = generation;
        this.index = index;
        this.commitText = text;
        this.action = action;

        // Native adapters use both Candidate.text and d.L() (constructor showText).
        // No iterator, native ID, cloud origin, or special candidate kind is invented.
        this.text = text;
        this.comment = "";
        this.jsonIdDesc = "";
        S0(candidateType);
        R0(generateType);
    }

    private static String requireText(String text) {
        if (text == null || text.length() == 0) {
            throw new IllegalArgumentException("empty Quick candidate text");
        }
        return text;
    }
}
