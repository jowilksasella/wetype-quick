package org.wetype.quick;

/** Immutable result. Commit text, not roots or the Cangjie diagnostic code. */
public final class Candidate {
    public final String text;
    public final String quickCode;
    public final String cangjieCode;
    public final int baseRank;
    public final long learnedCount;

    Candidate(String text, String quickCode, String cangjieCode, int baseRank, long learnedCount) {
        this.text = text;
        this.quickCode = quickCode;
        this.cangjieCode = cangjieCode;
        this.baseRank = baseRank;
        this.learnedCount = learnedCount;
    }
    @Override public String toString() { return text + " (" + quickCode + ")"; }
}
