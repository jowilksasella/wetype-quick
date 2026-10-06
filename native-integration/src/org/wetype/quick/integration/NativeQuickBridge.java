package org.wetype.quick.integration;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;
import com.tencent.wetype.plugin.hld.WxHldService;
import com.tencent.wetype.plugin.hld.candidate.d;
import com.tencent.wetype.plugin.hld.model.N;
import com.tencent.wetype.plugin.hld.model.i0;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.wetype.quick.Candidate;
import org.wetype.quick.CompositionEngine;
import org.wetype.quick.FileLearningStore;
import org.wetype.quick.MemoryLearningStore;
import org.wetype.quick.PhraseDictionary;
import org.wetype.quick.QuickDictionary;
import org.wetype.quick.QuickEngine;

/**
 * Quick backend for the original WeType keyboard, pending renderer and candidate UI.
 * No keyboard View is created, wrapped, reparented or resized here. Native code owns
 * gestures, key geometry, themes, symbols, editor deletion and settings. This class
 * owns only Quick query/selection semantics and app-private explicit learning.
 * Prefix-clear planning reads only bounded local context/selection metadata through
 * the preserved Q4 EditorDeletion helper; it retains no text and transmits nothing.
 */
public final class NativeQuickBridge {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService STORAGE = Executors.newSingleThreadExecutor();
    private static final int CHUNK = 64, WINDOW = 1024;
    private static final String PREFS = "quick-native-backend", PREF_SELECTED = "selected";
    private static volatile CompositionEngine engine;
    private static volatile QuickLocalActions localActions;
    private static volatile boolean loading, loadFailed;
    private static Context application;
    private static boolean preferencesLoaded;
    private static File profile, actionsFile;
    private static boolean temporaryLearning, actionLoadFailed;
    private static WxHldService service;
    private static View nativeRoot;
    private static InputConnection connection;
    private static volatile boolean selected, started, committing, publishing, backendOwned;
    private static boolean deletingPending, loadMorePosted;
    private static volatile String query = "";
    private static volatile long generation;
    private static long session, editorRevision;
    private static volatile long publishingSession = -1;
    private static volatile Long inFlightSession;
    private static boolean privateMode = true, allowInput;
    private static int selectionStart = -1, selectionEnd = -1, composingStart = -1, composingEnd = -1;
    private static final ArrayList<int[]> expectedSelections = new ArrayList<int[]>();
    private static BigInteger windowOffset = BigInteger.ZERO, consumed = BigInteger.ZERO;
    private static BigInteger count = BigInteger.ZERO;
    private static final ArrayList<Result> results = new ArrayList<Result>();
    private static final ArrayList<QuickNativeCandidate> visible = new ArrayList<QuickNativeCandidate>();
    private static String publicationQuery = "";
    private static boolean more;
    private static final class Result {
        final String text, lookup, remaining;
        Result(String text, String lookup, String remaining) {
            this.text = text; this.lookup = lookup; this.remaining = remaining;
        }
    }
    private NativeQuickBridge() { }

