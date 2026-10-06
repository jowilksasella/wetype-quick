package com.tencent.wetype.plugin.hld.view.settingkeyboard;
import android.content.Context;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;
/** Explicit host picker double with the original seven-config/two-row contract. */
public class S10SettingKeyboardTypeView extends FrameLayout {
    private final LinearLayout first, second;
    private final List<a> configs = new ArrayList<a>();
    public S10SettingKeyboardTypeView(Context context) {
        super(context);
        first = new LinearLayout(context); second = new LinearLayout(context);
        first.setOrientation(LinearLayout.HORIZONTAL); second.setOrientation(LinearLayout.HORIZONTAL);
        addView(first); addView(second);
        for (int bit : new int[]{1,2,4,8,16,32,64}) configs.add(new a(bit));
        rebuildOriginalRows();
    }
    public void rebuildOriginalRows() {
        first.removeAllViews(); second.removeAllViews();
        for (a config : configs) {
            S10SettingItemView item = (S10SettingItemView) d(config);
            item.setTag(config); item.setSelected(config.c() == 2);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1); p.weight = 1;
            (first.getChildCount() < 4 ? first : second).addView(item, p);
        }
    }
    public LinearLayout getFirstLineViewGroup() { return first; }
    public LinearLayout getSecondLineViewGroup() { return second; }
    public List<a> getItemConfig() { return configs; }
    public ViewGroup d(a config) { return new S10SettingItemView(getContext()); }
    public int quickNativeScene() { return 3; }
}
