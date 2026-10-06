package com.tencent.wetype.plugin.hld;
import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
/** Compile-only pinned host contracts. Never package this class. */
public class WxHldService extends InputMethodService {
    public InputConnection B(boolean forceRealEdit) { throw new UnsupportedOperationException(); }
    public EditorInfo y(boolean forceRealEdit) { throw new UnsupportedOperationException(); }
    public void setComposingText(CharSequence text, int fromCursorMode) { throw new UnsupportedOperationException(); }
    public void finishComposingText() { throw new UnsupportedOperationException(); }
    public void c() { throw new UnsupportedOperationException(); }
    public void d() { throw new UnsupportedOperationException(); }
    public void V(int before, int after, boolean forceRealEdit) { throw new UnsupportedOperationException(); }
    public CharSequence n0() { throw new UnsupportedOperationException(); }
    public boolean quickNativeCommit(CharSequence text) { throw new UnsupportedOperationException(); }
    public void quickNativeResetCompositionCache() { throw new UnsupportedOperationException(); }
}
