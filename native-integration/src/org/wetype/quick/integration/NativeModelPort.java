package org.wetype.quick.integration;

import android.os.Looper;
import com.tencent.wetype.plugin.hld.candidate.d;
import com.tencent.wetype.plugin.hld.model.Z;
import com.tencent.wetype.plugin.hld.model.i0;
import com.tencent.wxhld.info.PendingExactlyMatch;
import com.tencent.wxhld.info.PendingInput;
import com.tencent.wxhld.info.PendingInternalCursor;
import com.tencent.wxhld.info.PendingSeparator;
import java.util.ArrayList;
import java.util.List;

/** Main-thread-only publication into the ORIGINAL WeType candidate and pending model. */
public final class NativeModelPort {
    private NativeModelPort() {}

    /** Publishes one complete snapshot synchronously; the caller owns generation checks. */
    public static void publish(i0 model, List<QuickNativeCandidate> candidates,
            int listType, boolean hasMore, String rootLabels, boolean deleted) {
        publish(model, candidates, listType, hasMore, rootLabels, deleted, true);
    }

    /** Same-generation additive loads use newList=false to preserve native scrolling. */
    public static void publish(i0 model, List<QuickNativeCandidate> candidates,
            int listType, boolean hasMore, String rootLabels, boolean deleted, boolean newList) {
        publish(model, candidates, listType, hasMore, pending(rootLabels, deleted), newList);
    }

    /** Supports explicit root segments, including native apostrophe separators if needed. */
    public static void publish(i0 model, List<QuickNativeCandidate> candidates,
            int listType, boolean hasMore, Z pending) {
        publish(model, candidates, listType, hasMore, pending, true);
    }

    public static void publish(i0 model, List<QuickNativeCandidate> candidates,
            int listType, boolean hasMore, Z pending, boolean newList) {
        requireMainThread();
        if (model == null || candidates == null || pending == null) {
            throw new NullPointerException("model, candidates and pending are required");
        }
        ArrayList<d> snapshot = new ArrayList<d>(candidates.size());
        long generation = 0;
        for (int i = 0; i < candidates.size(); i++) {
            QuickNativeCandidate candidate = candidates.get(i);
            if (candidate == null) throw new NullPointerException("candidate");
            if (i == 0) generation = candidate.generation;
            else if (candidate.generation != generation) {
                throw new IllegalArgumentException("mixed Quick candidate generations");
            }
            snapshot.add(candidate);
        }
        // The appended smali method owns the old pending text and the q write lock.
        // togetherUpdate=true keeps candidate and pending observers in one UI turn.
        model.quickPublish(snapshot, listType, hasMore, copyPending(pending), true, newList);
    }

    public static void clear(i0 model) {
        publish(model, new ArrayList<QuickNativeCandidate>(), 0, false, "", false);
    }

    /** Invalidates lifecycle ownership without calling any observer/editor callback. */
    public static void invalidate(i0 model) {
        requireMainThread();
        if (model == null) throw new NullPointerException("model");
        model.quickInvalidate();
    }

    /** No Wxhld renderer/converter is called to construct these explicit states. */
    public static Z pending(String rootLabels, boolean deleted) {
        if (rootLabels == null) throw new NullPointerException("rootLabels");
        return pending(rootLabels.length() == 0 ? new String[0]
                : rootLabels.split("'", -1), rootLabels.indexOf('\'') >= 0, deleted);
    }

    /**
     * Separators, when requested, are native apostrophes. The existing converter
     * normalizes PendingSeparator to an apostrophe; arbitrary separator text is unsafe.
     */
    public static Z pending(String[] rootSegments, boolean separators, boolean deleted) {
        if (rootSegments == null) throw new NullPointerException("rootSegments");
        ArrayList<PendingInput> inputs = new ArrayList<PendingInput>();
        StringBuilder content = new StringBuilder();
        int characterLength = 0;
        for (int i = 0; i < rootSegments.length; i++) {
            String root = rootSegments[i];
            // A trailing apostrophe finalizes a one-root segment in the Quick engine.
            boolean trailingSeparator = separators && i > 0 && i == rootSegments.length - 1
                    && root != null && root.length() == 0;
            if (root == null || (root.length() == 0 && !trailingSeparator)) {
                throw new IllegalArgumentException("empty root segment");
            }
            if (separators && i > 0) {
                PendingSeparator separator = new PendingSeparator();
                separator.text = "'";
                inputs.add(separator);
                content.append('\'');
            }
            if (!trailingSeparator) {
                PendingExactlyMatch match = new PendingExactlyMatch();
                match.match = root;
                inputs.add(match);
            }
            content.append(root);
            // This matches native g1.i(): UTF-16 characters excluding separators.
            characterLength += root.length();
        }
        // A truly empty array is needed by native expanded-candidate close handling.
        if (!inputs.isEmpty()) inputs.add(new PendingInternalCursor());
        Z result = new Z();
        result.g();
        result.h(deleted);
        result.i(""); // quickPublish replaces this with the previous D.c().
        result.j(content.toString());
        result.k(characterLength);
        result.l(content.length());
        result.m(inputs.toArray(new PendingInput[inputs.size()]));
        return result;
    }

    // Every publication gets a distinct D identity. A newer publish/clear made by
    // an observer invalidates the older callback fanout in quickPublish.
    private static Z copyPending(Z source) {
        PendingInput[] original = source.f();
        PendingInput[] copy = new PendingInput[original == null ? 0 : original.length];
        for (int i = 0; i < copy.length; i++) {
            if (original[i] instanceof PendingExactlyMatch) {
                PendingExactlyMatch match = new PendingExactlyMatch();
                match.match = ((PendingExactlyMatch) original[i]).match;
                copy[i] = match;
            } else if (original[i] instanceof PendingSeparator) {
                PendingSeparator separator = new PendingSeparator();
                separator.text = ((PendingSeparator) original[i]).text;
                copy[i] = separator;
            } else if (original[i] instanceof PendingInternalCursor) {
                copy[i] = new PendingInternalCursor();
            } else {
                throw new IllegalArgumentException("unsupported Quick pending input");
            }
        }
        Z result = new Z();
        result.h(source.a());
        result.i(source.b().toString());
        result.j(source.c().toString());
        result.k(source.d());
        result.l(source.e());
        result.m(copy);
        return result;
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Quick model publication requires the main thread");
        }
    }
}
