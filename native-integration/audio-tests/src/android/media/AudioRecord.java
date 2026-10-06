package android.media;
import android.os.Handler;
import java.util.ArrayList;
import java.util.List;
/** Explicit host double. No native recording, microphone, or hardware is used. */
public class AudioRecord implements AudioRouting {
    public static final int STATE_INITIALIZED=1, RECORDSTATE_RECORDING=3;
    public int state=1, recordingState=1, routedQueries;
    public AudioDeviceInfo preferred, routed;
    public final List<AudioDeviceInfo> preferences=new ArrayList<>();
    public final List<String> events=new ArrayList<>();
    public OnRoutingChangedListener listener;
    public boolean rejectFirst, throwFirst, throwAdd, throwRemove, throwRestore, throwRoute;
    public int getState() { return state; }
    public int getRecordingState() { return recordingState; }
    public AudioDeviceInfo getPreferredDevice() { return preferred; }
    public AudioDeviceInfo getRoutedDevice() {
        routedQueries++;
        if(recordingState!=3) throw new AssertionError("Queried route while stopped");
        if(throwRoute) throw new IllegalStateException("host route failure");
        return routed;
    }
    public boolean setPreferredDevice(AudioDeviceInfo device) {
        events.add("set"); preferences.add(device); preferred=device;
        if(preferences.size()==1 && throwFirst) throw new IllegalStateException("host partial set");
        if(preferences.size()>1 && throwRestore) throw new IllegalStateException("host released record");
        return !(preferences.size()==1 && rejectFirst);
    }
    public void addOnRoutingChangedListener(OnRoutingChangedListener listener, Handler handler) {
        events.add("add"); this.listener=listener;
        if(throwAdd) throw new IllegalStateException("host partial listener registration");
    }
    public void removeOnRoutingChangedListener(OnRoutingChangedListener listener) {
        events.add("remove");
        if(this.listener!=listener) throw new AssertionError("Wrong listener");
        this.listener=null;
        if(throwRemove) throw new IllegalStateException("host released record");
    }
}
