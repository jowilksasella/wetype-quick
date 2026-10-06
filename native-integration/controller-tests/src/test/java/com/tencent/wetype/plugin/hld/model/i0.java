package com.tencent.wetype.plugin.hld.model;
import com.tencent.wetype.plugin.hld.candidate.d;import java.util.*;
/** Explicit publication/observer fixture, not original i0 implementation. */
public final class i0 {
 public static final i0 a=new i0();public static ArrayList<d> candidates=new ArrayList<d>();public static Z pending=new Z();public static boolean hasMore,newList,nativePendingEmpty=true;public static int publications,clears;public static Runnable observer;
 public void quickInvalidate(){candidates=new ArrayList<d>();pending=new Z();hasMore=false;clears++;}
 public boolean A3(){return nativePendingEmpty;}
 public void quickPublish(ArrayList<d> c,int type,boolean more,Z p,boolean together,boolean fresh){p.i(pending.c());candidates=new ArrayList<d>(c);pending=p;hasMore=more;newList=fresh;publications++;if(c.isEmpty()&&p.c().length()==0)clears++;if(observer!=null)observer.run();}
 public static void reset(){candidates.clear();pending=new Z();hasMore=false;newList=false;nativePendingEmpty=true;publications=clears=0;observer=null;}
}
