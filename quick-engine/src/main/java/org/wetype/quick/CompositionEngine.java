package org.wetype.quick;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Bounded multi-character Quick composition; lookup never commits text or records choices. */
public final class CompositionEngine {
    public static final int MAX_CHARACTERS = 8;
    public static final int MAX_ROOTS = 16;
    public static final int MAX_PAGE_SIZE = 4096;
    public static final int MAX_LEARNED_PHRASES = 4096;
    public static final int MAX_STORED_ENTRIES = QuickEngine.MAX_LEARNED_ENTRIES + MAX_LEARNED_PHRASES;
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private final QuickDictionary dictionary;
    private final PhraseDictionary phrases;
    private final LearningStore store;
    private final MemoryLearningStore characterStore;
    private final QuickEngine single;
    private final Map<String, SelectionStat> learned = new HashMap<String, SelectionStat>();
    private long sequence;
    private boolean dirty;
    private Plan cached;

    private static final class Query {
        final String canonical;
        final String[] codes;
        final boolean finalPrefix;
        Query(String canonical, List<String> codes, boolean finalPrefix) {
            this.canonical = canonical; this.codes = codes.toArray(new String[codes.size()]);
            this.finalPrefix = finalPrefix;
        }
    }
    private static Query parse(String raw) {
        if (raw == null || raw.length() == 0 || raw.length() > MAX_ROOTS + MAX_CHARACTERS) return null;
        List<String> codes = new ArrayList<String>();
        StringBuilder current = new StringBuilder(2);
        int roots = 0; boolean separator = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
            if (c == '\'') {
                if (i == 0 || separator) return null;
                if (current.length() > 0) { codes.add(current.toString()); current.setLength(0); }
                separator = true;
            } else {
                if (c < 'a' || c > 'y' || ++roots > MAX_ROOTS) return null;
                if (current.length() == 2) { codes.add(current.toString()); current.setLength(0); }
                current.append(c); separator = false;
            }
            if (codes.size() > MAX_CHARACTERS) return null;
        }
        if (current.length() > 0) codes.add(current.toString());
        if (codes.isEmpty() || codes.size() > MAX_CHARACTERS) return null;
        boolean prefix = !separator && codes.get(codes.size() - 1).length() == 1;
        StringBuilder canonical = new StringBuilder();
        for (String code : codes) { if (canonical.length() > 0) canonical.append('\''); canonical.append(code); }
        if (separator && codes.get(codes.size() - 1).length() == 1) canonical.append('\'');
        return new Query(canonical.toString(), codes, prefix);
    }
    /** Invalid input returns empty; equivalent implicit/explicit two-root segmentation shares a key. */
    public static String normalizeQuery(String query) { Query q = parse(query); return q == null ? "" : q.canonical; }
    public static boolean isValidQuery(String query) { return parse(query) != null; }
    public static int segmentCount(String query) { Query q = parse(query); return q == null ? 0 : q.codes.length; }
    public static String rootLabels(String query) {
        String code = normalizeQuery(query); StringBuilder labels = new StringBuilder();
        for (int i = 0; i < code.length(); i++) labels.append(code.charAt(i) == '\'' ? "'" : QuickEngine.rootLabel(code.charAt(i)));
        return labels.toString();
    }
    static boolean validPhraseKey(String key) {
        if (key == null || !key.startsWith("p:")) return false;
        int tab = key.indexOf('\t');
        if (tab < 3) return false;
        String code = key.substring(2, tab), text = key.substring(tab + 1);
        Query q = parse(code);
        return q != null && q.codes.length >= 2 && q.canonical.equals(code)
            && PhraseDictionary.validPhrase(text) && text.codePointCount(0, text.length()) == q.codes.length;
    }
    static boolean validStoredKey(String key) { return QuickEngine.validKey(key) || validPhraseKey(key); }
    public CompositionEngine(QuickDictionary dictionary, PhraseDictionary phrases, LearningStore store) throws IOException {
        if (dictionary == null || phrases == null || store == null) throw new NullPointerException();
        this.dictionary = dictionary; this.phrases = phrases; this.store = store;
        Map<String, SelectionStat> loaded = store.load();
        if (loaded == null || loaded.size() > MAX_STORED_ENTRIES) throw new IOException("Invalid composition learning snapshot");
        Map<String, SelectionStat> characters = new HashMap<String, SelectionStat>();
        int phraseRecords = 0;
        for (Map.Entry<String, SelectionStat> item : loaded.entrySet()) {
            String key = item.getKey(); SelectionStat stat = item.getValue();
            if (!validStoredKey(key) || stat == null || stat.count < 1 || stat.sequence < 1)
                throw new IOException("Invalid composition learning entry");
            if (QuickEngine.validKey(key)) characters.put(key, stat);
            else {
                phraseRecords++;
                int tab = key.indexOf('\t');
                if (matches(parse(key.substring(2, tab)), key.substring(tab + 1))) {
                    learned.put(key, stat); sequence = Math.max(sequence, stat.sequence);
                }
            }
        }
        if (characters.size() > QuickEngine.MAX_LEARNED_ENTRIES || phraseRecords > MAX_LEARNED_PHRASES)
            throw new IOException("Too many composition learning entries");
        characterStore = new MemoryLearningStore(); characterStore.save(characters);
        single = new QuickEngine(dictionary, characterStore);
    }
    private boolean matches(Query q, String text) {
        if (q == null || text == null || text.codePointCount(0, text.length()) != q.codes.length) return false;
        String[] chars = PhraseDictionary.splitCharacters(text);
        for (int i = 0; i < chars.length; i++) {
            boolean found = false;
            for (QuickDictionary.Entry entry : dictionary.find(q.codes[i])) {
                if (entry.text.equals(chars[i]) && (q.codes[i].length() == 2
                    || (i == chars.length - 1 && q.finalPrefix) || entry.code.equals(q.codes[i]))) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }
    private static final class Promoted {
        final Candidate candidate;
        final long recency;
        final int weight;
        Promoted(Candidate base, int weight, SelectionStat stat) {
            candidate = new Candidate(base.text, base.quickCode, base.cangjieCode, weight < 0 ? Integer.MAX_VALUE : Integer.MAX_VALUE - weight,
                stat == null ? 0 : stat.count);
            this.weight = weight; recency = stat == null ? 0 : stat.sequence;
        }
    }
    private static final class Plan {
        final Query query;
        final List<List<Candidate>> slots = new ArrayList<List<Candidate>>();
        final List<Map<String, Integer>> positions = new ArrayList<Map<String, Integer>>();
        final List<Promoted> promoted = new ArrayList<Promoted>();
        final TreeSet<BigInteger> promotedIndices = new TreeSet<BigInteger>();
        BigInteger count = BigInteger.ONE;
        Plan(Query query) { this.query = query; }
        BigInteger indexOf(String[] characters) {
            if (characters.length != positions.size()) return null;
            BigInteger index = BigInteger.ZERO;
            for (int i = 0; i < characters.length; i++) {
                Integer position = positions.get(i).get(characters[i]);
                if (position == null) return null;
                index = index.multiply(BigInteger.valueOf(slots.get(i).size())).add(BigInteger.valueOf(position));
            }
            return index;
        }
        Candidate at(BigInteger index) {
            Candidate[] parts = new Candidate[slots.size()];
            for (int i = parts.length - 1; i >= 0; i--) {
                BigInteger[] step = index.divideAndRemainder(BigInteger.valueOf(slots.get(i).size()));
                parts[i] = slots.get(i).get(step[1].intValue()); index = step[0];
            }
            if (parts.length == 1) return parts[0];
            StringBuilder text = new StringBuilder(), code = new StringBuilder(), full = new StringBuilder();
            for (Candidate part : parts) {
                text.append(part.text);
                if (code.length() > 0) { code.append('\''); full.append('\''); }
                code.append(part.quickCode); full.append(part.cangjieCode);
            }
            return new Candidate(text.toString(), code.toString(), full.toString(), Integer.MAX_VALUE, 0);
        }
    }
    private Plan plan(String input) {
        Query q = parse(input);
        if (q == null) return null;
        if (cached != null && cached.query.canonical.equals(q.canonical)) return cached;
        Plan result = new Plan(q);
        for (int i = 0; i < q.codes.length; i++) {
            List<Candidate> slot = new ArrayList<Candidate>();
            Map<String, Integer> positions = new HashMap<String, Integer>();
            for (Candidate c : single.lookup(q.codes[i], 0, Integer.MAX_VALUE)) {
                if (q.codes[i].length() == 1 && !(i == q.codes.length - 1 && q.finalPrefix)
                    && !c.quickCode.equals(q.codes[i])) continue;
                positions.put(c.text, slot.size()); slot.add(c);
            }
            result.slots.add(slot); result.positions.add(positions);
            result.count = result.count.multiply(BigInteger.valueOf(slot.size()));
        }
        if (q.codes.length > 1 && result.count.signum() > 0) {
            Map<String, Integer> weights = new HashMap<String, Integer>();
            for (String first : result.positions.get(0).keySet()) {
                for (PhraseDictionary.Entry phrase : phrases.find(q.codes.length, first)) {
                    if (result.indexOf(phrase.characters) != null) weights.put(phrase.text, phrase.weight);
                }
            }
            if (!single.isPrivateMode()) {
                String prefix = "p:" + q.canonical + "\t";
                for (String key : learned.keySet()) if (key.startsWith(prefix)) {
                    String text = key.substring(prefix.length());
                    if (!weights.containsKey(text)) weights.put(text, -1);
                }
            }
            for (Map.Entry<String, Integer> item : weights.entrySet()) {
                String text = item.getKey();
                BigInteger index = result.indexOf(PhraseDictionary.splitCharacters(text));
                if (index == null) continue;
                SelectionStat stat = single.isPrivateMode() ? null : learned.get("p:" + q.canonical + "\t" + text);
                result.promoted.add(new Promoted(result.at(index), item.getValue(), stat));
                result.promotedIndices.add(index);
            }
            Collections.sort(result.promoted, new Comparator<Promoted>() {
                @Override public int compare(Promoted a, Promoted b) {
                    int c = Long.compare(b.candidate.learnedCount, a.candidate.learnedCount);
                    if (c == 0) c = Long.compare(b.recency, a.recency);
                    if (c == 0) c = Integer.compare(b.weight, a.weight);
                    return c == 0 ? a.candidate.text.compareTo(b.candidate.text) : c;
                }
            });
        }
        cached = result; return result;
    }
    /** Exact total counts all distinct code-valid combinations, including prioritized known phrases. */
    public synchronized BigInteger exactCandidateCount(String query) {
        Plan p = plan(query); return p == null ? BigInteger.ZERO : p.count;
    }
    /** Saturates at Long.MAX_VALUE. Use exactCandidateCount or isCountExact before displaying a total. */
    public synchronized long candidateCount(String query) { return exactCandidateCount(query).min(LONG_MAX).longValue(); }
    public synchronized boolean isCountExact(String query) { return exactCandidateCount(query).compareTo(LONG_MAX) <= 0; }
    public synchronized List<Candidate> lookup(String query, long offset, int limit) {
        return lookup(query, BigInteger.valueOf(offset), limit);
    }
    /** Random-access lazy pagination, even beyond Long.MAX_VALUE; no Cartesian list is materialized. */
    public synchronized List<Candidate> lookup(String query, BigInteger offset, int limit) {
        if (offset == null || offset.signum() < 0 || limit < 0 || limit > MAX_PAGE_SIZE)
            throw new IllegalArgumentException("Invalid pagination");
        if (limit == 0) return Collections.emptyList();
        Plan p = plan(query);
        if (p == null || offset.compareTo(p.count) >= 0) return Collections.emptyList();
        List<Candidate> page = new ArrayList<Candidate>();
        BigInteger promotedCount = BigInteger.valueOf(p.promoted.size());
        while (offset.compareTo(promotedCount) < 0 && page.size() < limit) {
            page.add(p.promoted.get(offset.intValue()).candidate); offset = offset.add(BigInteger.ONE);
        }
        if (page.size() < limit) {
            BigInteger raw = offset.subtract(promotedCount);
            for (BigInteger excluded : p.promotedIndices) {
                if (excluded.compareTo(raw) > 0) break;
                raw = raw.add(BigInteger.ONE);
            }
            while (raw.compareTo(p.count) < 0 && page.size() < limit) {
                if (!p.promotedIndices.contains(raw)) page.add(p.at(raw));
                raw = raw.add(BigInteger.ONE);
            }
        }
        return Collections.unmodifiableList(page);
    }
    /** Read-only membership check for local pin/block adapters; never records a choice. */
    public synchronized boolean containsCandidate(String query, String text) { return matches(parse(query), text); }
    /** Call only after a successful explicit candidate commit. Does not collect surrounding text. */
    public synchronized boolean recordSelection(String query, String text) {
        Query q = parse(query);
        if (!matches(q, text)) return false;
        if (q.codes.length == 1) {
            boolean recorded = single.recordSelection(q.codes[0], text);
            if (!single.isPrivateMode() && recorded) { dirty = true; cached = null; }
            return recorded;
        }
        if (single.isPrivateMode()) return true;
        if (sequence == Long.MAX_VALUE) rebaseSequences();
        String key = "p:" + q.canonical + "\t" + text;
        SelectionStat previous = learned.get(key);
        long count = previous == null ? 1 : previous.count == Long.MAX_VALUE ? Long.MAX_VALUE : previous.count + 1;
        learned.put(key, new SelectionStat(count, ++sequence));
        if (learned.size() > MAX_LEARNED_PHRASES) {
            String oldest = null; long oldestSequence = Long.MAX_VALUE;
            for (Map.Entry<String, SelectionStat> item : learned.entrySet()) {
                if (item.getValue().sequence < oldestSequence || (item.getValue().sequence == oldestSequence
                    && (oldest == null || item.getKey().compareTo(oldest) < 0))) {
                    oldest = item.getKey(); oldestSequence = item.getValue().sequence;
                }
            }
            learned.remove(oldest);
        }
        dirty = true; cached = null; return true;
    }
    private void rebaseSequences() {
        List<Map.Entry<String, SelectionStat>> entries = new ArrayList<Map.Entry<String, SelectionStat>>(learned.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, SelectionStat>>() {
            @Override public int compare(Map.Entry<String, SelectionStat> a, Map.Entry<String, SelectionStat> b) {
                int c = Long.compare(a.getValue().sequence, b.getValue().sequence);
                return c == 0 ? a.getKey().compareTo(b.getKey()) : c;
            }
        });
        sequence = 0;
        for (Map.Entry<String, SelectionStat> entry : entries)
            learned.put(entry.getKey(), new SelectionStat(entry.getValue().count, ++sequence));
    }
    public synchronized void flush() throws IOException {
        if (!dirty) return;
        single.flush();
        Map<String, SelectionStat> snapshot = characterStore.load(); snapshot.putAll(learned);
        store.save(snapshot); dirty = false;
    }
    /** Durable empty snapshot first; failed deletion leaves the current in-memory profile intact. */
    public synchronized void clearLearning() throws IOException {
        store.save(Collections.<String, SelectionStat>emptyMap());
        single.clearLearning(); learned.clear(); sequence = 0; dirty = false; cached = null;
    }
    /** Reasserting private mode also releases a prior session's transient query plan. */
    public synchronized void setPrivateMode(boolean enabled) {
        if (enabled || enabled != single.isPrivateMode()) {
            single.setPrivateMode(enabled);
            cached = null;
        }
    }
    public synchronized boolean isPrivateMode() { return single.isPrivateMode(); }
    public synchronized int learnedEntryCount() { return single.learnedEntryCount() + learned.size(); }
    public synchronized boolean hasUnsavedLearning() { return dirty; }
}