    public static void attach(WxHldService host, View original) {
        requireMain();
        service = host; nativeRoot = original; initialize(host);
        if (started) refreshEditor();
    }
    private static void initialize(Context context) {
        if (application == null) {
            application = context.getApplicationContext();
            if (application == null) application = context;
        }
        if (!preferencesLoaded) {
            selected = application.getSharedPreferences(PREFS, 0).getBoolean(PREF_SELECTED, false);
            preferencesLoaded = true;
        }
        ensureLoaded();
    }
    /** Release only a prior Quick owner before the original service initializes a new editor. */
    public static void willStart(WxHldService host) {
        requireMain();
        boolean wasQuick = backendOwned || hasComposition();
        if (wasQuick) {
            NativeModelPort.invalidate(i0.a);
            if (service != null && inFlightSession == null) service.quickNativeResetCompositionCache();
        }
        session++; started = false; backendOwned = false; allowInput = false; privateMode = true;
        if (engine != null) engine.setPrivateMode(true);
        query = ""; generation++; results.clear(); visible.clear(); publicationQuery = "";
        expectedSelections.clear(); connection = null;
        selectionStart = selectionEnd = composingStart = composingEnd = -1;
    }
    /** Native callback epilogues may reuse their EditorInfo parameter register. */
    public static void start(WxHldService host) { start(host, null); }
    /** Read effective editor metadata from the completed native service state. */
    public static void viewStarted(WxHldService host) { viewStarted(host, null); }
    public static void start(WxHldService host, EditorInfo ignoredExternalInfo) {
        requireMain();
        if (backendOwned || hasComposition()) NativeModelPort.invalidate(i0.a);
        backendOwned = false;
        service = host; initialize(host);
        session++; started = true; committing = false; deletingPending = false; expectedSelections.clear();
        query = ""; generation++; results.clear(); visible.clear(); publicationQuery = "";
        connection = null; selectionStart = selectionEnd = composingStart = composingEnd = -1;
        refreshEditor();
    }
    public static void viewStarted(WxHldService host, EditorInfo ignoredExternalInfo) {
        requireMain();
        if (service != host || !started) { start(host, ignoredExternalInfo); return; }
        refreshEditor();
        if (hasComposition()) publish(false, true);
        invalidateKeys();
    }
    public static void viewFinished(WxHldService host, boolean finishingInput) {
        requireMain();
        if (host != service || !started) return;
        refreshEditor();
        final long token = session;
        final InputConnection target = connection;
        if (inFlightSession == null && target != null && service.B(false) == target && hasComposition()) {
            // The original hide path settles its own backend. Select the complete Quick
            // phrase here, never a first-character fallback or raw root-label string.
            Result full = null;
            for (Result result : results) if (result.remaining.length() == 0) { full = result; break; }
            if (full != null) commit(full, false);
        }
        if (token != session || target != connection) return;
        finish(host);
    }
    public static void finish(WxHldService host) {
        requireMain();
        if (host != service) return;
        boolean wasQuick = backendOwned || hasComposition();
        final long endingSession = session;
        final WxHldService endingService = service;
        // Clear the original keyboard pending line only; Quick never creates editor
        // preedit spans. A reentrant old commit cannot cancel the replacement query.
        if (started && inFlightSession == null && hasComposition() && connection != null
                && service.B(false) == connection) {
            resetQuery(true);
            if (endingSession != session || endingService != service) return;
            if (backendOwned) service.quickNativeResetCompositionCache();
        }
        // An original observer may have started a replacement input session.
        if (endingSession != session || endingService != service) return;
        if (wasQuick && backendOwned) NativeModelPort.invalidate(i0.a);
        session++; started = false; committing = false; backendOwned = false; deletingPending = false; expectedSelections.clear();
        query = ""; generation++; results.clear(); visible.clear(); publicationQuery = "";
        connection = null; allowInput = false; privateMode = true;
        if (engine != null) engine.setPrivateMode(true);
        flushSoon();
    }
    public static boolean isQuickSelected() { return selected; }
    public static boolean active() {
        return selected && started && allowInput && N.a.n0() == 1 && N.a.u0() == 1;
    }
    public static boolean ownsPending() {
        return backendOwned || (publishing && publishingSession == session)
                || (inFlightSession != null && inFlightSession.longValue() == session);
    }
    public static boolean hasComposition() {
        return selected && started && query.length() != 0;
    }
    public static String pendingText() { return CompositionEngine.rootLabels(query); }
    public static int pendingLength() { return CompositionEngine.rootLabels(query).replace("'", "").length(); }
    public static boolean pendingEmpty() { return query.length() == 0; }
    public static boolean useInlinePending(boolean originalPreference) { return ownsPending() ? false : originalPreference; }
    /** Native voice/other h0 calls remain available outside Quick UI publication. */
    public static boolean allowComposingText(CharSequence text) { return !(publishing && publishingSession == session); }
    public static boolean isQuickCandidate(d candidate) { return candidate instanceof QuickNativeCandidate; }
    public static boolean allowNativeCandidates(ArrayList<?> candidates) {
        if (!ownsPending()) return true;
        if (candidates == null || candidates.isEmpty()) return false;
        for (Object candidate : candidates) {
            if (!(candidate instanceof QuickNativeCandidate)
                    || ((QuickNativeCandidate) candidate).generation != generation) return false;
        }
        return true;
    }
    /** Report gates use this only for the currently owned Quick input session. */
    public static boolean quickSession() { return ownsPending(); }
    public static boolean selectQuick(Context context) {
        requireMain(); initialize(context); refreshEditor();
        if (!backendOwned && !i0.a.A3()) {
            notice("請先選取或清除原本的候選字，再切換速成"); return false;
        }
        selected = true;
        application.getSharedPreferences(PREFS, 0).edit().putBoolean(PREF_SELECTED, true).apply();
        refreshEditor();
        if (service != null) service.quickNativeResetCompositionCache();
        invalidateKeys(); return true;
    }
    public static void selectOriginal(Context context) {
        requireMain(); initialize(context); refreshEditor();
        boolean wasQuick = backendOwned || hasComposition();
        long token = session;
        if (hasComposition() && !settle(false)) resetQuery(true);
        if (token != session) return;
        if (wasQuick && service != null) service.quickNativeResetCompositionCache();
        selected = false; backendOwned = false; deletingPending = false;
        application.getSharedPreferences(PREFS, 0).edit().putBoolean(PREF_SELECTED, false).apply();
        invalidateKeys();
    }
    public static void modeChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) { MAIN.post(new Runnable() { public void run() { modeChanged(); } }); return; }
        refreshEditor(); deletingPending = false; invalidateKeys();
    }
    public static String displayRoot(String id, String original) {
        if (!active() || id == null || original == null || original.length() != 1) return original;
        if (id.endsWith("_copy")) id = id.substring(0, id.length() - 5);
        if (id.length() != 8 || !id.startsWith("S2_key_")) return original;
        char key = id.charAt(7), value = Character.toLowerCase(original.charAt(0));
        return key >= 'a' && key <= 'y' && key == value ? QuickEngine.rootLabel(key) : original;
    }
    private static boolean rootEvent(wa.h key) {
        if (key == null || key.a == null) return false;
        if ("'".equals(key.a)) return hasComposition();
        String id = key.b;
        if (id != null && id.endsWith("_copy")) id = id.substring(0, id.length() - 5);
        if (id == null || !id.startsWith("S2_key_") || id.length() != 8 || key.a.length() != 1) return false;
        char c = Character.toLowerCase(key.a.charAt(0));
        return c >= 'a' && c <= 'y' && c == id.charAt(7);
    }
    public static boolean prepress(wa.h key) {
        if (!active()) return false;
        refreshEditor();
        if (!active() || key == null || key.a == null || key.a.length() != 1) return false;
        char c = Character.toLowerCase(key.a.charAt(0));
        // Original Down params have no keyId yet; release performs the stable-ID check.
        return (c >= 'a' && c <= 'z') || (c == '\'' && hasComposition());
    }
    public static boolean key(wa.h key) {
        requireMain();
        if (!active()) return false;
        refreshEditor();
        if (!active()) return false;
        deletingPending = false;
        if (rootEvent(key)) {
            String next = query + key.a.toLowerCase(java.util.Locale.ROOT);
            if (CompositionEngine.isValidQuery(next)) { query = next; changed(false); }
            // Invalid extra roots or duplicate separators do not commit/drop composition.
            return true;
        }
        if (hasComposition() && !settle(false)) resetQuery(true);
        if (key != null && "z".equalsIgnoreCase(key.a) && key.b != null && key.b.startsWith("S2_key_z")) {
            com.tencent.wetype.plugin.hld.key.d.a.O(4, null); return true;
        }
        return false; // Original float symbols, punctuation, English and other keys.
    }
    public static boolean function(int function, Object args) {
        requireMain();
        if (!selected || !started) return false;
        refreshEditor();
        // The Chinese-backend preference can remain selected while original English,
        // handwriting or protected-field keyboards are active. Their deletes stay native.
        if (!backendOwned && !hasComposition()) return false;
        if (function == 1 && args instanceof com.tencent.wetype.plugin.hld.keyboard.s.a) {
            com.tencent.wetype.plugin.hld.keyboard.s.a deletion = (com.tencent.wetype.plugin.hld.keyboard.s.a) args;
            if (deletion.b() == 1) {
                int phase = deletion.c().ordinal();
                int length = Math.max(0, deletion.a());
                boolean apply = phase != 2 || !deletingPending;
                if (phase == 2) deletingPending = false;
                else deletingPending = true;
                if (apply && length > 0 && query.length() > 0) {
                    int cut = query.length();
                    while (cut > 0 && length > 0) {
                        char removed = query.charAt(--cut);
                        if (removed != '\'') length--;
                    }
                    while (cut > 0 && query.charAt(cut - 1) == '\'') cut--;
                    query = query.substring(0, cut);
                    changed(true);
                }
                // Last catches the native engine up with earlier previews; Quick has
                // already applied them. Never let stale target-1 events delete editor text.
                return true;
            }
            deletingPending = false;
            if (deletion.b() == 2 && hasComposition()) resetQuery(true);
            return false; // Native editor deletion, sweep, repeat and restore remain original.
        }
        deletingPending = false;
        if (!hasComposition()) return false;
        if (function == 2 || function == 6) { settle(true); return true; }
        // Retain original symbol/number/voice/mode/cursor actions after finishing Quick.
        if (!settle(false)) resetQuery(true);
        return false;
    }
    /** Original upward-clear completion only; regular tap/repeat stays native. */
    public static boolean nativeClearBeforeCursor() {
        if (!ownsPending()) return false;
        requireMain(); refreshEditor();
        if (!backendOwned) return false;
        if (connection == null || inFlightSession != null) return true;
        final InputConnection target = connection;
        final long token = session, epoch = editorRevision;
        long beforeClear = generation;
        resetQuery(true);
        if (session != token || connection != target || editorRevision != epoch
                || generation != beforeClear + 1 || query.length() != 0) return true;
        final long revision = generation;
        try {
            // Q4's verified planner requires any native/foreign composing span to be
            // finished before it computes a prefix-only range. Quick adds no such span.
            if (!target.finishComposingText()) return true;
            refreshEditor();
            if (!clearOwner(target, token, revision, epoch)) return true;
            EditorDeletion.DeletionPlan plan = EditorDeletion.planBeforeCursor(target);
            refreshEditor();
            if (!clearOwner(target, token, revision, epoch)) return true;
            if (plan.operation == EditorDeletion.NO_OP) return true;
            if (plan.operation != EditorDeletion.DELETE_BEFORE) {
                notice("未能安全確認游標位置；速成字根已清除，欄位文字未刪除"); return true;
            }
            int previousStart = plan.selectionStart, previousEnd = plan.selectionEnd;
            if (previousStart != selectionStart || previousEnd != selectionEnd) expectedSelections.clear();
            ArrayList<int[]> previousExpected = new ArrayList<int[]>(expectedSelections);
            boolean batch = false, changed = false;
            try {
                batch = target.beginBatchEdit();
                refreshEditor();
                if (clearOwner(target, token, revision, epoch)) {
                    selectionStart = plan.expectedSelectionStart; selectionEnd = plan.expectedSelectionEnd;
                    expectedSelections.add(new int[] {selectionStart, selectionEnd});
                    if (expectedSelections.size() > 32) expectedSelections.remove(0);
                    changed = target.deleteSurroundingText(plan.beforeUtf16, 0);
                }
            } finally {
                if (batch) try { target.endBatchEdit(); } catch (RuntimeException ignored) { }
                if (!changed && clearOwner(target, token, revision, epoch)) {
                    selectionStart = previousStart; selectionEnd = previousEnd;
                    expectedSelections.clear(); expectedSelections.addAll(previousExpected);
                }
            }
            if (changed && clearOwner(target, token, revision, epoch)) {
                // The original undo snapshot may describe an older operation. Never
                // offer that unrelated text as an undo of this prefix-only deletion.
                com.tencent.wetype.plugin.hld.delete.d.a.c();
                service.quickNativeResetCompositionCache();
            } else if (clearOwner(target, token, revision, epoch)) notice("這個輸入框未接受清除；欄位文字未刪除");
        } catch (RuntimeException editorRejected) {
            if (clearOwner(target, token, revision, epoch)) notice("這個輸入框未接受清除");
        }
        return true;
    }
    private static boolean clearOwner(InputConnection target, long token, long revision, long epoch) {
        return selected && started && backendOwned && allowInput && session == token
                && generation == revision && editorRevision == epoch && connection == target
                && service != null && service.B(false) == target;
    }
    private static void refreshEditor() {
        if (service == null || !started) return;
        final boolean wasQuick = backendOwned;
        InputConnection current = service.B(false);
        EditorInfo info = service.y(false);
        boolean changedConnection = connection != null && current != connection;
        boolean permitted = current != null && info != null && InputPolicy.allowQuick(info.inputType);
        boolean nextOwner = selected && started && permitted && N.a.n0() == 1;
        if (wasQuick && (changedConnection || !nextOwner)) {
            // This is the only old-owner cleanup. Inactive original-to-original
            // transitions never touch native candidate/pending or service caches.
            NativeModelPort.invalidate(i0.a);
            query = ""; generation++; expectedSelections.clear(); results.clear(); visible.clear(); publicationQuery = "";
            deletingPending = false;
        }
        if (changedConnection) {
            selectionStart = selectionEnd = composingStart = composingEnd = -1;
            session++; editorRevision++; expectedSelections.clear();
            query = ""; generation++; results.clear(); visible.clear(); publicationQuery = "";
        }
        connection = current; allowInput = permitted;
        privateMode = info == null || InputPolicy.privateMode(info.inputType, info.imeOptions);
        backendOwned = nextOwner;
        if (selectionStart < 0 && info != null) { selectionStart = info.initialSelStart; selectionEnd = info.initialSelEnd; }
        if (engine != null) engine.setPrivateMode(privateMode);
        if (nextOwner && (!wasQuick || changedConnection)) resetQuery(true);
    }
    public static void selection(WxHldService host, int oldStart, int oldEnd,
            int newStart, int newEnd, int candidateStart, int candidateEnd) {
        if (host != service || !started) return;
        // A reentrant input-session replacement cannot inherit notifications from the
        // older commit still on this stack, even when an editor reuses its connection.
        if (inFlightSession != null && inFlightSession.longValue() != session) return;
        refreshEditor();
        for (int i = 0; i < expectedSelections.size(); i++) {
            int[] expected = expectedSelections.get(i);
            if (expected[0] == newStart && expected[1] == newEnd && candidateStart < 0 && candidateEnd < 0) {
                expectedSelections.subList(0, i + 1).clear(); return;
            }
        }
        selectionStart = newStart; selectionEnd = newEnd;
        composingStart = candidateStart; composingEnd = candidateEnd;
        if (!committing && !publishing && (oldStart != newStart || oldEnd != newEnd)) editorRevision++;
        if (committing || publishing || !hasComposition()) return;
        // Native pending renderer may move the cursor to the end of its composing span.
        if (candidateStart >= 0 && candidateEnd >= candidateStart
                && newStart == candidateEnd && newEnd == candidateEnd) return;
        if (oldStart == newStart && oldEnd == newEnd) return;
        // Quick preedit lives only in the original keyboard's pending line. Moving
        // the cursor cancels that local query without touching editor text or selection.
        resetQuery(true);
    }
    private static void changed(boolean deleted) {
        generation++; windowOffset = BigInteger.ZERO; consumed = BigInteger.ZERO;
        results.clear(); visible.clear(); publicationQuery = query;
        publish(deleted, true);
    }
    private static void resetQuery(boolean notify) {
        query = ""; generation++; publicationQuery = "";
        windowOffset = consumed = count = BigInteger.ZERO; results.clear(); visible.clear(); more = false;
        if (notify && service != null && started) {
            boolean previousPublishing = publishing;
            long previousPublication = publishingSession;
            publishingSession = session; publishing = true;
            try { NativeModelPort.clear(i0.a); }
            finally { publishing = previousPublishing; publishingSession = previousPublication; }
        }
    }
    private static void addResult(Result result, Set<String> seen) {
        if (!seen.add(result.text)) return;
        QuickLocalActions actions = localActions;
        if (actions != null && !privateMode && actions.isBlocked(result.lookup, result.text)) return;
        int index = results.size(); results.add(result);
        visible.add(new QuickNativeCandidate(generation, index, result.text, 0, 0, 99));
    }
    private static void publish(boolean deleted, boolean newList) {
        if (service == null || !started || !selected) return;
        refreshEditor();
        if (!backendOwned) return;
        if (query.length() == 0) { resetQuery(true); return; }
        final CompositionEngine current = engine;
        if (newList) { results.clear(); visible.clear(); consumed = BigInteger.ZERO; publicationQuery = query; }
        if (current == null) {
            visible.clear(); visible.add(new QuickNativeCandidate(generation, 2,
                    loadFailed ? "速成字詞庫未能載入" : "載入中 · " + CompositionEngine.rootLabels(query), 1, 0, 99));
            more = false;
        } else {
            current.setPrivateMode(privateMode);
            count = current.exactCandidateCount(query);
            Set<String> seen = new HashSet<String>();
            for (Result result : results) seen.add(result.text);
            if (newList && windowOffset.signum() == 0) {
                String pin = localActions == null || privateMode ? null : localActions.pinned(query);
                if (pin != null && current.containsCandidate(query, pin)) addResult(new Result(pin, query, ""), seen);
            }
            do {
                int left = Math.min(CHUNK, WINDOW - consumed.intValue());
                List<Candidate> page = current.lookup(query, windowOffset.add(consumed), Math.max(0, left));
                consumed = consumed.add(BigInteger.valueOf(page.size()));
                for (Candidate candidate : page) addResult(new Result(candidate.text, query, ""), seen);
                if (page.isEmpty()) break;
                // Skip fully blocked chunks, but never skip a code-valid unvisited result.
                // At the bounded window limit a working next-window control is published.
            } while (visible.isEmpty() && consumed.intValue() < WINDOW
                    && windowOffset.add(consumed).compareTo(count) < 0);
            // A native single-character tab can select the first segment and continue
            // with the remaining Quick phrase, without losing the full phrase list.
            if (newList && windowOffset.signum() == 0 && CompositionEngine.segmentCount(query) > 1) {
                String normalized = CompositionEngine.normalizeQuery(query);
                int boundary = normalized.indexOf('\'');
                String first = normalized.substring(0, boundary);
                String rest = normalized.substring(boundary + 1);
                String lookup = first.length() == 1 ? first + "'" : first;
                String partialPin = localActions == null || privateMode ? null : localActions.pinned(lookup);
                if (partialPin != null && current.containsCandidate(lookup, partialPin)) addResult(new Result(partialPin, lookup, rest), seen);
                // This is one exact segment, not a Cartesian product (current corpus
                // maximum105). All first-character choices remain in the native 單字 tab.
                for (Candidate c : current.lookup(lookup, BigInteger.ZERO, CompositionEngine.MAX_PAGE_SIZE)) addResult(new Result(c.text, lookup, rest), seen);
            }
            boolean unseen = windowOffset.add(consumed).compareTo(count) < 0;
            more = unseen && consumed.intValue() < WINDOW;
            // Native widgets have int positions. Bound each window; the next/previous
            // controls retain arbitrary BigInteger offsets, so every code match is reachable.
            if (!more) {
                if (windowOffset.signum() > 0) visible.add(new QuickNativeCandidate(generation, 1, "上一組候選", 1, 0, 99));
                if (unseen) visible.add(new QuickNativeCandidate(generation, 0, "下一組候選", 1, 0, 99));
            }
            if (visible.isEmpty()) visible.add(new QuickNativeCandidate(generation, 2, "沒有符合的字詞", 1, 0, 99));
        }
        boolean previousPublishing = publishing;
        long previousPublication = publishingSession;
        publishingSession = session; publishing = true;
        try { NativeModelPort.publish(i0.a, visible, 0, more, pendingText(), deleted, newList); }
        finally { publishing = previousPublishing; publishingSession = previousPublication; }
    }
    public static boolean loadMore() {
        if (!ownsPending()) return false;
        requireMain();
        refreshEditor();
        if (!backendOwned) return false;
        if (!hasComposition()) return true;
        if (hasComposition() && more && publicationQuery.equals(query)) {
            if (publishing) {
                if (!loadMorePosted) {
                    loadMorePosted = true;
                    final long token = session, revision = generation;
                    MAIN.post(new Runnable() { public void run() {
                        loadMorePosted = false;
                        if (token == session && revision == generation && hasComposition()) loadMore();
                    }});
                }
            } else publish(false, false);
        }
        return true;
    }
    public static boolean choose(d candidate) {
        if (!(candidate instanceof QuickNativeCandidate)) return false;
        requireMain();
        QuickNativeCandidate marker = (QuickNativeCandidate) candidate;
        refreshEditor();
        if (!valid(marker)) return true;
        if (marker.action == 1) {
            if (marker.index == 0) windowOffset = windowOffset.add(BigInteger.valueOf(WINDOW));
            else if (marker.index == 1) windowOffset = windowOffset.subtract(BigInteger.valueOf(WINDOW)).max(BigInteger.ZERO);
            else return true;
            generation++; consumed = BigInteger.ZERO; publish(false, true); return true;
        }
        commit(results.get(marker.index), true); return true;
    }
    private static boolean valid(QuickNativeCandidate marker) {
        return backendOwned && hasComposition() && allowInput && marker.generation == generation
                && publicationQuery.equals(query) && (marker.action == 1 || (marker.index < results.size()
                && marker.commitText.equals(results.get(marker.index).text)));
    }
    private static boolean settle(boolean explicit) {
        if (!hasComposition()) return true;
        if (engine == null || results.isEmpty()) return false;
        if (explicit) return commit(results.get(0), true);
        for (Result result : results) if (result.remaining.length() == 0) return commit(result, false);
        return false;
    }
    private static boolean commit(Result result, boolean explicit) {
        refreshEditor();
        if (!backendOwned || !hasComposition() || !allowInput || connection == null || engine == null) return false;
        final InputConnection target = connection;
        final long token = session, revision = generation;
        final int priorStart = selectionStart, priorEnd = selectionEnd;
        final boolean learn = explicit && !privateMode;
        int base = composingStart >= 0 && composingEnd >= composingStart ? composingStart : Math.min(selectionStart, selectionEnd);
        if (base >= 0) {
            selectionStart = selectionEnd = base + result.text.length();
            expectedSelections.add(new int[] {selectionStart, selectionEnd});
            if (expectedSelections.size() > 32) expectedSelections.remove(0);
        }
        boolean accepted = false;
        Long previousCommit = inFlightSession;
        inFlightSession = Long.valueOf(token); committing = true;
        try { accepted = service.quickNativeCommit(result.text); }
        catch (RuntimeException editorRejected) { accepted = false; }
        finally { inFlightSession = previousCommit; committing = previousCommit != null; }
        refreshEditor();
        if (token != session || target != connection || revision != generation) return accepted;
        if (!accepted) {
            selectionStart = priorStart; selectionEnd = priorEnd; expectedSelections.clear();
            return false;
        }
        composingStart = composingEnd = -1;
        if (learn && !privateMode) { engine.recordSelection(result.lookup, result.text); flushSoon(); }
        query = result.remaining;
        changed(false);
        return true;
    }
    public static List<com.tencent.wetype.plugin.hld.floatview.a> candidateActions(d candidate) {
        if (!(candidate instanceof QuickNativeCandidate)) return null;
        final QuickNativeCandidate marker = (QuickNativeCandidate) candidate;
        refreshEditor();
        if (!valid(marker) || marker.action != 0 || privateMode || localActions == null) return Collections.emptyList();
        final Result result = results.get(marker.index);
        final boolean pinned = result.text.equals(localActions.pinned(result.lookup));
        List<com.tencent.wetype.plugin.hld.floatview.a> actions = new ArrayList<com.tencent.wetype.plugin.hld.floatview.a>();
        actions.add(new com.tencent.wetype.plugin.hld.floatview.a(1, pinned ? "取消固定" : "固定首位", true, null, null, new xc.a<nc.F>() {
            public nc.F invoke() { mutate(marker, result, pinned ? 1 : 0); return nc.F.a; }
        }));
        actions.add(new com.tencent.wetype.plugin.hld.floatview.a(2, "刪除該詞", true, null, null, new xc.a<nc.F>() {
            public nc.F invoke() { mutate(marker, result, 2); return nc.F.a; }
        }));
        return actions;
    }
    private static void mutate(QuickNativeCandidate marker, Result result, int action) {
        requireMain(); refreshEditor();
        if (!valid(marker) || privateMode || localActions == null || engine == null
                || !engine.containsCandidate(result.lookup, result.text)) return;
        try {
            if (action == 0) localActions.pin(result.lookup, result.text);
            else if (action == 1) localActions.unpin(result.lookup);
            else localActions.block(result.lookup, result.text);
            changed(false); flushSoon();
        } catch (IllegalStateException full) { notice("自訂候選紀錄已滿；可在原版個人詞典設定清除"); }
    }
    private static void ensureLoaded() {
        if (loading || engine != null || loadFailed || application == null) return;
        loading = true;
        STORAGE.execute(new Runnable() { public void run() {
            try {
                QuickDictionary dictionary; PhraseDictionary phrases;
                InputStream input = application.getAssets().open("quick/quick-traditional.tsv");
                try { dictionary = QuickDictionary.read(input); } finally { input.close(); }
                input = application.getAssets().open("quick/quick-phrases.tsv");
                try { phrases = PhraseDictionary.read(input); } finally { input.close(); }
                File directory = application.getNoBackupFilesDir();
                profile = new File(directory, "quick-learning.bin");
                actionsFile = new File(directory, "quick-native-actions.bin");
                CompositionEngine loaded;
                try { loaded = new CompositionEngine(dictionary, phrases, new FileLearningStore(profile)); }
                catch (IOException badProfile) { loaded = new CompositionEngine(dictionary, phrases, new MemoryLearningStore()); temporaryLearning = true; }
                loaded.setPrivateMode(true);
                try { localActions = QuickLocalActions.load(actionsFile); }
                catch (IOException badActions) { actionLoadFailed = true; }
                // Store assignment occurs on the same executor as clearHistory/flush.
                engine = loaded;
                MAIN.post(new Runnable() { public void run() {
                    loading = false;
                    if (started) { refreshEditor(); if (hasComposition()) publish(false, true); }
                    if (temporaryLearning || actionLoadFailed) notice("速成個人紀錄未能讀取；可在原版個人詞典設定清除後重試");
                }});
            } catch (Exception failure) {
                loadFailed = true;
                MAIN.post(new Runnable() { public void run() { loading = false; if (hasComposition()) publish(false, true); notice("速成字詞庫未能載入，請選回原版鍵盤"); }});
            }
        }});
    }
    private static void flushSoon() {
        STORAGE.execute(new Runnable() { public void run() {
            try {
                if (engine != null) engine.flush();
                if (localActions != null) localActions.save();
            } catch (IOException error) { MAIN.post(new Runnable() { public void run() { notice("速成個人紀錄未能儲存"); }}); }
        }});
    }
    public static void clearHistory() {
        if (application == null) {
            Context context = e7.u.b();
            application = context.getApplicationContext();
            if (application == null) application = context;
        }
        STORAGE.execute(new Runnable() { public void run() {
            try {
                File directory = application.getNoBackupFilesDir();
                if (profile == null) profile = new File(directory, "quick-learning.bin");
                if (actionsFile == null) actionsFile = new File(directory, "quick-native-actions.bin");
                if (engine != null) engine.clearLearning();
                else new FileLearningStore(profile).save(Collections.<String,org.wetype.quick.SelectionStat>emptyMap());
                // Missing/corrupt actions are reset explicitly only by the native Clear command.
                if (localActions != null) localActions.clear();
                else if (actionsFile != null) {
                    for (String suffix : new String[] {"", ".bak", ".new"}) {
                        File file = new File(actionsFile.getPath() + suffix);
                        if (file.exists() && !file.delete()) throw new IOException("Cannot clear Quick actions");
                    }
                    localActions = QuickLocalActions.load(actionsFile); actionLoadFailed = false;
                }
                if (temporaryLearning && profile != null) {
                    new FileLearningStore(profile).save(Collections.<String,org.wetype.quick.SelectionStat>emptyMap());
                    // Existing in-memory fallback remains empty and deliberately nonpersistent
                    // until the next process load; never copy an older failed profile back.
                }
                MAIN.post(new Runnable() { public void run() { if (hasComposition()) changed(false); }});
            } catch (IOException error) { MAIN.post(new Runnable() { public void run() { notice("速成個人紀錄未能完全清除，請重試"); }}); }
        }});
    }
    private static void invalidateKeys() { if (nativeRoot != null) nativeRoot.invalidate(); }
    private static void notice(String text) {
        if (application != null) Toast.makeText(application, text, Toast.LENGTH_SHORT).show();
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Quick input requires main thread");
    }
}
