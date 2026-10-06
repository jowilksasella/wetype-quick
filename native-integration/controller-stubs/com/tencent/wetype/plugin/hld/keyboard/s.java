package com.tencent.wetype.plugin.hld.keyboard;
import android.content.Context;
import android.widget.LinearLayout;
/** Compile-only original keyboard and delete payload contracts. Never package. */
public abstract class s extends LinearLayout {
    public s(Context context) { super(context); }
    public abstract t getKeyboardType();
    public static final class a {
        public int a() { throw new UnsupportedOperationException(); }
        public int b() { throw new UnsupportedOperationException(); }
        public b c() { throw new UnsupportedOperationException(); }
    }
    /** Original raw names: a=First, b=Continue, c=Last. */
    public enum b { a,b,c }
}
