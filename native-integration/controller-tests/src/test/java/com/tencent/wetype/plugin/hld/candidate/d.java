package com.tencent.wetype.plugin.hld.candidate;
import com.tencent.wxhld.info.Candidate;
/** Explicit native candidate wrapper boundary. Candidate base is an authored test double. */
public class d extends Candidate {private CharSequence shown;public int candidateType,generateType;public d(int n,CharSequence s){super(n);shown=s;}public void S0(int t){candidateType=t;}public void R0(int t){generateType=t;}public CharSequence L(){return shown;}}
