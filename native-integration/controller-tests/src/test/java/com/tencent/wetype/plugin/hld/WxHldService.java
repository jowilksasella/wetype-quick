package com.tencent.wetype.plugin.hld;
import android.inputmethodservice.InputMethodService;import android.view.inputmethod.*;import java.util.*;
/** Explicit service boundary; no Tencent lifecycle/native code. */
public class WxHldService extends InputMethodService {
 public EditorInfo internalInfo,externalInfo;public InputConnection internalConnection,externalConnection;public boolean accepts=true,throwCommit;public Runnable duringCommit,afterCommit;public int resets;public final List<String> commits=new ArrayList<String>();public int bCalls,yCalls;
 @Override public void onCreate(){}@Override public void onDestroy(){}
 public InputConnection B(boolean external){bCalls++;return external?externalConnection:internalConnection;}
 public EditorInfo y(boolean external){yCalls++;return external?externalInfo:internalInfo;}
 @Override public InputConnection getCurrentInputConnection(){return externalConnection;}
 @Override public EditorInfo getCurrentInputEditorInfo(){return externalInfo;}
 public void quickNativeResetCompositionCache(){resets++;}
 public boolean quickNativeCommit(CharSequence value){String text=value.toString();commits.add(text);InputConnection target=internalConnection;if(duringCommit!=null)duringCommit.run();if(throwCommit)throw new IllegalStateException("test commit");boolean result=accepts&&target!=null&&target.commitText(text,1);if(result&&afterCommit!=null)afterCommit.run();return result;}
}
