package org.wetype.quick;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/** Regression tests for segmentation, lazy Cartesian pagination, migration and private learning. */
public final class CompositionEngineTest {
    private static int assertions;
    private static final String CHARS = "e\t水\t0\te\n"
        + "en\t測\t5\tebn\nen\t側\t1\ten\n"
        + "y\t卜\t0\ty\nym\t試\t8\tyrm\nym\t式\t2\tyim\n"
        + "ya\t言\t10\tya\nya\t試\t11\tyaa\n"
        + "a\t日\t0\ta\nab\t明\t1\tab\naa\t晶\t2\taaa\nhm\t𠂉\t1\thm\n";
    private interface Checked { void run() throws Exception; }
    private static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    private static void equal(Object actual, Object expected, String label) {
        check(actual.equals(expected), label + ": expected " + expected + ", got " + actual);
    }
    private static void fails(Class<? extends Throwable> type, Checked checked, String label) throws Exception {
        try { checked.run(); } catch (Throwable e) { check(type.isInstance(e), label + ": " + e); return; }
        throw new AssertionError(label + " did not fail");
    }
    private static QuickDictionary chars(String text) throws IOException {
        return QuickDictionary.read(new ByteArrayInputStream(text.getBytes("UTF-8")));
    }
    private static PhraseDictionary phrases(String text) throws IOException {
        return PhraseDictionary.read(new ByteArrayInputStream(text.getBytes("UTF-8")));
    }
    private static CompositionEngine small() throws IOException {
        return new CompositionEngine(chars(CHARS), phrases("測試\t100\n側式\t2\n測試\t9\n水日\t20\n𠂉日\t1\n"), new MemoryLearningStore());
    }
    private static List<String> texts(List<Candidate> candidates) {
        List<String> result = new ArrayList<String>(); for (Candidate c : candidates) result.add(c.text); return result;
    }
    private static void segmentation() throws Exception {
        CompositionEngine engine = small();
        equal(CompositionEngine.normalizeQuery("ENYM"), "en'ym", "implicit canonical");
        equal(CompositionEngine.normalizeQuery("en'ym"), "en'ym", "explicit canonical");
        equal(CompositionEngine.rootLabels("enym"), "水弓'卜一", "multi roots");
        equal(CompositionEngine.segmentCount("enym"), 2, "two slots");
        equal(CompositionEngine.normalizeQuery("en'"), "en", "redundant completed boundary");
        equal(CompositionEngine.normalizeQuery("en'y'"), "en'y'", "one-root exact marker");
        equal(texts(engine.lookup("enym", 0, 20)), Arrays.asList("測試", "側式", "側試", "測式"), "known phrases before all combos");
        equal(texts(engine.lookup("en'ym", 0, 20)), texts(engine.lookup("enym", 0, 20)), "explicit aliases");
        equal(engine.candidateCount("enym"), 4L, "all combinations counted once");
        equal(engine.candidateCount("eny"), 8L, "odd final root includes exact and completions, dedup");
        check(texts(engine.lookup("eny", 0, 20)).contains("測試"), "odd prefix known phrase");
        equal(texts(engine.lookup("en'y'", 0, 20)), Arrays.asList("側卜", "測卜"), "explicit final one-root exact");
        equal(engine.candidateCount("e'a"), 3L, "internal one-root exact, final prefix");
        equal(texts(engine.lookup("e'a'", 0, 20)), Arrays.asList("水日"), "both explicit one-root exact");
        equal(engine.candidateCount("eya"), 0L, "continuous roots do not branch segmentation");
        equal(texts(engine.lookup("e'", 0, 20)), Arrays.asList("水"), "single exact root");
        equal(texts(engine.lookup("e", 0, 20)), Arrays.asList("水", "側", "測"), "Q2 single prefix");
        equal(texts(engine.lookup("hm'a'", 0, 20)), Arrays.asList("𠂉日"), "nonBMP slot");
        equal(engine.learnedEntryCount(), 0, "lookup does not record");
        for (String invalid : new String[]{null, "", "'en", "en''ym", "z", "en ym", "en’ym", "１２", "en\tym", "a\n", "enenenenenenenena", "a'a'a'a'a'a'a'a'a"}) {
            check(!CompositionEngine.isValidQuery(invalid), "invalid syntax " + invalid);
            check(engine.lookup(invalid, 0, 2).isEmpty(), "invalid no lookup");
            equal(engine.exactCandidateCount(invalid), BigInteger.ZERO, "invalid zero count");
            check(!engine.recordSelection(invalid, "測試"), "invalid not recorded");
        }
        check(CompositionEngine.isValidQuery("enenenenenenenen"), "max 16 roots");
        equal(CompositionEngine.segmentCount("enenenenenenenen"), 8, "max8 chars");
        check(CompositionEngine.isValidQuery("a'a'a'a'a'a'a'a'"), "max8 exact one roots");
        final CompositionEngine f = engine;
        fails(IllegalArgumentException.class, new Checked() { public void run() { f.lookup("enym", -1, 1); } }, "negative offset");
        fails(IllegalArgumentException.class, new Checked() { public void run() { f.lookup("enym", 0, -1); } }, "negative limit");
        fails(IllegalArgumentException.class, new Checked() { public void run() { f.lookup("enym", 0, 4097); } }, "bounded page");
        fails(UnsupportedOperationException.class, new Checked() { public void run() { f.lookup("enym", 0, 1).clear(); } }, "immutable page");
        check(engine.lookup("enym", 0, 0).isEmpty(), "zero page");
        check(engine.lookup("enym", Long.MAX_VALUE, 10).isEmpty(), "out-of-range long offset");
    }
    private static void paging() throws Exception {
        CompositionEngine engine = small();
        List<String> full = texts(engine.lookup("eny", 0, 100));
        for (int offset = 0; offset <= full.size() + 2; offset++) {
            for (int limit = 0; limit <= full.size() + 1; limit++) {
                equal(texts(engine.lookup("eny", offset, limit)), full.subList(Math.min(offset, full.size()), Math.min(offset + limit, full.size())), "all page boundary pairs");
            }
        }
        equal(new HashSet<String>(full).size(), full.size(), "dedup across phrase/Cartesian and alternate codes");
        StringBuilder synthetic = new StringBuilder();
        for (int i = 0; i < 1000; i++) synthetic.append("aa\t").append((char)(0x4e00 + i)).append('\t').append(i).append("\taa\n");
        QuickDictionary big = chars(synthetic.toString());
        String query = "aaaaaaaaaaaaaaaa";
        String last = new String(new char[]{(char)(0x4e00 + 999)});
        StringBuilder last8 = new StringBuilder(); for (int i = 0; i < 8; i++) last8.append(last);
        CompositionEngine huge = new CompositionEngine(big, phrases(last8 + "\t9\n"), new MemoryLearningStore());
        BigInteger count = BigInteger.valueOf(1000).pow(8);
        equal(huge.exactCandidateCount(query), count, "exact BigInteger count 10^24");
        equal(huge.candidateCount(query), Long.MAX_VALUE, "saturating long count");
        check(!huge.isCountExact(query), "saturated count flag");
        equal(huge.lookup(query, 0, 1).get(0).text, last8.toString(), "far-end known phrase promoted directly");
        equal(huge.lookup(query, count.subtract(BigInteger.ONE), 1).size(), 1, "last candidate random access above long max");
        equal(huge.lookup(query, count, 1).size(), 0, "exact terminal offset");
        Set<String> nearEnd = new HashSet<String>(texts(huge.lookup(query, count.subtract(BigInteger.TEN), 20)));
        equal(nearEnd.size(), 10, "tail page exact no duplicates");
        check(!nearEnd.contains(last8.toString()), "promoted end item removed from tail");
        BigInteger deep = new BigInteger("123456789012345678901234");
        List<Candidate> middle = huge.lookup(query, deep, 3);
        equal(middle.size(), 3, "deep direct page");
        for (int i = 0; i < middle.size(); i++) {
            BigInteger raw = deep.add(BigInteger.valueOf(i)).subtract(BigInteger.ONE);
            StringBuilder expected = new StringBuilder(); int[] digits = new int[8];
            for (int j = 7; j >= 0; j--) { BigInteger[] d = raw.divideAndRemainder(BigInteger.valueOf(1000)); digits[j] = d[1].intValue(); raw = d[0]; }
            for (int digit : digits) expected.append((char)(0x4e00 + digit));
            equal(middle.get(i).text, expected.toString(), "deep mixed-radix direct index");
        }
    }
    private static void learning() throws Exception {
        MemoryLearningStore store = new MemoryLearningStore();
        CompositionEngine engine = new CompositionEngine(chars(CHARS), phrases("測試\t100\n側式\t2\n"), store);
        check(engine.recordSelection("enym", "側試"), "learn arbitrary code-valid phrase");
        equal(engine.lookup("en'ym", 0, 1).get(0).text, "側試", "equivalent segmentation shares learning");
        check(engine.recordSelection("enym", "測式"), "learn second combo");
        equal(engine.lookup("enym", 0, 1).get(0).text, "測式", "phrase recency tie-break");
        engine.recordSelection("enym", "側試");
        equal(engine.lookup("enym", 0, 1).get(0).text, "側試", "phrase count beats recency");
        equal(engine.lookup("eny", 0, 1).get(0).text, "測試", "prefix phrase learning query-specific");
        check(!engine.recordSelection("enym", "水日"), "reject code-invalid phrase");
        check(!engine.recordSelection("enym", "測"), "reject wrong length");
        check(!engine.recordSelection("en'y'", "測試"), "exact final single root cannot learn prefix");
        check(!engine.recordSelection("e'a", "側日"), "exact internal root cannot learn prefix");
        check(engine.recordSelection("en", "測"), "single selection in facade");
        equal(engine.lookup("en", 0, 1).get(0).text, "測", "Q2 learned single rank");
        equal(store.load().size(), 0, "no writes until flush");
        engine.flush(); equal(store.load().size(), 3, "combined snapshot");
        CompositionEngine reloaded = new CompositionEngine(chars(CHARS), phrases("測試\t100\n側式\t2\n"), store);
        equal(reloaded.lookup("enym", 0, 1).get(0).text, "側試", "reload phrase choice");
        equal(reloaded.lookup("en", 0, 1).get(0).text, "測", "reload character choice");
        reloaded.setPrivateMode(true);
        equal(reloaded.lookup("enym", 0, 1).get(0).text, "測試", "private phrase ranking default");
        equal(reloaded.lookup("en", 0, 1).get(0).text, "側", "private character ranking default");
        for (Candidate c : reloaded.lookup("enym", 0, 4)) equal(c.learnedCount, 0L, "private hides counts");
        reloaded.recordSelection("enym", "側試"); reloaded.recordSelection("en", "測");
        check(!reloaded.hasUnsavedLearning(), "private selections don't dirty profile");
        reloaded.flush(); equal(store.load().get("p:en'ym\t側試").count, 2L, "private phrase no record");
        reloaded.setPrivateMode(false);
        equal(reloaded.lookup("enym", 0, 1).get(0).learnedCount, 2L, "private retains old phrase count");
        reloaded.clearLearning(); equal(store.load().size(), 0, "clear combined profile");
        equal(reloaded.learnedEntryCount(), 0, "clear in-memory combined");
        equal(reloaded.lookup("enym", 0, 1).get(0).text, "測試", "clear invalidates cached rank");
    }
    private static void privateCacheLifecycle() throws Exception {
        CompositionEngine engine = small();
        java.lang.reflect.Field field = CompositionEngine.class.getDeclaredField("cached");
        field.setAccessible(true);
        engine.recordSelection("enym", "測試");
        int learned = engine.learnedEntryCount();
        boolean dirty = engine.hasUnsavedLearning();
        engine.lookup("enym", 0, 1);
        Object ordinary = field.get(engine);
        check(ordinary != null, "ordinary lookup creates a bounded query plan");
        engine.setPrivateMode(false);
        check(ordinary == field.get(engine), "repeated ordinary mode preserves lookup cache");
        engine.setPrivateMode(true);
        check(field.get(engine) == null, "entering private mode clears prior plan");
        engine.lookup("enym", 0, 1);
        check(field.get(engine) != null, "private lookup has transient in-memory plan");
        engine.setPrivateMode(true);
        check(field.get(engine) == null, "already-private session boundary clears query plan");
        equal(engine.learnedEntryCount(), learned, "transient cleanup preserves stored learning");
        equal(engine.hasUnsavedLearning(), dirty, "transient cleanup does not change persistence state");
        engine.setPrivateMode(true);
        check(field.get(engine) == null, "repeated private cleanup remains empty");
    }
    private static File file(String name) {
        File f = new File("build/test-data/" + name); f.getParentFile().mkdirs();
        f.delete(); new File(f + ".bak").delete(); new File(f + ".new").delete(); return f;
    }
    private static void migration() throws Exception {
        File f = file("composition.bin");
        ByteArrayOutputStream payload = new ByteArrayOutputStream(); DataOutputStream entries = new DataOutputStream(payload);
        entries.writeInt(1); entries.writeUTF("en\t測"); entries.writeLong(7); entries.writeLong(19); entries.flush();
        CRC32 crc = new CRC32(); crc.update(payload.toByteArray());
        DataOutputStream original = new DataOutputStream(new FileOutputStream(f));
        original.writeInt(0x514c5331); original.writeInt(1); original.writeInt(payload.size()); original.write(payload.toByteArray()); original.writeLong(crc.getValue()); original.close();
        FileLearningStore store = new FileLearningStore(f);
        CompositionEngine engine = new CompositionEngine(chars(CHARS), PhraseDictionary.empty(), store);
        equal(engine.lookup("en", 0, 1).get(0).text, "測", "v1 char migration rank");
        equal(engine.lookup("en", 0, 1).get(0).learnedCount, 7L, "v1 count intact");
        engine.recordSelection("enym", "測式"); engine.flush();
        Map<String, SelectionStat> saved = store.load();
        equal(saved.get("en\t測").count, 7L, "v2 snapshot preserves char count");
        equal(saved.get("en\t測").sequence, 19L, "v2 snapshot preserves char sequence");
        check(saved.containsKey("p:en'ym\t測式"), "v2 phrase key");
        DataInputStream header = new DataInputStream(new FileInputStream(f)); header.readInt(); equal(header.readInt(), 2, "v2 format version"); header.close();
        QuickEngine legacy = new QuickEngine(chars(CHARS), store);
        legacy.recordSelection("a", "明"); legacy.flush();
        check(store.load().containsKey("p:en'ym\t測式"), "Q2 client retains unknown phrase partition");
        equal(new CompositionEngine(chars(CHARS), PhraseDictionary.empty(), store).lookup("enym", 0, 1).get(0).text, "測式", "mixed store reload after Q2 write");
        Map<String, SelectionStat> stale = store.load(); stale.put("p:en'ym\t水日", new SelectionStat(1, 1)); store.save(stale);
        CompositionEngine filtered = new CompositionEngine(chars(CHARS), PhraseDictionary.empty(), store);
        check(!texts(filtered.lookup("enym", 0, 10)).contains("水日"), "stale code-invalid loaded phrase filtered");
        final LearningStore failureStore = new LearningStore() {
            public Map<String, SelectionStat> load() { return new HashMap<String, SelectionStat>(); }
            public void save(Map<String, SelectionStat> data) throws IOException { throw new IOException("test write failure"); }
        };
        final CompositionEngine failed = new CompositionEngine(chars(CHARS), PhraseDictionary.empty(), failureStore);
        failed.recordSelection("enym", "側試"); failed.recordSelection("en", "測");
        fails(IOException.class, new Checked() { public void run() throws Exception { failed.flush(); } }, "failed combined flush");
        check(failed.hasUnsavedLearning(), "failed outer flush remains dirty");
        fails(IOException.class, new Checked() { public void run() throws Exception { failed.clearLearning(); } }, "failed erase");
        equal(failed.learnedEntryCount(), 2, "failed clear retains choices");
        Map<String, SelectionStat> extreme = new HashMap<String, SelectionStat>(); extreme.put("p:en'ym\t側試", new SelectionStat(Long.MAX_VALUE, Long.MAX_VALUE));
        MemoryLearningStore memory = new MemoryLearningStore(); memory.save(extreme);
        CompositionEngine overflow = new CompositionEngine(chars(CHARS), PhraseDictionary.empty(), memory);
        overflow.recordSelection("enym", "側試"); overflow.flush();
        equal(memory.load().get("p:en'ym\t側試").count, Long.MAX_VALUE, "phrase count saturates");
        check(memory.load().get("p:en'ym\t側試").sequence > 0, "phrase logical recency rebases");
    }
    private static void parser() throws Exception {
        equal(phrases("測試\t1\n測試\t9\n").entryCount(), 1, "duplicate phrase dedup");
        equal(phrases("# comments only\n").entryCount(), 0, "empty phrase fallback allowed");
        for (final String invalid : new String[]{"測\t1", "測試\t-1", "測試\t1.5", "測試\t1\textra", "測試", "測試測試測試測試測\t1", "測 試\t1"}) {
            fails(IOException.class, new Checked() { public void run() throws Exception { phrases(invalid); } }, "malformed phrase");
        }
        fails(IOException.class, new Checked() { public void run() throws Exception { PhraseDictionary.read(new ByteArrayInputStream(new byte[]{(byte)0xc3, 0x28})); } }, "bad UTF8 phrase file");
    }
    private static void boundsAndConcurrency() throws Exception {
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < 17; i++) data.append("aa\t").append((char)(0x4e00 + i)).append('\t').append(i).append("\taa\n");
        MemoryLearningStore memory = new MemoryLearningStore();
        CompositionEngine bounded = new CompositionEngine(chars(data.toString()), PhraseDictionary.empty(), memory);
        String first = null, last = null;
        for (int n = 0; n <= CompositionEngine.MAX_LEARNED_PHRASES; n++) {
            int value = n; char[] text = new char[3];
            for (int i = 2; i >= 0; i--) { text[i] = (char)(0x4e00 + value % 17); value /= 17; }
            String candidate = new String(text);
            if (first == null) first = candidate; last = candidate;
            check(bounded.recordSelection("aaaaaa", candidate), "bounded selection accepts legal Cartesian text");
        }
        equal(bounded.learnedEntryCount(), CompositionEngine.MAX_LEARNED_PHRASES, "bounded phrase retention");
        bounded.flush();
        check(!memory.load().containsKey("p:aa'aa'aa\t" + first), "least recent phrase evicted");
        check(memory.load().containsKey("p:aa'aa'aa\t" + last), "newest phrase retained");
        equal(bounded.candidateCount("aaaaaa"), 4913L, "learning never changes candidate coverage");
        final MemoryLearningStore concurrentStore = new MemoryLearningStore();
        final CompositionEngine concurrent = new CompositionEngine(chars(CHARS), phrases("測試\t1\n"), concurrentStore);
        final Throwable[] errors = new Throwable[4]; Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            final int index = t;
            threads[t] = new Thread(new Runnable() { public void run() {
                try {
                    for (int n = 0; n < 100; n++) {
                        concurrent.recordSelection("enym", "側試");
                        concurrent.lookup("enym", n % 4, 1); concurrent.exactCandidateCount("enym");
                        if (n % 10 == 0) concurrent.flush();
                    }
                } catch (Throwable e) { errors[index] = e; }
            }});
            threads[t].start();
        }
        for (Thread thread : threads) thread.join();
        for (Throwable error : errors) check(error == null, "concurrent safe: " + error);
        concurrent.flush();
        equal(concurrentStore.load().get("p:en'ym\t側試").count, 400L, "concurrent selections not lost");
        // A failed combined durable write can be retried even after the single-character partition flushed.
        final MemoryLearningStore durable = new MemoryLearningStore(); final boolean[] failing = {true};
        LearningStore retryStore = new LearningStore() {
            public Map<String, SelectionStat> load() { return durable.load(); }
            public void save(Map<String, SelectionStat> values) throws IOException {
                if (failing[0]) throw new IOException("transient failure"); durable.save(values);
            }
        };
        final CompositionEngine retry = new CompositionEngine(chars(CHARS), PhraseDictionary.empty(), retryStore);
        retry.recordSelection("en", "測"); retry.recordSelection("enym", "側試");
        fails(IOException.class, new Checked() { public void run() throws Exception { retry.flush(); } }, "transient combined flush");
        failing[0] = false; retry.flush();
        equal(durable.load().size(), 2, "retry saves both partitions");
        check(!retry.hasUnsavedLearning(), "successful retry clears dirty");
    }
    private static void production() throws Exception {
        FileInputStream stream = new FileInputStream("resources/quick-traditional.tsv"); QuickDictionary dictionary;
        try { dictionary = QuickDictionary.read(stream); } finally { stream.close(); }
        File source = new File("resources/quick-phrases.tsv"); PhraseDictionary phrases;
        stream = new FileInputStream(source);
        try { phrases = PhraseDictionary.read(stream); } finally { stream.close(); }
        equal(phrases.entryCount(), 100032, "production licensed phrase count");
        CompositionEngine engine = new CompositionEngine(dictionary, phrases, new MemoryLearningStore());
        check(texts(engine.lookup("enym", 0, 256)).contains("測試"), "production enym has 測試 near front");
        for (String[] regression : new String[][]{{"sq'vm", "群組"}, {"u'uj", "山峰"}, {"aw'ki'nh", "晒太陽"}}) {
            List<Candidate> matches = engine.lookup(regression[0], 0, CompositionEngine.MAX_PAGE_SIZE);
            check(texts(matches).contains(regression[1]), "retained traditional phrase reachable " + regression[1]);
            if (!regression[1].equals("晒太陽")) {
                boolean known = false;
                for (Candidate c : matches) if (c.text.equals(regression[1]) && c.baseRank != Integer.MAX_VALUE) known = true;
                check(known, "restored curated phrase prioritized " + regression[1]);
            }
        }
        check(!engine.recordSelection("wi'ee", "国汉"), "simplified-only phrase rejected after HK allowlist");
        equal(engine.exactCandidateCount("enym"), new BigInteger("" + new QuickEngine(dictionary, new MemoryLearningStore()).candidateCount("en")).multiply(new BigInteger("" + new QuickEngine(dictionary, new MemoryLearningStore()).candidateCount("ym"))), "production whole product count");
        List<String> all = new ArrayList<String>();
        for (long offset = 0; offset < engine.candidateCount("enym"); offset += 31) all.addAll(texts(engine.lookup("enym", offset, 31)));
        equal(new HashSet<String>(all).size(), all.size(), "production pages no duplicate");
        equal((long)all.size(), engine.candidateCount("enym"), "production every combination reachable");
        System.out.println("Production enym: " + all.size() + " candidates; phrases: " + phrases.entryCount());
    }
    public static void main(String[] args) throws Exception {
        segmentation(); paging(); learning(); privateCacheLifecycle(); migration(); parser(); boundsAndConcurrency(); production();
        System.out.println("PASS Q3: " + assertions + " assertions");
    }
}
