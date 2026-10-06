package org.wetype.quick.integration;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import com.tencent.wetype.plugin.hld.keyboard.selfdraw.j;
import com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingItemView;
import com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingKeyboardTypeView;
import com.tencent.wetype.plugin.hld.view.settingkeyboard.a;
import java.util.List;

/** Version-pinned display and native picker adapter. Never mutates a key's input text. */
public final class NativeKeyboardPort {
    private static final String TILE_TAG = "org.wetype.quick.nativePickerTile";

    private NativeKeyboardPort() { }

    /** Called before the original drawer calculates text placement. */
    public static String displayMainText(j key) {
        String original = key.R();
        if (!NativeQuickBridge.active()) return original;
        String id = key.K();
        if (id != null && id.length() == 13 && id.endsWith("_copy")) id = id.substring(0, 8);
        // z keeps its original model and label; the controller owns its symbol action.
        if (id == null || !id.startsWith("S2_key_") || id.length() != 8) return original;
        char letter = id.charAt(7);
        if (letter < 'a' || letter > 'y') return original;
        String label = NativeQuickBridge.displayRoot(id, original);
        return label == null || label.length() == 0 ? original : label;
    }

    /** Runs after native layout/selection refresh, including its unchanged-layout return. */
    public static void refreshPicker(final S10SettingKeyboardTypeView picker) {
        LinearLayout first = picker.getFirstLineViewGroup();
        LinearLayout second = picker.getSecondLineViewGroup();
        if (first == null || second == null) return;

        S10SettingItemView quick = findAndDeduplicate(first, null);
        quick = findAndDeduplicate(second, quick);
        a pinyin = pinyinConfig(picker.getItemConfig());
        if (pinyin == null) return;
        if (quick == null) {
            ViewGroup nativeItem = picker.d(pinyin);
            if (!(nativeItem instanceof S10SettingItemView)) return;
            quick = (S10SettingItemView) nativeItem;
            quick.setTag(TILE_TAG);
            quick.setContentDescription("速成");
            quick.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    Context context = picker.getContext();
                    if (!NativeQuickBridge.selectQuick(context)) return;
                    // The existing native transition persists S2 and closes its picker.
                    com.tencent.wetype.plugin.hld.settings.b.a(context, 2, picker.quickNativeScene());
                    refreshPicker(picker);
                }
            });
            LinearLayout line = first.getChildCount() < 4 ? first : second;
            // Native picker has seven items and reserves at most four slots per row.
            if (line.getChildCount() >= 4) return;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1);
            params.weight = 1.0f;
            line.addView(quick, params);
        }
        quick.J(pinyin.a(), "速成");
        quick.K();
        boolean selected = NativeQuickBridge.isQuickSelected();
        quick.setSelected(selected);
        if (selected) {
            clearPinyinHighlight(first);
            clearPinyinHighlight(second);
        }
    }

    /** Exactly one call after the native click handler has resolved a valid config bit. */
    public static void onOriginalChoice(S10SettingKeyboardTypeView picker, int valueBit) {
        if (valueBit == 1 || valueBit == 2 || valueBit == 4 || valueBit == 8
                || valueBit == 16 || valueBit == 32) {
            NativeQuickBridge.selectOriginal(picker.getContext());
        }
        // bit 64 opens keyboard management rather than choosing another input backend.
    }

    private static a pinyinConfig(List<a> configs) {
        if (configs == null) return null;
        for (a config : configs) if (config != null && config.c() == 2) return config;
        return null;
    }

    private static S10SettingItemView findAndDeduplicate(LinearLayout line, S10SettingItemView keep) {
        for (int i = 0; i < line.getChildCount();) {
            View child = line.getChildAt(i);
            if (TILE_TAG.equals(child.getTag()) && child instanceof S10SettingItemView) {
                if (keep == null) keep = (S10SettingItemView) child;
                else {
                    line.removeViewAt(i);
                    continue;
                }
            }
            i++;
        }
        return keep;
    }

    private static void clearPinyinHighlight(LinearLayout line) {
        for (int i = 0; i < line.getChildCount(); i++) {
            View item = line.getChildAt(i);
            Object tag = item.getTag();
            if (tag instanceof a && ((a) tag).c() == 2) item.setSelected(false);
        }
    }
}
