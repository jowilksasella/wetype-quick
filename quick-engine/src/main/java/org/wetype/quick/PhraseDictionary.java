package org.wetype.quick;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable offline phrase priorities. No code-product expansion or runtime prediction. */
public final class PhraseDictionary {
    public static final int MAX_ENTRIES = 500000;
    static final class Entry {
        final String text;
        final String[] characters;
        final int weight;
        Entry(String text, int weight) {
            this.text = text; this.weight = weight;
            characters = splitCharacters(text);
        }
    }
    private final Map<String, List<Entry>> firstCharacterIndex;
    private final int count;
    private PhraseDictionary(Map<String, Integer> phrases) {
        Map<String, List<Entry>> index = new HashMap<String, List<Entry>>();
        for (Map.Entry<String, Integer> phrase : phrases.entrySet()) {
            Entry entry = new Entry(phrase.getKey(), phrase.getValue());
            String key = entry.characters.length + "\t" + entry.characters[0];
            List<Entry> list = index.get(key);
            if (list == null) { list = new ArrayList<Entry>(); index.put(key, list); }
            list.add(entry);
        }
        for (Map.Entry<String, List<Entry>> item : index.entrySet())
            item.setValue(Collections.unmodifiableList(item.getValue()));
        firstCharacterIndex = Collections.unmodifiableMap(index);
        count = phrases.size();
    }
    public static PhraseDictionary empty() { return new PhraseDictionary(Collections.<String, Integer>emptyMap()); }
    /** UTF-8 text TAB nonnegative integer weight. Higher weights rank first. Caller owns stream. */
    public static PhraseDictionary read(InputStream input) throws IOException {
        if (input == null) throw new NullPointerException("input");
        BufferedReader reader = new BufferedReader(new InputStreamReader(input,
            Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)));
        Map<String, Integer> phrases = new HashMap<String, Integer>();
        String line; int lineNumber = 0;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (lineNumber == 1 && line.startsWith("\ufeff")) line = line.substring(1);
            if (line.length() == 0 || line.startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            int weight;
            try { weight = fields.length == 2 ? Integer.parseInt(fields[1]) : -1; }
            catch (NumberFormatException e) { weight = -1; }
            if (fields.length != 2 || !validPhrase(fields[0]) || weight < 0)
                throw new IOException("Invalid phrase dictionary at line " + lineNumber);
            Integer old = phrases.get(fields[0]);
            if (old == null || old < weight) phrases.put(fields[0], weight);
            if (phrases.size() > MAX_ENTRIES) throw new IOException("Phrase dictionary exceeds entry limit");
        }
        return new PhraseDictionary(phrases);
    }
    static String[] splitCharacters(String text) {
        String[] result = new String[text.codePointCount(0, text.length())];
        for (int offset = 0, i = 0; offset < text.length(); i++) {
            int next = offset + Character.charCount(text.codePointAt(offset));
            result[i] = text.substring(offset, next); offset = next;
        }
        return result;
    }
    static boolean validPhrase(String text) {
        if (text == null || text.length() > CompositionEngine.MAX_CHARACTERS * 2) return false;
        String[] characters = splitCharacters(text);
        if (characters.length < 2 || characters.length > CompositionEngine.MAX_CHARACTERS) return false;
        for (String character : characters) if (!QuickDictionary.singleCharacter(character)) return false;
        return true;
    }
    List<Entry> find(int length, String firstCharacter) {
        List<Entry> found = firstCharacterIndex.get(length + "\t" + firstCharacter);
        return found == null ? Collections.<Entry>emptyList() : found;
    }
    public int entryCount() { return count; }
}
