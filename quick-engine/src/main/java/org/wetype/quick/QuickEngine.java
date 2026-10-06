package org.wetype.quick;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Thread-safe, deterministic offline Quick lookup and explicit-selection learning. */
public final class QuickEngine {
    public static final int MAX_LEARNED_ENTRIES = 4096;
    public static final String QWERTY_ROOTS = "手田水口廿卜山戈人心日尸木火土竹十大中難金女月弓一";
    private static final String ROOTS = "日月金木水火土竹戈十大中一弓人心手口尸廿山女田難卜";
    private final QuickDictionary dictionary;
    private final LearningStore store;
    private Map<String, SelectionStat> learned;
    // Preserve Q3 records if a legacy single-character client opens the same profile.
    private final Map<String, SelectionStat> phraseRecords = new HashMap<String, SelectionStat>();
    private long sequence;
    private boolean privateMode;
    private boolean dirty;

    public QuickEngine(QuickDictionary dictionary, LearningStore store) throws IOException {
        if (dictionary == null || store == null) throw new NullPointerException();
        this.dictionary = dictionary; this.store = store;
        learned = new HashMap<String, SelectionStat>();
        Map<String, SelectionStat> loaded = store.load();
        if (loaded == null || loaded.size() > CompositionEngine.MAX_STORED_ENTRIES) throw new IOException("Invalid learning snapshot");
        for (Map.Entry<String, SelectionStat> item : loaded.entrySet()) {
            String key = item.getKey();
            SelectionStat value = item.getValue();
            if (!CompositionEngine.validStoredKey(key) || value == null || value.count < 1 || value.sequence < 1)
                throw new IOException("Invalid learning snapshot");
            if (CompositionEngine.validPhraseKey(key)) {
                phraseRecords.put(key, value);
                continue;
            }
            int tab = key.indexOf('\t');
            if (contains(key.substring(0, tab), key.substring(tab + 1))) {
                learned.put(key, value);
                sequence = Math.max(sequence, value.sequence);
            }
        }
        if (learned.size() > MAX_LEARNED_ENTRIES || phraseRecords.size() > CompositionEngine.MAX_LEARNED_PHRASES)
            throw new IOException("Invalid learning snapshot partition sizes");
    }
    static boolean validKey(String key) {
        if (key == null) return false;
        int tab = key.indexOf('\t');
        return tab >= 1 && tab <= 2 && QuickDictionary.validCode(key.substring(0, tab))
            && QuickDictionary.singleCharacter(key.substring(tab + 1));
    }
    private boolean contains(String code, String text) {
        for (QuickDictionary.Entry entry : dictionary.find(code)) if (entry.text.equals(text)) return true;
        return false;
    }
    private static final class Ranked {
        final QuickDictionary.Entry entry;
        final long count, recency;
        Ranked(QuickDictionary.Entry entry, SelectionStat stat) {
            this.entry = entry;
            count = stat == null ? 0 : stat.count;
            recency = stat == null ? 0 : stat.sequence;
        }
    }
    /**
     * ASCII uppercase is accepted. Empty, whitespace, invalid and overlong queries return no results.
     * A one-letter query includes exact roots and completions. Two letters require an exact Quick code.
     * Paginate with offset/limit; invalid negative pagination throws. No implicit commits or learning.
     */
    public synchronized List<Candidate> lookup(String query, int offset, int limit) {
        if (offset < 0 || limit < 0) throw new IllegalArgumentException("Negative pagination");
        if (limit == 0) return Collections.emptyList();
        final String code = QuickDictionary.normalize(query);
        if (code.length() == 0) return Collections.emptyList();
        List<Ranked> ranked = new ArrayList<Ranked>();
        for (QuickDictionary.Entry entry : dictionary.find(code)) {
            ranked.add(new Ranked(entry, privateMode ? null : learned.get(code + "\t" + entry.text)));
        }
        Collections.sort(ranked, new Comparator<Ranked>() {
            @Override public int compare(Ranked a, Ranked b) {
                // Learned choices outrank all default ordering, including one-code roots.
                int result = Long.compare(b.count, a.count);
                if (result != 0) return result;
                result = Long.compare(b.recency, a.recency);
                if (result != 0) return result;
                boolean aExact = a.entry.code.equals(code), bExact = b.entry.code.equals(code);
                if (aExact != bExact) return aExact ? -1 : 1;
                result = Integer.compare(a.entry.rank, b.entry.rank);
                if (result != 0) return result;
                result = a.entry.text.compareTo(b.entry.text);
                return result != 0 ? result : a.entry.code.compareTo(b.entry.code);
            }
        });
        Set<String> seen = new HashSet<String>();
        List<Candidate> result = new ArrayList<Candidate>();
        int position = 0;
        for (Ranked item : ranked) {
            QuickDictionary.Entry entry = item.entry;
            if (!seen.add(entry.text)) continue;
            if (position++ < offset) continue;
            result.add(new Candidate(entry.text, entry.code, entry.full, entry.rank, item.count));
            if (result.size() == limit) break;
        }
        return Collections.unmodifiableList(result);
    }
    public synchronized int candidateCount(String query) {
        String code = QuickDictionary.normalize(query);
        if (code.length() == 0) return 0;
        Set<String> texts = new HashSet<String>();
        for (QuickDictionary.Entry entry : dictionary.find(code)) texts.add(entry.text);
        return texts.size();
    }
    /**
     * Call only after a successful explicit candidate commit, not lookup, highlight, space guesses,
     * pasted content, surrounding-text scans, canceled composing text or failed InputConnection commits.
     * Invalid or non-dictionary choices return false. A valid private-mode choice returns true but is not saved.
     */
    public synchronized boolean recordSelection(String query, String text) {
        String code = QuickDictionary.normalize(query);
        if (code.length() == 0 || !QuickDictionary.singleCharacter(text) || !contains(code, text)) return false;
        if (privateMode) return true;
        if (sequence == Long.MAX_VALUE) rebaseSequences();
        String key = code + "\t" + text;
        SelectionStat old = learned.get(key);
        long count = old == null ? 1 : (old.count == Long.MAX_VALUE ? Long.MAX_VALUE : old.count + 1);
        learned.put(key, new SelectionStat(count, ++sequence));
        if (learned.size() > MAX_LEARNED_ENTRIES) {
            String oldestKey = null; long oldestSequence = Long.MAX_VALUE;
            for (Map.Entry<String, SelectionStat> item : learned.entrySet()) {
                if (item.getValue().sequence < oldestSequence
                    || (item.getValue().sequence == oldestSequence && (oldestKey == null || item.getKey().compareTo(oldestKey) < 0))) {
                    oldestKey = item.getKey(); oldestSequence = item.getValue().sequence;
                }
            }
            learned.remove(oldestKey);
        }
        dirty = true;
        return true;
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
        for (Map.Entry<String, SelectionStat> entry : entries) {
            learned.put(entry.getKey(), new SelectionStat(entry.getValue().count, ++sequence));
        }
    }
    /** Call off the UI thread after selection or input-session end; no background thread is created here. */
    public synchronized void flush() throws IOException {
        if (dirty) {
            Map<String, SelectionStat> snapshot = new HashMap<String, SelectionStat>(learned);
            snapshot.putAll(phraseRecords); store.save(snapshot); dirty = false;
        }
    }
    /** On failure, leave the in-memory state unchanged so that the host can report or retry deletion. */
    public synchronized void clearLearning() throws IOException {
        store.save(Collections.<String, SelectionStat>emptyMap());
        learned.clear(); phraseRecords.clear(); sequence = 0; dirty = false;
    }
    /** Private mode bypasses learned ranking and suppresses learning. It does not erase earlier choices. */
    public synchronized void setPrivateMode(boolean enabled) { privateMode = enabled; }
    public synchronized boolean isPrivateMode() { return privateMode; }
    public synchronized int learnedEntryCount() { return learned.size(); }
    public synchronized boolean hasUnsavedLearning() { return dirty; }
    public static String rootLabel(char letter) {
        if (letter >= 'A' && letter <= 'Z') letter = (char) (letter + ('a' - 'A'));
        return letter >= 'a' && letter <= 'y' ? String.valueOf(ROOTS.charAt(letter - 'a')) : "";
    }
    public static String rootLabels(String query) {
        String code = QuickDictionary.normalize(query);
        StringBuilder labels = new StringBuilder();
        for (int i = 0; i < code.length(); i++) labels.append(rootLabel(code.charAt(i)));
        return labels.toString();
    }
}
