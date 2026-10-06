package com.tencent.wetype.plugin.hld.model;
import com.tencent.wxhld.info.PendingInput;
/** Explicit functional model-state double; does not test the original model implementation. */
public final class Z {
 private boolean deleted;private CharSequence old="",text="";private int length,cursor;private PendingInput[] inputs=new PendingInput[0];
 public boolean a(){return deleted;}public CharSequence b(){return old;}public CharSequence c(){return text;}public int d(){return length;}public int e(){return cursor;}public PendingInput[] f(){return inputs;}
 public void g(){deleted=false;old=text="";length=cursor=0;inputs=new PendingInput[0];}public void h(boolean x){deleted=x;}public void i(CharSequence x){old=x;}public void j(CharSequence x){text=x;}public void k(int x){length=x;}public void l(int x){cursor=x;}public void m(PendingInput[] x){inputs=x;}
}
