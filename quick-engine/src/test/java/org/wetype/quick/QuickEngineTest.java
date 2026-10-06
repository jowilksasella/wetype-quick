package org.wetype.quick;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency-free executable test suite; exits nonzero on failure. */
public final class QuickEngineTest {
    private static int assertions;
    private static final String FIXTURE = "# synthetic ordering fixture\n"
        + "a\t日\t10\ta\n" + "a\t曰\t20\ta\n"
        + "aa\t昌\t30\taa\n" + "aa\t晶\t40\taaa\n"
        + "ab\t明\t5\tab\n" + "ab\t朋\t50\taab\n"
        + "ac\t明\t6\taac\n" + "hm\t𠂉\t70\thm\n";
    private interface Checked { void run() throws Exception; }
    private static void check(boolean pass, String message) {
        assertions++;
        if (!pass) throw new AssertionError(message);
    }
    private static void equal(Object actual, Object expected, String message) {
        check(actual.equals(expected), message + ": expected " + expected + ", got " + actual);
    }
    private static void failure(Class<? extends Throwable> type, Checked task, String message) throws Exception {
        try { task.run(); }
        catch (Throwable e) { check(type.isInstance(e), message + " wrong exception " + e); return; }
        throw new AssertionError(message + " did not fail");
    }
    private static QuickDictionary parse(String text) throws IOException {
        return QuickDictionary.read(new ByteArrayInputStream(text.getBytes("UTF-8")));
    }
    private static QuickEngine small() throws IOException { return new QuickEngine(parse(FIXTURE), new MemoryLearningStore()); }
    private static List<String> texts(List<Candidate> candidates) {
        List<String> texts = new ArrayList<String>();
        for (Candidate c : candidates) texts.add(c.text);
        return texts;
    }
    private static byte[] read(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { byte[] buffer = new byte[4096]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n); }
        finally { in.close(); }
        return out.toByteArray();
    }
    private static void write(File file, byte[] bytes) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try { out.write(bytes); } finally { out.close(); }
    }
    private static File testFile(String name) {
        File dir = new File("build/test-data"); dir.mkdirs();
        File file = new File(dir, name);
        file.delete(); new File(file + ".bak").delete(); new File(file + ".new").delete();
        return file;
    }
    private static void lookupTests() throws Exception {
        final QuickEngine engine = small();
        equal(texts(engine.lookup("ab", 0, 20)), Arrays.asList("明", "朋"), "exact lookup");
        equal(texts(engine.lookup("a", 0, 20)), Arrays.asList("日", "曰", "明", "昌", "晶", "朋"), "root priority and dedup");
        equal(texts(engine.lookup("AB", 0, 20)), Arrays.asList("明", "朋"), "ASCII uppercase");
        equal(texts(engine.lookup("a", 2, 2)), Arrays.asList("明", "昌"), "pagination");
        equal(engine.candidateCount("a"), 6, "deduplicated count");
        check(engine.lookup("a", Integer.MAX_VALUE, Integer.MAX_VALUE).isEmpty(), "large offset safe");
        check(engine.lookup("a", 0, 0).isEmpty(), "zero limit");
        for (String invalid : new String[]{null, "", " ", "a ", " a", "abx", "z", "az", "a1", "中", "Ａ", "a\n", "\t", "a'", "a;", "a-"}) {
            check(engine.lookup(invalid, 0, 20).isEmpty(), "invalid input lookup");
            equal(engine.candidateCount(invalid), 0, "invalid count");
            check(!engine.recordSelection(invalid, "明"), "invalid selection code");
        }
        failure(IllegalArgumentException.class, new Checked() { public void run() { engine.lookup("a", -1, 2); } }, "negative offset");
        failure(IllegalArgumentException.class, new Checked() { public void run() { engine.lookup("a", 0, -2); } }, "negative limit");
        failure(UnsupportedOperationException.class, new Checked() { public void run() { engine.lookup("a", 0, 2).clear(); } }, "immutable result");
        equal(QuickDictionary.quickCode("bbpe"), "be", "first last");
        equal(QuickDictionary.quickCode("a"), "a", "one code");
        equal(QuickEngine.rootLabels("OF"), "人火", "root labels");
        equal(QuickEngine.rootLabel('q'), "手", "Q root");
        equal(QuickEngine.rootLabel('x'), "難", "X root");
        equal(QuickEngine.rootLabel('z'), "", "Z is symbol action");
        equal(engine.lookup("hm", 0, 1).get(0).text, "𠂉", "supplementary Unicode");
        check(!engine.recordSelection("ab", "我"), "reject outside dictionary");
        check(!engine.recordSelection("ab", "明朋"), "reject phrases");
        check(!engine.recordSelection("ab", null), "reject null text");
        equal(engine.learnedEntryCount(), 0, "lookup does not learn");
    }
    private static void learningTests() throws Exception {
        MemoryLearningStore store = new MemoryLearningStore();
        QuickEngine engine = new QuickEngine(parse(FIXTURE), store);
        check(engine.recordSelection("ab", "朋"), "learn selection");
        equal(engine.lookup("ab", 0, 1).get(0).text, "朋", "promote selection");
        equal(engine.lookup("ab", 0, 1).get(0).learnedCount, 1L, "count");
        equal(engine.lookup("a", 0, 1).get(0).text, "日", "prefix learning separate");
        engine.recordSelection("AB", "明");
        equal(engine.lookup("ab", 0, 1).get(0).text, "明", "recency tie break");
        engine.recordSelection("ab", "朋");
        equal(engine.lookup("ab", 0, 1).get(0).text, "朋", "frequency outranks recency");
        check(engine.hasUnsavedLearning(), "dirty after select");
        equal(store.load().size(), 0, "explicit flush only");
        engine.flush(); check(!engine.hasUnsavedLearning(), "flush clears dirty");
        QuickEngine reloaded = new QuickEngine(parse(FIXTURE), store);
        equal(reloaded.lookup("ab", 0, 1).get(0).text, "朋", "reload ranking");
        engine.setPrivateMode(true);
        equal(engine.lookup("ab", 0, 1).get(0).text, "明", "private hides learned rank");
        equal(engine.lookup("ab", 0, 1).get(0).learnedCount, 0L, "private hides learned count");
        check(engine.recordSelection("ab", "朋"), "private commit allowed");
        engine.flush();
        equal(store.load().get("ab\t朋").count, 2L, "private does not learn");
        engine.setPrivateMode(false);
        equal(engine.lookup("ab", 0, 1).get(0).learnedCount, 2L, "private preserves previous choices");
        engine.recordSelection("a", "晶");
        equal(engine.lookup("a", 0, 1).get(0).text, "晶", "prefix learning promotes above root");
        equal(engine.lookup("aa", 0, 1).get(0).text, "昌", "prefix learning does not leak to exact");
        engine.clearLearning(); equal(store.load().size(), 0, "clear persisted");
        equal(engine.learnedEntryCount(), 0, "clear memory");
        equal(engine.lookup("a", 0, 1).get(0).text, "日", "clear ranking");
        Map<String, SelectionStat> extreme = new HashMap<String, SelectionStat>();
        extreme.put("ab\t朋", new SelectionStat(Long.MAX_VALUE, Long.MAX_VALUE));
        store.save(extreme);
        QuickEngine overflow = new QuickEngine(parse(FIXTURE), store);
        overflow.recordSelection("ab", "朋"); overflow.flush();
        equal(store.load().get("ab\t朋").count, Long.MAX_VALUE, "count saturation");
        check(store.load().get("ab\t朋").sequence > 0, "recency overflow rebase");
    }
    private static void persistenceTests() throws Exception {
        final File file = testFile("learning.bin");
        final FileLearningStore store = new FileLearningStore(file);
        equal(store.load().size(), 0, "missing file");
        QuickEngine engine = new QuickEngine(parse(FIXTURE), store);
        engine.recordSelection("ab", "朋"); engine.recordSelection("hm", "𠂉"); engine.flush();
        byte[] saved = read(file);
        equal(new QuickEngine(parse(FIXTURE), store).lookup("ab", 0, 1).get(0).text, "朋", "disk reload");
        engine.flush(); check(Arrays.equals(saved, read(file)), "no-op flush");
        store.save(store.load()); check(Arrays.equals(saved, read(file)), "deterministic encoding");
        check(!new File(file + ".bak").exists() && !new File(file + ".new").exists(), "no leftovers");
        byte[] bad = saved.clone(); bad[16] ^= 1; write(file, bad);
        failure(IOException.class, new Checked() { public void run() throws Exception { store.load(); } }, "checksum corruption");
        write(new File(file + ".bak"), saved);
        equal(store.load().size(), 2, "fallback from corrupt primary");
        store.save(store.load()); equal(store.load().size(), 2, "save recovered profile");
        check(file.renameTo(new File(file + ".bak")), "simulate rename interruption");
        equal(store.load().size(), 2, "recover missing primary");
        store.save(store.load()); check(file.exists(), "restore after recovery");
        for (int i : new int[]{0, 1, 4, 10, 19, saved.length - 1}) {
            write(file, Arrays.copyOf(saved, i));
            failure(IOException.class, new Checked() { public void run() throws Exception { store.load(); } }, "truncated file " + i);
        }
        write(file, saved); engine.clearLearning();
        equal(store.load().size(), 0, "clear file contents");
        check(!new File(file + ".bak").exists(), "clear removes old profile backup");
        // IOException preserves dirty state and earlier in-memory choices for a retry.
        LearningStore failing = new LearningStore() {
            public Map<String, SelectionStat> load() { return new HashMap<String, SelectionStat>(); }
            public void save(Map<String, SelectionStat> snapshot) throws IOException { throw new IOException("test disk failure"); }
        };
        final QuickEngine failed = new QuickEngine(parse(FIXTURE), failing);
        failed.recordSelection("ab", "朋");
        failure(IOException.class, new Checked() { public void run() throws Exception { failed.flush(); } }, "save failure");
        check(failed.hasUnsavedLearning(), "failed flush retains dirty");
        failure(IOException.class, new Checked() { public void run() throws Exception { failed.clearLearning(); } }, "clear failure");
        equal(failed.learnedEntryCount(), 1, "failed clear retains memory");
    }
    private static void parserTests() throws Exception {
        for (final String text : new String[]{"", "# none", "ab\t明\t1", "ab\t明\t-1\tab", "ab\t明\tfoo\tab",
                "ab\t明\t1\taa", "a1\t明\t1\tab", "ab\t明\t1\tab\n ab\t朋\t2\taab",
                "ab\t明\t1\tab\nab\t明\t2\taab", "ab\t明朋\t1\tab", "z\t中\t1\tz"}) {
            failure(IOException.class, new Checked() { public void run() throws Exception { parse(text); } }, "invalid dictionary");
        }
        failure(IOException.class, new Checked() { public void run() throws Exception {
            QuickDictionary.read(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28}));
        } }, "malformed UTF-8");
        equal(parse("\ufeff" + FIXTURE).entryCount(), 8, "BOM accepted");
        final boolean[] closed = {false};
        InputStream tracking = new ByteArrayInputStream(FIXTURE.getBytes("UTF-8")) {
            @Override public void close() { closed[0] = true; }
        };
        QuickDictionary.read(tracking); check(!closed[0], "caller owns stream"); tracking.close();
        MemoryLearningStore memory = new MemoryLearningStore();
        Map<String, SelectionStat> source = new HashMap<String, SelectionStat>();
        source.put("ab\t明", new SelectionStat(1, 1)); memory.save(source); source.clear();
        equal(memory.load().size(), 1, "store copies input"); memory.load().clear(); equal(memory.load().size(), 1, "store copies output");
    }
    private static void productionTests() throws Exception {
        FileInputStream input = new FileInputStream("resources/quick-traditional.tsv");
        QuickDictionary dictionary;
        try { dictionary = QuickDictionary.read(input); } finally { input.close(); }
        equal(dictionary.entryCount(), 18105, "production entries");
        equal(dictionary.characterCount(), 17213, "production characters");
        equal(dictionary.codeCount(), 622, "production codes");
        QuickEngine engine = new QuickEngine(dictionary, new MemoryLearningStore());
        String fixture = new String(read(new File("fixtures/lookup.tsv")), "UTF-8");
        for (String line : fixture.split("\n")) {
            if (line.length() == 0 || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            List<String> values = texts(engine.lookup(f[0], 0, Integer.MAX_VALUE));
            check(values.contains(f[1]), "production fixture " + f[0] + " -> " + f[1]);
            if (f.length > 2 && !f[2].isEmpty()) check(!values.contains(f[2]), "exclude simplified " + f[2]);
        }
        Set<String> all = new HashSet<String>();
        List<Candidate> learnable = new ArrayList<Candidate>();
        long start = System.nanoTime();
        for (char a = 'a'; a <= 'y'; a++) {
            List<Candidate> prefix = engine.lookup("" + a, 0, Integer.MAX_VALUE);
            equal(prefix.size(), engine.candidateCount("" + a), "prefix counts");
            Set<String> seen = new HashSet<String>();
            for (Candidate c : prefix) { check(seen.add(c.text), "prefix unique"); check(c.quickCode.startsWith("" + a), "prefix match"); all.add(c.text); }
            for (char b = 'a'; b <= 'y'; b++) {
                String code = "" + a + b;
                List<Candidate> exact = engine.lookup(code, 0, Integer.MAX_VALUE);
                equal(exact.size(), engine.candidateCount(code), "exact counts");
                for (Candidate c : exact) { equal(c.quickCode, code, "exact code"); learnable.add(c); }
                List<String> paged = new ArrayList<String>();
                for (int off = 0; off < exact.size(); off += 7) paged.addAll(texts(engine.lookup(code, off, 7)));
                equal(paged, texts(exact), "pagination full coverage");
            }
        }
        equal(all.size(), 17213, "all characters reachable");
        for (String traditional : new String[]{"群", "峰", "晒"}) check(all.contains(traditional), "traditional HK variant retained " + traditional);
        for (String simplified : new String[]{"汉", "爱", "国", "门", "龙", "发", "简", "后"}) {
            check(simplified.equals("后") ? all.contains(simplified) : !all.contains(simplified), "traditional filter " + simplified);
        }
        for (int i = 0; i < 4200; i++) {
            Candidate c = learnable.get(i);
            check(engine.recordSelection(c.quickCode, c.text), "valid production selection");
        }
        equal(engine.learnedEntryCount(), QuickEngine.MAX_LEARNED_ENTRIES, "bounded learning LRU");
        engine.flush();
        System.out.println("Production lookup/paging/4,200 selections: " + (System.nanoTime() - start) / 1000000 + " ms");
        // Concurrent calls share a single engine safely.
        final QuickEngine concurrent = new QuickEngine(dictionary, new MemoryLearningStore());
        final List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<Throwable>());
        List<Thread> threads = new ArrayList<Thread>();
        for (int i = 0; i < 4; i++) {
            Thread thread = new Thread(new Runnable() { public void run() {
                try { for (int j = 0; j < 100; j++) { concurrent.recordSelection("of", "你"); concurrent.lookup("of", 0, 9); } }
                catch (Throwable e) { errors.add(e); }
            } });
            threads.add(thread); thread.start();
        }
        for (Thread t : threads) t.join();
        check(errors.isEmpty(), "concurrent calls");
        equal(concurrent.lookup("of", 0, 1).get(0).learnedCount, 400L, "no lost concurrent choices");
    }
    public static void main(String[] args) throws Exception {
        lookupTests(); learningTests(); persistenceTests(); parserTests(); productionTests();
        System.out.println("PASS: " + assertions + " assertions");
    }
}
