package org.wetype.quick.audio;
import android.media.*;
import android.os.*;
import java.util.*;

/** Runs the actual production helper JAR against deliberately limited host doubles. */
public final class VoiceMicrophoneRouteTest {
    private static final AudioDeviceInfo PHONE=new AudioDeviceInfo(15,true);
    private static final AudioDeviceInfo BT=new AudioDeviceInfo(7,true);
    private static final AudioDeviceInfo USB=new AudioDeviceInfo(11,true);
    private static int count;
    public static void main(String[] args) {
        test("skip invalid and unrelated routes", () -> {
            VoiceMicrophoneRoute.beforeStart(null, new AudioManager(PHONE,BT));
            AudioRecord r=new AudioRecord();
            VoiceMicrophoneRoute.beforeStart(r,null);
            VoiceMicrophoneRoute.beforeStart(r,new AudioManager(PHONE,USB));
            VoiceMicrophoneRoute.beforeStart(r,new AudioManager(BT));
            VoiceMicrophoneRoute.beforeStart(r,new AudioManager(new AudioDeviceInfo(15,false),BT));
            r.state=0; VoiceMicrophoneRoute.beforeStart(r,new AudioManager(PHONE,BT));
            r.state=1; Build.VERSION.SDK_INT=23;
            VoiceMicrophoneRoute.beforeStart(r,new AudioManager(PHONE,BT));
            Build.VERSION.SDK_INT=36;
            eq(0,r.preferences.size()); eq("INACTIVE",VoiceMicrophoneRoute.status(r));
            VoiceMicrophoneRoute.afterStart(r); VoiceMicrophoneRoute.end(r);
        });
        test("SCO A2DP BLE headset hearing-aid detection", () -> {
            for(int type:new int[]{7,8,26,23}) {
                AudioRecord r=new AudioRecord();
                VoiceMicrophoneRoute.beforeStart(r,new AudioManager(PHONE,new AudioDeviceInfo(type,false)));
                same(PHONE,r.preferred); eq("PREFERENCE_ACCEPTED",VoiceMicrophoneRoute.status(r));
                VoiceMicrophoneRoute.end(r); same(null,r.preferred);
            }
        });
        test("success needs actual recording route", () -> {
            AudioRecord r=begin();
            VoiceMicrophoneRoute.afterStart(r);
            eq("NOT_RECORDING",VoiceMicrophoneRoute.status(r)); eq(0,r.routedQueries);
            r.recordingState=3; VoiceMicrophoneRoute.afterStart(r);
            eq("ROUTE_UNVERIFIED",VoiceMicrophoneRoute.status(r));
            r.routed=PHONE; r.listener.onRoutingChanged(r);
            eq("BUILTIN_CONFIRMED",VoiceMicrophoneRoute.status(r));
            VoiceMicrophoneRoute.end(r);
        });
        test("OEM mismatch is detected without recursive preference changes", () -> {
            AudioRecord r=begin(); r.recordingState=3; r.routed=BT;
            VoiceMicrophoneRoute.afterStart(r);
            eq("ROUTE_MISMATCH",VoiceMicrophoneRoute.status(r));
            for(int i=0;i<20;i++) r.listener.onRoutingChanged(r);
            eq(1,r.preferences.size());
            r.routed=PHONE; Handler.drain();
            eq("BUILTIN_CONFIRMED",VoiceMicrophoneRoute.status(r));
            r.routed=USB; r.listener.onRoutingChanged(r);
            eq("ROUTE_MISMATCH",VoiceMicrophoneRoute.status(r));
            VoiceMicrophoneRoute.end(r);
        });
        test("stop restores saved nondefault preference and removes listener first", () -> {
            AudioRecord r=new AudioRecord(); r.preferred=USB;
            VoiceMicrophoneRoute.beforeStart(r,new AudioManager(PHONE,BT));
            VoiceMicrophoneRoute.beforeStart(r,new AudioManager(PHONE,BT));
            eq(1,r.preferences.size());
            VoiceMicrophoneRoute.end(r); VoiceMicrophoneRoute.end(r);
            same(USB,r.preferred); eq(Arrays.asList("set","add","remove","set"),r.events);
            eq("INACTIVE",VoiceMicrophoneRoute.status(r));
        });
        test("cancel invalidates queued and stale callbacks including same-recorder reuse", () -> {
            AudioRecord r=begin(); r.recordingState=3; r.routed=PHONE;
            VoiceMicrophoneRoute.afterStart(r);
            AudioRouting.OnRoutingChangedListener old=r.listener;
            Runnable stale=Handler.queued.get(0);
            VoiceMicrophoneRoute.end(r); eq(0,Handler.queued.size());
            int queries=r.routedQueries; old.onRoutingChanged(r); stale.run();
            eq(queries,r.routedQueries);
            VoiceMicrophoneRoute.beforeStart(r,new AudioManager(PHONE,BT));
            old.onRoutingChanged(r); stale.run(); eq(queries,r.routedQueries);
            eq("PREFERENCE_ACCEPTED",VoiceMicrophoneRoute.status(r));
            VoiceMicrophoneRoute.end(r);
        });
        test("false set and partial throwing set restore previous preference", () -> {
            for(boolean throwsInstead:new boolean[]{false,true}) {
                AudioRecord r=new AudioRecord(); r.preferred=USB;
                r.rejectFirst=!throwsInstead; r.throwFirst=throwsInstead;
                VoiceMicrophoneRoute.beforeStart(r,new AudioManager(PHONE,BT));
                eq("INACTIVE",VoiceMicrophoneRoute.status(r)); same(USB,r.preferred);
                eq(Arrays.asList(PHONE,USB),r.preferences); same(null,r.listener);
            }
        });
        test("failed listener registration unwinds partial installation", () -> {
            AudioRecord r=new AudioRecord(); r.throwAdd=true;
            VoiceMicrophoneRoute.beforeStart(r,new AudioManager(PHONE,BT));
            eq("INACTIVE",VoiceMicrophoneRoute.status(r)); same(null,r.listener); same(null,r.preferred);
            eq(Arrays.asList("set","add","remove","set"),r.events);
        });
        test("platform lookup and released-recorder failures never escape", () -> {
            AudioRecord r=new AudioRecord(); AudioManager manager=new AudioManager(PHONE,BT); manager.fail=true;
            VoiceMicrophoneRoute.beforeStart(r,manager); eq(0,r.preferences.size());
            r=begin(); r.recordingState=3; r.throwRoute=true;
            VoiceMicrophoneRoute.afterStart(r); eq("ROUTE_UNVERIFIED",VoiceMicrophoneRoute.status(r));
            r.throwRemove=true; r.throwRestore=true; VoiceMicrophoneRoute.end(r);
            eq("INACTIVE",VoiceMicrophoneRoute.status(r)); eq(0,Handler.queued.size());
            eq(Arrays.asList("set","add","remove","set"),r.events);
        });
        test("independent recorders and foreign callbacks stay isolated", () -> {
            AudioRecord a=begin(), b=begin(); a.recordingState=b.recordingState=3;
            a.routed=PHONE; b.routed=BT;
            a.listener.onRoutingChanged(b); eq(0,a.routedQueries);
            VoiceMicrophoneRoute.afterStart(a); VoiceMicrophoneRoute.afterStart(b);
            VoiceMicrophoneRoute.end(a);
            eq("ROUTE_MISMATCH",VoiceMicrophoneRoute.status(b)); same(PHONE,b.preferred);
            VoiceMicrophoneRoute.end(b);
        });
        test("start failure cleanup and release cleanup are idempotent", () -> {
            AudioRecord r=begin(); VoiceMicrophoneRoute.end(r); VoiceMicrophoneRoute.end(r);
            eq(0,r.routedQueries); eq(2,r.preferences.size()); same(null,r.listener);
        });
        eq(0,Handler.queued.size());
        System.out.println("PASS: "+count+" production-helper host-double tests; no microphone hardware used");
    }
    private static AudioRecord begin() {
        AudioRecord r=new AudioRecord(); VoiceMicrophoneRoute.beforeStart(r,new AudioManager(PHONE,BT)); return r;
    }
    private static void test(String name,Runnable test) { test.run(); count++; System.out.println("PASS "+name); }
    private static void eq(Object a,Object b) { if(!Objects.equals(a,b)) throw new AssertionError(a+" != "+b); }
    private static void same(Object a,Object b) { if(a!=b) throw new AssertionError("Identity mismatch"); }
}
