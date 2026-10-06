package org.wetype.quick;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable dictionary; no Android, Rime, native library or network dependency. */
public final class QuickDictionary {
    static final class Entry {
        final String code, text, full;
        final int rank;
        Entry(String code, String text, int rank, String full) {
            this.code = code; this.text = text; this.rank = rank; this.full = full;
        }
    }
    private final Map<String, List<Entry>> exact;
    private final Map<String, List<Entry>> prefixes;
    private final int entryCount;
    private final int characterCount;

    private QuickDictionary(Map<String, List<Entry>> exact, Map<String, List<Entry>> prefixes,
                            int entryCount, int characterCount) {
        this.exact = freeze(exact); this.prefixes = freeze(prefixes);
        this.entryCount = entryCount; this.characterCount = characterCount;
    }
    private static Map<String, List<Entry>> freeze(Map<String, List<Entry>> source) {
        Map<String, List<Entry>> result = new HashMap<String, List<Entry>>();
        for (Map.Entry<String, List<Entry>> entry : source.entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }
    private static void add(Map<String, List<Entry>> index, String code, Entry entry) {
        List<Entry> list = index.get(code);
        if (list == null) { list = new ArrayList<Entry>(); index.put(code, list); }
        list.add(entry);
    }
    /** Reads UTF-8 TSV, closes neither the caller's stream nor any external resource. */
    public static QuickDictionary read(InputStream input) throws IOException {
        if (input == null) throw new NullPointerException("input");
        BufferedReader reader = new BufferedReader(new InputStreamReader(input,
            Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)));
        Map<String, List<Entry>> exact = new HashMap<String, List<Entry>>();
        Map<String, List<Entry>> prefixes = new HashMap<String, List<Entry>>();
        Set<String> keys = new HashSet<String>();
        Set<String> characters = new HashSet<String>();
        int lineNumber = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (lineNumber == 1 && line.startsWith("\ufeff")) line = line.substring(1);
            if (line.length() == 0 || line.startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 4) throw malformed(lineNumber);
            String code = fields[0], text = fields[1], full = fields[3];
            int rank;
            try { rank = Integer.parseInt(fields[2]); }
            catch (NumberFormatException e) { throw malformed(lineNumber); }
            if (!validCode(code) || !singleCharacter(text) || rank < 0
                    || !validFullCode(full) || !quickCode(full).equals(code)
                    || !keys.add(code + "\t" + text)) throw malformed(lineNumber);
            Entry entry = new Entry(code, text, rank, full);
            add(exact, code, entry);
            add(prefixes, code.substring(0, 1), entry);
            characters.add(text);
            if (keys.size() > 150000) throw new IOException("Dictionary exceeds entry limit");
        }
        if (keys.isEmpty()) throw new IOException("Empty Quick dictionary");
        return new QuickDictionary(exact, prefixes, keys.size(), characters.size());
    }
    private static IOException malformed(int line) {
        return new IOException("Invalid Quick dictionary at line " + line);
    }
    static boolean singleCharacter(String text) {
        if (text == null || text.length() == 0 || text.codePointCount(0, text.length()) != 1) return false;
        int cp = text.codePointAt(0);
        return cp > 0x20 && !(cp >= 0xd800 && cp <= 0xdfff) && cp != 0x7f;
    }
    static boolean validCode(String code) {
        if (code == null || code.length() < 1 || code.length() > 2) return false;
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) < 'a' || code.charAt(i) > 'y') return false;
        }
        return true;
    }
    private static boolean validFullCode(String code) {
        if (code.length() < 1 || code.length() > 5 || code.charAt(0) == 'z') return false;
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) < 'a' || code.charAt(i) > 'z') return false;
        }
        return true;
    }
    /** First and last strokes; a one-stroke Cangjie character retains its one stroke. */
    public static String quickCode(String fullCode) {
        if (fullCode == null || !validFullCode(fullCode)) throw new IllegalArgumentException("Cangjie code");
        return fullCode.length() == 1 ? fullCode : "" + fullCode.charAt(0) + fullCode.charAt(fullCode.length() - 1);
    }
    static String normalize(String code) {
        if (code == null || code.length() > 2) return "";
        StringBuilder result = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c >= 'A' && c <= 'Z') c = (char) (c + ('a' - 'A'));
            result.append(c);
        }
        String resultCode = result.toString();
        return validCode(resultCode) ? resultCode : "";
    }
    List<Entry> find(String code) {
        List<Entry> result = code.length() == 1 ? prefixes.get(code) : exact.get(code);
        return result == null ? Collections.<Entry>emptyList() : result;
    }
    public int entryCount() { return entryCount; }
    public int characterCount() { return characterCount; }
    public int codeCount() { return exact.size(); }
}
