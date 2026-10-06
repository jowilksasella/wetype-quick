package android.media;
/** Explicit host double, never package this with the app. */
public final class AudioDeviceInfo {
    public static final int TYPE_BUILTIN_MIC=15, TYPE_BLUETOOTH_SCO=7,
        TYPE_BLUETOOTH_A2DP=8, TYPE_HEARING_AID=23, TYPE_BLE_HEADSET=26;
    private final int type;
    private final boolean source;
    public AudioDeviceInfo(int type, boolean source) { this.type=type; this.source=source; }
    public int getType() { return type; }
    public boolean isSource() { return source; }
}
