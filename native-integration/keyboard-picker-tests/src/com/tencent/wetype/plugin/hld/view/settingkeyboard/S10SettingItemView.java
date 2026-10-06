package com.tencent.wetype.plugin.hld.view.settingkeyboard;
import android.content.Context;
import android.widget.FrameLayout;
/** Explicit host tile double. Android view hierarchy/click/selected behavior is real. */
public class S10SettingItemView extends FrameLayout {
    public int icon, adjusts;
    public CharSequence text;
    public S10SettingItemView(Context context) { super(context); }
    public void J(int icon, CharSequence text) { this.icon = icon; this.text = text; }
    public void K() { adjusts++; }
}
