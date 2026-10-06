package com.tencent.wetype.plugin.hld.settings;
import android.content.Context;
import org.wetype.quick.integration.NativeQuickBridge;
/** Explicit native transition spy; records arguments and backend state at dispatch. */
public final class b {
    public static int calls, bit, scene;
    public static boolean quickSelectedAtDispatch;
    public static void a(Context context, int chosenBit, int fromScene) {
        calls++; bit = chosenBit; scene = fromScene;
        quickSelectedAtDispatch = NativeQuickBridge.isQuickSelected();
    }
}
