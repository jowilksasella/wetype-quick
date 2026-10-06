package org.wetype.quick;

import java.io.FileInputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;

/** Repeatable descriptive JVM timing. Not an on-device Android latency guarantee. */
public final class CompositionEngineBenchmark {
    private static double ms(long started) { return (System.nanoTime() - started) / 1000000.0; }
    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        FileInputStream input = new FileInputStream("resources/quick-traditional.tsv"); QuickDictionary dictionary;
        try { dictionary = QuickDictionary.read(input); } finally { input.close(); }
        System.out.printf(Locale.ROOT, "character_load_ms=%.3f entries=%d%n", ms(start), dictionary.entryCount());
        start = System.nanoTime(); input = new FileInputStream("resources/quick-phrases.tsv"); PhraseDictionary phrases;
        try { phrases = PhraseDictionary.read(input); } finally { input.close(); }
        System.out.printf(Locale.ROOT, "phrase_load_ms=%.3f entries=%d%n", ms(start), phrases.entryCount());
        start = System.nanoTime(); CompositionEngine engine = new CompositionEngine(dictionary, phrases, new MemoryLearningStore());
        System.out.printf(Locale.ROOT, "engine_init_ms=%.3f%n", ms(start));
        for (String query : new String[]{"enym", "en'y", "en'y'", "enymenymenymenym", "enenenenenenenen", "mrmrmrmrmrmrmrmr", "mrmrmrmrmrmrmrm"}) {
            start = System.nanoTime(); List<Candidate> first = engine.lookup(query, 0, 256); double firstMs = ms(start);
            BigInteger count = engine.exactCandidateCount(query);
            BigInteger offset = count.multiply(BigInteger.valueOf(7)).divide(BigInteger.TEN);
            start = System.nanoTime(); List<Candidate> deep = engine.lookup(query, offset, 256); double deepMs = ms(start);
            start = System.nanoTime(); List<Candidate> tail = engine.lookup(query, count.max(BigInteger.ONE).subtract(BigInteger.ONE), 1); double tailMs = ms(start);
            System.out.printf(Locale.ROOT, "query=%s count=%s first_page_ms=%.3f deep_offset=%s deep_page_ms=%.3f tail_ms=%.3f page_sizes=%d/%d/%d first=%s%n",
                query, count, firstMs, offset, deepMs, tailMs, first.size(), deep.size(), tail.size(), first.isEmpty() ? "" : first.get(0).text);
        }
    }
}
