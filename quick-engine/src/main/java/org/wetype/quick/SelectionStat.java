package org.wetype.quick;

/** Counts and logical recency only; contains no wall-clock timestamp or surrounding text. */
public final class SelectionStat {
    public final long count;
    public final long sequence;
    public SelectionStat(long count, long sequence) {
        if (count < 1 || sequence < 1) throw new IllegalArgumentException("Positive learning values required");
        this.count = count; this.sequence = sequence;
    }
}
