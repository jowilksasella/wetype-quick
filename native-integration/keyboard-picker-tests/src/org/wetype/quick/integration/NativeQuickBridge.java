package org.wetype.quick.integration;
import android.content.Context;
/** Explicit controller double; the production picker port itself is loaded from its jar. */
public final class NativeQuickBridge {
    public static boolean selected;
    public static boolean acceptQuick = true;
    public static int quickCalls, originalCalls;
    public static boolean isQuickSelected() { return selected; }
    public static boolean active() { return selected; }
    public static boolean selectQuick(Context context) {
        quickCalls++;
        if (!acceptQuick) return false;
        selected = true;
        return true;
    }
    public static void selectOriginal(Context context) { selected = false; originalCalls++; }
    public static String displayRoot(String id, String original) { return original; }
}
