package org.wetype.quick.keyboardtest;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingItemView;
import com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingKeyboardTypeView;
import com.tencent.wetype.plugin.hld.view.settingkeyboard.a;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.wetype.quick.integration.NativeKeyboardPort;
import org.wetype.quick.integration.NativeQuickBridge;
import static org.junit.Assert.*;

/** Production port + clearly labeled host doubles + real Android view operations. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE, sdk=36, qualifiers="w393dp-h852dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class KeyboardPickerTest {
    private Context context;
    private S10SettingKeyboardTypeView picker;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        NativeQuickBridge.selected = false;
        NativeQuickBridge.acceptQuick = true;
        NativeQuickBridge.quickCalls = NativeQuickBridge.originalCalls = 0;
        com.tencent.wetype.plugin.hld.settings.b.calls = 0;
        picker = new S10SettingKeyboardTypeView(context);
    }
    @Test public void repeatedRefreshAndNativeRowRebuildHaveOneQuickTile() {
        for (int i=0; i<12; i++) NativeKeyboardPort.refreshPicker(picker);
        assertEightAndOne();
        S10SettingItemView firstTile = quick();
        picker.rebuildOriginalRows();
        NativeKeyboardPort.refreshPicker(picker);
        assertEightAndOne(); assertNotSame(firstTile, quick());
        S10SettingItemView duplicate = new S10SettingItemView(context);
        duplicate.setTag(quick().getTag());
        picker.getSecondLineViewGroup().addView(duplicate);
        NativeKeyboardPort.refreshPicker(picker);
        assertEightAndOne();
    }
    @Test public void quickTileUsesEqualNativeRowAllocationAtBothWidths() {
        NativeKeyboardPort.refreshPicker(picker);
        for (int width : new int[]{400,800}) {
            LinearLayout line = picker.getSecondLineViewGroup();
            line.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY));
            line.layout(0,0,width,100);
            for(int i=0;i<4;i++) assertEquals(width/4,line.getChildAt(i).getWidth());
            assertEquals(100,quick().getHeight());
        }
        assertEquals("速成",quick().text.toString());
        assertEquals(1002,quick().icon); assertTrue(quick().adjusts>0);
    }
    @Test public void quickClickSelectsBeforeOriginalTransitionAndHighlightsOnlyQuick() {
        NativeKeyboardPort.refreshPicker(picker);
        assertTrue(pinyin().isSelected()); assertFalse(quick().isSelected());
        assertTrue(quick().performClick());
        assertEquals(1,NativeQuickBridge.quickCalls);
        assertEquals(1,com.tencent.wetype.plugin.hld.settings.b.calls);
        assertEquals(2,com.tencent.wetype.plugin.hld.settings.b.bit);
        assertEquals(3,com.tencent.wetype.plugin.hld.settings.b.scene);
        assertTrue(com.tencent.wetype.plugin.hld.settings.b.quickSelectedAtDispatch);
        assertTrue(quick().isSelected()); assertFalse(pinyin().isSelected());
        picker.rebuildOriginalRows(); NativeKeyboardPort.refreshPicker(picker);
        assertEightAndOne(); assertTrue(quick().isSelected()); assertFalse(pinyin().isSelected());
    }
    @Test public void originalChoiceClearsOnceAndManagementDoesNotChangeBackend() {
        NativeQuickBridge.selected = true;
        NativeKeyboardPort.onOriginalChoice(picker,64);
        NativeKeyboardPort.onOriginalChoice(picker,999);
        assertTrue(NativeQuickBridge.selected); assertEquals(0,NativeQuickBridge.originalCalls);
        NativeKeyboardPort.onOriginalChoice(picker,2);
        assertFalse(NativeQuickBridge.selected); assertEquals(1,NativeQuickBridge.originalCalls);
        picker.rebuildOriginalRows(); NativeKeyboardPort.refreshPicker(picker);
        assertTrue(pinyin().isSelected()); assertFalse(quick().isSelected());
        for(int bit:new int[]{1,4,8,16,32}) {
            NativeQuickBridge.selected=true;
            NativeKeyboardPort.onOriginalChoice(picker,bit);
            assertFalse(NativeQuickBridge.selected);
        }
        assertEquals(6,NativeQuickBridge.originalCalls);
    }
    @Test public void refusedQuickSelectionPreservesOriginalKeyboardAndSelection() {
        NativeKeyboardPort.refreshPicker(picker);
        NativeQuickBridge.acceptQuick = false;
        assertTrue(quick().performClick());
        assertEquals(1,NativeQuickBridge.quickCalls);
        assertEquals(0,com.tencent.wetype.plugin.hld.settings.b.calls);
        assertFalse(NativeQuickBridge.selected);
        assertTrue(pinyin().isSelected());
        assertFalse(quick().isSelected());
        assertEightAndOne();
    }
    private void assertEightAndOne() {
        int total=0,quick=0;
        for(LinearLayout line:lines()) {
            assertEquals(4,line.getChildCount()); total+=line.getChildCount();
            for(int i=0;i<line.getChildCount();i++) if(!(line.getChildAt(i).getTag() instanceof a)) quick++;
        }
        assertEquals(8,total); assertEquals(1,quick);
    }
    private S10SettingItemView quick() {
        for(LinearLayout line:lines()) for(int i=0;i<line.getChildCount();i++) {
            View item=line.getChildAt(i); if(!(item.getTag() instanceof a)) return (S10SettingItemView)item;
        }
        throw new AssertionError("Quick tile missing");
    }
    private View pinyin() {
        for(LinearLayout line:lines()) for(int i=0;i<line.getChildCount();i++) {
            View item=line.getChildAt(i); Object tag=item.getTag();
            if(tag instanceof a && ((a)tag).c()==2) return item;
        }
        throw new AssertionError("Original Pinyin tile missing");
    }
    private LinearLayout[] lines() { return new LinearLayout[]{picker.getFirstLineViewGroup(),picker.getSecondLineViewGroup()}; }
}
