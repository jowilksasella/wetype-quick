package android.media;
public class AudioManager {
    public static final int GET_DEVICES_ALL=3;
    public AudioDeviceInfo[] devices;
    public boolean fail;
    public AudioManager(AudioDeviceInfo... devices) { this.devices=devices; }
    public AudioDeviceInfo[] getDevices(int flags) {
        if (flags != GET_DEVICES_ALL) throw new AssertionError("Wrong enumeration scope");
        if (fail) throw new SecurityException("host permission failure");
        return devices;
    }
}
